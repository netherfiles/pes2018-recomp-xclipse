package com.pes2017.recomp.patch

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Locale

/**
 * An archive read through libarchive: ZIP/ZIP64, RAR5 (single or multi-part)
 * and single-volume 7z.
 *
 * A single archive selected through SAF stays on its descriptor and is read
 * with [Archive.readOpenFd]; it is not copied first. If lseek says the
 * descriptor is a pipe, the archive is copied once to
 * `cache/import-parts` and the copy is reported in [sourceBytes].
 *
 * Multi-part RAR needs real paths for [Archive.readOpenFileNames]. The source
 * uses the paths when the provider exposes them, and otherwise copies every
 * part to the same cache directory. A multi-volume 7z (.7z.001) is refused
 * with an explanation: concatenating a 20 GB set is not a scan operation.
 */
class ArchiveSource private constructor(
    private val context: Context?,
    private val input: Input,
    override val label: String,
    override val kind: PatchSourceKind,
    override val sourceBytes: Long,
    override val partCount: Int,
    private val password: String?,
    private val ownedDirs: List<File>,
) : PatchSource {

    private var closed = false

    override fun openSession(): PatchSourceSession {
        if (closed) throw PatchSourceException("source is closed", PatchSourceFailure.IO)
        return Session()
    }

    override fun close() {
        if (closed) return
        closed = true
        ownedDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private sealed interface Input {
        class Paths(val paths: List<String>) : Input
        class Descriptor(val uri: Uri) : Input
    }

    private inner class Session : PatchSourceSession {
        var archive: Long = 0
        private var descriptor: ParcelFileDescriptor? = null
        private var currentOpen = false
        private var closed = false
        var currentPath = ""
        private var stream: EntryStream? = null

        init {
            archive = Archive.readNew()
            try {
                Archive.readSupportFilterAll(archive)
                Archive.readSupportFormatAll(archive)
                password?.takeIf { it.isNotEmpty() }?.let {
                    Archive.readAddPassphrase(archive, it.toByteArray(Charsets.UTF_8))
                }
                when (input) {
                    is Input.Paths -> Archive.readOpenFileNames(
                        archive,
                        input.paths.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                        READ_BLOCK,
                    )

                    is Input.Descriptor -> {
                        val opened = context?.contentResolver
                            ?.openFileDescriptor(input.uri, "r")
                            ?: throw PatchSourceException(
                                "cannot open ${input.uri}",
                                PatchSourceFailure.IO,
                            )
                        descriptor = opened
                        runCatching {
                            Os.lseek(opened.fileDescriptor, 0, OsConstants.SEEK_SET)
                        }
                        Archive.readOpenFd(archive, opened.fd, READ_BLOCK)
                    }
                }
            } catch (t: Throwable) {
                close()
                throw archiveFailure("cannot open ${label}", t)
            }
        }

        override fun next(): PatchSourceEntry? {
            if (closed) return null
            stream?.invalidate()
            stream = null
            if (currentOpen) skipCurrent()
            while (true) {
                val entry = try {
                    Archive.readNextHeader(archive)
                } catch (t: Throwable) {
                    throw archiveFailure("cannot read the next entry of $label", t)
                }
                if (entry == 0L) return null
                val rawPath = ArchiveEntry.pathnameUtf8(entry) ?: ""
                val path = try {
                    normalizePatchPath(rawPath)
                } catch (e: PatchSourceException) {
                    throw e
                }
                if (path.isEmpty()) {
                    runCatching { Archive.readDataSkip(archive) }
                    continue
                }
                val directory = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFDIR ||
                    rawPath.endsWith("/")
                val size = if (directory) {
                    0L
                } else {
                    // Some ZIP64 providers leave ArchiveEntry.size() at 0
                    // until the data is consumed. stSize is the reliable
                    // 64-bit size; -1 means "read until EOF" and is not
                    // treated as an empty file.
                    val statSize = runCatching { ArchiveEntry.stat(entry).stSize }
                        .getOrDefault(-1L)
                    if (statSize > 0) {
                        statSize
                    } else if (ArchiveEntry.sizeIsSet(entry)) {
                        ArchiveEntry.size(entry).takeIf { it > 0 } ?: -1L
                    } else {
                        -1L
                    }
                }
                currentPath = path
                currentOpen = !directory
                return PatchSourceEntry(
                    path = path,
                    size = size,
                    isDirectory = directory,
                    file = null,
                )
            }
        }

        override fun open(): InputStream {
            if (closed) throw PatchSourceException("session is closed", PatchSourceFailure.IO)
            if (!currentOpen) throw PatchSourceException(
                "open() called before next()",
                PatchSourceFailure.IO,
            )
            val created = EntryStream(this)
            stream = created
            return created
        }

        fun skipCurrent() {
            if (!currentOpen) return
            currentOpen = false
            try {
                Archive.readDataSkip(archive)
            } catch (t: Throwable) {
                throw archiveFailure("cannot skip $currentPath", t)
            }
        }

        fun entryEnded() {
            currentOpen = false
        }

        override fun close() {
            if (closed) return
            closed = true
            stream?.invalidate()
            stream = null
            runCatching { if (currentOpen) Archive.readDataSkip(archive) }
            currentOpen = false
            runCatching { Archive.readClose(archive) }
            runCatching { Archive.readFree(archive) }
            runCatching { descriptor?.close() }
            descriptor = null
        }
    }

    private class EntryStream(private val session: Session) : InputStream() {
        private val direct: ByteBuffer = ByteBuffer.allocateDirect(READ_BUFFER)
        private val bytes = ByteArray(READ_BUFFER)
        private var position = 0
        private var limit = 0
        private var ended = false
        private var closed = false

        override fun read(): Int {
            if (!fill()) return -1
            return bytes[position++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val count = minOf(len, limit - position)
            System.arraycopy(bytes, position, b, off, count)
            position += count
            return count
        }

        private fun fill(): Boolean {
            if (closed) return false
            if (position < limit) return true
            if (ended) return false
            direct.clear()
            try {
                Archive.readData(session.archive, direct)
            } catch (t: Throwable) {
                ended = true
                session.entryEnded()
                throw archiveFailure("cannot read ${session.currentPath}", t)
            }
            val read = direct.position()
            if (read <= 0) {
                ended = true
                session.entryEnded()
                return false
            }
            direct.flip()
            direct.get(bytes, 0, read)
            position = 0
            limit = read
            return true
        }

        fun invalidate() {
            closed = true
        }

        override fun close() {
            if (closed) return
            closed = true
            if (!ended) session.skipCurrent()
        }
    }

    private companion object {
        const val READ_BLOCK = 1L shl 20
        const val READ_BUFFER = 64 * 1024

        fun archiveFailure(action: String, error: Throwable): PatchSourceException {
            val message = error.message ?: error.toString()
            val lower = message.lowercase(Locale.US)
            val password = lower.contains("passphrase") ||
                lower.contains("password") ||
                lower.contains("encrypt")
            return PatchSourceException(
                if (password) {
                    "$action: $message (the archive is encrypted; supply its password)"
                } else {
                    "$action: $message"
                },
                if (password) PatchSourceFailure.PASSWORD else PatchSourceFailure.IO,
                error,
            )
        }
    }

    // ---- construction ----------------------------------------------------

    internal object Factory {

        fun fromFile(
            context: Context?,
            file: File,
            label: String?,
            password: String?,
        ): ArchiveSource {
            if (!file.isFile) {
                throw PatchSourceException(
                    "not an archive: ${file.path}",
                    PatchSourceFailure.UNSUPPORTED_ARCHIVE,
                )
            }
            val parts = gatherParts(file)
            return fromFiles(context, parts, label ?: file.name, password)
        }

        fun fromFiles(
            context: Context?,
            files: List<File>,
            label: String,
            password: String?,
        ): ArchiveSource {
            if (files.isEmpty()) {
                throw PatchSourceException(
                    "no archive files",
                    PatchSourceFailure.MISSING_PART,
                )
            }
            files.forEach {
                if (!it.isFile || !it.canRead()) {
                    throw PatchSourceException(
                        "cannot read archive part ${it.path}",
                        PatchSourceFailure.IO,
                    )
                }
            }
            val names = files.map { it.name }
            val chosen = choosePartGroup(names) ?: throw PatchSourceException(
                "not an archive: $label",
                PatchSourceFailure.UNSUPPORTED_ARCHIVE,
            )
            val byName = files.groupBy { it.name }
            val ordered = chosen.map { name ->
                val matches = byName[name].orEmpty()
                if (matches.size != 1) {
                    throw PatchSourceException(
                        "ambiguous archive part $name",
                        PatchSourceFailure.UNSUPPORTED_ARCHIVE,
                    )
                }
                matches[0]
            }
            val kind = archiveKind(ordered[0].name)
            return ArchiveSource(
                context = context,
                input = Input.Paths(ordered.map { it.absolutePath }),
                label = label,
                kind = kind,
                sourceBytes = ordered.sumOf { it.length() },
                partCount = ordered.size,
                password = password,
                ownedDirs = emptyList(),
            )
        }

        fun fromDocument(
            context: Context,
            uri: Uri,
            displayName: String?,
            password: String?,
        ): PatchSource {
            val name = displayName?.takeIf { it.isNotBlank() }
                ?: queryDisplayName(context, uri)
                ?: uri.lastPathSegment
                ?: "archive"
            val real = resolveRealFile(context, uri)
            if (real != null && real.canRead()) {
                return fromFile(context, real, name, password)
            }
            if (looksLikePart(name)) {
                throw PatchSourceException(
                    "$name is one part of a multi-part archive; choose the folder " +
                        "that contains every part, not a single part",
                    PatchSourceFailure.MISSING_PART,
                )
            }
            val kind = archiveKind(name)
            if (kind == PatchSourceKind.UNKNOWN) {
                throw PatchSourceException(
                    "not an archive: $name",
                    PatchSourceFailure.UNSUPPORTED_ARCHIVE,
                )
            }
            val originalSize = querySize(context, uri)
            val pfd = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (t: Throwable) {
                throw PatchSourceException(
                    "cannot open $name: ${t.message}",
                    PatchSourceFailure.IO,
                    t,
                )
            } ?: throw PatchSourceException(
                "cannot open $name: provider returned no descriptor",
                PatchSourceFailure.IO,
            )
            val seekable = isSeekable(pfd)
            if (seekable) {
                val size = maxOf(originalSize, runCatching { pfd.statSize }.getOrDefault(-1L))
                runCatching { pfd.close() }
                return ArchiveSource(
                    context = context,
                    input = Input.Descriptor(uri),
                    label = name,
                    kind = kind,
                    sourceBytes = size.coerceAtLeast(0),
                    partCount = 1,
                    password = password,
                    ownedDirs = emptyList(),
                )
            }

            // A pipe cannot be reopened at an arbitrary offset. Copy it once,
            // report the cost, and let libarchive open the regular file.
            val dir = createCacheDir(context, "import-parts")
            try {
                val target = copyDescriptor(context, pfd, dir, "00-$name", originalSize)
                runCatching { pfd.close() }
                return ArchiveSource(
                    context = context,
                    input = Input.Paths(listOf(target.absolutePath)),
                    label = name,
                    kind = kind,
                    sourceBytes = originalSize.coerceAtLeast(0) + target.length(),
                    partCount = 1,
                    password = password,
                    ownedDirs = listOf(dir),
                )
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                dir.deleteRecursively()
                throw t
            }
        }

        fun fromUris(
            context: Context,
            uris: List<Uri>,
            displayName: String,
            password: String?,
        ): PatchSource {
            if (uris.isEmpty()) {
                throw PatchSourceException("no archive files", PatchSourceFailure.MISSING_PART)
            }
            if (uris.size == 1) return fromDocument(context, uris[0], displayName, password)
            val incoming = uris.mapIndexed { index, uri ->
                IncomingPart(
                    name = queryDisplayName(context, uri) ?: "part${index + 1}",
                    uri = uri,
                    file = resolveRealFile(context, uri),
                    size = querySize(context, uri),
                )
            }
            return buildFromIncoming(context, incoming, displayName, password)
        }

        fun fromDocumentFiles(
            context: Context,
            documents: List<DocumentFile>,
            label: String,
            password: String?,
        ): PatchSource {
            val incoming = documents.mapIndexedNotNull { index, document ->
                val name = document.name ?: return@mapIndexedNotNull null
                IncomingPart(
                    name = name,
                    uri = document.uri,
                    file = resolveRealFile(context, document.uri),
                    size = document.length(),
                )
            }
            if (incoming.isEmpty()) {
                throw PatchSourceException(
                    "no archive files in $label",
                    PatchSourceFailure.MISSING_PART,
                )
            }
            return buildFromIncoming(context, incoming, label, password)
        }

        private fun buildFromIncoming(
            context: Context?,
            incoming: List<IncomingPart>,
            label: String,
            password: String?,
        ): ArchiveSource {
            val chosenNames = choosePartGroup(incoming.map { it.name }) ?: throw PatchSourceException(
                "no ZIP, RAR or 7z archive in $label",
                PatchSourceFailure.UNSUPPORTED_ARCHIVE,
            )
            val byName = incoming.groupBy { it.name }
            val chosen = chosenNames.map { name ->
                val matches = byName[name].orEmpty()
                if (matches.size != 1) {
                    throw PatchSourceException(
                        "ambiguous archive part $name",
                        PatchSourceFailure.UNSUPPORTED_ARCHIVE,
                    )
                }
                matches[0]
            }
            val kind = archiveKind(chosen[0].name)
            var originalBytes = 0L
            var copiedBytes = 0L
            var cacheDir: File? = null
            try {
                val paths = ArrayList<String>(chosen.size)
                for ((index, part) in chosen.withIndex()) {
                    originalBytes += part.size.coerceAtLeast(0)
                    val real = part.file
                    if (real != null && real.canRead()) {
                        paths.add(real.absolutePath)
                        continue
                    }
                    val uri = part.uri ?: throw PatchSourceException(
                        "cannot read archive part ${part.name}",
                        PatchSourceFailure.IO,
                    )
                    if (context == null) {
                        throw PatchSourceException(
                            "cannot copy ${part.name} without a Context",
                            PatchSourceFailure.IO,
                        )
                    }
                    val dir = cacheDir ?: createCacheDir(context, "import-parts").also {
                        cacheDir = it
                    }
                    val target = copyUriToCache(
                        context,
                        uri,
                        dir,
                        index.toString().padStart(2, '0') + "-" + part.name,
                        part.size,
                    )
                    copiedBytes += target.length()
                    paths.add(target.absolutePath)
                }
                return ArchiveSource(
                    context = context,
                    input = Input.Paths(paths),
                    label = label,
                    kind = kind,
                    sourceBytes = originalBytes + copiedBytes,
                    partCount = paths.size,
                    password = password,
                    ownedDirs = listOfNotNull(cacheDir),
                )
            } catch (t: Throwable) {
                cacheDir?.deleteRecursively()
                throw t
            }
        }
    }

    private data class IncomingPart(
        val name: String,
        val uri: Uri?,
        val file: File?,
        val size: Long,
    )
}

// ---- part discovery and SAF helpers ------------------------------------

private fun looksLikePart(name: String): Boolean {
    val lower = name.lowercase(Locale.US)
    return PART_RAR.containsMatchIn(lower) ||
        RAR_OLD.containsMatchIn(lower) ||
        SPLIT_ZIP.containsMatchIn(lower) ||
        lower.matches(Regex(""".*\.7z\.\d{3}$""")) ||
        (lower.matches(Regex(""".*\.\d{3}$""")) && !lower.endsWith(".7z"))
}

private fun archiveKind(name: String): PatchSourceKind {
    val lower = name.lowercase(Locale.US)
    return when {
        lower.endsWith(".zip") -> PatchSourceKind.ZIP
        lower.endsWith(".rar") || lower.endsWith(".r00") -> PatchSourceKind.RAR
        lower.endsWith(".7z") -> PatchSourceKind.SEVEN_ZIP
        else -> PatchSourceKind.UNKNOWN
    }
}

/**
 * Expands the selected file into the full multi-part set that sits beside it.
 * A complete single archive is returned as a one-element list.
 */
private fun gatherParts(selected: File): List<File> {
    val name = selected.name
    val lower = name.lowercase(Locale.US)
    val directory = selected.parentFile ?: return listOf(selected)

    if (lower.matches(Regex(""".*\.7z\.\d{3}$"""))) {
        throw PatchSourceException(
            "$name is a multi-volume 7z; this importer accepts a single .7z archive. " +
                "Join the parts first or extract the patch to a folder.",
            PatchSourceFailure.UNSUPPORTED_ARCHIVE,
        )
    }
    if (lower.matches(Regex(""".*\.\d{3}$""")) && !lower.endsWith(".7z")) {
        throw PatchSourceException(
            "$name looks like a numbered volume; select the folder with all parts " +
                "or a complete archive",
            PatchSourceFailure.UNSUPPORTED_ARCHIVE,
        )
    }

    val part = PART_RAR.matchEntire(lower)
    if (part != null) {
        val prefix = part.groupValues[1] + ".part"
        val siblings = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.lowercase(Locale.US).startsWith(prefix) &&
                PART_RAR.matches(it.name.lowercase(Locale.US)) }
            .sortedBy { partNumber(it.name) }
        if (siblings.isEmpty()) return listOf(selected)
        requireContiguous(siblings, start = 1)
        return siblings
    }

    if (lower.endsWith(".rar")) {
        val base = lower.dropLast(4)
        val siblings = directory.listFiles().orEmpty()
            .filter {
                it.isFile && it.name.lowercase(Locale.US).matches(
                    Regex("^" + Regex.escape(base) + "\\.r\\d\\d$"),
                )
            }
            .sortedBy { it.name.lowercase(Locale.US).takeLast(2).toIntOrNull() ?: 0 }
        return listOf(selected) + siblings
    }

    if (RAR_OLD.matches(lower)) {
        // The old RAR naming starts at .r00 and has its first volume in .rar.
        // Selecting any .rNN part should still open the whole set.
        val baseName = name.substringBeforeLast('.') + ".rar"
        val baseFile = File(directory, baseName)
        if (!baseFile.isFile) {
            throw PatchSourceException(
                "$name is part of an old-style multi-part RAR, but $baseName is " +
                    "missing; choose the folder containing every part",
                PatchSourceFailure.MISSING_PART,
            )
        }
        val base = baseName.lowercase(Locale.US).dropLast(4)
        val siblings = directory.listFiles().orEmpty()
            .filter {
                it.isFile && it.name.lowercase(Locale.US).matches(
                    Regex("^" + Regex.escape(base) + "\\.r\\d\\d$"),
                )
            }
            .sortedBy { it.name.lowercase(Locale.US).takeLast(2).toIntOrNull() ?: 0 }
        return listOf(baseFile) + siblings
    }

    if (lower.endsWith(".zip")) {
        val base = lower.dropLast(4)
        val split = directory.listFiles().orEmpty().any {
            it.isFile && it.name.lowercase(Locale.US).matches(
                Regex("^" + Regex.escape(base) + "\\.z\\d\\d$"),
            )
        }
        if (split) {
            throw PatchSourceException(
                "$name has split ZIP volumes; this importer accepts a single ZIP/ZIP64",
                PatchSourceFailure.UNSUPPORTED_ARCHIVE,
            )
        }
    }
    return listOf(selected)
}

private fun requireContiguous(parts: List<File>, start: Int) {
    val numbers = parts.map { partNumber(it.name) }
    if (numbers.isEmpty() || numbers.first() != start) {
        throw PatchSourceException(
            "multi-part archive is missing part $start",
            PatchSourceFailure.MISSING_PART,
        )
    }
    for (i in numbers.indices) {
        if (numbers[i] != start + i) {
            throw PatchSourceException(
                "multi-part archive is missing part ${start + i}",
                PatchSourceFailure.MISSING_PART,
            )
        }
    }
}

private fun partNumber(name: String): Int {
    val lower = name.lowercase(Locale.US)
    PART_RAR.matchEntire(lower)?.let { return it.groupValues[2].toInt() }
    if (lower.matches(Regex(""".*\.r\d\d$"""))) {
        return lower.takeLast(2).toInt() + 1
    }
    return Int.MAX_VALUE
}

/**
 * Returns the ordered file names of one archive group, or null when [names]
 * contains no archive. More than one group is ambiguous and is rejected.
 */
internal fun choosePartGroup(names: List<String>): List<String>? {
    if (names.isEmpty()) return null
    val lower = names.map { it.lowercase(Locale.US) }

    if (lower.any { it.matches(Regex(""".*\.7z\.\d{3}$""")) }) {
        throw PatchSourceException(
            "multi-volume 7z (.001) is not supported; use a single .7z or extract " +
                "the patch to a folder",
            PatchSourceFailure.UNSUPPORTED_ARCHIVE,
        )
    }

    val partGroups = HashMap<String, MutableList<Pair<String, Int>>>()
    names.forEach { name ->
        val match = PART_RAR.matchEntire(name.lowercase(Locale.US))
        if (match != null) {
            partGroups.getOrPut(match.groupValues[1]) { ArrayList() }
                .add(name to match.groupValues[2].toInt())
        }
    }
    if (partGroups.isNotEmpty()) {
        if (partGroups.size > 1) {
            throw PatchSourceException(
                "more than one multi-part RAR in the folder: " +
                    partGroups.keys.sorted().joinToString(", "),
                PatchSourceFailure.UNSUPPORTED_ARCHIVE,
            )
        }
        val group = partGroups.values.first().sortedBy { it.second }
        if (group.first().second != 1) {
            throw PatchSourceException(
                "multi-part RAR is missing part 1",
                PatchSourceFailure.MISSING_PART,
            )
        }
        group.forEachIndexed { index, pair ->
            if (pair.second != index + 1) {
                throw PatchSourceException(
                    "multi-part RAR is missing part ${index + 1}",
                    PatchSourceFailure.MISSING_PART,
                )
            }
        }
        return group.map { it.first }
    }

    val rarBases = names.filter {
        it.lowercase(Locale.US).endsWith(".rar") &&
            !PART_RAR.matches(it.lowercase(Locale.US))
    }
    if (rarBases.isNotEmpty()) {
        if (rarBases.size > 1) {
            throw PatchSourceException(
                "more than one RAR in the folder: ${rarBases.joinToString(", ")}",
                PatchSourceFailure.UNSUPPORTED_ARCHIVE,
            )
        }
        val selected = rarBases[0]
        val base = selected.lowercase(Locale.US).dropLast(4)
        val extras = names.filter {
            it.lowercase(Locale.US).matches(
                Regex("^" + Regex.escape(base) + "\\.r\\d\\d$"),
            )
        }.sortedBy { it.lowercase(Locale.US).takeLast(2).toIntOrNull() ?: 0 }
        extras.forEachIndexed { index, name ->
            val number = name.lowercase(Locale.US).takeLast(2).toIntOrNull() ?: -1
            if (number != index) {
                throw PatchSourceException(
                    "multi-part RAR is missing part r${index.toString().padStart(2, '0')}",
                    PatchSourceFailure.MISSING_PART,
                )
            }
        }
        return listOf(selected) + extras
    }

    if (lower.any { RAR_OLD.matches(it) }) {
        throw PatchSourceException(
            "old-style RAR parts (.r00, .r01, ...) need their .rar first volume; " +
                "choose the folder that contains every part",
            PatchSourceFailure.MISSING_PART,
        )
    }

    val sevenZips = names.filter { it.lowercase(Locale.US).endsWith(".7z") }
    if (sevenZips.isNotEmpty()) {
        if (sevenZips.size > 1) {
            throw PatchSourceException(
                "more than one 7z in the folder: ${sevenZips.joinToString(", ")}",
                PatchSourceFailure.UNSUPPORTED_ARCHIVE,
            )
        }
        return sevenZips
    }

    val zips = names.filter { it.lowercase(Locale.US).endsWith(".zip") }
    if (zips.isNotEmpty()) {
        if (zips.size > 1) {
            throw PatchSourceException(
                "more than one ZIP in the folder: ${zips.joinToString(", ")}",
                PatchSourceFailure.UNSUPPORTED_ARCHIVE,
            )
        }
        val base = zips[0].lowercase(Locale.US).dropLast(4)
        if (names.any { it.lowercase(Locale.US).matches(Regex("^" + Regex.escape(base) + "\\.z\\d\\d$")) }) {
            throw PatchSourceException(
                "split ZIP volumes are not supported; use a single ZIP/ZIP64",
                PatchSourceFailure.UNSUPPORTED_ARCHIVE,
            )
        }
        return zips
    }

    if (lower.any { it.matches(Regex(""".*\.\d{3}$""")) && !it.endsWith(".7z") }) {
        throw PatchSourceException(
            "numbered archive volumes are not supported; select the folder with all " +
                "parts or a complete archive",
            PatchSourceFailure.UNSUPPORTED_ARCHIVE,
        )
    }
    return null
}

internal fun findArchiveGroup(directory: File): List<File>? {
    val files = directory.listFiles().orEmpty()
        .filter { it.isFile }
        .sortedBy { it.name.lowercase(Locale.US) }
    val names = files.map { it.name }
    val chosen = choosePartGroup(names) ?: return null
    val byName = files.groupBy { it.name }
    return chosen.map { name ->
        val matches = byName[name].orEmpty()
        if (matches.size != 1) {
            throw PatchSourceException(
                "ambiguous archive part $name",
                PatchSourceFailure.UNSUPPORTED_ARCHIVE,
            )
        }
        matches[0]
    }
}

internal fun hasContentDirectory(directory: File): Boolean =
    directory.name.equals("Content", ignoreCase = true) ||
        File(directory, "Content").isDirectory ||
        File(directory, "content").isDirectory

internal fun resolveRealFile(context: Context, uri: Uri): File? {
    if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf { it.isFile }
    val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        ?: return null
    val authority = uri.authority ?: return null
    if (authority == "com.android.externalstorage.documents") {
        val split = documentId.split(':', limit = 2)
        val volume = split[0]
        val relative = split.getOrElse(1) { "" }
        val root = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/$volume")
        }
        val file = File(root, relative)
        return if (file.isFile) file else null
    }
    if (authority == "com.android.providers.downloads.documents" &&
        documentId.startsWith("raw:")
    ) {
        val file = File(documentId.substring(4))
        return if (file.isFile) file else null
    }
    return null
}

