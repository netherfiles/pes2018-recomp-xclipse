package com.pes2017.recomp

import android.content.Context
import java.io.File

/**
 * The log screen's text: what is running, what went wrong, and where the run got
 * to -- not the log.
 *
 * The log is a few hundred lines that record every decision the runtime took,
 * which is the right thing to attach to a bug report and the wrong thing to put
 * in front of somebody on a phone: the four lines that matter are somewhere in
 * the middle of it. So this reads the end of the log, keeps the run that is
 * being asked about, counts what warned and what failed, and prints each kind of
 * warning once with a count rather than printing four hundred of them. The whole
 * log is still one button away, because sometimes the whole log is what is
 * wanted.
 */
object LogSummary {

    /** A run is appended after a line of its own, so the tail holds several. */
    private const val kRunMarker = "===== new run"

    /** The end of the log is the part that describes the run being looked at. */
    private const val kScanBytes = 1 shl 20

    /** Kinds of warning and error printed; the rest are counted, not shown. */
    private const val kNotableLines = 20

    /** How far the run got: a line or two, twelve to be sure. */
    private const val kTailLines = 12

    /** A crash is the most precise evidence there is, and its top says it. */
    private const val kCrashLines = 14

    /** One line, so a dumped buffer cannot fill the screen it is shown on. */
    private const val kLineLength = 200

    /**
     * What a file shows in the whole-log view. The diagnostics export carries
     * every byte; this is the part somebody reads on a phone.
     */
    private const val kWholeFileBytes = 60_000

    /**
     * The renderer's telemetry: one line a second, the same counters again and
     * again. It is context for the tail (what the frame was doing) but it is
     * never the answer, so it is not one of the notable kinds.
     */
    private const val kNoise = "tele:"

    /**
     * An address, which is the part a line repeats with and the part nobody
     * reads. Two lines that differ only here are one line that happened twice:
     * the runtime naming four hundred different blocks in four hundred warnings
     * is four hundred warnings about one thing.
     */
    private val ADDRESS = Regex("0x[0-9a-fA-F]+")

    /**
     * The prefix the runtime writes in front of a line now: the UTC time in ISO
     * form, then the boot it belongs to. The time differs on every line, so it
     * has to come off before two lines can be compared; see [bodyOf].
     */
    private val STAMP = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z\\s+(?:\\S+\\s+)?")

    fun build(context: Context, renderer: String, sessionTail: String): String
    {
        // game.log.1 is the run the runtime rotated away when this one started:
        // after a restart game.log can hold nothing but its own header, and the
        // run worth reading is the one before it.
        val current = lastRun(File(context.filesDir, "game.log").readTextTail(kScanBytes))
        val previous = lastRun(File(context.filesDir, "game.log.1").readTextTail(kScanBytes))
        val fromPrevious = current.none { levelOf(it) != null } && previous.any { levelOf(it) != null }
        val lines = when {
            fromPrevious -> previous
            current.isNotEmpty() -> current
            else -> lastRun(File(context.filesDir, "launcher.log").readTextTail(kScanBytes))
        }
        val crash = crashOf(context)
        // Nothing has run yet: the screen says so rather than counting the
        // errors of a log that does not exist.
        if (lines.isEmpty() && crash.isBlank() && sessionTail.isBlank())
            return context.getString(R.string.log_empty)

        val errors = lines.count { levelOf(it) == 'E' }
        val warnings = lines.count { levelOf(it) == 'w' }

        // How often each kind happened, and where it last happened: the count is
        // the pattern, the last index is what the run was doing when it stopped,
        // and a kind is worth showing for either of them.
        val counts = LinkedHashMap<String, Int>()
        val levels = HashMap<String, Char>()
        val lastAt = HashMap<String, Int>()
        val examples = LinkedHashMap<String, String>()
        for ((index, line) in lines.withIndex())
        {
            val level = levelOf(line) ?: continue
            if (level != 'w' && level != 'E') continue
            val body = bodyOf(line)
            if (body.startsWith(kNoise)) continue
            val shape = shapeOf(line)
            if (!counts.containsKey(shape)) {
                examples[shape] = trimmed(body)
                levels[shape] = level
            }
            counts[shape] = (counts[shape] ?: 0) + 1
            lastAt[shape] = index
        }
        // Every error first -- an error is what the screen is opened to read, and
        // taking the last-seen kinds alone dropped one that came before a long
        // stretch of warnings. Then half the list by frequency (the pattern) and
        // half by the most recent occurrence (what it was doing when it stopped).
        val chosen = LinkedHashSet<String>()
        counts.keys.filter { levels[it] == 'E' }.forEach { chosen.add(it) }
        counts.keys.sortedByDescending { counts[it] ?: 0 }
            .take(kNotableLines / 2).forEach { chosen.add(it) }
        counts.keys.sortedByDescending { lastAt[it] ?: -1 }
            .take(kNotableLines / 2).forEach { chosen.add(it) }
        for (shape in counts.keys.sortedByDescending { counts[it] ?: 0 })
            if (chosen.size < kNotableLines) chosen.add(shape)
        val recent = chosen.take(kNotableLines)
        val hidden = counts.size - recent.size

        val where = collapse(lines.takeLast(kTailLines * 4).ifEmpty {
            sessionTail.lines().filter { it.isNotBlank() }.takeLast(kTailLines * 4)
        })

        return buildString {
            append(context.getString(R.string.app_name))
                .append(" · build ").append(BuildConfig.BUILD_SHA).append('\n')
            if (renderer.isNotBlank())
                append(renderer.split('|').joinToString(" · ")).append('\n')
            if (fromPrevious)
                append(context.getString(R.string.log_previous_run)).append(" = game.log.1\n")

            if (crash.isNotBlank())
            {
                append('\n').append(context.getString(R.string.log_stopped)).append('\n')
                crash.lines().filter { it.isNotBlank() }.take(kCrashLines)
                    .forEach { append(trimmed(it)).append('\n') }
            }

            append('\n')
            if (errors == 0 && warnings == 0)
                append(context.getString(R.string.log_none)).append('\n')
            else
                append(context.getString(R.string.log_counts, errors, warnings)).append('\n')
            for (shape in recent)
            {
                append(examples[shape])
                val count = counts[shape] ?: 1
                if (count > 1) append("  ×").append(count)
                append('\n')
            }
            if (hidden > 0) append(context.getString(R.string.log_more, hidden)).append('\n')

            val tail = where.takeLast(kTailLines)
            if (tail.isNotEmpty())
            {
                append('\n').append(context.getString(R.string.log_where)).append('\n')
                for ((line, count) in tail)
                {
                    append(line)
                    if (count > 1) append("  ×").append(count)
                    append('\n')
                }
            }

            append('\n').append(context.getString(R.string.log_share_note))
        }
    }

