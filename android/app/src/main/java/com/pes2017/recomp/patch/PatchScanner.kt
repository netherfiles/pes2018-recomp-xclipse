package com.pes2017.recomp.patch

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Reads a patch source and produces a [PatchPlan].
 *
 * Only the first 0x20000 bytes of each CON are parsed. A CPK's 1.5 GB payload
 * is never extracted for scanning; a save (about 5 MB) is read in full because
 * its outer SHA-256 is what identifies duplicate copies, and its data.bin is
 * verified block by block to report a hash the installer can trust.
 *
 * The scanner is JVM-testable: it only knows [PatchSource] and [StfsPackage],
 * not Android or libarchive.
 */
class PatchScanner(private val scratchDir: File) {

    fun scan(source: PatchSource, backupBytes: Long = 0L): PatchPlan {
        val temps = ArrayList<File>()
        val cpks = LinkedHashMap<String, CpkCandidate>()
        val saves = ArrayList<SaveCandidate>()
        val skipped = ArrayList<SkippedItem>()
        val warnings = ArrayList<String>()
        var dpFileList: DpCandidate? = null
        var sawTitle = false

        try {
            val session = source.openSession()
            try {
                while (true) {
                    val entry = session.next() ?: break
                    if (entry.isDirectory) continue
                    val insideTitle = inTitleTree(entry.path)
                    if (insideTitle) sawTitle = true

                    val stream = try {
                        session.open()
                    } catch (e: PatchSourceException) {
                        throw sourceFailure(e)
                    }
                    var head: ByteArray
                    try {
                        head = readUpTo(stream, if (insideTitle) PREFIX_BYTES else OUTSIDE_HEAD)
                        if (!isStfsMagic(head)) {
                            skipped.add(
                                SkippedItem(
                                    entry.path,
                                    entry.size,
                                    if (insideTitle) "not an STFS package" else "outside 4A3007D3",
                                ),
                            )
                            continue
                        }
                        if (!insideTitle) {
                            val outsideName = entry.path.substringAfterLast('/')
                            if (hasSegment(entry.path, "FFFE07D1")) {
                                skipped.add(
                                    SkippedItem(entry.path, entry.size, "Xbox profile container"),
                                )
                                continue
                            }
                            if (outsideName.equals("ContentCache.pkg", ignoreCase = true)) {
                                skipped.add(
                                    SkippedItem(entry.path, entry.size, "content cache"),
                                )
                                continue
                            }
                            if (head.size >= 0x348) {
                                when (be32(head, 0x344)) {
                                    CT_TITLE_UPDATE -> {
                                        skipped.add(
                                            SkippedItem(entry.path, entry.size, "title update"),
                                        )
                                        continue
                                    }

                                    CT_PROFILE -> {
                                        skipped.add(
                                            SkippedItem(entry.path, entry.size, "Xbox profile container"),
                                        )
                                        continue
                                    }

                                    CT_CACHE -> {
                                        skipped.add(
                                            SkippedItem(entry.path, entry.size, "content cache"),
                                        )
                                        continue
                                    }
                                }
                            }
                            if (head.size < TITLE_FIELD_END) {
                                skipped.add(
                                    SkippedItem(entry.path, entry.size, "truncated package outside 4A3007D3"),
                                )
                                continue
                            }
                            val title = be32(head, 0x360)
                            if (title != TITLE_ID) {
                                skipped.add(
                                    SkippedItem(
                                        entry.path,
                                        entry.size,
                                        "another title (0x${title.toString(16).uppercase(Locale.US)})",
                                    ),
                                )
                                continue
                            }
                            sawTitle = true
                            val missing = PREFIX_BYTES - head.size
                            if (missing > 0) {
                                head += readUpTo(stream, missing)
                            }
                        }

                        val prefix = try {
                            StfsPrefix.parse(head)
                        } catch (e: StfsException) {
                            throw stfsFailure(entry.path, e)
                        }

                        when (val result = classify(prefix, entry)) {
                            is Classification.Cpk -> {
                                val key = result.candidate.innerName.lowercase(Locale.US)
                                if (cpks.put(key, result.candidate) != null) {
                                    throw PatchScanException(
                                        PatchScanFailure.DUPLICATE_CPK,
                                        "the patch contains two CPKs named " +
                                            result.candidate.innerName,
                                    )
                                }
                            }

                            is Classification.DpFileList -> {
                                if (dpFileList != null) {
                                    throw PatchScanException(
                                        PatchScanFailure.DUPLICATE_DPFILELIST,
                                        "the patch contains more than one DpFileList.bin " +
                                            "(${dpFileList!!.sourcePath} and ${result.candidate.sourcePath})",
                                    )
                                }
                                dpFileList = result.candidate
                            }

                            is Classification.Save -> {
                                val candidate = if (entry.file != null) {
                                    readSave(
                                        entry.file,
                                        entry,
                                        result.slot,
                                        outerSha256 = sha256File(entry.file),
                                    )
                                } else {
                                    if (entry.size < 0) {
                                        throw PatchScanException(
                                            PatchScanFailure.UNSUPPORTED,
                                            "${entry.path}: save CON size is unknown; " +
                                                "cannot decide whether it is safe to copy",
                                        )
                                    }
                                    if (entry.size > MAX_SAVE_CON) {
                                        throw PatchScanException(
                                            PatchScanFailure.UNSUPPORTED,
                                            "${entry.path} is ${entry.size} bytes; " +
                                                "the scanner reads saves up to " +
                                                "${MAX_SAVE_CON} bytes. Extract the patch " +
                                                "to a folder and scan it there.",
                                        )
                                    }
                                    val (temp, hash) = spool(head, stream, temps)
                                    readSave(temp, entry, result.slot, hash)
                                }
                                saves.add(candidate)
                            }

                            is Classification.Skip -> skipped.add(
                                SkippedItem(entry.path, entry.size, result.reason),
                            )
                        }
                    } finally {
                        runCatching { stream.close() }
                    }
                }
            } finally {
                session.close()
            }
        } catch (e: PatchSourceException) {
            throw sourceFailure(e)
        } finally {
            temps.forEach { runCatching { it.delete() } }
        }

        if (!sawTitle) {
            throw PatchScanException(
                PatchScanFailure.WRONG_TITLE,
                "no content under title 4A3007D3 was found in ${source.label}",
            )
        }

        val list = dpFileList
        if (list == null) {
            if (cpks.size == 1) {
                throw PatchScanException(
                    PatchScanFailure.SINGLE_CPK_WITHOUT_LIST,
                    "the patch contains one CPK but no DpFileList.bin; a single CPK " +
                        "cannot be ordered against a patch list",
                )
            }
            throw PatchScanException(
                PatchScanFailure.NO_DPFILELIST,
                "no DpFileList.bin was found under 4A3007D3",
            )
        }

        val priorities = HashMap<String, Int>()
        list.entries.forEach { record ->
            val key = record.name.lowercase(Locale.US)
            val cpk = cpks[key] ?: throw PatchScanException(
                PatchScanFailure.MISSING_LISTED_CPK,
                "DpFileList.bin references ${record.name}, but no CPK with that " +
                    "inner name was found",
            )
            if (priorities.put(key, record.priority) != null) {
                warnings.add("DpFileList.bin lists ${record.name} more than once")
            }
            if (!cpk.innerName.equals(record.name, ignoreCase = true)) {
                warnings.add(
                    "DpFileList.bin spells ${record.name}; the CPK inner name is " +
                        cpk.innerName,
                )
            }
        }
        val plannedCpks = cpks.values.map { candidate ->
            val key = candidate.innerName.lowercase(Locale.US)
            candidate.toPlanned(
                listed = priorities.containsKey(key),
                priority = priorities[key],
            )
        }.sortedWith(compareBy({ it.priority ?: Int.MAX_VALUE }, { it.innerName }))

        if (plannedCpks.any { !it.listed }) {
            warnings.add(
                "CPK(s) not referenced by DpFileList.bin: " +
                    plannedCpks.filter { !it.listed }
                        .joinToString(", ") { it.innerName } +
                    " (still extracted; the game may enumerate the folder)",
            )
        }
        if (list.entries.zipWithNext().any { (a, b) -> a.priority <= b.priority }) {
            warnings.add("DpFileList.bin priorities are not strictly descending")
        }

        val byHash = saves.groupBy { it.outerSha256.lowercase(Locale.US) }
        val uniqueSaves = ArrayList<SaveCandidate>()
        val duplicates = ArrayList<DuplicateSave>()
        byHash.forEach { (hash, group) ->
            if (group.map { it.slot }.distinct().size > 1) {
                throw PatchScanException(
                    PatchScanFailure.CONFLICTING_SAVES,
                    "one save package is named both EDIT00000000 and SYSTEM00000000",
                )
            }
            val canonical = group.sortedWith(
                compareBy(
                    { it.sourcePath.contains("/0000000000000000/", ignoreCase = true) },
                    { it.sourcePath },
                ),
            ).first()
            val copies = group.map { it.sourcePath }
            if (copies.size > 1) {
                duplicates.add(
                    DuplicateSave(
                        slot = canonical.slot,
                        sha256 = canonical.outerSha256,
                        canonicalPath = canonical.sourcePath,
                        copyPaths = copies.filter { it != canonical.sourcePath },
                    ),
                )
                warnings.add(
                    "${canonical.slot.targetName} appears ${copies.size} times with the " +
                        "same SHA-256; using ${canonical.sourcePath}",
                )
            }
            uniqueSaves.add(canonical.copy(copies = copies))
        }
        uniqueSaves.groupBy { it.slot }.forEach { (slot, group) ->
            if (group.size > 1) {
                throw PatchScanException(
                    PatchScanFailure.CONFLICTING_SAVES,
                    "the patch contains ${group.size} different ${slot.targetName} " +
                        "saves (${group.joinToString(", ") { it.sourcePath }}); " +
                        "only one can be installed",
                )
            }
        }
        val plannedSaves = uniqueSaves.sortedBy { it.slot.ordinal }.map { it.toPlanned() }
        val plannedDp = list.toPlanned()

        val cpkBytes = plannedCpks.fold(0L) { total, cpk ->
            PatchPlan.safeAdd(total, cpk.size)
        }
        val saveBytes = plannedSaves.fold(0L) { total, save ->
            PatchPlan.safeAdd(total, save.outerSize)
        }
        val dataBinBytes = plannedSaves.fold(0L) { total, save ->
            PatchPlan.safeAdd(total, save.dataBinSize)
        }
        val rawBytes = PatchPlan.safeAdd(
            cpkBytes,
            PatchPlan.safeAdd(saveBytes, plannedDp.size),
        )
        val stagingBytes = rawBytes
        val sourceParts = source.sourceBytes.coerceAtLeast(0)
        val backup = backupBytes.coerceAtLeast(0)
        val peakBytes = PatchPlan.safeAdd(
            sourceParts,
            PatchPlan.safeAdd(stagingBytes, backup),
        )

        return PatchPlan(
            name = planName(source),
            sourceKind = source.kind,
            sourceLabel = source.label,
            titleId = "4A3007D3",
            cpks = plannedCpks,
            dpFileList = plannedDp,
            saves = plannedSaves,
            duplicates = duplicates,
            skipped = skipped,
            warnings = warnings,
            cpkBytes = cpkBytes,
            dataBinBytes = dataBinBytes,
            dpFileListBytes = plannedDp.size,
            saveBytes = saveBytes,
            rawBytes = rawBytes,
            stagingBytes = stagingBytes,
            sourcePartsBytes = sourceParts,
            backupBytes = backup,
            peakBytes = peakBytes,
        )
    }

