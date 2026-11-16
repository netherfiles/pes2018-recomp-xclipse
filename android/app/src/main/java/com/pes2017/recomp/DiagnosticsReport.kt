package com.pes2017.recomp

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a developer reads out of a diagnostics ZIP: `report.txt`,
 * `manifest.json`, and the short text the screen copies.
 *
 * Everything here is pure: it takes the facts somebody gathered and turns them
 * into text, so a test can hand it a fabricated install record and check the
 * Install section without a phone, a Context or a log file. The gathering --
 * files, `StatFs`, the event log -- lives in Diagnostics.kt.
 *
 * The report is written for a person: the first screenfuls say what went wrong
 * and the last sections are the evidence. The manifest is written for a script:
 * a fixed schema, one entry per file in the zip, and nothing that has to be
 * parsed out of prose.
 */
object DiagnosticsReport {

    /** What the manifest says about one file, and the report's Evidence column. */
    enum class Status(val json: String) {
        PRESENT("present"),
        EMPTY("empty"),
        MISSING("missing"),
        ERROR("error"),
    }

    data class Entry(
        val path: String,
        val bytes: Long,
        val mtime: Long,
        val status: Status,
    )

    data class App(
        val versionName: String,
        val versionCode: Int,
        val buildSha: String,
        val buildStamp: String,
        val pkg: String,
    )

    data class Device(
        val manufacturer: String,
        val model: String,
        val android: String,
        val api: Int,
        val abi: String,
    )

    /**
     * The four property files an install leaves behind. Their key names are the
     * install machinery's, not this file's, and they have changed before; the
     * reader below matches a handful of likely names and prints whatever it
     * did not recognise, so a report of an unfamiliar build still says what was
     * on disk instead of claiming there was nothing.
     */
    data class InstallFacts(
        val state: Map<String, String> = emptyMap(),
        val result: Map<String, String> = emptyMap(),
        val live: Map<String, String> = emptyMap(),
        val journal: Map<String, String> = emptyMap(),
        val livePresent: Boolean = false,
        val liveAgeSeconds: Long = -1L,
        val resultPresent: Boolean = false,
        val resultAgeSeconds: Long = -1L,
        val resultNewerThanLive: Boolean = false,
    )

    data class Install(
        val id: String? = null,
        val started: String? = null,
        val updated: String? = null,
        val finished: String? = null,
        val phase: String? = null,
        val percent: String? = null,
        val processed: String? = null,
        val total: String? = null,
        val current: String? = null,
        val selection: String? = null,
        val policy: String? = null,
        val plan: String? = null,
        val terminal: String? = null,
        val staleness: String? = null,
        val other: List<Pair<String, String>> = emptyList(),
    ) {
        /** Nothing was ever written: the report says so instead of printing blanks. */
        val empty: Boolean
            get() = id == null && phase == null && terminal == null && started == null &&
                updated == null && other.isEmpty()

        /** One line for the TL;DR and the summary. */
        val headline: String
            get() = buildString {
                append(terminal ?: phase ?: "no record")
                percent?.let { append(" · ").append(it) }
                updated?.let { append(" · updated ").append(it) }
            }
    }

    /**
     * Everything the text needs, already bounded. [entries] is filled in as the
     * zip is written; a report built before that simply has an empty table.
     */
    data class Facts(
        val app: App,
        val device: Device,
        val createdAt: Long = System.currentTimeMillis(),
        val note: String = "",
        val entries: List<Entry> = emptyList(),
        val redactions: List<String> = emptyList(),
        val crash: String = "",
        val install: InstallFacts = InstallFacts(),
        val folder: String = "missing (no folder chosen)",
        val free: String = "not measured",
        val events: List<String> = emptyList(),
        val gameTail: List<String> = emptyList(),
        val ui: List<String> = emptyList(),
        val androidReport: String = "",
        val storage: List<Pair<String, String>> = emptyList(),
        val dashboard: List<Pair<String, String>> = emptyList(),
        val dashboardNote: String = "",
    )

    // ---- report.txt ---------------------------------------------------------

