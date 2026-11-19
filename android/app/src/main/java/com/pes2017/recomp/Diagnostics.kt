package com.pes2017.recomp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import androidx.core.content.FileProvider
import com.pes2017.recomp.DiagnosticsReport.Entry
import com.pes2017.recomp.DiagnosticsReport.Status
import com.pes2017.recomp.patch.PatchesDashboard
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The one ZIP a bug report needs, made on the phone: the logs, the crashes, the
 * install record and the state files a developer otherwise asks for over adb.
 *
 *     Diagnostics.export(activity, note) { done -> ... }
 *     Diagnostics.summary(context)          // the short text for a copy button
 *     Diagnostics.share(context, done.zip)  // the system share sheet
 *
 * The export never runs on the main thread and never reads a whole log into
 * memory: a log is copied 64 KB at a time from the end, scrubbed line by line,
 * so a 2 MB game.log costs 64 KB of heap. One missing file is a manifest entry
 * saying `missing`, not a failed export -- the point of the zip is the
 * evidence that survived, and a phone in a bad state is exactly when something
 * is missing.
 *
 * A second tap while the first export is writing is refused rather than
 * started: two streams writing two zips in one folder is how the file the
 * share sheet opens is the half-written one.
 */
object Diagnostics {

    /** What the caller gets back, on the main thread: the zip, its size, or why not. */
    data class Done(val zip: File?, val bytes: Long, val error: String?)

    private const val TAG = "PES17.Diagnostics"
    private const val DIR = "diagnostics"
    private const val LAST_EXPORT = "last-export.txt"

    /** game.log is the run's own account and gets the most room. */
    private const val GAME_LOG_BYTES = 2 * 1024 * 1024

    /**
     * How that run began: the marker, the build, the driver and the capacity
     * line. Small, but it is the first thing a run longer than [GAME_LOG_BYTES]
     * loses to the tail above, and the first thing a developer reads.
     */
    private const val GAME_START_BYTES = 64 * 1024

    /** Everything else: a tail long enough for a fault, short enough to read. */
    private const val TAIL_BYTES = 256 * 1024
    private const val THREADS_BYTES = 512 * 1024
    private const val CRASH_HEAD_BYTES = 8 * 1024
    private const val STDERR_CAP = 2048

    /**
     * telemetry.csv is one row a second, so 2 MB is over half an hour of frame
     * times and audio counters -- the only place a run's frame distribution
     * survives it. Its own head is put back in front when the tail is cut.
     */
    private const val TELEMETRY_BYTES = 2 * 1024 * 1024

    /** Enough of a CSV's head to reach `# v1`, `# resolved:` and the row header. */
    private const val TELEMETRY_HEAD_BYTES = 8 * 1024

    /** The report reads a window of rows, not the run. */
    private const val TELEMETRY_REPORT_BYTES = 128 * 1024
    private const val TELEMETRY_REPORT_ROWS = 200

    /** Two zips' worth of headroom before the first byte is written. */
    private const val MIN_FREE = 2L * 1024 * 1024

    private const val KEEP_ZIPS = 3
    private const val TEN_MINUTES = 10 * 60 * 1000L