    // ---- classification --------------------------------------------------

    private sealed interface Classification {
        data class Cpk(val candidate: CpkCandidate) : Classification
        data class DpFileList(val candidate: DpCandidate) : Classification
        data class Save(val slot: SaveSlot) : Classification
        data class Skip(val reason: String) : Classification
    }

    private fun classify(prefix: StfsPrefix, entry: PatchSourceEntry): Classification {
        val header = prefix.header
        val path = entry.path
        val name = path.substringAfterLast('/')

        if (header.contentType == CT_TITLE_UPDATE || hasSegment(path, "000B0000")) {
            return Classification.Skip("title update (content type 0xB0000)")
        }
        if (header.contentType == CT_PROFILE || hasSegment(path, "FFFE07D1")) {
            return Classification.Skip("Xbox profile container (content type 0x10000)")
        }
        if (header.contentType == CT_CACHE ||
            name.equals("ContentCache.pkg", ignoreCase = true)
        ) {
            return Classification.Skip("content cache (content type 0x40000)")
        }
        if (header.titleId != TITLE_ID) {
            if (isPatchShaped(prefix)) {
                throw PatchScanException(
                    PatchScanFailure.WRONG_TITLE,
                    "${entry.path}: STFS title ID is 0x" +
                        header.titleId.toString(16).uppercase(Locale.US) +
                        ", expected 0x4A3007D3",
                )
            }
            return Classification.Skip(
                "another title ID (0x${header.titleId.toString(16).uppercase(Locale.US)})",
            )
        }

        return when (header.layout) {
            StfsLayout.PERSISTENT -> classifyPersistent(prefix, entry)
            StfsLayout.SAVE -> classifySave(prefix, entry, name)
        }
    }

