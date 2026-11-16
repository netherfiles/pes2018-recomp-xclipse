package com.pes2017.recomp

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why Android itself says the previous runs ended, which is written down even
 * when the process had no chance to write anything: a native crash, a low
 * memory kill, an ANR, a swipe from the recents screen.
 *
 * The numbers are mapped here because the codes are not the order one would
 * guess -- 4 is a Java crash and 5 a native one, and 6 is an ANR, not a
 * native crash as an earlier report in this app claimed.
 */
object Exits {

    /** An ANR trace is long; enough to see the thread that was blocked. */
    private const val MAX_TRACE = 16 * 1024

    /** API 30 is where the system started keeping this history. */
    fun report(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "unavailable (API < 30)"
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return "exit reasons unavailable"
            val reasons = manager.getHistoricalProcessExitReasons(null, 0, 16)
            if (reasons.isEmpty()) return "no earlier exits recorded"
            val out = StringBuilder()
            for (info in reasons.sortedByDescending { it.timestamp }) {
                out.append(stamp(info.timestamp))
                out.append("  ").append(info.processName)
                out.append("  pid=").append(info.pid)
                out.append("  ").append(reasonName(info.reason))
                out.append(" (reason=").append(info.reason).append(')')
                out.append("  status=").append(info.status)
                out.append("  importance=").append(info.importance)
                out.append("  rss=").append(info.rss / (1024 * 1024)).append(" MB")
                out.append("  pss=").append(info.pss / (1024 * 1024)).append(" MB")
                val description = info.description
                if (!description.isNullOrBlank()) out.append("  \"").append(description).append('"')
                out.append('\n')
                // The trace only exists for an ANR (reason 6), and only when
                // the system kept it. It is the one piece of evidence no
                // handler in this app can produce, so it is worth the lines.
                if (info.reason == 6) {
                    val trace = readTrace(info.traceInputStream).trimEnd()
                    if (trace.isNotEmpty()) {
                        out.append("    trace:\n")
                        for (line in trace.lineSequence()) {
                            out.append("    ").append(line).append('\n')
                        }
                    }
                }
            }
            out.toString().trimEnd()
        } catch (error: Throwable) {
            "exit reasons failed: $error"
        }
    }

    /**
     * The numeric codes are written out rather than taken from the constants:
     * the same numbers on every API level, and no version-gated field lookups
     * in a method that only ever runs on API 30+ anyway.
     */
    private fun reasonName(reason: Int): String = when (reason) {
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH (java)"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "UNKNOWN"
    }

    /** At most [MAX_TRACE] bytes of the trace, as text; the pipe may not exist. */
    private fun readTrace(stream: InputStream?): String = try {
        if (stream == null) "" else stream.use {
            val bytes = ByteArray(MAX_TRACE)
            var length = 0
            while (length < bytes.size) {
                val read = it.read(bytes, length, bytes.size - length)
                if (read < 0) break
                length += read
            }
            String(bytes, 0, length)
        }
    } catch (_: Throwable) {
        ""
    }

    private fun stamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
}