    fun report(facts: Facts): String {
        val app = facts.app
        val install = install(facts.install, facts.createdAt)
        val out = StringBuilder(32 * 1024)

        out.append("PES 2018 diagnostics\n")
        // The player's own words first: it is the only part of this file that
        // says what they were doing when it broke.
        if (facts.note.isNotBlank()) out.append("note: ").append(oneLine(facts.note, 400)).append('\n')
        out.append("build ").append(app.buildSha).append(' ').append(app.buildStamp)
            .append(", versionCode ").append(app.versionCode)
            .append(", package ").append(app.pkg).append('\n')
        out.append("exported ").append(stamp(facts.createdAt))
            .append(", device ").append(deviceLine(facts.device))
            .append(", abi ").append(facts.device.abi)
            .append(", Android ").append(facts.device.android)
            .append(" (API ").append(facts.device.api).append(")\n")

        out.append("\nTL;DR\n")
        out.append("last crash     ").append(crashHeadline(facts.crash)).append('\n')
        out.append("last install   ").append(if (install.empty) "no record on disk" else install.headline).append('\n')
        out.append("game folder    ").append(facts.folder).append('\n')
        out.append("free space     ").append(facts.free).append('\n')

        out.append("\nTimeline\n")
        appendTimeline(out, facts.events, facts.gameTail)

        out.append("\nInstall\n")
        appendInstall(out, install)

        out.append("\nUI\n")
        appendUi(out, facts.ui)

        out.append("\nAndroid\n")
        appendBlock(out, facts.androidReport.trim().ifEmpty { "not available" })

        out.append("\nStorage\n")
        appendPairs(out, facts.storage, "app storage")
        if (facts.dashboard.isEmpty()) {
            out.append("dashboard      ").append(facts.dashboardNote.ifBlank { "not measured" }).append('\n')
        } else {
            appendPairs(out, facts.dashboard, "dashboard")
        }

        out.append("\nEvidence\n")
        appendEvidence(out, facts)

        out.append("\nNext\n")
        out.append("attach this zip to the issue; the first 40 lines are the summary\n")

        return limit(out.toString())
    }

    private fun appendTimeline(out: StringBuilder, events: List<String>, game: List<String>) {
        val lines = ArrayList<Pair<Long?, String>>(events.size + game.size)
        for (line in events) lines += timestamp(line) to line
        for (line in game) lines += timestamp(line) to line
        if (lines.isEmpty()) {
            out.append("no events recorded\n")
            return
        }
        val timed = lines.filter { it.first != null }.sortedBy { it.first }
        // Nothing parses: the event log as it was written is more honest than a
        // sort order invented here.
        if (timed.isEmpty()) {
            val raw = if (events.isNotEmpty()) events else game
            raw.takeLast(MAX_TIMELINE).forEach { out.append(oneLine(it, MAX_LINE)).append('\n') }
            return
        }
        var previous: Long? = null
        for ((at, line) in timed.takeLast(MAX_TIMELINE)) {
            val millis = at ?: continue
            val last = previous
            if (last != null && millis - last > GAP_MILLIS) {
                out.append("-- ").append((millis - last) / 1000).append(" s --\n")
            }
            previous = millis
            out.append(oneLine(line, MAX_LINE)).append('\n')
        }
        val untimed = lines.filter { it.first == null }
        if (untimed.isNotEmpty()) {
            // Kept, but after the ordered part: a line with no timestamp cannot
            // be placed, and guessing would rewrite the evidence.
            out.append("-- ").append(untimed.size).append(" lines without a timestamp --\n")
            untimed.takeLast(MAX_UNTIMED).forEach { out.append(oneLine(it.second, MAX_LINE)).append('\n') }
        }
    }