internal fun resolveRealDirectory(context: Context, uri: Uri): File? {
    if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf { it.isDirectory }
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        ?: return null
    val authority = uri.authority ?: return null
    if (authority != "com.android.externalstorage.documents") return null
    val split = documentId.split(':', limit = 2)
    val volume = split[0]
    val relative = split.getOrElse(1) { "" }
    val root = if (volume.equals("primary", ignoreCase = true)) {
        Environment.getExternalStorageDirectory()
    } else {
        File("/storage/$volume")
    }
    val file = File(root, relative)
    return if (file.isDirectory) file else null
}

internal fun queryDisplayName(context: Context, uri: Uri): String? {
    var name: String? = null
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                name = cursor.getString(column)
            }
        }
    }
    return name ?: uri.lastPathSegment?.substringAfterLast('/')
}

internal fun querySize(context: Context, uri: Uri): Long {
    var size = -1L
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                size = cursor.getLong(column)
            }
        }
    }
    return size
}

private fun isSeekable(descriptor: ParcelFileDescriptor): Boolean =
    try {
        Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_CUR)
        true
    } catch (_: ErrnoException) {
        false
    }

private fun createCacheDir(context: Context, name: String): File {
    val dir = File(context.cacheDir, "$name/${System.currentTimeMillis()}-${COUNTER.incrementAndGet()}")
    if (!dir.mkdirs() && !dir.isDirectory) {
        throw PatchSourceException(
            "cannot create ${dir.path}",
            PatchSourceFailure.IO,
        )
    }
    return dir
}

