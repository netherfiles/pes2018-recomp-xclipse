package com.pes2017.recomp

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One line-oriented timeline per process, for the failures that leave no
 * stack trace: a native crash, an ANR, a process killed from outside.
 *
 * Trail answered "what was it doing"; this answers "when, and in what order".
 * The two processes write their own file, `events-launcher.txt` and
 * `events-game.txt`, because a launcher start and a game start are different
 * stories and one must never overwrite the other.
 *
 * The timestamp is local time with its offset, not UTC: these files are read
 * by a person comparing them against what they saw on the phone at 07:03.
 */
object EventLog {

    /** Beyond this the file moves to `.1`; one old run is enough to compare against. */
    private const val LIMIT = 256 * 1024

    /** A value longer than this is a stack trace in a field, not a value. */
    private const val VALUE_LIMIT = 200

    /**
     * The launcher and the game are separate processes and both call in.
     * Same rule as Trail: the process is known from its command line.
     */
    private val suffix: String by lazy {
        val name = try {
            File("/proc/self/cmdline").readText().trim('\u0000')
        } catch (_: Throwable) {
            ""
        }
        if (name.endsWith(":game")) "game" else "launcher"
    }

    /** `filesDir/events-launcher.txt` or `filesDir/events-game.txt`. */
    fun file(context: Context): File = File(context.filesDir, "events-$suffix.txt")

    /**
     * Appends one event: `2026-11-12T07:03:22.123+07:00 I kind key="value"`.
     *
     * The process can be killed between two of these, so every line is
     * flushed before the call returns.
     */
    fun note(context: Context, kind: String, vararg fields: Pair<String, String>) {
        val line = buildString {
            append(stamp())
            append(" I ")
            append(clean(kind, VALUE_LIMIT))
            for ((key, value) in fields) {
                append(' ').append(clean(key, VALUE_LIMIT))
                append("=\"").append(clean(value, VALUE_LIMIT)).append('"')
            }
            append('\n')
        }
        append(context, line)
    }

    /**
     * The run header, written once from [PES17Application.onCreate].
     *
     * Application.onCreate runs once per process, so anything already in the
     * file belongs to an earlier start: it moves to `.1` instead of being
     * mixed into this run's timeline.
     */
    fun header(context: Context) {
        val text = "===== new run ${stamp()} pid=${android.os.Process.myPid()}" +
            " app=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE}" +
            " build=${BuildConfig.BUILD_SHA}" +
            " device=${Build.MANUFACTURER} ${Build.MODEL}" +
            " android=${Build.VERSION.RELEASE}/${Build.VERSION.SDK_INT} =====\n"
        try {
            synchronized(this) {
                val file = file(context)
                if (file.length() > 0) rotate(file)
                write(file, text)
            }
        } catch (_: Throwable) {
            // Evidence is a bonus; never make the process start fail for it.
        }
    }

    private fun append(context: Context, text: String) {
        try {
            synchronized(this) {
                val file = file(context)
                if (file.length() > LIMIT) rotate(file)
                write(file, text)
            }
        } catch (_: Throwable) {
            // Same: a full disk must not turn a note into a crash.
        }
    }

    private fun write(file: File, text: String) {
        FileOutputStream(file, true).use { out ->
            out.write(text.toByteArray())
            out.flush()
        }
    }

    private fun rotate(file: File) {
        val old = File(file.parentFile, file.name + ".1")
        try {
            old.delete()
            if (!file.renameTo(old)) {
                // Some storage refuses to rename a file that is dirty; keep the
                // evidence by copying and only then dropping the original.
                file.copyTo(old, overwrite = true)
                file.delete()
            }
        } catch (_: Throwable) {
            // A file that keeps growing beats a file that was thrown away.
        }
    }

    /**
     * Newlines and tabs would break the one-line-per-event contract, and a
     * quote would break the `key="value"` shape.
     */
    private fun clean(value: String, limit: Int): String {
        val cut = if (value.length > limit) value.substring(0, limit) else value
        return cut
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .replace('"', '\'')
    }

    /** Local time with its offset and milliseconds, e.g. 2026-11-12T07:03:22.123+07:00. */
    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
}