    /**
     * True when the package has the shape of a DLC CPK/list or a save. Such a
     * package under the 4A3007D3 tree with another title ID is a wrong-title
     * failure, not something to skip silently.
     */
    private fun isPatchShaped(prefix: StfsPrefix): Boolean {
        if (prefix.header.contentType != CT_DLC) return false
        val files = prefix.entries.filter { !it.directory }
        return when (prefix.header.layout) {
            StfsLayout.PERSISTENT -> files.size == 1 && (
                files[0].name.equals("DpFileList.bin", ignoreCase = true) ||
                    files[0].name.lowercase(Locale.US).endsWith(".cpk")
                )

            StfsLayout.SAVE -> files.any {
                it.name.equals("data.bin", ignoreCase = true)
            }
        }
    }

    private fun classifyPersistent(
        prefix: StfsPrefix,
        entry: PatchSourceEntry,
    ): Classification {
        if (prefix.header.contentType != CT_DLC) {
            return Classification.Skip(
                "0xAD0E CON with content type 0x" +
                    prefix.header.contentType.toString(16),
            )
        }
        val files = prefix.entries.filter { !it.directory }
        if (files.size != 1) {
            return Classification.Skip(
                "0xAD0E CON with an unrecognised file table " +
                    "(${files.size} file entries)",
            )
        }
        val inner = files[0]
        if (inner.name.equals("DpFileList.bin", ignoreCase = true)) {
            if (inner.size > STFS_BLOCK_SIZE) {
                throw PatchScanException(
                    PatchScanFailure.UNSUPPORTED,
                    "${entry.path}: DpFileList.bin is ${inner.size} bytes and spans " +
                        "more than one block; the scanner only reads one block",
                )
            }
            val bytes = try {
                prefix.readInner(inner, inner.size.toInt())
            } catch (e: StfsException) {
                throw stfsFailure(entry.path, e)
            }
            val records = try {
                DpFileListParser.parse(bytes)
            } catch (e: StfsException) {
                throw PatchScanException(
                    PatchScanFailure.CORRUPT,
                    "${entry.path}: ${e.message}",
                    e,
                )
            }
            return Classification.DpFileList(
                DpCandidate(
                    sourcePath = entry.path,
                    innerName = inner.name,
                    size = inner.size,
                    sha256 = sha256(bytes),
                    blocks = inner.blocks,
                    startBlock = inner.start,
                    consecutive = inner.consecutive,
                    entries = records,
                ),
            )
        }
        if (!inner.name.lowercase(Locale.US).endsWith(".cpk")) {
            return Classification.Skip("0xAD0E CON with an unrecognised file table")
        }
        if (!isSafePatchFileName(inner.name)) {
            throw PatchScanException(
                PatchScanFailure.UNSAFE_NAME,
                "${entry.path}: inner CPK name is not a plain filename: ${inner.name}",
            )
        }
        val magic = try {
            prefix.readInner(inner, 0x40)
        } catch (e: StfsException) {
            throw stfsFailure(entry.path, e)
        }
        if (magic.size < 0x40 || String(magic, 0, 4, Charsets.ISO_8859_1) != "CPK ") {
            throw PatchScanException(
                PatchScanFailure.CORRUPT,
                "${entry.path}: ${inner.name} does not start with CPK magic",
            )
        }
        val variant = magic[4].toInt() and 0xFF
        if (variant != 0xFF && variant != 0x00) {
            throw PatchScanException(
                PatchScanFailure.CORRUPT,
                "${entry.path}: ${inner.name} has CPK variant " +
                    "0x${variant.toString(16)}, expected 0xFF or 0x00",
            )
        }
        if (inner.size <= 0) {
            throw PatchScanException(
                PatchScanFailure.CORRUPT,
                "${entry.path}: ${inner.name} is empty",
            )
        }
        return Classification.Cpk(
            CpkCandidate(
                innerName = inner.name,
                sourcePath = entry.path,
                outerSize = entry.size.coerceAtLeast(0),
                size = inner.size,
                blocks = inner.blocks,
                startBlock = inner.start,
                consecutive = inner.consecutive,
                variant = variant,
            ),
        )
    }

