package com.pes2017.recomp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import androidx.core.content.ContextCompat
import com.pes2017.recomp.patch.PatchPlan
import com.pes2017.recomp.patch.PatchSource
import com.pes2017.recomp.patch.PatchSourceException
import com.pes2017.recomp.patch.PatchScanException
import com.pes2017.recomp.patch.install.InstallBackupPolicy
import com.pes2017.recomp.patch.install.InstallFailure
import com.pes2017.recomp.patch.install.InstallProgress
import com.pes2017.recomp.patch.install.InstallResult
import com.pes2017.recomp.patch.install.InstallSelection
import com.pes2017.recomp.patch.install.InstallPhase
import com.pes2017.recomp.patch.install.InstallSlot
import com.pes2017.recomp.patch.install.PatchInstallLiveStore
import com.pes2017.recomp.patch.install.PatchInstallResultStore
import com.pes2017.recomp.patch.install.PatchInstaller
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns a long import. The activity can be rotated, covered or destroyed while
 * the service keeps staging, swapping and notifying; it never cancels on
 * unbind. A new activity binds and immediately receives the current snapshot.
 *
 * It runs in the launcher process (not `:game`), holds a partial wake lock and
 * a dataSync foreground notification with a STOP action. The installer itself
 * is JVM-only; this class is the Android shell around it.
 *
 * The service is not trusted to live to the end of the run. MIUI/HyperOS stops
 * it with "Stop FGS timeout", Android 15 closes the dataSync window with
 * [onTimeout], and a force-stop takes the process without any callback. Every
 * way out publishes a terminal record to [PatchInstallResultStore] first, so
 * the import screen can say what happened instead of holding a percentage.
 */
class PatchInstallService : Service() {