    private fun appendInstall(out: StringBuilder, install: Install) {
        if (install.empty) {
            out.append("no install record on disk\n")
            return
        }
        field(out, "id", install.id)
        field(out, "started", install.started)
        field(out, "updated", install.updated)
        field(out, "finished", install.finished)
        field(out, "phase", install.phase)
        field(out, "percent", install.percent)
        val progress = listOfNotNull(install.processed, install.total)
        if (progress.isNotEmpty()) field(out, "progress", progress.joinToString(" / "))
        field(out, "current", install.current)
        field(out, "selection", install.selection)
        field(out, "policy", install.policy)
        field(out, "plan", install.plan)
        field(out, "terminal", install.terminal)
        field(out, "live file", install.staleness)
        if (install.other.isNotEmpty()) {
            field(out, "other", "${install.other.size} keys")
            install.other.take(MAX_OTHER).forEach { (key, value) ->
                out.append("  ").append(key).append(" = ").append(oneLine(value, 160)).append('\n')
            }
        }
    }

    private fun appendUi(out: StringBuilder, ui: List<String>) {
        if (ui.isEmpty()) {
            out.append("no ui events recorded\n")
            return
        }
        ui.takeLast(MAX_UI).forEach { out.append("  ").append(oneLine(it, 240)).append('\n') }
    }

    private fun appendPairs(out: StringBuilder, pairs: List<Pair<String, String>>, empty: String) {
        if (pairs.isEmpty()) {
            out.append(empty.padEnd(16)).append("not measured\n")
            return
        }
        pairs.forEach { (label, value) -> out.append(label.padEnd(16)).append(value).append('\n') }
    }

    private fun appendBlock(out: StringBuilder, text: String) {
        text.lines().forEach { out.append(it).append('\n') }
    }

    private fun appendEvidence(out: StringBuilder, facts: Facts) {
        out.append("path".padEnd(PATH_WIDTH)).append("status".padEnd(9))
            .append("bytes".padStart(10)).append("  mtime\n")
        for (entry in facts.entries) {
            out.append(oneLine(entry.path, PATH_WIDTH - 1).padEnd(PATH_WIDTH))
                .append(entry.status.json.padEnd(9))
                .append(entry.bytes.toString().padStart(10))
                .append("  ").append(stamp(entry.mtime)).append('\n')
        }
        if (facts.redactions.isNotEmpty()) {
            out.append("redacted: ").append(facts.redactions.joinToString("; ")).append('\n')
        }
        out.append("not listed above: report.txt (this file) and manifest.json (this list)\n")
    }

    private fun field(out: StringBuilder, label: String, value: String?) {
        if (value.isNullOrBlank()) return
        out.append(label.padEnd(10)).append(value).append('\n')
    }

    /**
     * The report is built from bounded pieces already; this is the belt to that
     * brace, so one pathological event log cannot make the file that explains
     * the bug the thing that fills the disk.
     */
    private fun limit(text: String): String {
        if (text.length <= MAX_REPORT_CHARS) return text
        val cut = text.take(MAX_REPORT_CHARS)
        val at = cut.lastIndexOf('\n')
        val body = if (at > 0) cut.substring(0, at) else cut
        return body + "\n[... report truncated at $MAX_REPORT_CHARS characters ...]\n"
    }

    // ---- manifest.json ------------------------------------------------------