    private fun classifySave(
        prefix: StfsPrefix,
        entry: PatchSourceEntry,
        name: String,
    ): Classification {
        if (prefix.header.contentType != CT_DLC) {
            return Classification.Skip(
                "0x971A CON with content type 0x" +
                    prefix.header.contentType.toString(16),
            )
        }
        val dataBin = prefix.entries.firstOrNull {
            !it.directory && it.name.equals("data.bin", ignoreCase = true)
        } ?: return Classification.Skip("save container without data.bin")
        if (dataBin.size <= 0) {
            return Classification.Skip("save container with an empty data.bin")
        }
        val slot = when (name.uppercase(Locale.US)) {
            "EDIT00000000" -> SaveSlot.EDIT
            "SYSTEM00000000" -> SaveSlot.SYSTEM
            else -> return Classification.Skip("save container with an unknown slot name")
        }
        return Classification.Save(slot)
    }

    // ---- save reading ----------------------------------------------------

    private fun readSave(
        conFile: File,
        entry: PatchSourceEntry,
        slot: SaveSlot,
        outerSha256: String,
    ): SaveCandidate {
        if (entry.size >= 0 && conFile.length() != entry.size) {
            throw PatchScanException(
                PatchScanFailure.TRUNCATED_CON,
                "${entry.path}: ${entry.size} bytes in the source, ${conFile.length()} " +
                    "on disk",
            )
        }
        try {
            StfsPackage.open(conFile).use { pkg ->
                val dataBin = pkg.findNamed("data.bin") ?: throw PatchScanException(
                    PatchScanFailure.CORRUPT,
                    "${entry.path}: no data.bin in the save container",
                )
                val sink = Sha256Sink()
                val read = pkg.readEntry(dataBin, sink, verify = true)
                if (read.bytes != dataBin.size) {
                    throw PatchScanException(
                        PatchScanFailure.CORRUPT,
                        "${entry.path}: data.bin wrote ${read.bytes} of ${dataBin.size} bytes",
                    )
                }
                val dataBinSha = sink.digestHex()
                val payload = try {
                    SavePayloadParser.parse(sink.headerBytes(), dataBin.size)
                } catch (e: StfsException) {
                    throw PatchScanException(
                        PatchScanFailure.CORRUPT,
                        "${entry.path}: ${e.message}",
                        e,
                    )
                }
                val inf = pkg.findNamed("data.inf")
                val infName = if (inf != null) {
                    val bytes = pkg.readPrefix(inf, minOf(inf.size, 4096L).toInt())
                    String(bytes, Charsets.ISO_8859_1).substringBefore('\u0000')
                } else {
                    null
                }
                val pngSize = pkg.findNamed("data.png")?.size
                return SaveCandidate(
                    slot = slot,
                    sourcePath = entry.path,
                    outerSize = conFile.length(),
                    outerSha256 = outerSha256,
                    dataBinSize = dataBin.size,
                    dataBinSha256 = dataBinSha,
                    payload = payload,
                    dataInfName = infName,
                    dataPngSize = pngSize,
                    copies = listOf(entry.path),
                )
            }
        } catch (e: StfsException) {
            throw stfsFailure(entry.path, e)
        }
    }