    private val busy = AtomicBoolean(false)

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diagnostics-export").apply { isDaemon = true }
    }

    private val mainHandler: Handler? by lazy {
        val looper = Looper.getMainLooper()
        if (looper == null) null else Handler(looper)
    }

    // ---- the API other screens call -----------------------------------------

    /**
     * Writes the zip and answers on the main thread. If [activity] is gone by
     * then -- the player pressed Back while it worked -- the result is kept in
     * `filesDir/last-export.txt` instead, so the next screen can say what
     * happened ([lastExport]).
     */
    fun export(activity: Activity, note: String, onDone: (Done) -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            deliver(
                activity,
                onDone,
                Done(null, 0, activity.applicationContext.getString(R.string.diagnostics_running)),
            )
            return
        }
        val context = activity.applicationContext
        worker.execute {
            val result = try {
                build(context, note)
            } catch (t: Throwable) {
                Log.e(TAG, "export failed", t)
                Done(null, 0, failure(context, t))
            } finally {
                busy.set(false)
            }
            deliver(activity, onDone, result)
        }
    }

    /**
     * The short text a Share or Copy button sends: header, the crash, the
     * install state, the game folder and the last few UI events. Bounded tails
     * only, so calling it from a click handler is safe.
     */
    fun summary(context: Context): String {
        val app = context.applicationContext
        return try {
            DiagnosticsReport.summary(gather(app, "", System.currentTimeMillis(), exits = false))
        } catch (t: Throwable) {
            "PES 2018 diagnostics\nsummary unavailable: ${DiagnosticsReport.oneLine(t.toString(), 200)}"
        }
    }

    /** The zip the system share sheet sends, through this app's own provider. */
    fun share(context: Context, zip: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostics_share_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.diagnostics_share_title))
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "could not share the zip", t)
        false
    }

    /**
     * The path of an export whose screen went away, or the reason there is no
     * zip. One read of a small file; safe on the main thread. The screen shows
     * this as "saved to ..." and then calls [clearLastExport].
     */
    fun lastExport(context: Context): String? = runCatching {
        val file = File(context.applicationContext.filesDir, LAST_EXPORT)
        if (!file.isFile) null else LogTail.tailText(file, 4 * 1024).trim().ifEmpty { null }
    }.getOrNull()

    /** Consumes the marker [lastExport] reads, so a toast shows once per export. */
    fun clearLastExport(context: Context) {
        runCatching { File(context.applicationContext.filesDir, LAST_EXPORT).delete() }
    }

    // ---- writing the zip ----------------------------------------------------

    private fun build(context: Context, note: String): Done {
        val dir = directory(context)
            ?: return Done(null, 0, context.getString(R.string.diagnostics_no_folder))
        deleteTemporaries(dir)
        val free = runCatching { dir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (free < MIN_FREE) return Done(null, 0, context.getString(R.string.diagnostics_no_space))

        val name = fileName()
        val target = File(dir, "$name.zip")
        val temp = File(dir, "$name.zip.tmp")
        val now = System.currentTimeMillis()
        val entries = ArrayList<Entry>()

        val facts = try {
            gather(context, note, now)
        } catch (t: Throwable) {
            // A report with only the header still names the build and the
            // device; that is worth more than no zip at all.
            Log.e(TAG, "could not gather every fact", t)
            DiagnosticsReport.Facts(app = appFacts(context), device = deviceFacts(), createdAt = now, note = note)
        }

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp), LogTail.CHUNK)).use { zip ->
                copyLogs(context, zip, entries)
                copyCrashes(context, zip, entries)
                copyState(context, zip, entries, now)
                copyLogcat(context, zip, entries, now)
                val report = DiagnosticsReport.report(facts.copy(entries = entries.toList()))
                writeText(zip, entries, "report.txt", report, now) { Scrub.text(context, it) }
                // The manifest is the file list, so it is written last and its
                // note is scrubbed before it goes in -- the rest of it (build,
                // device, entry paths) names nothing about the player.
                val manifest = DiagnosticsReport.manifest(
                    facts.copy(entries = entries.toList(), note = Scrub.text(context, note)),
                )
                writeText(zip, entries, "manifest.json", manifest, now, null)
            }
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            Log.e(TAG, "the zip could not be written", t)
            return Done(null, 0, failure(context, t))
        }

        if (target.exists()) runCatching { target.delete() }
        if (!temp.renameTo(target)) {
            runCatching { temp.delete() }
            return Done(
                null,
                0,
                context.getString(R.string.diagnostics_failed, "the ZIP could not be moved into place"),
            )
        }
        prune(dir, target)
        return Done(target, runCatching { target.length() }.getOrDefault(0L), null)
    }

    private fun directory(context: Context): File? {
        val external = context.getExternalFilesDir(DIR)
        if (external != null && (external.isDirectory || external.mkdirs())) return external
        // External storage can be unmounted; the provider exposes this one too.
        val internal = File(context.filesDir, DIR)
        return if (internal.isDirectory || internal.mkdirs()) internal else null
    }

    private fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val version = clean(BuildConfig.VERSION_NAME, "dev")
        val model = clean(Build.MODEL.ifBlank { Build.DEVICE }, "device")
        return "pes2018-diagnostics-$version-$stamp-$model"
    }

    /** A file name has no spaces, parentheses or slashes; a device name has all three. */
    private fun clean(raw: String, fallback: String): String {
        val cleaned = raw.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .replace(Regex("-{2,}"), "-")
            .trim('-', '.')
            .take(40)
        return cleaned.ifEmpty { fallback }
    }

    private fun deleteTemporaries(dir: File) {
        // Only one export runs at a time, so any .tmp here is from a process
        // that died mid-write.
        runCatching {
            dir.listFiles { file -> file.isFile && file.name.endsWith(".zip.tmp") }
                ?.forEach { it.delete() }
        }
    }

    private fun prune(dir: File, keep: File) {
        val now = System.currentTimeMillis()
        val zips = runCatching {
            dir.listFiles { file -> file.isFile && file.name.endsWith(".zip") }
        }.getOrNull()?.sortedByDescending { it.lastModified() } ?: return
        zips.forEachIndexed { index, file ->
            if (file.absolutePath == keep.absolutePath) return@forEachIndexed
            if (index < KEEP_ZIPS) return@forEachIndexed
            // A zip made minutes ago may be the one being sent right now.
            if (now - file.lastModified() < TEN_MINUTES) return@forEachIndexed
            runCatching { file.delete() }
        }
    }

    // ---- the files in it ----------------------------------------------------

    private fun copyLogs(context: Context, zip: ZipOutputStream, entries: MutableList<Entry>) {
        val internal = context.filesDir
        val external = context.getExternalFilesDir(null)
        // The runtime writes game.log twice: once in the app's own files and
        // once beside its external files for a developer who can reach them.
        val gameLog = firstFile(File(internal, "game.log"), external?.let { File(it, "game.log") })
        copyTailEntry(context, zip, entries, "logs/game.log", gameLog, GAME_LOG_BYTES)
        // The same file again, from where this run starts: the tail above is
        // capped, and a run past the cap loses its own header first.
        copyRunStart(context, zip, entries, gameLog)
        copyTailEntry(
            context, zip, entries, "logs/game.log.1",
            firstFile(File(internal, "game.log.1"), external?.let { File(it, "game.log.1") }),
            TAIL_BYTES,
        )
        copyTailEntry(context, zip, entries, "logs/launcher.log", File(internal, "launcher.log"), TAIL_BYTES)
        copyTailEntry(context, zip, entries, "logs/launcher.log.1", File(internal, "launcher.log.1"), TAIL_BYTES)
        for (name in listOf("events-launcher.txt", "events-game.txt")) {
            copyTailEntry(context, zip, entries, "logs/$name", File(internal, name), TAIL_BYTES)
            copyTailEntry(context, zip, entries, "logs/$name.1", File(internal, "$name.1"), TAIL_BYTES)
        }
        // The renderer's per-second counters, which the report's Performance
        // section reads too. Whole rows have to stay whole for a reader to add
        // them up, so the copy keeps the CSV's own head in front of its tail.
        copyTelemetry(
            context, zip, entries, "logs/telemetry.csv",
            firstFile(File(internal, "telemetry.csv"), external?.let { File(it, "telemetry.csv") }),
            TELEMETRY_BYTES,
        )
        copyTelemetry(
            context, zip, entries, "logs/telemetry.csv.1",
            firstFile(File(internal, "telemetry.csv.1"), external?.let { File(it, "telemetry.csv.1") }),
            TAIL_BYTES,
        )
    }

    /**
     * `logs/game.log.start`: how the run in `logs/game.log` began.
     *
     * The runtime writes the run marker before anything else, so the head runs
     * from the last marker in the file -- that is the run whose tail is beside
     * it, not the one before. A file without a marker (rotated, or trimmed on
     * a computer) still has a beginning, so its head is copied and the evidence
     * row says which of the two it is.
     */
    private fun copyRunStart(
        context: Context,
        zip: ZipOutputStream,
        entries: MutableList<Entry>,
        file: File?,
    ) {
        val zipPath = "logs/game.log.start"
        if (file == null || !file.isFile) {
            entries += Entry(zipPath, 0, 0, Status.MISSING)
            return
        }
        val mtime = runCatching { file.lastModified() }.getOrDefault(0L)
        try {
            // Opened before the read, like copyTailEntry: the entry is there to
            // close, so a file that cannot be read costs its own row and not
            // the whole export.
            zip.putNextEntry(ZipEntry(zipPath).also { if (mtime > 0) it.time = mtime })
            val (text, note) = runStartText(file)
            // Scrubbed line by line like everything else here: no rule may
            // match across a line end.
            val body = text.lineSequence().joinToString("\n") { Scrub.text(context, it) }
            val bytes = body.toByteArray(Charsets.UTF_8)
            zip.write(bytes)
            zip.closeEntry()
            entries += Entry(
                zipPath,
                bytes.size.toLong(),
                mtime,
                if (bytes.isEmpty()) Status.EMPTY else Status.PRESENT,
                // An empty file is `empty`; a note about where a copy came
                // from would contradict the status beside it.
                if (bytes.isEmpty()) "" else note,
            )
        } catch (t: Throwable) {
            if (runCatching { zip.closeEntry() }.isFailure) throw t
            Log.w(TAG, "could not add $zipPath", t)
            entries += Entry(zipPath, 0, mtime, Status.ERROR)
        }
    }

    /** The text of this run's head, and what the evidence row should say about it. */
    private fun runStartText(file: File): Pair<String, String> {
        val at = lastRunStart(file)
        val bytes = readHead(file, at ?: 0L, GAME_START_BYTES)
        // Half a line at the cap reads as corruption; the last full one is the
        // honest end of a head.
        val text = String(bytes, 0, lastNewline(bytes), Charsets.UTF_8)
        val note = if (at != null) {
            ""
        } else {
            "no run marker; copied the first ${DiagnosticsReport.size(GAME_START_BYTES.toLong())}"
        }
        return text to note
    }

    /**
     * Up to [maxBytes] from [from], read straight from the file. game.log is
     * capped at 4 MB; this must not be the export that loads it into memory.
     */
    private fun readHead(file: File, from: Long, maxBytes: Int): ByteArray {
        RandomAccessFile(file, "r").use { input ->
            val length = (input.length() - from).coerceIn(0L, maxBytes.toLong()).toInt()
            if (length <= 0) return ByteArray(0)
            input.seek(from)
            val bytes = ByteArray(length)
            var read = 0
            // The runtime may still be appending: a short read is a shorter
            // head, not a reason to fail the entry.
            while (read < length) {
                val n = input.read(bytes, read, length - read)
                if (n <= 0) break
                read += n
            }
            return if (read == length) bytes else bytes.copyOf(read)
        }
    }

    /**
     * The offset of the last [RUN_MARKER] in [file], or null when it holds none.
     *
     * Forward, [LogTail.CHUNK] at a time, carrying the marker's length minus
     * one byte over so a marker split across two reads is still found. The file
     * is capped at 4 MB, so one pass costs one 64 KB buffer, not the file.
     */
    private fun lastRunStart(file: File): Long? {
        val marker = RUN_MARKER_BYTES
        var found: Long? = null
        RandomAccessFile(file, "r").use { input ->
            val buffer = ByteArray(LogTail.CHUNK + marker.size - 1)
            // Where buffer[0] sits in the file, so a hit in the window is a
            // file offset.
            var offset = 0L
            var carried = 0
            while (true) {
                val n = input.read(buffer, carried, LogTail.CHUNK)
                if (n <= 0) break
                val length = carried + n
                var at = 0
                while (at + marker.size <= length) {
                    if (buffer[at] == marker[0] && matchesAt(buffer, at, marker)) {
                        found = offset + at
                        at += marker.size
                    } else {
                        at++
                    }
                }
                val keep = minOf(marker.size - 1, length)
                System.arraycopy(buffer, length - keep, buffer, 0, keep)
                offset += length - keep
                carried = keep
            }
        }
        return found
    }

    private fun matchesAt(buffer: ByteArray, at: Int, marker: ByteArray): Boolean {
        for (i in marker.indices) {
            if (buffer[at + i] != marker[i]) return false
        }
        return true
    }

    /** One past the last `\n` in [bytes], or its length when it holds none. */
    private fun lastNewline(bytes: ByteArray): Int {
        for (at in bytes.indices.reversed()) {
            if (bytes[at].toInt() == NEWLINE) return at + 1
        }
        return bytes.size
    }

    private fun copyCrashes(context: Context, zip: ZipOutputStream, entries: MutableList<Entry>) {
        // Every process keeps its own last crash, so the suffix is not fixed.
        val latest = runCatching {
            context.filesDir.listFiles { file ->
                file.isFile && file.name.startsWith("last-crash") && file.name.endsWith(".txt")
            }
        }.getOrNull().orEmpty().sortedBy { it.name }.take(4)
        for (file in latest) {
            copyTailEntry(context, zip, entries, "crashes/${file.name}", file, TAIL_BYTES)
        }
        val history = runCatching { File(context.filesDir, "crash-history").listFiles() }
            .getOrNull().orEmpty()
            .filter { it.isFile && it.name.endsWith(".txt") }
            .sortedByDescending { it.name }
            .take(10)
        for (file in history) {
            copyTailEntry(context, zip, entries, "crashes/crash-history/${file.name}", file, TAIL_BYTES)
        }
    }

    private fun copyState(context: Context, zip: ZipOutputStream, entries: MutableList<Entry>, now: Long) {
        val root = runCatching { PatchesRepository.gameRoot(context) }.getOrNull()
        val sources = listOf(
            "patch-state.properties" to listOf("patch-state.properties"),
            "patch-install-result.properties" to listOf("patch-install-result.properties"),
            "patch-install-live.properties" to listOf("patch-install-live.properties"),
            "patch-journal.properties" to listOf("patch-journal.properties"),
            // The installer's lock has been written both ways; one of them wins.
            "patch-install.lock" to listOf("patch-install.lock", ".patch-install.lock"),
        )
        for ((zipName, names) in sources) {
            val file = if (root == null) {
                null
            } else {
                names.map { File(root, it) }.firstOrNull { it.isFile }
            }
            copyTailEntry(context, zip, entries, "state/$zipName", file, TAIL_BYTES)
        }
        writeText(zip, entries, "state/folders.txt", foldersText(context, root), now) { Scrub.text(context, it) }
        writeText(zip, entries, "state/threads.txt", threadsText(), now) { Scrub.text(context, it) }
    }

    private fun copyTailEntry(
        context: Context,
        zip: ZipOutputStream,
        entries: MutableList<Entry>,
        zipPath: String,
        file: File?,
        maxBytes: Int,
    ) {
        if (file == null || !file.isFile) {
            entries += Entry(zipPath, 0, 0, Status.MISSING)
            return
        }
        val mtime = runCatching { file.lastModified() }.getOrDefault(0L)
        try {
            zip.putNextEntry(ZipEntry(zipPath).also { if (mtime > 0) it.time = mtime })
            val written = LogTail.copyTail(file, zip, maxBytes) { Scrub.text(context, it) }
            zip.closeEntry()
            entries += Entry(zipPath, written, mtime, if (written > 0) Status.PRESENT else Status.EMPTY)
        } catch (t: Throwable) {
            // A source that cannot be read leaves the stream intact; a zip that
            // cannot be written does not, and then the export has failed.
            if (runCatching { zip.closeEntry() }.isFailure) throw t
            Log.w(TAG, "could not add $zipPath", t)
            entries += Entry(zipPath, 0, mtime, Status.ERROR)
        }
    }

    /**
     * `logs/telemetry.csv`: the same tail copy every log here gets, with the
     * CSV's own head put back in front of it.
     *
     * A cut CSV is a table with no column names. The runtime writes `# v1`, a
     * `# resolved:` line naming the settings that produced the numbers, and the
     * `boot_ms,...` row header at the top of the file; a run longer than the cap
     * starts after all three. They are a few dozen bytes, so the head is read
     * and written first, and the evidence row says the tail was cut.
     */
    private fun copyTelemetry(
        context: Context,
        zip: ZipOutputStream,
        entries: MutableList<Entry>,
        zipPath: String,
        file: File?,
        maxBytes: Int,
    ) {
        if (file == null || !file.isFile) {
            entries += Entry(zipPath, 0, 0, Status.MISSING)
            return
        }
        val mtime = runCatching { file.lastModified() }.getOrDefault(0L)
        try {
            zip.putNextEntry(ZipEntry(zipPath).also { if (mtime > 0) it.time = mtime })
            var written = 0L
            var note = ""
            val length = runCatching { file.length() }.getOrDefault(0L)
            if (length > maxBytes) {
                val head = telemetryHead(file)
                if (head.isNotEmpty()) {
                    // Scrubbed line by line like every other copy here.
                    val prefix = head.joinToString("\n") { Scrub.text(context, it) } + "\n"
                    val bytes = prefix.toByteArray(Charsets.UTF_8)
                    zip.write(bytes)
                    written += bytes.size
                    note = "head prepended: the tail starts at " +
                        DiagnosticsReport.size(length - maxBytes)
                }
            }
            written += LogTail.copyTail(file, zip, maxBytes) { Scrub.text(context, it) }
            zip.closeEntry()
            entries += Entry(zipPath, written, mtime, if (written > 0) Status.PRESENT else Status.EMPTY, note)
        } catch (t: Throwable) {
            if (runCatching { zip.closeEntry() }.isFailure) throw t
            Log.w(TAG, "could not add $zipPath", t)
            entries += Entry(zipPath, 0, mtime, Status.ERROR)
        }
    }

    /**
     * The lines at the top of a telemetry CSV that make it readable: its `#`
     * comments -- the version and what the build resolved -- and the first line
     * after them, the row header. Empty when the file starts with neither.
     */
    private fun telemetryHead(file: File): List<String> {
        val bytes = readHead(file, 0L, TELEMETRY_HEAD_BYTES)
        if (bytes.isEmpty()) return emptyList()
        // A head read to its cap ends mid-line; that line is not the row header.
        var text = String(bytes, Charsets.UTF_8)
        if (bytes.size >= TELEMETRY_HEAD_BYTES) text = text.substringBeforeLast('\n', text)
        val kept = ArrayList<String>(4)
        for (line in text.lines()) {
            if (line.startsWith("#")) {
                kept += line
                continue
            }
            if (line.isBlank()) continue
            kept += line
            break
        }
        return kept
    }

    private fun writeText(
        zip: ZipOutputStream,
        entries: MutableList<Entry>,
        path: String,
        text: String,
        mtime: Long,
        scrub: ((String) -> String)?,
    ) {
        val body = scrub?.invoke(text) ?: text
        val bytes = body.toByteArray(Charsets.UTF_8)
        try {
            zip.putNextEntry(ZipEntry(path).also { if (mtime > 0) it.time = mtime })
            zip.write(bytes)
            zip.closeEntry()
            entries += Entry(path, bytes.size.toLong(), mtime, if (bytes.isEmpty()) Status.EMPTY else Status.PRESENT)
        } catch (t: Throwable) {
            if (runCatching { zip.closeEntry() }.isFailure) throw t
            Log.w(TAG, "could not add $path", t)
            entries += Entry(path, 0, mtime, Status.ERROR)
        }
    }

    // ---- logcat -------------------------------------------------------------

    private class LogcatRun(val exit: Int, val bytes: Long, val stderr: String)

    private fun copyLogcat(
        context: Context,
        zip: ZipOutputStream,
        entries: MutableList<Entry>,
        now: Long,
    ) {
        val meta = StringBuilder()
        var total = 0L
        try {
            zip.putNextEntry(ZipEntry("logs/logcat.txt").also { it.time = now })
            val counter = Counting(zip)
            val pid = android.os.Process.myPid()
            val withPid = listOf("logcat", "-d", "-b", "main,crash", "-t", "4000", "--pid=$pid")
            meta.append("command  ").append(withPid.joinToString(" ")).append('\n')
            var run = runLogcat(withPid, counter) { Scrub.text(context, it) }
            appendLogcatRun(meta, run)
            // Only a logcat that refused to start is worth running again. The
            // unfiltered command is the one every phone understands.
            if (run.exit != 0 && run.bytes == 0L) {
                val without = listOf("logcat", "-d", "-b", "main,crash", "-t", "4000")
                meta.append("fallback ").append(without.joinToString(" ")).append('\n')
                run = runLogcat(without, counter) { Scrub.text(context, it) }
                appendLogcatRun(meta, run)
            }
            zip.closeEntry()
            total = counter.count
            // An empty logcat is normal on a phone that filters it; it must not
            // fail the export.
            entries += Entry("logs/logcat.txt", total, now, if (total > 0) Status.PRESENT else Status.EMPTY)
        } catch (t: Throwable) {
            if (runCatching { zip.closeEntry() }.isFailure) throw t
            meta.append("error    ").append(DiagnosticsReport.oneLine(t.toString(), 300)).append('\n')
            entries += Entry("logs/logcat.txt", total, now, Status.ERROR)
        }
        writeText(zip, entries, "logs/logcat.meta", meta.toString(), now, null)
    }

    private fun appendLogcatRun(meta: StringBuilder, run: LogcatRun) {
        meta.append("exit     ").append(run.exit).append('\n')
        meta.append("bytes    ").append(run.bytes).append('\n')
        meta.append("stderr   ").append(DiagnosticsReport.oneLine(run.stderr, STDERR_CAP).ifBlank { "(none)" })
            .append('\n')
    }

    private fun runLogcat(args: List<String>, out: OutputStream, scrub: (String) -> String): LogcatRun {
        val counter = Counting(out)
        val error = StringBuilder()
        var process: Process? = null
        try {
            val started = ProcessBuilder(args).redirectErrorStream(false).start()
            process = started
            // stderr is read on its own thread: a logcat that complains while
            // it writes would otherwise fill the pipe and deadlock the read.
            val reader = Thread {
                runCatching {
                    started.errorStream.use { stream ->
                        val buffer = ByteArray(512)
                        var kept = 0
                        while (true) {
                            val n = stream.read(buffer)
                            if (n <= 0) break
                            if (kept < STDERR_CAP) {
                                val take = minOf(n, STDERR_CAP - kept)
                                error.append(String(buffer, 0, take, Charsets.UTF_8))
                                kept += take
                            }
                        }
                    }
                }
            }.apply {
                isDaemon = true
                name = "diagnostics-logcat"
                start()
            }
            started.inputStream.use { LogTail.copyStream(it, counter, scrub) }
            val exit = started.waitFor()
            reader.join(2000)
            return LogcatRun(exit, counter.count, error.toString())
        } catch (t: Throwable) {
            runCatching { process?.destroy() }
            error.append(DiagnosticsReport.oneLine(t.toString(), 200))
            return LogcatRun(-1, counter.count, error.toString())
        }
    }

    /**
     * Counts what passes through without closing the zip it writes into: the
     * meta file says how many bytes logcat produced, which is how a reader
     * tells "no logs" from "the command failed".
     */
    private class Counting(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() {
            out.flush()
        }

        override fun close() {
            flush()
        }
    }

    // ---- gathering ----------------------------------------------------------

    private fun gather(context: Context, note: String, now: Long, exits: Boolean = true): DiagnosticsReport.Facts {
        val internal = context.filesDir
        val external = context.getExternalFilesDir(null)
        val gameLog = firstFile(File(internal, "game.log"), external?.let { File(it, "game.log") })
        val events = eventLines(context)
        val configured = runCatching { Prefs(context).gameDirectory }.getOrDefault("")
        val game = configured.ifBlank { null }?.let { File(it) }
        val root = runCatching { PatchesRepository.gameRoot(context) }.getOrNull()
        val (dashboard, dashboardNote) = dashboardFacts()
        val exitReport = if (!exits) {
            ""
        } else {
            runCatching { Exits.report(context) }.getOrElse {
                "exit report failed: " + DiagnosticsReport.oneLine(it.toString(), 200)
            }.orEmpty()
        }
        return DiagnosticsReport.Facts(
            app = appFacts(context),
            device = deviceFacts(),
            createdAt = now,
            note = note,
            redactions = Scrub.notes(context),
            crash = crashText(context),
            install = installFacts(root, now),
            folder = folderStatus(game),
            free = freeSpace(game?.parentFile ?: external ?: internal),
            events = events,
            gameTail = if (gameLog == null) emptyList() else LogTail.tailLines(gameLog, TAIL_BYTES, 40),
            ui = DiagnosticsReport.lastUi(events, 20),
            androidReport = exitReport,
            storage = storageLines(internal, external, game),
            dashboard = dashboard,
            dashboardNote = dashboardNote,
            telemetry = telemetryLines(internal, external),
        )
    }

    private fun appFacts(context: Context) = DiagnosticsReport.App(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        buildSha = BuildConfig.BUILD_SHA,
        buildStamp = BuildConfig.BUILD_STAMP,
        pkg = context.packageName,
    )

    private fun deviceFacts() = DiagnosticsReport.Device(
        manufacturer = Build.MANUFACTURER.ifBlank { "unknown" },
        model = Build.MODEL.ifBlank { "unknown" },
        android = Build.VERSION.RELEASE.ifBlank { "?" },
        api = Build.VERSION.SDK_INT,
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
    )

    /**
     * What the report's Performance section reads: the head that names the
     * columns and the settings, then the newest rows. Bounded like every other
     * tail here, and empty when this build writes no telemetry.
     */
    private fun telemetryLines(internal: File, external: File?): List<String> {
        val file = firstFile(File(internal, "telemetry.csv"), external?.let { File(it, "telemetry.csv") })
            ?: return emptyList()
        val tail = LogTail.tailLines(file, TELEMETRY_REPORT_BYTES, TELEMETRY_REPORT_ROWS)
        val head = telemetryHead(file).filterNot { it in tail }
        return head + tail
    }

    private fun eventLines(context: Context): List<String> {
        // Each file's newest 300 lines; the report sorts by timestamp before it
        // truncates, so a global cap here would drop the launcher's side of the
        // story before anyone could compare the two.
        val lines = ArrayList<String>(620)
        for (name in listOf("events-launcher.txt", "events-game.txt")) {
            lines += LogTail.tailLines(File(context.filesDir, name), TAIL_BYTES, 300)
        }
        return lines
    }

    private fun crashText(context: Context): String {
        val newest = newestCrash(context) ?: return ""
        return LogTail.tailText(newest, CRASH_HEAD_BYTES)
    }

    private fun newestCrash(context: Context): File? {
        val files = ArrayList<File>(4)
        runCatching {
            context.filesDir.listFiles { file ->
                file.isFile && file.name.startsWith("last-crash") && file.name.endsWith(".txt")
            }
        }.getOrNull()?.let { files.addAll(it) }
        runCatching {
            File(context.filesDir, "crash-history").listFiles { file ->
                file.isFile && file.name.endsWith(".txt")
            }
        }.getOrNull()?.let { files.addAll(it) }
        return files.maxByOrNull { it.lastModified() }
    }

    private fun installFacts(root: File?, now: Long): DiagnosticsReport.InstallFacts {
        if (root == null) return DiagnosticsReport.InstallFacts()
        val state = File(root, "patch-state.properties")
        val result = File(root, "patch-install-result.properties")
        val live = File(root, "patch-install-live.properties")
        val journal = File(root, "patch-journal.properties")
        return DiagnosticsReport.InstallFacts(
            state = properties(state),
            result = properties(result),
            live = properties(live),
            journal = properties(journal),
            livePresent = live.isFile,
            liveAgeSeconds = ageSeconds(live, now),
            resultPresent = result.isFile,
            resultAgeSeconds = ageSeconds(result, now),
            resultNewerThanLive = result.isFile && live.isFile && result.lastModified() > live.lastModified(),
        )
    }

    private fun properties(file: File): Map<String, String> {
        if (!file.isFile || file.length() > TAIL_BYTES) return emptyMap()
        val values = Properties()
        runCatching { file.inputStream().use { values.load(it) } }.getOrElse { return emptyMap() }
        val out = LinkedHashMap<String, String>()
        for (key in values.stringPropertyNames().sorted()) out[key] = values.getProperty(key).orEmpty()
        return out
    }

    private fun ageSeconds(file: File, now: Long): Long =
        if (!file.isFile) -1L else ((now - file.lastModified()) / 1000L).coerceAtLeast(0L)

    private fun folderStatus(game: File?): String = runCatching {
        when {
            game == null -> "missing (no folder chosen)"
            !game.isDirectory -> "missing (the chosen folder is not there)"
            !File(game, GameFolder.XEX).isFile -> "missing (no ${GameFolder.XEX})"
            !game.canRead() -> "missing (not readable; all-files access is off)"
            else -> "ok (${GameFolder.XEX} present)"
        }
    }.getOrDefault("missing (could not be checked)")

    /**
     * StatFs only: walking the 20 GB game folder is what the PATCHES screen's
     * dashboard does once, and an export that walks it again would be slow on
     * exactly the phone that is already struggling.
     */
    private fun storageLines(internal: File, external: File?, game: File?): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(4)
        stat(out, "app internal", internal)
        stat(out, "app external", external)
        stat(out, "game volume", game?.parentFile)
        return out
    }

    private fun stat(out: MutableList<Pair<String, String>>, label: String, dir: File?) {
        if (dir == null) {
            out += label to "not measured (no folder)"
            return
        }
        val space = runCatching { StatFs(dir.absolutePath) }.map { it.availableBytes to it.totalBytes }.getOrNull()
        out += label to if (space == null) {
            "not measured"
        } else {
            "${DiagnosticsReport.size(space.first)} free of ${DiagnosticsReport.size(space.second)}"
        }
    }

    private fun freeSpace(dir: File?): String {
        if (dir == null) return "not measured"
        val space = runCatching { StatFs(dir.absolutePath) }
            .map { it.availableBytes to it.totalBytes }
            .getOrNull() ?: return "not measured"
        return "${DiagnosticsReport.size(space.first)} free of ${DiagnosticsReport.size(space.second)}"
    }

    private fun dashboardFacts(): Pair<List<Pair<String, String>>, String> {
        val snapshot = PatchesDashboard.cached()
            ?: return emptyList<Pair<String, String>>() to
                "not measured (the PATCHES screen has not built it yet)"
        val out = ArrayList<Pair<String, String>>(16)
        val age = System.currentTimeMillis() - snapshot.builtAt
        out += "built" to (if (age < 0L) "unknown" else "${ageWords(age)} ago")
        snapshot.error?.let { out += "error" to DiagnosticsReport.oneLine(it, 200) }
        val state = snapshot.state
        out += "patch" to describePatch(state.patch)
        out += "edited" to describeEdited(state.edited)
        out += "previous patch" to describePrevious(state.patchBackup)
        out += "previous edit" to describePrevious(state.editBackup)
        out += "layout" to state.patchLayout.name
        out += "journal" to (if (state.journalPending) "pending (an install was interrupted)" else "clear")
        snapshot.storage?.let { facts ->
            out += "game files" to DiagnosticsReport.size(facts.game.bytes)
            out += "dlc" to DiagnosticsReport.size(facts.dlc.bytes)
            out += "saves" to DiagnosticsReport.size(facts.saves.bytes)
            out += "patch backups" to DiagnosticsReport.size(facts.patchBackups.bytes)
            out += "staging" to DiagnosticsReport.size(facts.staging.bytes)
            out += "logs" to DiagnosticsReport.size(facts.logs.bytes)
            out += "captures" to DiagnosticsReport.size(facts.captures.bytes)
            out += "free (measured)" to
                "${DiagnosticsReport.size(facts.freeBytes)} of ${DiagnosticsReport.size(facts.totalBytes)}"
            facts.problem?.let { out += "problem" to DiagnosticsReport.oneLine(it, 200) }
        }
        return out to ""
    }

    private fun describePatch(data: PatchesRepository.PatchData?): String {
        if (data == null) return "none"
        val verdict = if (data.complete) "complete" else "incomplete"
        return "${data.name} · ${data.cpk} CPK · ${DiagnosticsReport.size(data.bytes)} · $verdict · " +
            data.provenance.name.lowercase(Locale.US)
    }

    private fun describeEdited(data: PatchesRepository.EditedData?): String {
        if (data == null) return "none"
        return "${data.name} · ${DiagnosticsReport.size(data.bytes)} · ${data.players} players · ${data.teams} teams"
    }

    private fun describePrevious(previous: PatchesRepository.Previous?): String =
        previous?.let { "${it.name} · ${DiagnosticsReport.size(it.bytes)} · ${it.date}" } ?: "none"

    private fun ageWords(millis: Long): String = when {
        millis < 0L -> "an unknown time"
        millis < 60_000L -> "${millis / 1000L} s"
        millis < 3_600_000L -> "${millis / 60_000L} min"
        else -> "${millis / 3_600_000L} h"
    }

    // ---- generated text -----------------------------------------------------

    private fun foldersText(context: Context, root: File?): String {
        val configured = runCatching { Prefs(context).gameDirectory }.getOrDefault("")
        val game = configured.ifBlank { null }?.let { File(it) }
        return buildString {
            append("configured     ").append(if (configured.isBlank()) "(none)" else configured).append('\n')
            append("exists         ").append(game?.exists() ?: false).append('\n')
            append("isDirectory    ").append(game?.isDirectory ?: false).append('\n')
            append("canRead        ").append(game?.canRead() ?: false).append('\n')
            append("canWrite       ").append(game?.canWrite() ?: false).append('\n')
            append("default.xex    ").append(game != null && File(game, GameFolder.XEX).isFile).append('\n')
            append("hasAccess      ").append(runCatching { GameFolder.hasAccess(context) }.getOrDefault(false))
                .append('\n')
            append("allFiles       ")
                .append(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
                    } else {
                        false
                    },
                )
                .append('\n')
            append("gameRoot       ").append(root?.absolutePath ?: "(null)").append('\n')
        }
    }

    private fun threadsText(): String {
        val out = StringBuilder(16 * 1024)
        val stacks: Map<Thread, Array<StackTraceElement>> =
            runCatching { Thread.getAllStackTraces() }.getOrDefault(emptyMap())
        for ((thread, stack) in stacks.entries.sortedBy { it.key.name }) {
            if (out.length >= THREADS_BYTES) {
                out.append("... truncated\n")
                break
            }
            out.append('"').append(thread.name).append("\" id=").append(thread.id)
            if (thread.isDaemon) out.append(" daemon")
            out.append(' ').append(thread.state).append('\n')
            for (element in stack) {
                if (out.length >= THREADS_BYTES) break
                out.append("    at ").append(element).append('\n')
            }
        }
        return out.toString()
    }

    // ---- small things -------------------------------------------------------

    /**
     * The runtime's separator, written before a run's first line
     * (runtime/android/log.cpp). The tools grep for it, and so does the head
     * above: it is the only way to tell one run from the one before it in a
     * file that is appended to.
     */
    private const val RUN_MARKER = "===== new run"
    private val RUN_MARKER_BYTES = RUN_MARKER.toByteArray(Charsets.US_ASCII)

    /** `\n`, compared against a byte read from a file. */
    private const val NEWLINE = 0x0A

    private fun firstFile(vararg files: File?): File? = files.firstOrNull { it != null && it.isFile }

    private fun failure(context: Context, error: Throwable): String {
        val text = error.message ?: error.toString()
        return if (
            text.contains("ENOSPC", ignoreCase = true) ||
            text.contains("No space", ignoreCase = true) ||
            text.contains("space left", ignoreCase = true)
        ) {
            context.getString(R.string.diagnostics_no_space)
        } else {
            context.getString(R.string.diagnostics_failed, DiagnosticsReport.oneLine(text, 240))
        }
    }

    /** The caller is gone: leave the answer where the next screen will look. */
    private fun deliver(activity: Activity, onDone: (Done) -> Unit, result: Done) {
        val run = Runnable {
            if (activity.isFinishing || activity.isDestroyed) {
                remember(activity, result)
            } else {
                runCatching { onDone(result) }
            }
        }
        val handler = mainHandler
        if (handler == null) run.run() else handler.post(run)
    }

    /**
     * The caller is gone: leave the answer where the next screen will look.
     *
     * One line, because the screen that reads it says "saved to <this>": the
     * path on success, the reason on failure.
     */
    private fun remember(context: Context, result: Done) {
        val zip = result.zip
        val text = zip?.absolutePath
            ?: DiagnosticsReport.oneLine(result.error ?: "unknown", 300)
        runCatching { File(context.applicationContext.filesDir, LAST_EXPORT).writeText(text + "\n") }
    }
}