    data class Snapshot(
        val progress: InstallProgress?,
        val result: InstallResult?,
        val running: Boolean,
    )

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "patch-install").apply { isDaemon = true }
    }

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var running = false

    /** True while the worker is inside [work], even after the service was stopped. */
    @Volatile
    private var working = false

    /** The installer reached CLEANING, so every slot rename is committed. */
    @Volatile
    private var committed = false

    @Volatile
    private var lastPhase: InstallPhase = InstallPhase.PREPARING

    @Volatile
    private var destroyed = false

    @Volatile
    private var lastSlots: List<InstallSlot> = emptyList()

    @Volatile
    private var lastPolicy = PatchesRepository.BackupPolicy.KEEP

    /**
     * The id of the run this service instance accepted; every durable record it
     * writes carries it, and so does the journal the installer writes.
     */
    @Volatile
    private var installId: String? = null

    /**
     * The game root this run writes to, captured when ACTION_START is accepted.
     * `PatchesRepository.gameRoot` reads the current preference, and the player
     * can change the folder while a run is swapping; resolving it again at the
     * end would publish the outcome into a folder this run never touched.
     */
    @Volatile
    private var runRoot: File? = null

    @Volatile
    private var startedAt = 0L

    @Volatile
    private var runSelection = InstallSelection()

    @Volatile
    private var runPolicy = PatchesRepository.BackupPolicy.KEEP

    /** Sum of the selected payload, known only after the scan. */
    @Volatile
    private var planBytes = 0L

    @Volatile
    private var planCpks = 0

    @Volatile
    private var dpBytes = 0L

    @Volatile
    private var dpEntries = 0

    @Volatile
    private var sourceKind = ""

    @Volatile
    private var lastPercent = 0

    @Volatile
    private var lastFile = ""

    /**
     * The exact counters from the last progress value. The terminal record must
     * repeat them: a run stopped at the 85% staging cap has real bytes behind
     * that number, and deriving them from the percentage would understate them.
     */
    @Volatile
    private var lastProcessedBytes = 0L

    @Volatile
    private var lastTotalBytes = 0L

    /** One `install.ignored_progress` per run, not one per dropped callback. */
    private val ignoredProgressReported = AtomicBoolean(false)

    // The heartbeat is written by the worker only, so plain fields are enough.
    private var lastLiveAt = 0L
    private var lastLivePhase: InstallPhase? = null
    private var lastLivePercent = -1

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationPercent = -1
    private var lastNotificationText = ""

    inner class LocalBinder : Binder() {
        fun service(): PatchInstallService = this@PatchInstallService

        fun addListener(listener: (Snapshot) -> Unit) {
            listeners.add(listener)
            listener(snapshot ?: Snapshot(null, null, running))
        }

        fun removeListener(listener: (Snapshot) -> Unit) {
            listeners.remove(listener)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        PatchesRepository.init(this)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.patch_install_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled.set(true)
            if (running) notify("Stopping the install…", lastNotificationPercent, true)
            return START_NOT_STICKY
        }
        if (running) {
            // A duplicate start still has to satisfy the foreground-service
            // contract; keep the existing notification rather than dropping it.
            startForegroundCompat(
                buildNotification(
                    lastNotificationText.ifEmpty { getString(R.string.patch_install_phase_preparing) },
                    lastNotificationPercent.coerceAtLeast(0),
                    true,
                ),
            )
            return START_NOT_STICKY
        }
        if (working) {
            // A run the system stopped is still unwinding on the worker. A
            // second install would clear its cancel flag and put two swaps on
            // one executor, so satisfy the foreground contract, keep asking
            // the old run to stop, and reserve the next start for later.
            startForegroundCompat(
                buildNotification(
                    "Stopping the install…",
                    lastNotificationPercent.coerceAtLeast(0),
                    true,
                ),
            )
            cancelled.set(true)
            return START_NOT_STICKY
        }

        // Everything durable about this run is keyed to the moment it was
        // accepted: one id, one start time, one root. A folder change after this
        // cannot redirect the journal, the heartbeat or the terminal record.
        val id = UUID.randomUUID().toString()
        installId = id
        startedAt = System.currentTimeMillis()
        runRoot = runCatching { PatchesRepository.gameRoot(this) }.getOrNull()
        runRoot?.let { PatchesRepository.pinRoot(it) }

        val spec = intent?.let(PatchSourceSpec::from)
        if (spec == null) {
            lastSlots = emptyList()
            startForegroundCompat(buildNotification("The import source is missing.", 0, false))
            finish(
                InstallResult.Failed(
                    InstallFailure.SOURCE,
                    "the import source is missing",
                ),
            )
            return START_NOT_STICKY
        }
        val selection = InstallSelection(
            patch = intent.getBooleanExtra(EXTRA_PATCH, true),
            editedData = intent.getBooleanExtra(EXTRA_EDIT, true),
            system = intent.getBooleanExtra(EXTRA_SYSTEM, false),
        )
        val policy = runCatching {
            PatchesRepository.BackupPolicy.valueOf(
                intent.getStringExtra(EXTRA_POLICY) ?: PatchesRepository.BackupPolicy.KEEP.name,
            )
        }.getOrDefault(PatchesRepository.BackupPolicy.KEEP)

        running = true
        runningNow = true
        working = true
        committed = false
        // A service instance can outlive a finished/cancelled run while an
        // activity is still bound; a new run must not inherit its cancel flag,
        // its last notification throttle or its terminal record.
        cancelled.set(false)
        ignoredProgressReported.set(false)
        lastNotificationPercent = -1
        lastNotificationText = ""
        lastPhase = InstallPhase.PREPARING
        lastSlots = selection.slots()
        lastPolicy = policy
        runSelection = selection
        runPolicy = policy
        sourceKind = spec.kind.name
        planCpks = 0
        planBytes = 0L
        dpBytes = 0L
        dpEntries = 0
        lastPercent = 0
        lastFile = ""
        lastProcessedBytes = 0L
        lastTotalBytes = 0L
        lastLiveAt = 0L
        lastLivePhase = null
        lastLivePercent = -1
        clearTerminalRecord()
        val start = Snapshot(InstallProgress(InstallPhase.PREPARING, 0), null, true)
        lastSnapshot = start
        startForegroundCompat(buildNotification("Preparing the import…", 0, true))
        acquireWakeLock()
        publish(start)
        executor.execute { work(spec, selection, policy) }
        return START_NOT_STICKY
    }

    private fun work(
        spec: PatchSourceSpec,
        selection: InstallSelection,
        policy: PatchesRepository.BackupPolicy,
    ) {
        // The start marker is written before anything is scanned: a kill during
        // the scan must not look like a run that never began, and a heartbeat
        // left by the previous run must not be read as this one's. The event
        // comes after the scan, where the plan's size is known.
        PatchInstallLiveStore.clear(runRoot)
        writeRunning(InstallPhase.PREPARING, 0, "")
        try {
            var source: PatchSource? = null
            val result = try {
                if (SaveBackup.gameRunning(this)) {
                    InstallResult.Failed(
                        InstallFailure.GAME_RUNNING,
                        getString(R.string.patches_game_running),
                    )
                } else {
                    // The root captured at ACTION_START, not a fresh lookup:
                    // the player may have changed the folder since.
                    val root = runRoot
                        ?: PatchesRepository.gameRoot(this)
                        ?: return finish(
                            InstallResult.Failed(
                                InstallFailure.NO_GAME_FOLDER,
                                "choose a game folder first",
                            ),
                        )
                    source = spec.open(this)
                    val scanning = InstallProgress(InstallPhase.SCANNING, 0, spec.displayName)
                    onProgress(scanning)
                    publish(Snapshot(scanning, null, true))
                    val plan = PatchesRepository.scan(
                        source,
                        spec.password,
                        PatchesRepository.backupBytes(selection),
                    )
                    rememberPlan(plan)
                    val filtered = selection.availableIn(plan)
                    EventLog.note(
                        this,
                        "install.start",
                        "id" to installId.orEmpty(),
                        "slots" to filtered.slots().joinToString(",") { it.key },
                        "policy" to policy.name,
                        "sourceKind" to plan.sourceKind.name,
                        "planBytes" to plan.stagingBytes.toString(),
                    )
                    if (!filtered.any) {
                        InstallResult.Failed(
                            InstallFailure.NOTHING_SELECTED,
                            "nothing selected is present in this source",
                        )
                    } else {
                        lastSlots = filtered.slots()
                        // Now that the plan exists, the start marker can say how
                        // much work this is; a kill mid-staging is then readable.
                        writeRunning(InstallPhase.PREPARING, 0, "")
                        val installer = PatchInstaller(root)
                        installer.install(
                            source = source,
                            plan = plan,
                            selection = filtered,
                            policy = policy.install(),
                            cancelled = { cancelled.get() },
                            installId = installId,
                            progress = progress@{ value ->
                                if (!running) {
                                    // The service was stopped (timeout,
                                    // force-stop of the FGS) and this is the
                                    // callback that used to vanish: one line
                                    // says where the run really was.
                                    if (ignoredProgressReported.compareAndSet(false, true)) {
                                        EventLog.note(
                                            this,
                                            "install.ignored_progress",
                                            "id" to installId.orEmpty(),
                                            "phase" to value.phase.name,
                                            "percent" to value.percent.toString(),
                                        )
                                    }
                                    return@progress
                                }
                                onProgress(value)
                                // CLEANING is emitted only after every slot
                                // rename committed; the terminal SUCCESS it
                                // writes must be the last word in the file, so
                                // it comes after the running marker.
                                if (value.phase == InstallPhase.CLEANING) markCommitted()
                                publish(Snapshot(value, null, true))
                                updateNotification(value)
                            },
                        )
                    }
                }
            } catch (e: PatchScanException) {
                InstallResult.Failed(InstallFailure.SOURCE, e.message ?: e.toString())
            } catch (e: PatchSourceException) {
                InstallResult.Failed(InstallFailure.SOURCE, e.message ?: e.toString())
            } catch (t: Throwable) {
                InstallResult.Failed(InstallFailure.IO, t.message ?: t.toString())
            } finally {
                runCatching { source?.close() }
            }
            finish(result)
        } finally {
            working = false
        }
    }

    private fun finish(result: InstallResult) {
        running = false
        runningNow = false
        val phase = when (result) {
            is InstallResult.Success -> InstallPhase.DONE
            is InstallResult.Cancelled -> InstallPhase.CANCELLED
            is InstallResult.Failed -> InstallPhase.FAILED
        }
        val final = Snapshot(
            InstallProgress(
                phase,
                100,
                processedBytes = lastProcessedBytes,
                totalBytes = lastTotalBytes,
            ),
            result,
            false,
        )
        publish(final)
        lastSnapshot = final
        writeResult(result, phase)
        // The heartbeat belongs to a live run. With the terminal record written
        // it has to go, or the next launch would read it as a run that never
        // ended.
        PatchInstallLiveStore.clear(runRoot)
        PatchesRepository.unpinRoot()
        releaseWakeLock()
        val text = when (result) {
            is InstallResult.Success -> getString(R.string.patch_install_done)
            is InstallResult.Cancelled -> getString(R.string.patch_import_result_cancelled)
            is InstallResult.Failed -> getString(R.string.patch_import_result_failed)
        }
        runCatching { notify(text, 100, false) }
        if (!destroyed) {
            // DETACH keeps the result notification after the service goes away;
            // REMOVE deleted it in the same frame it was posted.
            runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
            runCatching { stopSelf() }
        }
    }

    /**
     * Android 15 closes the dataSync foreground window with this callback;
     * MIUI/HyperOS uses its own "Stop FGS timeout" for the same effect. The
     * system stops the service either way, so publish the terminal state now
     * and let the worker stop at its next checkpoint.
     */
    override fun onTimeout(startId: Int) {
        EventLog.note(
            this,
            "install.timeout",
            "id" to installId.orEmpty(),
            "phase" to lastPhase.name,
            "percent" to lastPercent.toString(),
            "committed" to committed.toString(),
        )
        cancelled.set(true)
        publishStop(getString(R.string.patch_install_stopped_by_system))
        runCatching { stopSelf() }
    }

    /** Called when Android destroys the process; the journal is the recovery. */
    override fun onDestroy() {
        destroyed = true
        EventLog.note(
            this,
            "install.destroy",
            "id" to installId.orEmpty(),
            "phase" to lastPhase.name,
            "percent" to lastPercent.toString(),
            "committed" to committed.toString(),
            "working" to working.toString(),
        )
        // If the worker already reported, running is false and this is a no-op.
        // Otherwise publish what the files say, never the last percentage.
        publishStop(getString(R.string.patch_install_stopped_unexpectedly))
        cancelled.set(true)
        running = false
        runningNow = false
        runCatching { executor.shutdownNow() }
        releaseWakeLock()
        // A worker still unwinding writes its own record; it unpins when it
        // reaches finish(). Only an idle service can let go here.
        if (!working) PatchesRepository.unpinRoot()
        super.onDestroy()
    }

    /**
     * The service is going away without its worker reporting: a dataSync
     * timeout, a stopped foreground service, a bind failure or a plain
     * destroy. Publish the truth from the files and the commit point.
     */
    private fun publishStop(message: String) {
        if (!running) return
        running = false
        runningNow = false
        if (committed) {
            // The swap committed; only the cleanup and the result were left.
            // The activity still verifies the slots before it shows success.
            val result = InstallResult.Success(lastSlots, 0L, lastPolicy.install())
            val final = Snapshot(
                InstallProgress(
                    InstallPhase.DONE,
                    100,
                    processedBytes = lastProcessedBytes,
                    totalBytes = lastTotalBytes,
                ),
                result,
                false,
            )
            publish(final)
            lastSnapshot = final
            val record = recordOf(
                PatchInstallResultStore.State.SUCCESS,
                InstallPhase.DONE,
                getString(R.string.patch_install_done),
                lastSlots,
                0L,
            )
            writeTerminal(record)
            logResult(record)
            runCatching { notify(getString(R.string.patch_install_done), 100, false) }
        } else {
            // A stopped run must read as stopped: no progress, no result that
            // claims the slots were replaced.
            val stopped = Snapshot(null, null, false)
            publish(stopped)
            lastSnapshot = stopped
            val record = recordOf(
                PatchInstallResultStore.State.INTERRUPTED,
                lastPhase,
                message,
                lastSlots,
                0L,
            )
            writeTerminal(record)
            logResult(record)
            runCatching { notify(message, 100, false) }
        }
        // After the terminal record, never before: a death in between must leave
        // the record, not only a deleted heartbeat.
        PatchInstallLiveStore.clear(runRoot)
        runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
    }

    private fun publish(value: Snapshot) {
        value.progress?.let { lastPhase = it.phase }
        snapshot = value
        lastSnapshot = value
        main.post {
            listeners.forEach { listener ->
                runCatching { listener(value) }
            }
        }
    }

    // ---- terminal record -------------------------------------------------

    /**
     * CLEANING is emitted only after every slot rename is committed, so the
     * install is safe to call a success from that point on: a kill between the
     * commit and [finish] must still read as installed.
     */
    private fun markCommitted() {
        if (committed) return
        committed = true
        writeTerminal(
            PatchInstallResultStore.State.SUCCESS,
            InstallPhase.CLEANING,
            getString(R.string.patch_install_done),
            lastSlots,
            0L,
        )
    }

    private fun writeResult(result: InstallResult, phase: InstallPhase) {
        val record = when (result) {
            is InstallResult.Success -> recordOf(
                PatchInstallResultStore.State.SUCCESS,
                phase,
                getString(R.string.patch_install_done),
                result.slots,
                result.bytes,
            )

            is InstallResult.Cancelled -> recordOf(
                // A cancel that could not put the previous data back is not a
                // completed cancel; the player has to act on it.
                if (result.restored) {
                    PatchInstallResultStore.State.CANCELLED
                } else {
                    PatchInstallResultStore.State.FAILED
                },
                phase,
                withRestoreNote(result.restored, result.detail),
                lastSlots,
                0L,
            )

            is InstallResult.Failed -> recordOf(
                PatchInstallResultStore.State.FAILED,
                phase,
                withRestoreNote(result.restored, result.message),
                lastSlots,
                0L,
            )
        }
        writeTerminal(record)
        logResult(record)
    }

    private fun logResult(record: PatchInstallResultStore.Record) {
        EventLog.note(
            this,
            "install.result",
            "id" to record.installId,
            "state" to record.state.name,
            "slots" to record.slots.joinToString(",") { it.key },
            "bytes" to record.bytes.toString(),
            "durationMs" to record.durationMs.toString(),
        )
    }

    private fun withRestoreNote(restored: Boolean, detail: String): String =
        if (restored) {
            detail
        } else {
            listOf(getString(R.string.patch_import_not_restored), detail)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }

    /** One terminal record with the run's identity filled in; every way out uses it. */
    private fun recordOf(
        state: PatchInstallResultStore.State,
        phase: InstallPhase,
        message: String,
        slots: List<InstallSlot>,
        bytes: Long,
    ): PatchInstallResultStore.Record {
        val now = System.currentTimeMillis()
        return PatchInstallResultStore.Record(
            state = state,
            phase = phase,
            message = message,
            slots = slots,
            bytes = bytes,
            timestamp = now,
            installId = installId.orEmpty(),
            startedAt = startedAt,
            updatedAt = now,
            finishedAt = now,
            durationMs = if (startedAt > 0) (now - startedAt).coerceAtLeast(0L) else 0L,
            pid = Process.myPid().toLong(),
            percent = lastPercent,
            processedBytes = lastProcessedBytes,
            totalBytes = lastTotalBytes,
            file = lastFile,
            policy = runPolicy.name,
            selection = runSelection.slots().joinToString(",") { it.key },
            sourceKind = sourceKind,
            planCpk = planCpks,
            planBytes = planBytes,
            dpBytes = dpBytes,
            dpEntries = dpEntries,
        )
    }

    private fun writeTerminal(
        state: PatchInstallResultStore.State,
        phase: InstallPhase,
        message: String,
        slots: List<InstallSlot>,
        bytes: Long,
    ) {
        writeTerminal(recordOf(state, phase, message, slots, bytes))
    }

    private fun writeTerminal(record: PatchInstallResultStore.Record) {
        // The captured root, never a fresh PatchesRepository.gameRoot: this
        // record describes the folder this run actually wrote.
        val root = runRoot ?: return
        runCatching { PatchInstallResultStore.write(root, record) }
    }

    private fun clearTerminalRecord() {
        val root = runRoot ?: return
        runCatching { PatchInstallResultStore.clear(root) }
    }

    // ---- durable progress ------------------------------------------------

    /** Takes the plan's size facts into the run's records; they are unknown before the scan. */
    private fun rememberPlan(plan: PatchPlan) {
        sourceKind = plan.sourceKind.name
        planCpks = plan.cpks.size
        planBytes = plan.stagingBytes
        dpBytes = plan.dpFileList.size
        dpEntries = plan.dpFileList.entries.size
    }

    /**
     * One progress value: the durable start marker when the phase changes, and
     * the throttled heartbeat. The last phase, percent, file and byte counters
     * are kept here too, so a run that is stopped can still write a truthful
     * terminal record.
     */
    private fun onProgress(value: InstallProgress) {
        if (value.phase != lastPhase) {
            writeRunning(
                value.phase,
                value.percent,
                fileOf(value),
                value.processedBytes,
                value.totalBytes,
            )
            EventLog.note(
                this,
                "install.phase",
                "id" to installId.orEmpty(),
                "phase" to value.phase.name,
                "percent" to value.percent.toString(),
                "file" to fileOf(value),
            )
        }
        lastPercent = value.percent
        lastFile = fileOf(value)
        lastProcessedBytes = value.processedBytes
        lastTotalBytes = value.totalBytes
        writeLive(value)
    }

    /**
     * The start marker, refreshed on each phase. It is the record that survives
     * a kill even if the heartbeat file is lost; the live file carries the
     * seconds in between.
     */
    private fun writeRunning(
        phase: InstallPhase,
        percent: Int,
        file: String,
        processedBytes: Long = 0L,
        totalBytes: Long = 0L,
    ) {
        val id = installId ?: return
        val root = runRoot ?: return
        runCatching {
            PatchInstallResultStore.writeRunning(
                root,
                PatchInstallResultStore.Running(
                    installId = id,
                    startedAt = startedAt,
                    updatedAt = System.currentTimeMillis(),
                    pid = Process.myPid().toLong(),
                    phase = phase,
                    percent = percent,
                    selection = runSelection.slots().joinToString(",") { it.key },
                    policy = runPolicy.name,
                    sourceKind = sourceKind,
                    slots = lastSlots,
                    planCpk = planCpks,
                    planBytes = planBytes,
                    dpBytes = dpBytes,
                    dpEntries = dpEntries,
                    processedBytes = processedBytes,
                    totalBytes = totalBytes,
                    file = file,
                ),
            )
        }
    }

    /**
     * `patch-install-live.properties`: a phase change at once, otherwise one
     * write a second at most and one every five seconds at least. The file is
     * deleted on every terminal path, so one still there after a restart is the
     * proof the process was killed inside the run.
     */
    private fun writeLive(value: InstallProgress) {
        val id = installId ?: return
        val root = runRoot ?: return
        val now = System.currentTimeMillis()
        val phaseChanged = value.phase != lastLivePhase
        val due = lastLiveAt == 0L ||
            phaseChanged ||
            now - lastLiveAt >= LIVE_HEARTBEAT_MS ||
            (value.percent != lastLivePercent && now - lastLiveAt >= LIVE_MIN_INTERVAL_MS)
        if (!due) return
        lastLiveAt = now
        lastLivePhase = value.phase
        lastLivePercent = value.percent
        runCatching {
            PatchInstallLiveStore.write(
                root,
                PatchInstallLiveStore.Live(
                    installId = id,
                    pid = Process.myPid().toLong(),
                    startedAt = startedAt,
                    updatedAt = now,
                    phase = value.phase,
                    percent = value.percent,
                    processedBytes = value.processedBytes,
                    totalBytes = value.totalBytes,
                    file = fileOf(value),
                    slots = lastSlots,
                ),
            )
        }
    }

    /** What the notification also shows: the file the run is reading or writing. */
    private fun fileOf(value: InstallProgress): String {
        val detail = value.detail.trim()
        return detail.substringAfterLast('/').substringAfterLast('\\').take(MAX_FILE_NAME)
    }

    // ---- notification ----------------------------------------------------

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String, percent: Int, busy: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            // The tap lands on the result of this run, not on an empty source
            // picker: the activity resolves the terminal record itself.
            Intent(this, PatchImportActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(PatchImportActivity.EXTRA_FROM_NOTIFICATION, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.patch_install_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(busy)
            .setAutoCancel(!busy)
            .setShowWhen(false)
        if (busy) {
            val stop = PendingIntent.getService(
                this,
                1,
                Intent(this, PatchInstallService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        getString(R.string.patch_import_stop),
                        stop,
                    ).build(),
                )
                .setProgress(1000, percent.coerceIn(0, 100) * 10, percent < 0)
        }
        return builder.build()
    }

    private fun updateNotification(progress: InstallProgress) {
        if (!running) return
        if (progress.phase != InstallPhase.STAGING &&
            progress.phase != InstallPhase.VERIFYING &&
            progress.phase != InstallPhase.SWAPPING
        ) {
            return
        }
        val detail = progress.detail.trim()
        val file = detail.substringAfterLast('/').substringAfterLast('\\')
        val text = if (file.isEmpty()) {
            getString(
                R.string.patch_install_notification_progress_no_file,
                phaseLabel(progress.phase),
                progress.percent,
            )
        } else {
            getString(
                R.string.patch_install_notification_progress,
                phaseLabel(progress.phase),
                progress.percent,
                file,
            )
        }
        if (progress.percent == lastNotificationPercent && text == lastNotificationText) return
        lastNotificationPercent = progress.percent
        lastNotificationText = text
        notify(text, progress.percent, true)
    }

    private fun notify(text: String, percent: Int, busy: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text, percent, busy)) }
    }

    private fun phaseLabel(phase: InstallPhase): String = getString(
        when (phase) {
            InstallPhase.PREPARING -> R.string.patch_install_phase_preparing
            InstallPhase.SCANNING -> R.string.patch_install_phase_scanning
            InstallPhase.STAGING -> R.string.patch_install_phase_staging
            InstallPhase.VERIFYING -> R.string.patch_install_phase_verifying
            InstallPhase.SWAPPING -> R.string.patch_install_phase_swapping
            InstallPhase.CLEANING -> R.string.patch_install_phase_cleaning
            InstallPhase.DONE -> R.string.patch_install_done
            InstallPhase.CANCELLED -> R.string.patch_import_result_cancelled
            InstallPhase.FAILED -> R.string.patch_import_result_failed
        },
    )

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK).apply {
            setReferenceCounted(false)
            acquire(24L * 60 * 60 * 1000)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL = "pes17-patch-install"
        private const val NOTIFICATION_ID = 0x5041
        private const val WAKE_LOCK = "pes17:patch-install"

        /** A heartbeat at most once a second, and at least once every five. */
        private const val LIVE_MIN_INTERVAL_MS = 1_000L
        private const val LIVE_HEARTBEAT_MS = 5_000L
        private const val MAX_FILE_NAME = 120
        const val ACTION_START = "patch_install.start"
        const val ACTION_CANCEL = "patch_install.cancel"
        private const val EXTRA_PATCH = "patch_install.patch"
        private const val EXTRA_EDIT = "patch_install.edit"
        private const val EXTRA_SYSTEM = "patch_install.system"
        private const val EXTRA_POLICY = "patch_install.policy"

        @Volatile
        var runningNow: Boolean = false
            private set

        @Volatile
        var lastSnapshot: Snapshot? = null
            private set

        internal fun start(
            context: Context,
            spec: PatchSourceSpec,
            selection: InstallSelection,
            policy: PatchesRepository.BackupPolicy,
        ) {
            val intent = spec.put(
                Intent(context, PatchInstallService::class.java).setAction(ACTION_START),
            )
            intent.putExtra(EXTRA_PATCH, selection.patch)
            intent.putExtra(EXTRA_EDIT, selection.editedData)
            intent.putExtra(EXTRA_SYSTEM, selection.system)
            intent.putExtra(EXTRA_POLICY, policy.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, PatchInstallService::class.java)
                        .setAction(ACTION_CANCEL),
                )
            }
        }

        /**
         * Drops the in-process result so a freshly opened screen does not show
         * the previous run. The durable record is cleared by the next
         * [ACTION_START], not here: the notification tap still needs it.
         */
        fun clearResult() {
            if (!runningNow) lastSnapshot = null
        }
    }
}

private fun PatchesRepository.BackupPolicy.install(): InstallBackupPolicy = when (this) {
    PatchesRepository.BackupPolicy.KEEP -> InstallBackupPolicy.KEEP
    PatchesRepository.BackupPolicy.FREE_OLD -> InstallBackupPolicy.FREE_OLD
    PatchesRepository.BackupPolicy.NONE -> InstallBackupPolicy.NONE
}
