package com.pes2017.recomp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A debugging capture, started and stopped with the CAPTURE button: everything
 * needed to work out what went wrong in a stretch of play, in one folder.
 *
 *   files/captures/<name>/
 *     summary.txt    device, build, every setting, renderer and driver, frame statistics
 *     stats.csv      once every half second: fps, frame time, texture memory, temperatures
 *     game.log       the runtime's log for the stretch (and what led up to it)
 *     logcat.txt     Android's own log for the app, which holds crashes the runtime log cannot;
 *                    logcat.meta beside it says how the dump went and why it is short or empty
 *     screenshot.png the picture at the moment of stopping
 *     frames/        a small JPEG of the window every half second (the last 90 s), to see what the
 *                    screen showed; a flicker that is quicker than that shows as the odd black frame
 *     engine.txt     what the engine really ran with: PES17_* options and switch files as they were at
 *                    the start and at the stop, swapchain and present counts (upscaled, plain,
 *                    empty, let go), the display clock, Vulkan version, driver, features, extensions
 *     exits.txt      how earlier runs ended, per Android (native crash, ANR, low memory); beside it the
 *                    last-crash-*.txt files and launcher.log when they exist
 *     display.txt    the panel's refresh rate and modes, density, HDR, the window's size
 *     <name>.macro   the controller input of the stretch, replayable with Macro (also copied
 *                    to files/macros so it can be played from the menu)
 *
 * It also asks the renderer for its own capture (the CAPTURE request file the
 * renderer already understands) when it starts and when it stops, so the log
 * has the per-frame draw counts around both moments and a dump of a frame.
 */
class CaptureSession(private val activity: Activity, private val prefs: Prefs) {

    private val handler = Handler(Looper.getMainLooper())
    private val base: File = activity.getExternalFilesDir(null) ?: activity.filesDir
    private val stats = FloatArray(8)
    private val samples = StringBuilder("t_ms,fps,frame_ms,texture_mb,width,height,battery_c,thermal,ram_free_mb\n")
    private var fpsMin = Float.MAX_VALUE
    private var fpsMax = 0f
    private var fpsSum = 0.0
    private var worstFrameMs = 0f
    private var count = 0

    @Volatile var active = false
        private set
    private var startedAt = 0L
    private var startedWall = 0L

    /**
     * game.log as it was when the capture began: how many bytes it held and
     * when it was last written. Both are read again at the stop, because the
     * file the capture started on can be rotated away mid-capture -- the
     * runtime deletes a log past 4 MB and starts a fresh one -- and then what
     * led up to the start is in game.log.1 instead.
     */
    private var logBytes = 0L
    private var logStamp = 0L
    private lateinit var folder: File
    private var engineAtStart = ""
    private var frameSurface: SurfaceView? = null
    private var frameCount = 0
    @Volatile private var grabbing = false

