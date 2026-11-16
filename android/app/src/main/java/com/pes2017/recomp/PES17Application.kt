package com.pes2017.recomp

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Installed before anything else in either process, for one reason: so that
 * a crash says what it was doing.
 *
 * An app that dies on the launcher screen with nothing written down is a day
 * lost to guessing, and the interesting failures here happen on a phone that
 * is not attached to a computer. So the last exception is kept in a file,
 * and the launcher shows it on the next start.
 */
class PES17Application : Application() {

    override fun onCreate() {
        super.onCreate()
        Trail.install(this)
        // Before the first note of the run, so every timeline has a beginning.
        EventLog.header(this)
        Trail.note(this, "app: process started")
        PatchesRepository.init(this)
        // A patch install that lost its process leaves a journal. Put the
        // previous slots back before any screen reads their state; deleting a
        // 20 GB staging folder must not block the first frame.
        //
        // Only the launcher process may recover. PES17Application also runs in
        // the `:game` process, and the lock is per process: letting the game
        // recover while the launcher's PatchInstallService is mid-swap would
        // roll that transaction back from under it.
        if (!inGameProcess()) {
            // The game process starts its own sampler from GameActivity, once
            // its surface exists; two would double every stall line.
            StallWatch.start(this)
            thread(name = "patch-recover", isDaemon = true) {
                runCatching { PatchesRepository.recover() }
            }
        }    }

    private fun inGameProcess(): Boolean {
        val name = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getProcessName()
            } else {
                File("/proc/self/cmdline").readText().trim('\u0000')
            }
        } catch (_: Throwable) {
            ""
        }
        return name.endsWith(":game")
    }
}

/**
 * What is left after a run that is already over:
 *
 *   last-crash-<proc>.txt   the stack trace of whatever killed the app last time
 *   crash-history/          the last ten of those, kept after the dialog is gone
 *
 * The ongoing timeline lives in EventLog.kt. It matters for the failures an
 * exception handler never sees. A native crash inside System.loadLibrary
 * takes the process down without unwinding anything, and the only evidence
 * left is the last line written before it: "loading the runtime library".
 * That is the difference between a bug report and a shrug.
 */
object Trail {

    private const val LIMIT = 64 * 1024
    private const val ARCHIVES = 10

    /**
     * The launcher and the run are separate processes and both write here.
     * Each keeps its own pair of files, or one clearing its trail on a
     * successful start would throw away the other's evidence of a crash.
     */
    private val suffix: String by lazy {
        val name = try {
            File("/proc/self/cmdline").readText().trim('\u0000')
        } catch (_: Throwable) {
            ""
        }
        if (name.endsWith(":game")) "game" else "launcher"
    }

    private val CRASH: String get() = "last-crash-$suffix.txt"
    private val SEEN: String get() = "$CRASH.seen"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                val text = buildString {
                    append(stamp()).append('\n')
                    append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    append(", Android ").append(Build.VERSION.RELEASE)
                    append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                    append("thread: ").append(thread.name).append('\n')
                    // The tail of the timeline, not all of it: this file is
                    // read on a phone and the last few lines are the story.
                    append("what it was doing: ")
                    append(trail(context).takeLast(4_000).trim()).append("\n\n")
                    append(writer.toString())
                }
                File(context.filesDir, CRASH).writeText(text)
                archive(context, text)
                // A fresh crash is news again, even if the last one was read.
                File(context.filesDir, SEEN).delete()
                EventLog.note(
                    context, "crash",
                    "thread" to thread.name,
                    "exception" to error.toString(),
                )
            } catch (_: Throwable) {
                // Nothing useful is possible here; do not make it worse.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** One line in the event log: the breadcrumb and the timeline share a file. */
    fun note(context: Context, what: String) {
        EventLog.note(context, "trail", "msg" to what)
    }

    /**
     * The run is up. The lines stay: the failures worth reading happen after
     * a start that looked successful.
     */
    fun clear(context: Context) {
        EventLog.note(context, "run.clean-end")
    }

    fun lastCrash(context: Context): String {
        return try {
            val file = File(context.filesDir, CRASH)
            // Read once, gone from the dialog, still on disk for export.
            if (!file.exists() || File(context.filesDir, SEEN).exists()) "" else file.readText()
        } catch (_: Throwable) {
            ""
        }
    }

    fun forgetCrash(context: Context) {
        try {
            File(context.filesDir, SEEN).writeText(stamp())
        } catch (_: Throwable) {
        }
    }

    /** The event log tail: what the app was doing, newest last. */
    fun trail(context: Context): String = try {
        val file = EventLog.file(context)
        if (!file.exists()) "" else {
            val text = file.readText()
            if (text.length > LIMIT) "…\n" + text.takeLast(LIMIT) else text
        }
    } catch (_: Throwable) {
        ""
    }

    /**
     * The ten most recent crashes of this process, under their own names: the
     * dialog only ever shows the newest, and an older one must still be
     * exportable after that dialog was closed.
     */
    private fun archive(context: Context, text: String) {
        val directory = File(context.filesDir, "crash-history")
        if (!directory.isDirectory && !directory.mkdirs()) return
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-$suffix.txt"
        File(directory, name).writeText(text)
        val mine = directory.listFiles { file -> file.name.endsWith("-$suffix.txt") } ?: return
        // The name starts with the timestamp, so sorting by name sorts by age.
        for (old in mine.sortedBy { it.name }.dropLast(ARCHIVES)) old.delete()
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