private fun copyUriToCache(
    context: Context,
    uri: Uri,
    dir: File,
    name: String,
    originalSize: Long,
): File {
    val target = File(dir, safeName(name))
    checkSpace(dir, originalSize)
    val input = try {
        context.contentResolver.openInputStream(uri)
    } catch (t: Throwable) {
        throw PatchSourceException(
            "cannot read $name: ${t.message}",
            PatchSourceFailure.IO,
            t,
        )
    } ?: throw PatchSourceException(
        "cannot read $name: provider returned no stream",
        PatchSourceFailure.IO,
    )
    input.use { source ->
        BufferedOutputStream(FileOutputStream(target), COPY_BUFFER).use { sink ->
            source.copyTo(sink, COPY_BUFFER)
        }
    }
    return target
}

private fun copyDescriptor(
    context: Context,
    descriptor: ParcelFileDescriptor,
    dir: File,
    name: String,
    originalSize: Long,
): File {
    val target = File(dir, safeName(name))
    checkSpace(dir, originalSize)
    BufferedInputStream(FileInputStream(descriptor.fileDescriptor), COPY_BUFFER).use { source ->
        BufferedOutputStream(FileOutputStream(target), COPY_BUFFER).use { sink ->
            source.copyTo(sink, COPY_BUFFER)
        }
    }
    return target
}

private fun checkSpace(dir: File, size: Long) {
    if (size <= 0) return
    val available = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
    val reserve = 16L * 1024 * 1024
    if (available < size + reserve) {
        throw PatchSourceException(
            "the archive cannot be read in place; copying ${gb(size)} to ${dir.path} " +
                "needs ${gb(size + reserve)} free, but only ${gb(available)} is available",
            PatchSourceFailure.NOT_SEEKABLE,
        )
    }
}

private fun safeName(name: String): String {
    val cleaned = name.replace(Regex("""[^A-Za-z0-9._ -]"""), "_").take(120)
    return if (cleaned.isBlank()) "part" else cleaned
}

private fun gb(bytes: Long): String =
    String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)

private val PART_RAR = Regex("""^(.*)\.part(\d+)\.rar$""", RegexOption.IGNORE_CASE)
private val RAR_OLD = Regex(""".*\.r\d\d$""", RegexOption.IGNORE_CASE)
private val SPLIT_ZIP = Regex(""".*\.z\d\d$""", RegexOption.IGNORE_CASE)
private val COUNTER = java.util.concurrent.atomic.AtomicInteger(0)
private const val COPY_BUFFER = 1 shl 20