    /** Everything the app has written, in one string: what Share sends. */
    fun whole(context: Context, sessionTail: String): String = buildString {
        // Each file is cut at 60 KB: this is the view somebody reads, and the
        // diagnostics report is the export that carries everything.
        for (name in listOf("game.log", "game.log.1", "launcher.log", "launcher.log.1"))
        {
            val file = File(context.filesDir, name)
            if (!file.exists()) continue
            append("===== ").append(name)
            // The rotated file is the run before this one, and saying so is
            // what keeps the two apart in one long string.
            if (name.endsWith(".1")) append(" · ").append(context.getString(R.string.log_previous_run))
            append(" =====\n")
            append(file.readTextTail(kWholeFileBytes)).append("\n\n")
        }
        for (name in listOf("last-crash-game.txt", "last-crash-launcher.txt"))
        {
            val file = File(context.filesDir, name)
            if (!file.exists()) continue
            append("===== ").append(name).append(" =====\n")
            append(file.readTextTail(8_000)).append("\n\n")
        }
        if (sessionTail.isNotBlank())
            append("===== this session =====\n").append(sessionTail)
    }.let {
        // What this text is and is not: it is capped, the export is not. Only
        // beside something to read -- an empty export still says it is empty.
        if (it.isBlank()) context.getString(R.string.log_empty)
        else it + "\n\n" + context.getString(R.string.log_whole_note)
    }

    /** The other process's crash, which is the one that matters, or this one's. */
    private fun crashOf(context: Context): String
    {
        for (name in listOf("last-crash-game.txt", "last-crash-launcher.txt"))
        {
            val file = File(context.filesDir, name)
            if (file.exists() && file.length() > 0) return file.readTextTail(8_000)
        }
        // crash-history keeps the ones the per-process files rotate away, so it
        // is the last place the crash of the last run can still be found.
        newestIn(File(context.filesDir, "crash-history"))?.let {
            if (it.length() > 0) return it.readTextTail(8_000)
        }
        return Trail.lastCrash(context)
    }

    private fun newestIn(folder: File): File? = try {
        folder.listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }
    } catch (_: Throwable) {
        null
    }

    /** The last run in a log: the runtime separates runs with a line of its own. */
    private fun lastRun(text: String): List<String> =
        text.substringAfterLast(kRunMarker).lines().filter { it.isNotBlank() }

    /**
     * Runs of one line, as (the last of them, how many). The end of a log is
     * often the same line sixty times -- sixty objects waited on, sixty textures
     * restaged -- and the line that says where the run got to is the one after
     * them, which this is what leaves room for.
     */
    private fun collapse(lines: List<String>): List<Pair<String, Int>>
    {
        val out = ArrayList<Pair<String, Int>>()
        for (line in lines)
        {
            val last = out.lastOrNull()
            if (last != null && shapeOf(last.first) == shapeOf(line))
                out[out.size - 1] = last.first to last.second + 1
            else
                out.add(trimmed(line) to 1)
        }
        return out
    }

    /**
     * The level a line carries, or null for anything that is not one of the
     * runtime's lines: a stack trace, a blank line, the separator between runs.
     *
     * The marker is "[x] " in the message, behind whatever prefix the line
     * carries -- the ISO time and boot the runtime now writes, or the columns
     * PES17_LOG_TIME used to -- so the search is bounded rather than a prefix
     * test, and the bracket is closed at once, which is what keeps a genuine
     * line apart from a bracketed address inside a trace.
     */
    private fun levelOf(line: String): Char?
    {
        val at = markerAt(line)
        return if (at < 0) null else line[at + 1]
    }

    /** Where a line's "[x] " marker starts, or -1 when it has none. */
    private fun markerAt(line: String): Int
    {
        val at = line.indexOf('[')
        if (at < 0 || at > 48) return -1
        if (at + 2 >= line.length || line[at + 2] != ']') return -1
        val level = line[at + 1]
        return if (level == 'i' || level == 'w' || level == 'E' || level == 'k') at else -1
    }

    /**
     * The line without the parts that are not its message: the time and boot in
     * front of it, then the "[x] " level marker. Two lines that differ only in
     * the second they were written are the same line twice, which is what makes
     * the counts fold.
     */
    private fun bodyOf(line: String): String
    {
        val at = markerAt(line)
        if (at >= 0) return line.substring(at + 3)
        return STAMP.replace(line, "")
    }

    private fun shapeOf(line: String): String = ADDRESS.replace(bodyOf(line), "0x…")

    private fun trimmed(line: String): String =
        if (line.length <= kLineLength) line else line.take(kLineLength - 3) + "..."
}