    /** Hand-written JSON: org.json is a stub in a plain JVM test. */
    fun manifest(facts: Facts): String = buildString {
        val app = facts.app
        val device = facts.device
        append("{\n")
        append("  \"schema\": 1,\n")
        append("  \"app\": {\"versionName\": ").append(quote(app.versionName))
            .append(", \"versionCode\": ").append(app.versionCode)
            .append(", \"buildSha\": ").append(quote(app.buildSha))
            .append(", \"buildStamp\": ").append(quote(app.buildStamp))
            .append(", \"package\": ").append(quote(app.pkg)).append("},\n")
        append("  \"device\": {\"manufacturer\": ").append(quote(device.manufacturer))
            .append(", \"model\": ").append(quote(device.model))
            .append(", \"android\": ").append(quote(device.android))
            .append(", \"api\": ").append(device.api)
            .append(", \"abi\": ").append(quote(device.abi)).append("},\n")
        append("  \"createdAt\": ").append(quote(iso(facts.createdAt))).append(",\n")
        append("  \"source\": \"android\",\n")
        append("  \"files\": [")
        facts.entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append("\n    {\"path\": ").append(quote(entry.path))
                .append(", \"bytes\": ").append(entry.bytes)
                .append(", \"mtime\": ").append(entry.mtime)
                .append(", \"status\": ").append(quote(entry.status.json)).append('}')
        }
        if (facts.entries.isNotEmpty()) append("\n  ")
        append("],\n")
        append("  \"redactions\": [")
        facts.redactions.forEachIndexed { index, note ->
            if (index > 0) append(", ")
            append(quote(note))
        }
        append("],\n")
        append("  \"note\": ").append(quote(facts.note)).append('\n')
        append("}\n")
    }

    // ---- the short text the screen shows ------------------------------------

    fun summary(facts: Facts): String {
        val install = install(facts.install, facts.createdAt)
        val out = StringBuilder(2 * 1024)
        out.append("PES 2018 diagnostics · ").append(facts.app.versionName)
            .append(" · build ").append(facts.app.buildSha).append('\n')
        out.append(stamp(facts.createdAt)).append(" · ").append(deviceLine(facts.device))
            .append(" · Android ").append(facts.device.android).append(" (API ").append(facts.device.api)
            .append(") · ").append(facts.device.abi).append('\n')
        out.append("crash: ").append(crashHeadline(facts.crash)).append('\n')
        out.append("install: ").append(installLine(install)).append('\n')
        out.append("game folder: ").append(facts.folder).append('\n')
        if (facts.ui.isNotEmpty()) {
            out.append("ui:\n")
            facts.ui.takeLast(SUMMARY_UI).forEach { out.append("  ").append(oneLine(it, 160)).append('\n') }
        }
        return out.lines().take(SUMMARY_LINES).joinToString("\n").trimEnd()
    }

    /** The summary line names the run: id, state, percent and when it last moved. */
    private fun installLine(install: Install): String {
        if (install.empty) return "no record"
        return buildString {
            install.id?.let { append(it).append(" · ") }
            append(install.terminal ?: install.phase ?: "no phase")
            install.percent?.let { append(" · ").append(it) }
            install.updated?.let { append(" · updated ").append(it) }
        }
    }

    // ---- install state ------------------------------------------------------

    fun install(facts: InstallFacts, now: Long): Install {
        val live = Reader(facts.live)
        val result = Reader(facts.result)
        val journal = Reader(facts.journal)
        val state = Reader(facts.state)

        // The live file is the run in flight; the result file is the one that
        // finished, and either can be the only one on disk. Their key names are
        // the install machinery's (PatchInstallResultStore), so each field
        // accepts the prefix-less name the live file uses and the install.* one
        // the result file uses.
        val processedRaw = live.pick("processedBytes", "processed", "done", "bytes.done", "copied", "written")
            ?: result.pick("install.processedBytes", "processedBytes")
        val totalRaw = live.pick("totalBytes", "total", "bytes.total", "size", "bytes")
            ?: result.pick("install.totalBytes", "totalBytes")
        val planCpk = result.pick("install.planCpk")
        val planBytes = result.pick("install.planBytes")
        return Install(
            id = live.pick("install.id", "id", "installId", "run")
                ?: result.pick("install.id", "id")
                ?: journal.pick("id", "install.id", "installId"),
            started = pretty(live.pick("startedAt", "started", "start", "start.epoch", "startTime"))
                ?: pretty(result.pick("install.startedAt", "startedAt"))
                ?: pretty(journal.pick("started", "startedAt")),
            updated = pretty(live.pick("updatedAt", "updated", "at", "timestamp", "progress.at"))
                ?: pretty(result.pick("install.updatedAt", "updatedAt")),
            finished = pretty(result.pick("install.finishedAt", "finishedAt", "finished", "timestamp", "at"))
                ?: pretty(journal.pick("finished", "finishedAt", "doneAt")),
            phase = live.pick("phase") ?: result.pick("phase") ?: journal.pick("phase"),
            // "progress" is as likely to be a byte count as a percentage on an
            // unfamiliar build; a percentage is computed from the two numbers
            // the file does name.
            percent = live.pick("percent", "pct")
                ?: result.pick("install.percent", "percent")
                ?: percentOf(processedRaw, totalRaw),
            processed = byteText(processedRaw),
            total = byteText(totalRaw),
            current = live.pick("file", "current", "currentFile", "copying", "path")?.let { oneLine(it, 160) },
            selection = live.pick("selection", "slots")
                ?: result.pick("install.selection", "selection", "slots"),
            policy = live.pick("policy", "backup", "backupPolicy", "backup.policy")
                ?: result.pick("install.policy", "policy"),
            plan = live.pick("plan", "plan.summary")
                ?: planText(planCpk, planBytes)
                ?: state.pick("patch.plan"),
            terminal = terminal(result),
            staleness = staleness(facts, now),
            // When only patch-state.properties exists there was no live run to
            // read; its keys are then the only install detail there is.
            other = (live.other() + journal.other()).ifEmpty { state.other() }.take(MAX_OTHER),
        )
    }

    /** "3 CPK, 700 MB": what the run was going to write. */
    private fun planText(cpk: String?, bytes: String?): String? {
        if (cpk == null && bytes == null) return null
        return listOfNotNull(
            cpk?.let { "$it CPK" },
            bytes?.toLongOrNull()?.let { size(it) },
        ).joinToString(", ").ifEmpty { null }
    }

    /** A byte count as a person reads it; anything else as it was written. */
    private fun byteText(value: String?): String? {
        if (value == null) return null
        val number = value.toLongOrNull() ?: return oneLine(value, 160)
        return size(number)
    }

    private fun terminal(result: Reader): String? {
        // `state` also names the slot state in patch-state.properties; only the
        // result file's own value is a terminal state, and only that file is a
        // Reader here.
        val state = result.pick("state", "result", "outcome")
        val message = result.pick("message", "error", "detail")?.let { oneLine(it, 160) }
        if (state == null && message == null) return null
        return buildString {
            append(state ?: "finished")
            pretty(result.pick("timestamp", "finished", "finishedAt"))?.let { append(" · ").append(it) }
            if (!message.isNullOrBlank()) append(" · ").append(message)
        }
    }

    /**
     * Whether the live file still describes the run being reported. A leftover
     * live file is the classic way a screen shows a percentage for an install
     * that was killed, so the report says which of the three shapes it is.
     */
    private fun staleness(facts: InstallFacts, now: Long): String? {
        if (!facts.livePresent) return null
        val age = facts.liveAgeSeconds
        val ageText = ageText(age)
        return when {
            facts.resultPresent && facts.resultNewerThanLive ->
                "stale: the outcome was written after this file (last written $ageText)"
            facts.resultPresent ->
                "a newer run has written this file since the last outcome ($ageText)"
            age >= STALE_SECONDS ->
                "stale: nothing written for $ageText and no outcome on disk"
            else ->
                "live: last written $ageText"
        }
    }

    private fun ageText(seconds: Long): String = when {
        seconds < 0 -> "at an unknown time"
        seconds < 60 -> "$seconds s ago"
        seconds < 3600 -> "${seconds / 60} min ago"
        else -> "${seconds / 3600} h ago"
    }

    private fun percentOf(processed: String?, total: String?): String? {
        val done = processed?.toLongOrNull() ?: return null
        val all = total?.toLongOrNull() ?: return null
        if (all <= 0L) return null
        return "${done * 100 / all}%"
    }

    /** Epoch-shaped values read as a date; anything else is already text. */
    private fun pretty(value: String?): String? {
        if (value == null) return null
        val trimmed = value.trim()
        if (trimmed.length !in 10..13) return oneLine(trimmed, 160)
        val number = trimmed.toLongOrNull() ?: return oneLine(trimmed, 160)
        val millis = if (trimmed.length <= 10) number * 1000 else number
        return stamp(millis)
    }

    /**
     * A property map with a record of what was read, so the Install section can
     * print what it understood and then the keys it did not.
     */
    private class Reader(private val values: Map<String, String>) {

        private val used = HashSet<String>()

        fun pick(vararg keys: String): String? {
            for (key in keys) {
                val match = values.entries.firstOrNull { entry ->
                    entry.key.equals(key, ignoreCase = true) ||
                        entry.key.endsWith(".$key", ignoreCase = true)
                } ?: continue
                if (match.value.isBlank()) continue
                used += match.key
                return match.value.trim()
            }
            return null
        }

        fun other(): List<Pair<String, String>> = values.entries
            .filter { it.key !in used && it.value.isNotBlank() }
            .map { it.key to it.value.trim() }
            .sortedBy { it.first }
    }

    // ---- shared formatting --------------------------------------------------

    /** The event log's lines whose kind starts with `ui.`, newest last. */
    fun lastUi(events: List<String>, count: Int): List<String> {
        val ui = events.filter { UI_EVENT.containsMatchIn(it) }
        val timed = ui.map { timestamp(it) to it }
        return if (timed.all { it.first != null }) {
            timed.sortedBy { it.first }.takeLast(count).map { it.second }
        } else {
            ui.takeLast(count)
        }
    }

    /**
     * The first line worth reading in a crash file. Trail writes a stamp, the
     * device, the thread and what the app was doing before the trace; the
     * exception is the first line after that header.
     */
    fun crashHeadline(text: String): String {
        if (text.isBlank()) return "none recorded"
        val lines = text.lines()
        var index = lines.indexOfFirst { it.isNotBlank() }
        if (index < 0) return "none recorded"
        if (STAMP_AT_START.containsMatchIn(lines[index])) {
            val headerEnd = (index until minOf(lines.size, index + 10)).firstOrNull { lines[it].isBlank() }
            if (headerEnd != null) {
                val after = (headerEnd + 1 until lines.size).firstOrNull { lines[it].isNotBlank() }
                if (after != null) index = after
            }
        }
        return oneLine(lines[index], 200)
    }

    /**
     * Epoch millis when the line carries a date and time near its front. A
     * time without a date is not enough: two runs from different days cannot
     * be ordered by a clock alone.
     */
    fun timestamp(line: String): Long? {
        val match = DATE_TIME.find(line) ?: return null
        if (match.range.first > 40) return null
        val g = match.groupValues
        return runCatching {
            val millis = g[7].padEnd(3, '0').take(3).toInt() * 1_000_000
            LocalDateTime.of(
                g[1].toInt(), g[2].toInt(), g[3].toInt(),
                g[4].toInt(), g[5].toInt(), g[6].toInt(), millis,
            ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    fun stamp(millis: Long): String =
        if (millis <= 0L) "-" else STAMP.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    /** ISO-8601 with the phone's offset, for the manifest a script reads. */
    private fun iso(millis: Long): String =
        if (millis <= 0L) "-" else ISO.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(Locale.US, bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "%.1f MB".format(Locale.US, bytes / 1048576.0)
        bytes >= 1L shl 10 -> "%d KB".format(bytes shr 10)
        else -> "$bytes B"
    }

    /** One line of a value that came from a file: a dumped buffer must not own the page. */
    fun oneLine(text: String, max: Int): String {
        val single = text.replace('\n', ' ').replace('\r', ' ').trim()
        if (max <= 0) return ""
        return if (single.length <= max) single else single.take(max - 1) + "…"
    }

    private fun deviceLine(device: Device): String =
        listOf(device.manufacturer, device.model).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { "unknown device" }

    private fun quote(text: String): String = buildString(text.length + 2) {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append(String.format(Locale.US, "\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val ISO: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    private val DATE_TIME = Regex(
        "(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,3}))?"
    )
    private val STAMP_AT_START = Regex("^\\d{4}-\\d{2}-\\d{2}")
    private val UI_EVENT = Regex("(?:^|[\\s\\[(,])ui\\.[a-z]")

    private const val GAP_MILLIS = 10_000L
    private const val STALE_SECONDS = 600L
    private const val MAX_TIMELINE = 200
    private const val MAX_UNTIMED = 40
    private const val MAX_LINE = 400
    private const val MAX_UI = 20
    private const val MAX_OTHER = 12
    private const val PATH_WIDTH = 48
    private const val MAX_REPORT_CHARS = 190_000
    private const val SUMMARY_UI = 5
    private const val SUMMARY_LINES = 40
}
