package com.pes2017.recomp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * Evidence for the failure Android reports as "isn't responding" but the app
 * never sees: the main thread stopped draining its queue for long enough that
 * touches stopped being answered.
 *
 * A one second heartbeat that does nothing is cheap; the expensive part only
 * runs when a beat is late, which is exactly the moment worth recording.
 */
object StallWatch {

    private const val TICK_MS = 1_000L
    private const val LATE_MS = 2_000L
    private const val THROTTLE_MS = 5_000L
    private const val FRAMES = 12

    private val lock = Any()
    private var running = false

    /** When the main queue last proved it was moving. */
    @Volatile
    private var lastTick = 0L

    /** Starts the sampler once for this process; later calls do nothing. */
    fun start(context: Context) {
        synchronized(lock) {
            if (running) return
            running = true
        }
        val app = context.applicationContext
        lastTick = SystemClock.uptimeMillis()

        // The heartbeat: a message that comes back around proves the main
        // queue is draining. Missed beats are the signal.
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                lastTick = SystemClock.uptimeMillis()
                handler.postDelayed(this, TICK_MS)
            }
        }
        handler.postDelayed(tick, TICK_MS)

        // The sampler is a second thread on purpose: a late beat cannot say
        // what held it up, because by the time it runs the blocking frames are
        // gone and only the dispatcher is left. A thread that is not blocked
        // can read the main thread's stack while it is still stuck, which is
        // the frame list that identifies the stall.
        thread(name = "stall-watch", isDaemon = true) {
            var lastReport = 0L
            while (true) {
                try {
                    Thread.sleep(TICK_MS)
                    val now = SystemClock.uptimeMillis()
                    val late = now - lastTick
                    if (late <= LATE_MS || now - lastReport < THROTTLE_MS) continue
                    val frames = mainFrames()
                    // A main thread parked in the queue is not stalled: the
                    // process was frozen (doze, an OEM freezer) and the clock
                    // ran on without it. That would be a false report.
                    if (frames.isEmpty() || isIdle(frames)) continue
                    lastReport = now
                    EventLog.note(
                        app, "stall",
                        "ms" to late.toString(),
                        "thread" to "main",
                        "stack" to describe(frames),
                    )
                } catch (_: InterruptedException) {
                    return@thread
                } catch (_: Throwable) {
                    // A failed sample must not end the watch.
                }
            }
        }
    }

    /** The top frames of the main thread, read while it is blocked. */
    private fun mainFrames(): List<StackTraceElement> {
        val main = Looper.getMainLooper().thread
        val trace = try {
            Thread.getAllStackTraces()[main]
        } catch (_: Throwable) {
            null
        }
        return trace?.take(FRAMES) ?: emptyList()
    }

    /** Waiting for the next message is health, not a stall. */
    private fun isIdle(frames: List<StackTraceElement>): Boolean {
        val top = frames.firstOrNull() ?: return true
        return top.className == "android.os.MessageQueue" && top.methodName == "nativePollOnce"
    }

    private fun describe(frames: List<StackTraceElement>): String =
        frames.joinToString(" <- ") { frame ->
            val owner = frame.className.substringAfterLast('.')
            "$owner.${frame.methodName}(${frame.fileName ?: "?"}:${frame.lineNumber})"
        }
}
