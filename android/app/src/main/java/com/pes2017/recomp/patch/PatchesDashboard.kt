package com.pes2017.recomp.patch

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.pes2017.recomp.PatchesRepository
import java.util.concurrent.Executors

/**
 * The PATCHES screen's state and storage numbers, built off the main thread
 * and kept until something changes.
 *
 * Reading the installed slots opens every CPK for its magic and parses the
 * DpFileList, and measuring storage walks the 20 GB game folder. Doing that in
 * `onResume` or a click handler was what made the page hang, so the screen
 * asks here instead:
 *
 *     PatchesDashboard.cached()?.let(::render)          // instant, may be null
 *     PatchesDashboard.load(this) { render(it) }        // fresh, on the main thread
 *     PatchesDashboard.refresh(this) { render(it) }     // after an operation
 *
 * Callbacks always arrive on the main thread (or inline when there is no
 * Android main looper, as in a plain JVM test). Concurrent asks share one
 * build; a second ask before the first finishes does not walk the folder
 * twice.
 *
 * This object holds no Context: it takes one at [load] and keeps none, so it
 * cannot leak an Activity.
 */
object PatchesDashboard {

    /** Everything the PATCHES screen draws, measured together at [builtAt]. */
    data class Snapshot(
        val state: PatchesRepository.State,
        val storage: StorageFacts?,
        val builtAt: Long,
        val error: String? = null,
    ) {
        val hasGame: Boolean get() = state.hasGameFolder
        val patch: PatchesRepository.PatchData? get() = state.patch
        val edited: PatchesRepository.EditedData? get() = state.edited
    }

    private val lock = Any()
    private val waiting = ArrayList<(Snapshot) -> Unit>()
    private var scheduled = false

    @Volatile
    private var cache: Snapshot? = null

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "patches-dashboard").apply { isDaemon = true }
    }

    /** The last finished snapshot, for an immediate first paint; null until one exists. */
    fun cached(): Snapshot? = cache

    /** Drop the cached snapshot; the next [load] measures again. Called after every file operation. */
    fun invalidate() {
        cache = null
    }

    /** Build (or reuse) the snapshot and deliver it on the main thread. */
    fun load(context: Context?, force: Boolean = false, onResult: (Snapshot) -> Unit) {
        val ready = cache
        // A snapshot is a cheap first paint, not a permanent answer: a resume
        // after an install must measure again even if the screen did not ask
        // for a forced refresh.
        val fresh = ready != null &&
            System.currentTimeMillis() - ready.builtAt < CACHE_MILLIS
        if (ready != null && fresh && !force) {
            post { onResult(ready) }
            return
        }
        synchronized(lock) {
            waiting.add(onResult)
            if (scheduled) return
            scheduled = true
        }
        val app = context?.applicationContext
        worker.execute { build(app) }
    }

    /** [load] with the cache ignored: what a screen calls after REMOVE/RESTORE. */
    fun refresh(context: Context?, onResult: (Snapshot) -> Unit) = load(context, true, onResult)

    private fun build(context: Context?) {
        val snapshot = try {
            if (context != null) PatchesRepository.init(context)
            Snapshot(
                state = PatchesRepository.state(),
                storage = StorageFacts.measure(context),
                builtAt = System.currentTimeMillis(),
            )
        } catch (t: Throwable) {
            Snapshot(
                state = PatchesRepository.emptyState(),
                storage = null,
                builtAt = System.currentTimeMillis(),
                error = t.message ?: t.toString(),
            )
        }
        val callbacks: List<(Snapshot) -> Unit>
        synchronized(lock) {
            cache = snapshot
            callbacks = waiting.toList()
            waiting.clear()
            scheduled = false
        }
        callbacks.forEach { callback -> post { callback(snapshot) } }
    }

    private fun post(block: () -> Unit) {
        val handler = mainHandler
        if (handler == null) block() else handler.post(block)
    }

    private val mainHandler: Handler? by lazy {
        val looper = Looper.getMainLooper()
        if (looper == null) null else Handler(looper)
    }

    /** How long an unforced snapshot may be reused without measuring again. */
    private const val CACHE_MILLIS = 2_000L
}