    private fun spool(
        head: ByteArray,
        stream: InputStream,
        temps: MutableList<File>,
    ): Pair<File, String> {
        if (!scratchDir.isDirectory && !scratchDir.mkdirs()) {
            throw PatchScanException(
                PatchScanFailure.IO,
                "cannot create scan scratch directory ${scratchDir.path}",
            )
        }
        val temp = File(scratchDir, "scan-${UUID.randomUUID()}.con")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            DigestOutputStream(
                BufferedOutputStream(FileOutputStream(temp), COPY_BUFFER),
                digest,
            ).use { out ->
                out.write(head)
                stream.copyTo(out, COPY_BUFFER)
            }
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            throw PatchScanException(
                PatchScanFailure.IO,
                "cannot copy a save CON to ${temp.path}: ${t.message}",
                t,
            )
        }
        temps.add(temp)
        return temp to digest.digest().toHex()
    }

    // ---- failures --------------------------------------------------------

    private fun stfsFailure(path: String, e: StfsException): PatchScanException {
        val failure = when (e.kind) {
            StfsErrorKind.UNKNOWN_LAYOUT -> PatchScanFailure.UNKNOWN_STFS_HEADER
            StfsErrorKind.TRUNCATED -> PatchScanFailure.TRUNCATED_CON
            StfsErrorKind.HASH_MISMATCH -> PatchScanFailure.CORRUPT
            StfsErrorKind.MALFORMED -> PatchScanFailure.CORRUPT
            StfsErrorKind.UNSUPPORTED_LIMIT -> PatchScanFailure.UNSUPPORTED
        }
        return PatchScanException(failure, "$path: ${e.message}", e)
    }

    private fun sourceFailure(e: PatchSourceException): PatchScanException {
        val failure = when (e.failure) {
            PatchSourceFailure.PASSWORD -> PatchScanFailure.PASSWORD
            PatchSourceFailure.MISSING_PART -> PatchScanFailure.MISSING_PART
            PatchSourceFailure.UNSUPPORTED_ARCHIVE -> PatchScanFailure.UNSUPPORTED
            PatchSourceFailure.NOT_SEEKABLE -> PatchScanFailure.UNSUPPORTED
            PatchSourceFailure.IO -> PatchScanFailure.IO
        }
        return PatchScanException(failure, e.message ?: "source error", e)
    }

    // ---- small pieces ----------------------------------------------------

    private fun planName(source: PatchSource): String {
        val label = source.label.ifBlank { "PATCH" }
        return if (source.kind == PatchSourceKind.FOLDER) {
            label
        } else {
            label.substringBeforeLast('.', label)
        }
    }

    private fun inTitleTree(path: String): Boolean = hasSegment(path, "4A3007D3")

    private fun hasSegment(path: String, wanted: String): Boolean =
        path.split('/').any { it.equals(wanted, ignoreCase = true) }

    private fun isStfsMagic(head: ByteArray): Boolean {
        if (head.size < 4) return false
        val magic = String(head, 0, 4, Charsets.ISO_8859_1)
        return magic == "CON " || magic == "LIVE" || magic == "PIRS"
    }

    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, COPY_BUFFER))
        val buffer = ByteArray(minOf(COPY_BUFFER, limit.coerceAtLeast(1)))
        var left = limit
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, left))
            if (read <= 0) break
            out.write(buffer, 0, read)
            left -= read
        }
        return out.toByteArray()
    }

    private companion object {
        const val TITLE_ID = 0x4A3007D3L
        const val PREFIX_BYTES = 0x20000
        const val OUTSIDE_HEAD = 0x400
        const val TITLE_FIELD_END = 0x364
        const val MAX_SAVE_CON = 64L * 1024 * 1024
        const val COPY_BUFFER = 64 * 1024
        const val CT_DLC = 1L
        const val CT_PROFILE = 0x10000L
        const val CT_CACHE = 0x40000L
        const val CT_TITLE_UPDATE = 0xB0000L
    }

    private class Sha256Sink : OutputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        private val header = ByteArrayOutputStream()

        override fun write(b: Int) {
            digest.update(b.toByte())
            capture(byteArrayOf(b.toByte()))
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            digest.update(b, off, len)
            capture(b.copyOfRange(off, off + len))
        }

        private fun capture(bytes: ByteArray) {
            val remaining = SavePayloadParser.MIN_HEADER_BYTES - header.size()
            if (remaining > 0) header.write(bytes, 0, minOf(remaining, bytes.size))
        }

        fun digestHex(): String = digest.digest().toHex()

        fun headerBytes(): ByteArray = header.toByteArray()
    }

    private data class CpkCandidate(
        val innerName: String,
        val sourcePath: String,
        val outerSize: Long,
        val size: Long,
        val blocks: Int,
        val startBlock: Int,
        val consecutive: Boolean,
        val variant: Int,
        val listed: Boolean = false,
        val priority: Int? = null,
    ) {
        fun toPlanned(listed: Boolean, priority: Int?): PlannedCpk = PlannedCpk(
            innerName = innerName,
            sourcePath = sourcePath,
            outerSize = outerSize,
            size = size,
            blocks = blocks,
            startBlock = startBlock,
            consecutive = consecutive,
            variant = variant,
            priority = priority,
            listed = listed,
        )
    }

    private data class DpCandidate(
        val sourcePath: String,
        val innerName: String,
        val size: Long,
        val sha256: String,
        val blocks: Int,
        val startBlock: Int,
        val consecutive: Boolean,
        val entries: List<DpFileListEntry>,
    ) {
        fun toPlanned(): PlannedDpFileList = PlannedDpFileList(
            sourcePath = sourcePath,
            innerName = innerName,
            size = size,
            sha256 = sha256,
            blocks = blocks,
            startBlock = startBlock,
            consecutive = consecutive,
            entries = entries,
        )
    }

    private data class SaveCandidate(
        val slot: SaveSlot,
        val sourcePath: String,
        val outerSize: Long,
        val outerSha256: String,
        val dataBinSize: Long,
        val dataBinSha256: String,
        val payload: SavePayload,
        val dataInfName: String?,
        val dataPngSize: Long?,
        val copies: List<String>,
    ) {
        fun toPlanned(): PlannedSave = PlannedSave(
            slot = slot,
            sourcePath = sourcePath,
            targetName = slot.targetName,
            outerSize = outerSize,
            outerSha256 = outerSha256,
            dataBinSize = dataBinSize,
            dataBinSha256 = dataBinSha256,
            payload = payload,
            dataInfName = dataInfName,
            dataPngSize = dataPngSize,
            copies = copies,
        )
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}