    /** Called with the elapsed seconds, to keep the button's label current. */
    var onTick: ((Int) -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            sample()
            grabFrame()
            onTick?.invoke(((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt())
            handler.postDelayed(this, 500)
        }
    }

    fun start(surface: SurfaceView? = null) {
        if (active) return
        frameSurface = surface
        frameCount = 0
        engineAtStart = engineReport()
        samples.setLength(samples.indexOf("\n") + 1)
        fpsMin = Float.MAX_VALUE; fpsMax = 0f; fpsSum = 0.0; worstFrameMs = 0f; count = 0
        startedAt = SystemClock.elapsedRealtime()
        startedWall = System.currentTimeMillis()
        File(base, "game.log").let {
            logBytes = if (it.isFile) it.length() else 0L
            logStamp = if (it.isFile) it.lastModified() else 0L
        }
        folder = File(base, "captures/pending").apply { deleteRecursively(); mkdirs() }
        File(folder, "frames").mkdirs()
        // The renderer's black-frame probe runs while a capture does (it reads every frame back, which
        // costs a little speed): game.log gets "probe:" lines saying how many frames came out black and
        // from which slot of the renderer's rings.
        try { File(base, "black_probe").writeText("1") } catch (_: Throwable) { }
        Log.i(TAG, "capture started")
        // The renderer's view of the moments before the start, and a frame.
        requestRendererCapture()
        Macro.startRecording()
        active = true
        handler.post(tick)
    }

    /**
     * Stops and writes the folder. `name` and `note` come from the player (what
     * to call it, and what went wrong); `done` gets the folder written.
     */
    fun stop(surface: SurfaceView, name: String, note: String, done: (File?, String) -> Unit) {
        if (!active) return
        active = false
        handler.removeCallbacks(tick)
        sample()
        frameSurface = null
        // Left on until the log is written (below), so the last frames are counted.
        requestRendererCapture()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startedWall))
        val clean = name.trim().map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("").ifEmpty { "capture" }
        val finalFolder = File(base, "captures/$stamp-$clean")
        val events = Macro.stopRecording(activity, clean)
        screenshot(surface) { png ->
            // Give the renderer a moment to write its capture lines to the log.
            Thread {
                try {
                    Thread.sleep(1500)
                    folder.renameTo(finalFolder)
                    val target = if (finalFolder.isDirectory) finalFolder else folder
                    png?.let { copyBitmap(it, File(target, "screenshot.png")) }
                    try { File(base, "black_probe").delete() } catch (_: Throwable) { }
                    writeFiles(target, clean, note, events)
                    activity.runOnUiThread { done(target, "saved") }
                } catch (error: Throwable) {
                    Log.e(TAG, "capture failed", error)
                    activity.runOnUiThread { done(null, error.message ?: "failed") }
                }
            }.start()
        }
    }

    fun cancel() {
        try { File(base, "black_probe").delete() } catch (_: Throwable) { }
        active = false
        handler.removeCallbacks(tick)
        Macro.stopRecording(activity, "last")
    }

    // ---- gathering ------------------------------------------------------------

    private fun sample() {
        if (!NativeBridge.isLoaded()) return
        try {
            NativeBridge.nativeStats(stats)
        } catch (_: Throwable) {
            return
        }
        val fps = stats[0]
        val frameMs = stats[4]
        count++
        if (fps > 0f) {
            fpsMin = minOf(fpsMin, fps); fpsMax = maxOf(fpsMax, fps); fpsSum += fps
        }
        worstFrameMs = maxOf(worstFrameMs, frameMs)
        val ms = SystemClock.elapsedRealtime() - startedAt
        samples.append("$ms,${"%.1f".format(Locale.US, fps)},${"%.1f".format(Locale.US, frameMs)},")
            .append("${stats[1].toInt()},${stats[2].toInt()},${stats[3].toInt()},")
            .append("${"%.1f".format(Locale.US, batteryTemp())},${thermal()},${freeMemoryMb()}\n")
    }

    private fun batteryTemp(): Float {
        val intent = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    }

    private fun thermal(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            (activity.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        else -1

    private fun freeMemoryMb(): Long {
        val info = android.app.ActivityManager.MemoryInfo()
        (activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(info)
        return info.availMem shr 20
    }

    private fun engineReport(): String =
        try { NativeBridge.nativeEngineReport() } catch (error: Throwable) { "(engine report failed: $error)\n" }

    /**
     * A small JPEG of what the window shows, every tick, into frames/. The newest 180 are kept
     * (90 s), so a long capture does not fill the phone.
     */
    private fun grabFrame() {
        val surface = frameSurface ?: return
        if (grabbing || surface.width <= 0 || surface.height <= 0 || !::folder.isInitialized) return
        grabbing = true
        val at = SystemClock.elapsedRealtime() - startedAt
        val number = frameCount++
        val directory = File(folder, "frames")
        try {
            val bitmap = Bitmap.createBitmap(surface.width / 4, surface.height / 4, Bitmap.Config.ARGB_8888)
            PixelCopy.request(surface, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    Thread {
                        try {
                            File(directory, "f%04d_%dms.jpg".format(Locale.US, number, at)).outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it)
                            }
                            directory.listFiles()?.sortedBy { it.name }?.dropLast(180)?.forEach { it.delete() }
                        } catch (_: Throwable) {
                        }
                        grabbing = false
                    }.start()
                } else grabbing = false
            }, handler)
        } catch (_: Throwable) {
            grabbing = false
        }
    }

    /** Why recent runs of the app ended, from the system (API 30+): a native crash, low memory, an ANR. */
    private fun exitReport(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "not available before Android 11\n"
        return try {
            val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val reasons = manager.getHistoricalProcessExitReasons(null, 0, 8)
            if (reasons.isEmpty()) "no earlier exits recorded\n"
            else reasons.joinToString("\n") {
                "${java.util.Date(it.timestamp)} ${it.processName} pid ${it.pid}: reason ${it.reason} " +
                    "status ${it.status} importance ${it.importance} rss ${it.rss / 1024} MB: ${it.description ?: ""}"
            } + "\n(reason 4 = native crash, 5 = ANR, 3 = low memory, 2 = signaled, 10 = user requested)\n"
        } catch (error: Throwable) {
            "exit reasons failed: $error\n"
        }
    }

    private fun displayReport(): String {
        val out = StringBuilder()
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay
        if (display == null) return "no display\n"
        out.append("display: ${display.name}, refresh now ${display.refreshRate} Hz\n")
        val mode = display.mode
        out.append("mode now: ${mode.physicalWidth}x${mode.physicalHeight} at ${mode.refreshRate} Hz\n")
        out.append("modes:")
        for (m in display.supportedModes) out.append(" ${m.physicalWidth}x${m.physicalHeight}@${"%.2f".format(Locale.US, m.refreshRate)}")
        out.append("\n")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            out.append("HDR types: ${display.hdrCapabilities?.supportedHdrTypes?.joinToString() ?: "none"}, wide colour ${display.isWideColorGamut}\n")
        val metrics = activity.resources.displayMetrics
        out.append("density ${metrics.densityDpi} dpi, window ${frameSurface?.width}x${frameSurface?.height}\n")
        out.append("preferred refresh in prefs: 60 Hz lock ${prefs.prefer60Hz}\n")
        return out.toString()
    }

    private fun requestRendererCapture() {
        try {
            File(base, "capture_request").writeText("1")
        } catch (_: Throwable) {
        }
    }

    private fun screenshot(surface: SurfaceView, done: (Bitmap?) -> Unit) {
        if (surface.width <= 0 || surface.height <= 0) { done(null); return }
        try {
            val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(surface, bitmap, { result ->
                done(if (result == PixelCopy.SUCCESS) bitmap else null)
            }, handler)
        } catch (_: Throwable) {
            done(null)
        }
    }

    private fun copyBitmap(bitmap: Bitmap, file: File) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    private fun writeFiles(target: File, name: String, note: String, events: Int) {
        File(target, "stats.csv").writeText(samples.toString())

        // The runtime's log from where the session began, with 400 lines before
        // it for what led up to the stretch.
        writeGameLog(target)

        writeLogcat(target)

        val macro = File(Macro.directory(activity), "$name.macro")
        if (macro.isFile) macro.copyTo(File(target, "$name.macro"), overwrite = true)

        // Faults the capture itself cannot see because the run that had them is over: the last crash
        // each process left (internal storage), the launcher's log, and how Android says earlier
        // runs of this app ended (killed for memory, native crash, ANR).
        for (name in listOf("last-crash-game.txt", "last-crash-launcher.txt", "launcher.log")) {
            val file = File(activity.filesDir, name)
            if (file.isFile && file.length() > 0)
                try { File(target, name).writeText(file.readTextTail(200_000)) } catch (_: Throwable) { }
        }
        File(target, "exits.txt").writeText(exitReport())
        File(target, "engine.txt").writeText(
            "=== at the start of the capture ===\n$engineAtStart\n=== at the stop ===\n${engineReport()}")
        File(target, "display.txt").writeText(displayReport())
        // A capture is shared around: the folder the player's copy of the game
        // lives in and any absolute path in it say who they are, and nothing in
        // a bug report needs that.
        File(target, "summary.txt").writeText(Scrub.text(activity, summary(name, note, events)))
    }

    /**
     * game.log from where the capture began, with 400 lines before it for what led up
     * to the stretch.
     *
     * The start of the session was recorded as a byte count, so the cut is made in
     * bytes: dropping that count off the String instead (text.drop(from)) treated a
     * byte offset as a character index, and one line of multi-byte text before it
     * moved the cut into the middle of a line, or past the end of what was read.
     * The whole file is read (the runtime keeps it under 4 MB) so no offset has to
     * be translated at all.
     */
    private fun writeGameLog(target: File) {
        val log = File(base, "game.log")
        if (!log.isFile) return
        val bytes = try { log.readBytes() } catch (_: Throwable) { ByteArray(0) }
        // The file the capture began on only ever grows while the capture runs,
        // so a shorter file is a new one, and a write time that went backwards is
        // one put back in place. (Comparing the write time for a change is no
        // use: every line appended during the capture changes it.)
        val rotated = logBytes > 0L && (bytes.size.toLong() < logBytes || log.lastModified() < logStamp)
        val start = if (rotated) 0 else logBytes.coerceIn(0L, bytes.size.toLong()).toInt()
        val lead = if (rotated) {
            File(base, "game.log.1").let { if (it.isFile) it.readTextTail(200_000) else "" }
        } else {
            String(bytes, 0, start, Charsets.UTF_8).lines().takeLast(400).joinToString("\n")
        }
        val marker = if (rotated)
            "\n===== the log rotated mid-capture: the lines above are game.log.1 =====\n"
        else
            "\n===== capture starts here =====\n"
        File(target, "game.log").writeText(lead + marker + String(bytes, start, bytes.size - start, Charsets.UTF_8))
    }

    /**
     * Android's own log for this run. -d dumps and returns, so nothing is left
     * running; the main and crash buffers for this pid only, because the rest of
     * the phone's chatter is noise in a report. logcat.meta says how it went, so
     * an empty logcat.txt always has the reason written beside it.
     */
    private fun writeLogcat(target: File) {
        val command = "logcat -d -b main,crash -t 4000 --pid=${android.os.Process.myPid()}"
        var code = -1
        var error = ""
        var text = ""
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-b", "main,crash", "-t", "4000", "--pid=${android.os.Process.myPid()}"))
            text = process.inputStream.bufferedReader().readText()
            error = process.errorStream.bufferedReader().readText()
            code = process.waitFor()
        } catch (thrown: Throwable) {
            error = thrown.toString()
        }
        val body = text.ifBlank { "(logcat wrote nothing: ${error.ifBlank { "no reason given" }})\n" }
        File(target, "logcat.txt").writeText(body)
        File(target, "logcat.meta").writeText(
            "command: $command\nexit: $code\nbytes: ${body.length}\nstderr: ${error.ifBlank { "(none)" }.take(4000)}\n")
    }

    private fun summary(name: String, note: String, events: Int): String {
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        val average = if (count > 0) fpsSum / count else 0.0
        val renderer = try { NativeBridge.nativeRendererInfo() } catch (_: Throwable) { "" }
        val driver = try { NativeBridge.nativeDriverDescription() } catch (_: Throwable) { "" }
        val info = StringBuilder()
        info.append("PES 2018 Android capture: $name\n")
        info.append("what went wrong: ${note.ifBlank { "(not said)" }}\n\n")
        info.append("duration: ${"%.1f".format(Locale.US, seconds)} s, $count samples\n")
        info.append("fps: avg ${"%.1f".format(Locale.US, average)}, min ${if (fpsMin == Float.MAX_VALUE) 0 else fpsMin}, max $fpsMax; worst frame $worstFrameMs ms\n")
        info.append("controller events recorded: $events (macro \"$name\")\n\n")
        info.append("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        info.append("build: ${BuildConfig.BUILD_SHA} ${BuildConfig.BUILD_STAMP}\n")
        info.append("renderer: $renderer\n")
        info.append("driver: $driver\n")
        info.append("game folder: ${prefs.gameDirectory}\n\n")
        info.append("settings:\n")
        info.append("  performance preset ${prefs.preset.ifEmpty { "none" }}, heat scale ${prefs.heatScale}, " +
            "load scale ${prefs.loadScale}, performance hint ${prefs.perfHint}\n")
        info.append("  display resolution ${(prefs.resolutionScale * 100).toInt()}%, internal scale ${prefs.renderScale}x, " +
            "scene ${prefs.sceneScalePercent}%\n")
        info.append("  msaa ${prefs.multisampling}, 60 Hz lock ${prefs.prefer60Hz}, fsr ${prefs.fsr} (sharpness ${prefs.fsrSharpness}), " +
            "fps limit ${prefs.fpsLimit}, low memory ${prefs.lowMemory}\n")
        info.append("  texture memory ${if (prefs.textureBudgetMB > 0) "${prefs.textureBudgetMB} MB" else "auto"}, " +
            "compile all shaders ${prefs.prewarmAllShaders}, audio ${prefs.audioEnabled}\n")
        info.append("  experiments: arena ${if (prefs.arenaMB > 0) "${prefs.arenaMB} MB" else "auto"}, classic render path ${prefs.expLegacy}, " +
            "skip colour table ${prefs.expNoTable}, hardware BC ${prefs.expBcHw}, per-draw depth ${prefs.expDynamicDepth}, " +
            "black-frame guard ${prefs.blackGuard}\n")
        info.append("  touch controls ${prefs.touchControls}, gamepad ${prefs.gamepadEnabled}, vibration ${prefs.vibration}\n")
        return info.toString()
    }

    companion object {
        private const val TAG = "PES17.Capture"
    }
}
