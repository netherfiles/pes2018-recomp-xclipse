package com.pes2017.recomp

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.text.format.Formatter
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.pes2017.recomp.patch.PatchPlan
import com.pes2017.recomp.patch.PatchScanException
import com.pes2017.recomp.patch.PatchScanFailure
import com.pes2017.recomp.patch.PatchSourceKind
import com.pes2017.recomp.patch.SaveSlot
import com.pes2017.recomp.patch.install.InstallFailure
import com.pes2017.recomp.patch.install.InstallPhase
import com.pes2017.recomp.patch.install.InstallProgress
import com.pes2017.recomp.patch.install.InstallResult
import com.pes2017.recomp.patch.install.InstallSelection
import com.pes2017.recomp.patch.install.InstallSlot
import com.pes2017.recomp.patch.install.PatchInstallResultStore
import com.pes2017.recomp.patch.install.RecoveryReport
import java.io.File
import java.util.Locale
import java.util.Properties
import kotlin.concurrent.thread

/**
 * Importing a PATCH, in the order the approved flow asks for: source
 * (archive, extracted folder or part 1 of a multi-part set), password when the
 * archive is locked, a scan summary with PATCH / EDITED DATA / the global save
 * under ADVANCED, the peak space the install needs, progress, and a result.
 *
 * The scan is real. The install is owned by [PatchInstallService], so rotating
 * or leaving this screen does not cancel it; the activity reattaches to the
 * service and reads its snapshot.
 *
 * The service can be stopped by the system (MIUI "Stop FGS timeout", Android 15
 * dataSync timeout) or killed outright. The static snapshot is then stale, so
 * once the service is gone this screen stops using it and resolves the run from
 * [PatchInstallResultStore], the journal and the active slots.
 */
class PatchImportActivity : AppCompatActivity() {

    private enum class Step { PICK, SCANNING, SUMMARY, INSTALL, RESULT }

    private enum class ResultKind {
        SUCCESS, CANCELLED, FAILED, CORRUPT, MISSING_PART, WRONG_TITLE, NO_DPFILELIST,
        NO_PACKAGES, INTERRUPTED
    }

    /**
     * What the last run actually did, resolved without a live service. A null
     * outcome means there is nothing to report at all.
     */
    private sealed class Outcome {
        object Success : Outcome()
        data class Cancelled(val detail: String) : Outcome()
        data class Failed(val detail: String) : Outcome()
        data class Interrupted(val detail: String) : Outcome()
    }

    /**
     * An outcome plus where it came from. The source is logged so a support
     * log can tell a terminal record from journal recovery, and the install id
     * ties the screen to the files that produced it.
     */
    private class Resolution(
        val outcome: Outcome?,
        val source: String,
        val installId: String,
        val recordState: String,
    )

    private lateinit var prefs: Prefs

    private lateinit var stepPick: View
    private lateinit var stepScanning: View
    private lateinit var stepSummary: View
    private lateinit var stepInstall: View
    private lateinit var stepResult: View

    private lateinit var sourceText: TextView
    private lateinit var summarySource: TextView
    private lateinit var summaryFocus: TextView
    private lateinit var summarySkipped: TextView
    private lateinit var summaryWarning: TextView
    private lateinit var peakValue: TextView
    private lateinit var peakBreakdown: TextView
    private lateinit var lowSpace: TextView
    private lateinit var advancedToggle: Button
    private lateinit var advancedGroup: View
    private lateinit var installPatchSwitch: SwitchCompat
    private lateinit var installEditSwitch: SwitchCompat
    private lateinit var installSystemSwitch: SwitchCompat
    private lateinit var systemWarning: TextView
    private lateinit var installButton: Button
    private lateinit var installProgress: ProgressBar
    private lateinit var installText: TextView
    private lateinit var installFile: TextView
    private lateinit var resultBadge: TextView

    private var sourceSpec: PatchSourceSpec? = null
    private var plan: PatchPlan? = null
    private var scanParts = 1
    private var scanToken = 0
    private var focusSlot: String? = null
    private var policy = PatchesRepository.BackupPolicy.NONE
    private var advancedOpen = false
    private var systemConfirmed = false
    private var installing = false
    private var currentStep = Step.PICK

    private var fromNotification = false
    private var resolving = false
    private var resolveToken = 0
    private var installRequestedAt = 0L

    private val recheck = Handler(Looper.getMainLooper())
    private val liveness = Handler(Looper.getMainLooper())

    private var bound = false
    private var service: PatchInstallService? = null
    private var serviceBinder: PatchInstallService.LocalBinder? = null
    private var listener: ((PatchInstallService.Snapshot) -> Unit)? = null

    /** When the current bind was requested; a binder that has not answered yet is not dead. */
    private var bindRequestedAt = 0L
    private var connectedOnce = false

    /** True once the service has reported anything about the current run. */
    private var serviceReported = false

    // ---- evidence ---------------------------------------------------------
    // What the screen has actually seen, so "nothing new" can be told apart
    // from "a percentage that happens to be unchanged".

    /** Last moment the service or its files proved the run was still alive. */
    @Volatile
    private var lastEvidenceAt = 0L

    /** The log records the bar only when its visible label changes. */
    private var lastProgressKey: String? = null
    private var lastSnapshotPhase: InstallPhase? = null
    private var lastSnapshotPercent: Int? = null
    private var lastShownFile = ""
    private var lastLoggedResult: String? = null
    private var lastStepLogged: Step? = null
    private var lastStallLogAt = 0L
    private var livenessRebindAt = 0L
    private var startWaitLogged = false

    /** Install id last seen in the live file; unknown is an empty string. */
    @Volatile
    private var knownInstallId = ""

    private var liveReadAt = 0L
    private var liveProcessed = -1L
    private var liveTotal = -1L

    private val archivePick = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        takePersistable(uri)
        val name = documentName(uri)
        askPassword(name, allowNoPassword = true) { password ->
            if (password == null) return@askPassword
            startScan(
                PatchSourceSpec(
                    kind = PatchSourceSpec.Kind.ARCHIVE,
                    uri = uri,
                    displayName = name,
                    password = password.ifEmpty { null },
                ),
            )
        }
    }

    private val folderPick = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        takePersistable(uri)
        val name = treeName(uri)
        startScan(
            PatchSourceSpec(
                kind = PatchSourceSpec.Kind.TREE,
                tree = uri,
                displayName = name,
            ),
        )
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? PatchInstallService.LocalBinder
            if (local == null) {
                // A binder of another shape will never deliver a snapshot.
                noteDisconnect("bad_binder")
                scheduleDisconnectedResolve()
                return
            }
            // A rebind may arrive on top of the previous listener; only the
            // new binder's snapshots describe the run that exists now.
            listener?.let { previous -> runCatching { serviceBinder?.removeListener(previous) } }
            service = local.service()
            serviceBinder = local
            bound = true
            connectedOnce = true
            bindRequestedAt = 0L
            val callback: (PatchInstallService.Snapshot) -> Unit = { snapshot ->
                runOnUiThread { applySnapshot(snapshot) }
            }
            listener = callback
            // addListener delivers the current snapshot synchronously. It is
            // deliberately the only source: the static lastSnapshot may belong
            // to a previous service instance.
            local.addListener(callback)
            note(
                "ui.bind",
                "runningNow" to PatchInstallService.runningNow.toString(),
                "bound" to bound.toString(),
            )
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            dropConnection(dead = false)
            // The system may rebind on its own; give it the same short grace
            // the screen gives a start before resolving the run from disk.
            bindRequestedAt = System.currentTimeMillis()
            noteDisconnect("disconnected")
            scheduleDisconnectedResolve()
        }

        /** Android 26+: the whole binding died, so the old binder cannot be reused. */
        override fun onBindingDied(name: ComponentName?) {
            dropConnection(dead = true)
            noteDisconnect("binding_died")
            scheduleDisconnectedResolve()
        }

        override fun onNullBinding(name: ComponentName?) {
            dropConnection(dead = true)
            noteDisconnect("null_binding")
            scheduleDisconnectedResolve()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patch_import)
        applySystemBars()
        findViewById<View>(R.id.back_button).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        title = getString(R.string.patch_import_title)
        prefs = Prefs(this)
        PatchesRepository.init(this)

        focusSlot = intent.getStringExtra(EXTRA_SLOT)
        policy = try {
            PatchesRepository.BackupPolicy.valueOf(intent.getStringExtra(EXTRA_BACKUP) ?: "")
        } catch (_: Throwable) {
            PatchesRepository.BackupPolicy.NONE
        }

        stepPick = findViewById(R.id.step_pick)
        stepScanning = findViewById(R.id.step_scanning)
        stepSummary = findViewById(R.id.step_summary)
        stepInstall = findViewById(R.id.step_install)
        stepResult = findViewById(R.id.step_result)

        sourceText = findViewById(R.id.import_source)
        summarySource = findViewById(R.id.summary_source)
        summaryFocus = findViewById(R.id.summary_focus)
        summarySkipped = findViewById(R.id.summary_skipped)
        summaryWarning = findViewById(R.id.summary_warning)
        peakValue = findViewById(R.id.peak_value)
        peakBreakdown = findViewById(R.id.peak_breakdown)
        lowSpace = findViewById(R.id.low_space)
        advancedToggle = findViewById(R.id.advanced_toggle)
        advancedGroup = findViewById(R.id.advanced_group)
        installPatchSwitch = findViewById(R.id.install_patch)
        installEditSwitch = findViewById(R.id.install_edit)
        installSystemSwitch = findViewById(R.id.install_system)
        systemWarning = findViewById(R.id.system_warning)
        installButton = findViewById(R.id.install_button)
        installProgress = findViewById(R.id.install_progress)
        installText = findViewById(R.id.install_text)
        installFile = findViewById(R.id.install_file)
        resultBadge = findViewById(R.id.result_badge)

        findViewById<Button>(R.id.pick_archive).setOnClickListener { pickArchive() }
        findViewById<Button>(R.id.pick_folder).setOnClickListener { folderPick.launch(null) }
        findViewById<Button>(R.id.pick_multipart).setOnClickListener { folderPick.launch(null) }
        findViewById<Button>(R.id.import_cancel_pick).setOnClickListener { finish() }
        findViewById<Button>(R.id.summary_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.install_stop).setOnClickListener { confirmCancelInstall() }
        findViewById<Button>(R.id.result_done).setOnClickListener { finish() }

        advancedToggle.setOnClickListener {
            advancedOpen = !advancedOpen
            advancedGroup.visibility = if (advancedOpen) View.VISIBLE else View.GONE
            advancedToggle.setText(
                if (advancedOpen) R.string.patch_import_advanced_open
                else R.string.patch_import_advanced,
            )
            advancedToggle.contentDescription = getString(
                if (advancedOpen) R.string.patch_import_advanced_open
                else R.string.patch_import_advanced,
            )
            paintAdvanced()
        }
        installSystemSwitch.setOnCheckedChangeListener { _, checked ->
            if (!checked) systemConfirmed = false
            paintAdvanced()
            updatePeak()
        }
        installPatchSwitch.setOnCheckedChangeListener { _, _ -> updatePeak() }
        installEditSwitch.setOnCheckedChangeListener { _, _ -> updatePeak() }
        installButton.setOnClickListener { beginInstall() }

        installPatchSwitch.isChecked = false
        installEditSwitch.isChecked = false
        installSystemSwitch.isChecked = false
        paintAdvanced()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (PatchInstallService.runningNow) {
                    confirmCancelInstall()
                } else {
                    finish()
                }
            }
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 71)
        }

        fromNotification = intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)
        // A rotation during a cold start must keep the start window: without
        // it the recreated screen would call the run stopped before the
        // service has had its chance to report.
        installRequestedAt = savedInstanceState?.getLong(STATE_STARTED_AT, 0L) ?: 0L
        // A fresh screen drops the previous in-process result, but a
        // notification tap is asking for it: the durable record is the first
        // source then, and this is the fallback for an unwritable folder.
        if (savedInstanceState == null && !PatchInstallService.runningNow && !fromNotification) {
            PatchInstallService.clearResult()
        }
        val wasInstalling = savedInstanceState?.getBoolean(STATE_PENDING, false) ?: false
        if (PatchInstallService.runningNow) {
            installing = true
            showStep(Step.INSTALL)
            installText.text = getString(R.string.patch_install_phase_preparing)
            bindToService()
        } else if (wasInstalling || fromNotification) {
            // Not a fresh source picker: resolve what the last run did before
            // drawing anything. A saved INSTALL step means the process died
            // mid-install; a notification tap means the user came for a result.
            showChecking()
        } else {
            showStep(Step.PICK)
        }
    }

    override fun onStart() {
        super.onStart()
        reattachToService()
        if (currentStep == Step.INSTALL) {
            // The timer only describes a visible screen; start its clock from
            // the moment the screen is back so time away is not "silence".
            lastEvidenceAt = System.currentTimeMillis()
            startLiveness()
        }
    }

    override fun onResume() {
        super.onResume()
        note(
            "ui.resume",
            "step" to currentStep.name,
            "runningNow" to PatchInstallService.runningNow.toString(),
        )
        // The service can die while this screen is visible (no onStart runs
        // again). Re-resolve instead of keeping the last percentage.
        if (!PatchInstallService.runningNow && currentStep == Step.INSTALL) {
            resolveFinishedInstall()
        }
    }

    override fun onPause() {
        note(
            "ui.pause",
            "step" to currentStep.name,
            "runningNow" to PatchInstallService.runningNow.toString(),
        )
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PENDING, installing || currentStep == Step.INSTALL)
        outState.putLong(STATE_STARTED_AT, installRequestedAt)
    }

    override fun onStop() {
        // Nothing is visible to warn and no binder snapshot arrives while
        // stopped; the next onStart resolves or reattaches.
        stopLiveness()
        unbindFromService()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fromNotification = intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)
        if (PatchInstallService.runningNow) {
            installing = true
            showStep(Step.INSTALL)
            bindToService()
        } else if (fromNotification && currentStep != Step.RESULT) {
            // The tap came for a result. Never leave the source picker on
            // screen when the run has already ended.
            showChecking()
        } else {
            reattachToService()
        }
    }

    /**
     * The service outlives this screen. If it finished or was stopped while the
     * activity was away, [onStart] would otherwise leave the progress step
     * showing forever, with a STOP action for a service that is already gone.
     * The static snapshot is only trusted when it carries a result; a bare
     * percentage from a dead service is exactly the lie this screen must not
     * repeat.
     */
    private fun reattachToService() {
        if (PatchInstallService.runningNow) {
            bindToService()
        } else if (installing || currentStep == Step.INSTALL) {
            resolveFinishedInstall()
        }
    }

    override fun onDestroy() {
        scanToken++
        resolveToken++
        recheck.removeCallbacksAndMessages(null)
        liveness.removeCallbacksAndMessages(null)
        unbindFromService()
        super.onDestroy()
    }

    // ---- source ------------------------------------------------------------

    private fun pickArchive() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Providers label archives differently; everything is offered
            // rather than hiding the file the player came to install.
            type = "*/*"
        }
        archivePick.launch(intent)
    }

    private fun takePersistable(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    /**
     * [onChoice] receives null for cancel, "" for "no password", and the text
     * for a password. The empty choice is only offered for an archive picked
     * before any scan; a retry after PASSWORD asks for a real value.
     */
    private fun askPassword(
        name: String,
        allowNoPassword: Boolean,
        onChoice: (String?) -> Unit,
    ) {
        val field = EditText(this).apply {
            hint = getString(R.string.patch_import_password_field)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.patch_import_password_title)
            .setMessage(getString(R.string.patch_import_password_hint) + "\n\n" + name)
            .setView(field)
            .setNegativeButton(R.string.cancel) { _, _ -> onChoice(null) }
            .setPositiveButton(R.string.patch_import_password_use) { _, _ ->
                onChoice(field.text.toString())
            }
        if (allowNoPassword) {
            builder.setNeutralButton(R.string.patch_import_password_skip) { _, _ ->
                onChoice("")
            }
        }
        builder.show()
    }

    private fun startScan(spec: PatchSourceSpec) {
        if (PatchesRepository.gameRoot(this) == null) {
            showResult(ResultKind.FAILED, getString(R.string.patches_header_no_game_body))
            return
        }
        sourceSpec = spec
        sourceText.text = getString(R.string.patch_import_source, spec.displayName)
        showStep(Step.SCANNING)
        val token = ++scanToken
        thread(name = "patch-scan", isDaemon = true) {
            try {
                val source = spec.open(this)
                val result: PatchPlan
                val parts: Int
                try {
                    parts = source.partCount
                    result = PatchesRepository.scan(
                        source,
                        spec.password,
                        PatchesRepository.backupBytes(InstallSelection(true, true, true), this),
                    )
                } finally {
                    runCatching { source.close() }
                }
                runOnUiThread {
                    if (token != scanToken || isFinishing) return@runOnUiThread
                    scanParts = parts
                    showSummary(result)
                }
            } catch (e: PatchScanException) {
                runOnUiThread {
                    if (token != scanToken || isFinishing) return@runOnUiThread
                    if (e.failure == PatchScanFailure.PASSWORD && spec.password == null) {
                        askPassword(spec.displayName, allowNoPassword = false) { password ->
                            if (password == null) {
                                // Cancelling the password must not leave the
                                // scanning spinner running with no scan.
                                showStep(Step.PICK)
                                return@askPassword
                            }
                            startScan(spec.copy(password = password.ifEmpty { null }))
                        }
                    } else {
                        showScanFailure(e)
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    if (token != scanToken || isFinishing) return@runOnUiThread
                    showResult(ResultKind.FAILED, t.message ?: t.toString())
                }
            }
        }
    }

    private fun showScanFailure(e: PatchScanException) {
        val kind = when (e.failure) {
            PatchScanFailure.WRONG_TITLE -> ResultKind.WRONG_TITLE
            PatchScanFailure.NO_PACKAGES -> ResultKind.NO_PACKAGES
            PatchScanFailure.NO_DPFILELIST,
            PatchScanFailure.SINGLE_CPK_WITHOUT_LIST,
            -> ResultKind.NO_DPFILELIST

            PatchScanFailure.MISSING_PART -> ResultKind.MISSING_PART
            PatchScanFailure.CORRUPT,
            PatchScanFailure.UNKNOWN_STFS_HEADER,
            PatchScanFailure.TRUNCATED_CON,
            PatchScanFailure.MISSING_LISTED_CPK,
            PatchScanFailure.UNSAFE_NAME,
            PatchScanFailure.DUPLICATE_CPK,
            PatchScanFailure.DUPLICATE_DPFILELIST,
            -> ResultKind.CORRUPT

            else -> ResultKind.FAILED
        }
        showResult(kind, e.message)
    }

    private fun showSummary(scan: PatchPlan) {
        plan = scan
        val sourceName = if (scanParts > 1) {
            getString(R.string.patch_import_multipart_source, scan.sourceLabel, scanParts)
        } else {
            scan.sourceLabel
        }
        summarySource.text = getString(
            R.string.patch_import_summary_source,
            sourceName,
            kindLabel(scan.sourceKind),
        )

        if (focusSlot != null) {
            summaryFocus.visibility = View.VISIBLE
            summaryFocus.text = getString(
                R.string.patch_import_focus,
                getString(
                    if (focusSlot == PatchesRepository.SLOT_PATCH) R.string.patches_section_patch
                    else R.string.patches_section_edited,
                ),
                policyLabel(),
            )
        } else {
            summaryFocus.visibility = View.GONE
        }

        val editSave = scan.saves.firstOrNull { it.slot == SaveSlot.EDIT }
        val systemSave = scan.saves.firstOrNull { it.slot == SaveSlot.SYSTEM }
        val patchAvailable = scan.cpks.isNotEmpty()
        val editAvailable = editSave != null
        val systemAvailable = systemSave != null

        installPatchSwitch.isChecked = patchAvailable
        installEditSwitch.isChecked = editAvailable
        installSystemSwitch.isChecked = false
        systemConfirmed = false
        installPatchSwitch.isEnabled = patchAvailable
        installEditSwitch.isEnabled = editAvailable
        installSystemSwitch.isEnabled = systemAvailable
        installPatchSwitch.alpha = if (patchAvailable) 1f else 0.45f
        installEditSwitch.alpha = if (editAvailable) 1f else 0.45f
        installSystemSwitch.alpha = if (systemAvailable) 1f else 0.45f

        installPatchSwitch.text = getString(
            R.string.patch_import_patch_toggle,
            scan.cpkCount,
            gigabytes(scan.cpkBytes),
        )
        installEditSwitch.text = getString(
            R.string.patch_import_edit_toggle,
            String.format(Locale.US, "%,d", editSave?.payload?.players ?: 0),
            editSave?.payload?.teams ?: 0,
        )

        advancedOpen = false
        advancedGroup.visibility = View.GONE
        advancedToggle.setText(R.string.patch_import_advanced)
        advancedToggle.contentDescription = getString(R.string.patch_import_advanced)
        paintAdvanced()

        summarySkipped.text = getString(R.string.patch_import_skipped, scan.skipped.size)
        val warnings = scan.warnings.take(5)
        summaryWarning.visibility = if (warnings.isEmpty()) View.GONE else View.VISIBLE
        summaryWarning.text = warnings.joinToString("\n")

        updatePeak()
        showStep(Step.SUMMARY)
    }

    // ---- peak space --------------------------------------------------------

    private fun updatePeak() {
        val scan = plan ?: return
        val selection = currentSelection()
        val space = PatchesRepository.space(scan, selection, policy, this)
        val selected = selection.any

        peakValue.text = getString(R.string.patch_import_peak_value, gigabytes(space.requiredBytes))
        peakBreakdown.text = if (selected) {
            getString(
                R.string.patch_import_peak_breakdown,
                gigabytes(space.stagingBytes),
                gigabytes(space.backupBytes),
                gigabytes(space.spoolBytes),
                gigabytes(space.marginBytes),
            ) + if (space.freedOldBackupBytes > 0) {
                getString(R.string.patch_import_peak_freed, gigabytes(space.freedOldBackupBytes))
            } else {
                ""
            }
        } else {
            getString(R.string.patch_import_nothing)
        }

        if (!selected) {
            lowSpace.visibility = View.GONE
            installButton.isEnabled = false
            return
        }
        if (PatchesRepository.isInstalling(this)) {
            lowSpace.visibility = View.VISIBLE
            lowSpace.setText(R.string.patch_import_busy)
            installButton.isEnabled = false
            return
        }
        if (SaveBackup.gameRunning(this)) {
            lowSpace.visibility = View.VISIBLE
            lowSpace.setText(R.string.patches_game_running)
            installButton.isEnabled = false
            return
        }
        if (!space.enough || PatchesRepository.gameRoot(this) == null) {
            lowSpace.visibility = View.VISIBLE
            lowSpace.text = getString(
                R.string.patch_import_low_space,
                Formatter.formatFileSize(this, space.availableBytes),
                gigabytes(space.requiredBytes),
            )
            installButton.isEnabled = false
        } else {
            lowSpace.visibility = View.GONE
            installButton.isEnabled = true
        }
    }

    // ---- installing --------------------------------------------------------

    private fun beginInstall() {
        val scan = plan ?: return
        val spec = sourceSpec ?: return
        if (PatchInstallService.runningNow || PatchesRepository.isInstalling(this)) {
            updatePeak()
            return
        }
        if (SaveBackup.gameRunning(this)) {
            updatePeak()
            return
        }
        val selection = currentSelection()
        if (selection.system && !systemConfirmed) {
            AlertDialog.Builder(this)
                .setTitle(R.string.patch_system_confirm_title)
                .setMessage(R.string.patch_system_confirm_body)
                .setNegativeButton(R.string.patch_system_confirm_negative) { _, _ ->
                    installSystemSwitch.isChecked = false
                    updatePeak()
                }
                .setPositiveButton(R.string.patch_system_confirm_positive) { _, _ ->
                    systemConfirmed = true
                    beginInstall()
                }
                .show()
            return
        }
        val space = PatchesRepository.space(scan, selection, policy, this)
        if (!space.enough || !selection.any) {
            updatePeak()
            return
        }
        try {
            PatchInstallService.clearResult()
            installRequestedAt = System.currentTimeMillis()
            startWaitLogged = false
            serviceReported = false
            knownInstallId = ""
            liveProcessed = -1L
            liveTotal = -1L
            lastProgressKey = null
            PatchInstallService.start(this, spec, selection, policy)
        } catch (t: Throwable) {
            showResult(ResultKind.FAILED, t.message ?: t.toString())
            return
        }
        installing = true
        installProgress.isIndeterminate = false
        installProgress.progress = 0
        installText.text = getString(R.string.patch_install_phase_preparing)
        installFile.text = ""
        installFile.visibility = View.GONE
        showStep(Step.INSTALL)
        bindToService()
    }

    private fun confirmCancelInstall() {
        if (!PatchInstallService.runningNow) {
            // Nothing is running: never offer a cancel that would do nothing.
            // This is the window where the run has stopped and the screen is
            // resolving what it did.
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.patch_import_cancel_title)
            .setMessage(R.string.patch_import_cancel_body)
            .setNegativeButton(R.string.patch_import_cancel_keep, null)
            .setPositiveButton(R.string.patch_import_cancel_stop) { _, _ ->
                PatchInstallService.cancel(this)
                installText.text = getString(R.string.patch_import_stopping)
            }
            .show()
    }

    private fun bindToService() {
        if (bound) return
        bindRequestedAt = System.currentTimeMillis()
        val ok = runCatching {
            bindService(
                Intent(this, PatchInstallService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        bound = ok
        if (!ok) {
            // No binding means no snapshot will ever arrive. Resolve from the
            // durable state rather than leaving the last percentage on screen.
            noteDisconnect("bind_failed")
            resolveFinishedInstall()
        }
    }

    private fun unbindFromService() {
        dropConnection(dead = true)
        bindRequestedAt = 0L
    }

    /**
     * Forgets the binder without deciding what the run did. A dead binding is
     * dropped for good; an unexpected disconnect keeps the binding because
     * Android may reconnect it.
     */
    private fun dropConnection(dead: Boolean) {
        val callback = listener
        val local = serviceBinder
        if (callback != null && local != null) {
            runCatching { local.removeListener(callback) }
        }
        listener = null
        serviceBinder = null
        service = null
        connectedOnce = false
        if (dead) {
            if (bound) runCatching { unbindService(connection) }
            bound = false
        }
    }

    private fun noteDisconnect(reason: String) {
        note(
            "ui.disconnect",
            "runningNow" to PatchInstallService.runningNow.toString(),
            "bound" to bound.toString(),
            "reason" to reason,
        )
    }

    /**
     * A disconnect is not an answer yet: the system may rebind, and the
     * terminal record is written just before the service stops. Look again
     * shortly instead of leaving the last percentage frozen.
     */
    private val disconnectedResolve = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (currentStep != Step.INSTALL) return
            if (PatchInstallService.runningNow) {
                // The run is alive but this screen lost its binder. Reattach
                // to the snapshots instead of waiting for a result it cannot
                // hear.
                if (!bound) bindToService()
                return
            }
            resolveFinishedInstall()
        }
    }

    private fun scheduleDisconnectedResolve() {
        recheck.removeCallbacks(disconnectedResolve)
        recheck.postDelayed(disconnectedResolve, DISCONNECT_GRACE_MS)
    }

    private fun applySnapshot(snapshot: PatchInstallService.Snapshot) {
        lastEvidenceAt = System.currentTimeMillis()
        val result = snapshot.result
        if (snapshot.running || result != null) {
            // The service has been reached for this run, so a later silence is
            // not a slow start and must not wait out the whole start window.
            serviceReported = true
        }
        if (result != null) {
            if (currentStep == Step.RESULT) return
            installing = false
            note(
                "ui.resolve",
                "outcome" to installResultOutcome(result),
                "source" to "snapshot",
                "installId" to snapshotInstallId(),
                "recordState" to "",
            )
            showInstallResult(result)
            return
        }
        if (!snapshot.running) {
            // The service is reachable but says the run is over and published
            // no result: a stopped foreground service, a fresh binder, or a
            // MIUI timeout. The durable record is the truth now, so never
            // keep the percentage that was on screen when it stopped.
            scheduleDisconnectedResolve()
            return
        }
        val progress = snapshot.progress ?: return
        installing = true
        showStep(Step.INSTALL)
        val percent = progress.percent.coerceIn(0, 100)
        installProgress.isIndeterminate = false
        installProgress.progress = percent * 10
        installText.text = getString(
            R.string.patch_import_progress,
            phaseLabel(progress.phase),
            percent,
        )
        val detail = progress.detail.trim()
        val file = detail.substringAfterLast('/').substringAfterLast('\\')
        installFile.text = file
        installFile.visibility = if (file.isEmpty()) View.GONE else View.VISIBLE
        lastSnapshotPhase = progress.phase
        lastSnapshotPercent = percent
        lastShownFile = file
        noteProgress(progress.phase, percent, file)
    }

    private fun snapshotInstallId(): String {
        if (knownInstallId.isNotEmpty()) return knownInstallId
        val live = readLive(liveFile())
        val currentRun = installRequestedAt <= 0L || live.updatedAt >= installRequestedAt
        return if (live.exists && currentRun) live.installId else ""
    }

    /**
     * One event per visible step of the bar, not one per snapshot: the service
     * publishes far more often than the text changes. The byte counts do not
     * exist in the snapshot, so they come from the live file (throttled).
     */
    private fun noteProgress(phase: InstallPhase, percent: Int, file: String) {
        val key = phase.name + ':' + percent
        if (key == lastProgressKey) return
        lastProgressKey = key
        val bytes = liveBytes()
        val fields = mutableListOf(
            "percent" to percent.toString(),
            "phase" to phase.name,
            "file" to file,
        )
        if (bytes.first >= 0L && bytes.second >= 0L) {
            fields.add("processed" to bytes.first.toString())
            fields.add("total" to bytes.second.toString())
        }
        note("ui.progress", *fields.toTypedArray())
    }

    /** At most one live-file read a second; only used while progress is shown. */
    private fun liveBytes(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        if (now - liveReadAt >= LIVE_READ_MS) {
            liveReadAt = now
            val root = runCatching { PatchesRepository.gameRoot(this) }.getOrNull()
            val live = readLive(root?.let { File(it, LIVE_FILE) })
            // A live file from the previous run has no byte counts for this
            // one; unknown stays -1 rather than a plausible old number.
            val currentRun = installRequestedAt <= 0L || live.updatedAt >= installRequestedAt
            liveProcessed = if (currentRun) live.processed else -1L
            liveTotal = if (currentRun) live.total else -1L
            if (currentRun && live.installId.isNotEmpty()) knownInstallId = live.installId
        }
        return liveProcessed to liveTotal
    }

    /**
     * The progress screen without evidence of progress: an indeterminate bar
     * plus the file the live record names, so a dead 85% never stands in for
     * work that is not happening.
     */
    private fun showChecking(file: String = "") {
        installing = true
        showStep(Step.INSTALL)
        installProgress.isIndeterminate = true
        installProgress.progress = 0
        installText.setText(R.string.patch_import_checking)
        val name = file.substringAfterLast('/').substringAfterLast('\\')
        installFile.text = name
        installFile.visibility = if (name.isEmpty()) View.GONE else View.VISIBLE
        resolveFinishedInstall()
    }

    // ---- liveness ---------------------------------------------------------
    // MIUI's "Stop FGS timeout" and Android 15's dataSync timeout stop the
    // service without pausing the activity, so nothing in the lifecycle wakes
    // this screen. This timer is the only observer while it stays on screen.

    private val livenessTick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || currentStep != Step.INSTALL) return
            val now = System.currentTimeMillis()
            if (PatchInstallService.runningNow) {
                // Running but silent is evidence, not failure: log it once per
                // stall window and leave the screen alone.
                if (now - lastEvidenceAt >= STALL_LOG_MS && now - lastStallLogAt >= STALL_LOG_MS) {
                    lastStallLogAt = now
                    note(
                        "ui.stalled",
                        "phase" to (lastSnapshotPhase?.name ?: ""),
                        "percent" to (lastSnapshotPercent?.toString() ?: ""),
                        "file" to lastShownFile,
                        "silentMs" to (now - lastEvidenceAt).toString(),
                    )
                }
            } else if (!resolving) {
                val root = gameRoot()
                val live = readLive(root?.let { File(it, LIVE_FILE) })
                // A live file left by the previous run is not evidence about
                // the run this screen has just started.
                val currentRun = installRequestedAt <= 0L || live.updatedAt >= installRequestedAt
                if (currentRun && live.updatedAt > lastEvidenceAt) lastEvidenceAt = live.updatedAt
                if (currentRun && live.installId.isNotEmpty()) knownInstallId = live.installId
                val staleLive = live.exists && currentRun && live.updatedAt > 0L &&
                    now - live.updatedAt >= LIVE_STALE_MS
                val silent = now - lastEvidenceAt >= SILENT_RESOLVE_MS
                // A terminal record is this run's own ending, written just
                // before the service stops. It counts even if the last
                // snapshot never reached this screen.
                val recordAt = root?.let { PatchInstallResultStore.file(it).lastModified() } ?: 0L
                val recordForThisRun = recordAt > 0L &&
                    (installRequestedAt <= 0L || recordAt >= installRequestedAt)
                if (staleLive || silent || recordForThisRun) {
                    // Re-bind once: a service stopped by the system can leave
                    // the old binding alive without ever sending a snapshot.
                    if (livenessRebindAt == 0L) {
                        livenessRebindAt = now
                        unbindFromService()
                        bindToService()
                    }
                    val file = if (currentRun && live.file.isNotEmpty()) live.file else lastShownFile
                    showChecking(file)
                    return
                }
            }
            liveness.postDelayed(this, LIVENESS_TICK_MS)
        }
    }

    private fun startLiveness() {
        liveness.removeCallbacks(livenessTick)
        liveness.postDelayed(livenessTick, LIVENESS_TICK_MS)
    }

    private fun stopLiveness() {
        liveness.removeCallbacks(livenessTick)
    }

    private fun liveFile(): File? = gameRoot()?.let { File(it, LIVE_FILE) }

    private fun gameRoot(): File? =
        runCatching { PatchesRepository.gameRoot(this) }.getOrNull()

    // ---- resolving a run without a live service ---------------------------

    /**
     * The service is no longer running, so its snapshot is not the truth.
     * Resolve the outcome from, in order: the in-process result it published
     * before it stopped, the durable terminal record, then the journal and the
     * active slots. Recovery runs first when a transaction is still pending,
     * because the service that would have finished it may be gone.
     */
    private fun resolveFinishedInstall() {
        if (resolving) return
        if (PatchInstallService.runningNow) {
            // The run is alive. If this screen lost its binder (a rotation or
            // a dead binding), attach so the snapshots resume; there is
            // nothing to resolve while it runs.
            if (currentStep == Step.INSTALL) bindToService()
            return
        }
        if (awaitService()) return

        val published = PatchInstallService.lastSnapshot?.result
        if (published != null) {
            // Only a snapshot that carries a result is worth re-applying; a
            // bare percentage from a dead service is the 85% lie.
            installing = false
            note(
                "ui.resolve",
                "outcome" to installResultOutcome(published),
                "source" to "snapshot",
                "installId" to snapshotInstallId(),
                "recordState" to "",
            )
            showInstallResult(published)
            return
        }

        resolving = true
        val token = ++resolveToken
        // Switches are read here, on the main thread; the disk work is not.
        val fallbackSlots = if (plan != null) currentSelection().slots() else emptyList()
        val wasInstallStep = installing || currentStep == Step.INSTALL
        thread(name = "patch-resolve", isDaemon = true) {
            val resolution = runCatching { resolveOutcome(fallbackSlots, wasInstallStep) }.getOrElse {
                Resolution(
                    Outcome.Interrupted(getString(R.string.patch_install_result_unconfirmed)),
                    "state",
                    "",
                    "",
                )
            }
            runOnUiThread {
                if (token != resolveToken || isFinishing || isDestroyed) return@runOnUiThread
                resolving = false
                // A result that arrived from the service while the disk was
                // read is newer evidence than this reading; never replace it.
                if (currentStep == Step.RESULT) return@runOnUiThread
                if (PatchInstallService.runningNow) {
                    // The service answered while the disk was being read; its
                    // snapshots are live truth, so the reading is discarded.
                    bindToService()
                    return@runOnUiThread
                }
                note(
                    "ui.resolve",
                    "outcome" to outcomeName(resolution.outcome),
                    "source" to resolution.source,
                    "installId" to resolution.installId,
                    "recordState" to resolution.recordState,
                )
                val outcome = resolution.outcome
                if (outcome == null) {
                    // Nothing was ever reported for this screen. From the
                    // notification that means PATCHES; a stale INSTALL step
                    // goes back to the picker rather than showing fake work.
                    if (fromNotification) {
                        openPatches()
                    } else {
                        installing = false
                        showStep(Step.PICK)
                    }
                } else {
                    applyOutcome(outcome)
                }
            }
        }
    }

    /**
     * True while a start or a bind is still young enough to explain why there
     * is no service yet. A cold MIUI start can take several seconds, and a
     * binder that has not answered yet is not a stopped install. The deadline
     * also produces the single `ui.start_wait` event for this run.
     */
    private fun awaitService(): Boolean {
        val now = System.currentTimeMillis()
        // Only this process's own start is worth waiting for; a run inherited
        // from an earlier process is resolved from its durable record.
        val startDeadline = if (installRequestedAt > 0L && !serviceReported) {
            installRequestedAt + START_GRACE_MS
        } else {
            0L
        }
        val bindDeadline = if (bound && !connectedOnce && bindRequestedAt > 0L) {
            bindRequestedAt + BIND_GRACE_MS
        } else {
            0L
        }
        val deadline = maxOf(startDeadline, bindDeadline)
        if (deadline <= now) return false
        // Bind during the wait: a service that is still coming up will publish
        // its first snapshot to whoever is already listening.
        if (!bound && bindRequestedAt == 0L) bindToService()
        recheck.removeCallbacks(startWait)
        recheck.postDelayed(startWait, deadline - now)
        return true
    }

    /**
     * The end of the start window. It is the evidence for "the service never
     * came up": one event per run, never one per recheck.
     */
    private val startWait = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val now = System.currentTimeMillis()
            val from = when {
                installRequestedAt > 0L -> installRequestedAt
                bindRequestedAt > 0L -> bindRequestedAt
                else -> now
            }
            val started = PatchInstallService.runningNow || service != null
            if (!startWaitLogged) {
                startWaitLogged = true
                note(
                    "ui.start_wait",
                    "elapsed" to (now - from).coerceAtLeast(0L).toString(),
                    "result" to if (started) "started" else "timeout",
                    "runningNow" to PatchInstallService.runningNow.toString(),
                )
            }
            if (currentStep == Step.INSTALL) resolveFinishedInstall()
        }
    }

    private fun resolveOutcome(
        fallbackSlots: List<InstallSlot>,
        wasInstallStep: Boolean,
    ): Resolution {
        val root = PatchesRepository.gameRoot(this)
        val recorded = runCatching { PatchInstallResultStore.read(root) }.getOrNull()
        // The install id, timestamps and progress keys live beside the
        // outcome. Reading the same small file keeps the screen decoupled
        // from the record's Kotlin shape while the two changes land
        // separately.
        val recordedProps = if (recorded != null) {
            readProps(root?.let { PatchInstallResultStore.file(it) })
        } else {
            null
        }
        val recordedAt = property(recordedProps, "updatedAt")?.toLongOrNull()
            ?: recordedProps?.getProperty("timestamp")?.toLongOrNull()
            ?: 0L
        // A record left by the previous run must not answer for a run this
        // screen has just started. The service clears it on start, but a start
        // that never arrives would otherwise inherit the old success.
        val record = recorded?.takeIf {
            installRequestedAt <= 0L || recordedAt <= 0L || recordedAt >= installRequestedAt
        }
        val recordProps = if (record != null) recordedProps else null
        val live = readLive(root?.let { File(it, LIVE_FILE) })
        val currentRun = installRequestedAt <= 0L || live.updatedAt >= installRequestedAt
        if (currentRun && live.updatedAt > lastEvidenceAt) lastEvidenceAt = live.updatedAt
        val installId = property(recordProps, "id").orEmpty()
            .ifBlank { if (currentRun) live.installId else "" }
        val targets = record?.slots?.takeIf { it.isNotEmpty() } ?: fallbackSlots
        val gameRunning = SaveBackup.gameRunning(this)
        val pending = root != null && !gameRunning && PatchesRepository.isInstalling(this)
        // A recovery started here is the strongest evidence. When no journal is
        // pending, the report from the launch recovery still explains a run
        // that died before this screen came back.
        var report: RecoveryReport? = null
        if (pending) {
            runCatching {
                PatchesRepository.recover(this)
                report = PatchesRepository.recoveryReport()
            }
        } else if (wasInstallStep) {
            report = PatchesRepository.recoveryReport()
        }
        val state = runCatching { PatchesRepository.state() }.getOrNull()
        val allInstalled = state != null && targets.isNotEmpty() && targets.all { slot ->
            isInstalled(state, slot)
        }
        val committedKept = report?.committedInstallKept == true
        val recoveredSomething = report?.changed == true
        // A live record that stopped moving while no service is running is the
        // 85% screen: the run existed and is over. Trust it only for a
        // non-terminal phase; a terminal phase means the record should exist.
        val staleLiveRun = wasInstallStep && live.exists && currentRun && live.updatedAt > 0L &&
            System.currentTimeMillis() - live.updatedAt >= LIVE_STALE_MS &&
            !live.terminalPhase()

        val decision: Pair<Outcome?, String> = when (record?.state) {
            PatchInstallResultStore.State.SUCCESS ->
                // Never claim success for slots that are not complete on disk.
                if (targets.isEmpty() || allInstalled) {
                    Outcome.Success to "record"
                } else {
                    Outcome.Interrupted(getString(R.string.patch_install_result_unconfirmed)) to "state"
                }

            PatchInstallResultStore.State.CANCELLED ->
                Outcome.Cancelled(record?.message.orEmpty()) to "record"

            PatchInstallResultStore.State.FAILED ->
                Outcome.Failed(record?.message.orEmpty()) to "record"

            PatchInstallResultStore.State.INTERRUPTED ->
                // The record can be provisional: the service may have been
                // stopped in the frame between the commit and the result.
                if (committedKept && allInstalled) {
                    Outcome.Success to "journal"
                } else {
                    Outcome.Interrupted(
                        record?.message.orEmpty().ifBlank {
                            getString(R.string.patch_install_result_unconfirmed)
                        },
                    ) to "record"
                }

            null -> when {
                committedKept && allInstalled -> Outcome.Success to "journal"
                pending || recoveredSomething ->
                    Outcome.Interrupted(getString(R.string.patch_install_result_unconfirmed)) to "journal"

                staleLiveRun ->
                    Outcome.Interrupted(getString(R.string.patch_install_result_unconfirmed)) to "state"

                else -> null to "state"
            }
        }
        return Resolution(
            outcome = decision.first,
            source = decision.second,
            installId = installId,
            recordState = record?.state?.name.orEmpty(),
        )
    }

    private fun outcomeName(outcome: Outcome?): String = when (outcome) {
        is Outcome.Success -> "success"
        is Outcome.Cancelled -> "cancelled"
        is Outcome.Failed -> "failed"
        is Outcome.Interrupted -> "interrupted"
        null -> "none"
    }

    private fun installResultOutcome(result: InstallResult): String = when (result) {
        is InstallResult.Success -> "success"
        is InstallResult.Cancelled -> if (result.restored) "cancelled" else "failed"
        is InstallResult.Failed -> "failed"
    }

    private fun isInstalled(state: PatchesRepository.State, slot: InstallSlot): Boolean =
        when (slot) {
            InstallSlot.PATCH -> state.patch?.complete == true
            InstallSlot.EDITED_DATA -> state.edited?.complete == true
            InstallSlot.SYSTEM -> state.systemUpdated
        }

    private fun applyOutcome(outcome: Outcome) {
        installing = false
        when (outcome) {
            is Outcome.Success -> showResult(ResultKind.SUCCESS)
            is Outcome.Cancelled -> showResult(
                ResultKind.CANCELLED,
                outcome.detail.ifBlank { getString(R.string.unknown_reason) },
            )

            is Outcome.Failed -> showResult(
                ResultKind.FAILED,
                outcome.detail.ifBlank { getString(R.string.unknown_reason) },
            )

            is Outcome.Interrupted -> showResult(ResultKind.INTERRUPTED, outcome.detail)
        }
    }

    private fun openPatches() {
        runCatching { startActivity(Intent(this, PatchesActivity::class.java)) }
        finish()
    }

    private fun showInstallResult(result: InstallResult) {
        when (result) {
            is InstallResult.Success -> showResult(ResultKind.SUCCESS)
            is InstallResult.Cancelled -> {
                if (result.restored) {
                    showResult(ResultKind.CANCELLED, result.detail)
                } else {
                    showResult(ResultKind.FAILED, restoreFailureText(result.detail))
                }
            }

            is InstallResult.Failed -> {
                val kind = when {
                    !result.restored -> ResultKind.FAILED
                    result.failure == InstallFailure.VERIFY -> ResultKind.CORRUPT
                    else -> ResultKind.FAILED
                }
                val detail = if (result.restored) {
                    result.message
                } else {
                    restoreFailureText(result.message)
                }
                showResult(kind, detail)
            }
        }
    }

    private fun restoreFailureText(detail: String?): String =
        listOfNotNull(
            getString(R.string.patch_import_not_restored),
            detail?.takeIf { it.isNotBlank() },
        ).joinToString("\n\n")

    // ---- result ------------------------------------------------------------

    private fun showResult(kind: ResultKind, detail: String? = null) {
        installing = false
        // One event per shown result; a duplicate resolution must not double
        // the log or change what the player sees.
        val resultKey = kind.name + '|' + detail.orEmpty()
        if (resultKey != lastLoggedResult) {
            lastLoggedResult = resultKey
            note("ui.result", "kind" to kind.name, "message" to detail.orEmpty())
        }
        val title: Int
        val body: Int
        val badge: Int
        val tone: Tone
        when (kind) {
            ResultKind.SUCCESS -> {
                title = R.string.patch_import_result_success
                body = R.string.patch_import_result_success_body
                badge = R.string.patch_import_badge_installed
                tone = Tone.OK
            }

            ResultKind.CANCELLED -> {
                title = R.string.patch_import_result_cancelled
                body = R.string.patch_import_result_cancelled_body
                badge = R.string.patch_import_badge_cancelled
                tone = Tone.EMPTY
            }

            ResultKind.CORRUPT -> {
                title = R.string.patch_import_result_corrupt
                body = R.string.patch_import_result_corrupt_body
                badge = R.string.patch_import_badge_warning
                tone = Tone.WARN
            }

            ResultKind.MISSING_PART -> {
                title = R.string.patch_import_result_missing_part
                body = R.string.patch_import_result_missing_part_body
                badge = R.string.patch_import_badge_warning
                tone = Tone.WARN
            }

            ResultKind.WRONG_TITLE -> {
                title = R.string.patch_import_result_wrong_title
                body = R.string.patch_import_result_wrong_title_body
                badge = R.string.patch_import_badge_warning
                tone = Tone.WARN
            }

            ResultKind.NO_DPFILELIST -> {
                title = R.string.patch_import_result_no_dpfilelist
                body = R.string.patch_import_result_no_dpfilelist_body
                badge = R.string.patch_import_badge_warning
                tone = Tone.WARN
            }

            ResultKind.NO_PACKAGES -> {
                title = R.string.patch_import_result_no_packages
                body = R.string.patch_import_result_no_packages_body
                badge = R.string.patch_import_badge_warning
                tone = Tone.WARN
            }

            ResultKind.FAILED -> {
                title = R.string.patch_import_result_failed
                body = R.string.patch_import_result_failed_body
                badge = R.string.patch_import_badge_failed
                tone = Tone.WARN
            }

            ResultKind.INTERRUPTED -> {
                title = R.string.patch_import_result_interrupted
                body = R.string.patch_import_result_interrupted_body
                badge = R.string.patch_import_badge_interrupted
                tone = Tone.WARN
            }
        }

        findViewById<TextView>(R.id.result_title).setText(title)
        findViewById<TextView>(R.id.result_body).text = when (kind) {
            ResultKind.FAILED -> getString(
                body,
                detail ?: getString(R.string.unknown_reason),
            )

            ResultKind.SUCCESS -> getString(
                body,
                plan?.name ?: sourceSpec?.displayName ?: getString(R.string.patch_import_unknown_name),
            )

            else -> getString(body)
        }
        findViewById<TextView>(R.id.result_detail).text = when (kind) {
            ResultKind.SUCCESS -> getString(R.string.patch_import_result_note)
            ResultKind.INTERRUPTED -> listOfNotNull(
                detail?.takeIf { it.isNotBlank() },
                getString(R.string.patch_import_interrupted_hint),
            ).joinToString("\n\n")

            else -> detail.orEmpty()
        }
        val retry = kind == ResultKind.INTERRUPTED
        findViewById<Button>(R.id.result_done).apply {
            setText(if (retry) R.string.patch_import_result_retry else R.string.patch_import_result_done)
            setOnClickListener { if (retry) retryInstall() else finish() }
        }
        pill(resultBadge, getString(badge), tone)
        showStep(Step.RESULT)
    }

    /**
     * An interrupted run can be repeated. The plan and the source are still in
     * memory when the activity survived, so the summary can be shown again;
     * after a process death only the picker makes sense.
     */
    private fun retryInstall() {
        installing = false
        if (plan != null && sourceSpec != null) {
            updatePeak()
            showStep(Step.SUMMARY)
        } else {
            showStep(Step.PICK)
        }
    }

    // ---- small pieces ------------------------------------------------------

    private fun showStep(step: Step) {
        val changed = currentStep != step
        currentStep = step
        stepPick.visibility = if (step == Step.PICK) View.VISIBLE else View.GONE
        stepScanning.visibility = if (step == Step.SCANNING) View.VISIBLE else View.GONE
        stepSummary.visibility = if (step == Step.SUMMARY) View.VISIBLE else View.GONE
        stepInstall.visibility = if (step == Step.INSTALL) View.VISIBLE else View.GONE
        stepResult.visibility = if (step == Step.RESULT) View.VISIBLE else View.GONE
        if (step == Step.INSTALL) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (changed) {
                // A new view of the run: previous progress keys and result
                // guards belong to the run that was already on screen.
                lastEvidenceAt = System.currentTimeMillis()
                livenessRebindAt = 0L
                lastStallLogAt = 0L
                lastProgressKey = null
                lastLoggedResult = null
            }
            startLiveness()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            stopLiveness()
        }
        if (changed || lastStepLogged != step) {
            lastStepLogged = step
            note("ui.step", "step" to step.name)
        }
    }

    /**
     * Diagnostic only: the screen must never fail because the event log did.
     * Fields are plain pairs, so no JSON dependency is needed to read them.
     */
    private fun note(kind: String, vararg fields: Pair<String, String>) {
        runCatching { EventLog.note(this, kind, *fields) }
    }

    private fun paintAdvanced() {
        val red = advancedOpen || installSystemSwitch.isChecked
        advancedToggle.setTextColor(getColor(if (red) R.color.pes_red else R.color.pes_blue))
        systemWarning.visibility = if (installSystemSwitch.isChecked) View.VISIBLE else View.GONE
    }

    private fun currentSelection(): InstallSelection = InstallSelection(
        patch = installPatchSwitch.isChecked,
        editedData = installEditSwitch.isChecked,
        system = installSystemSwitch.isChecked,
    )

    private fun policyLabel(): String = getString(
        when (policy) {
            PatchesRepository.BackupPolicy.KEEP -> R.string.patch_import_backup_keep
            PatchesRepository.BackupPolicy.FREE_OLD -> R.string.patch_import_backup_free
            PatchesRepository.BackupPolicy.NONE -> R.string.patch_import_backup_none
        },
    )

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

    private fun kindLabel(kind: PatchSourceKind): String = when (kind) {
        PatchSourceKind.FOLDER -> "FOLDER"
        PatchSourceKind.ZIP -> "ZIP"
        PatchSourceKind.RAR -> "RAR"
        PatchSourceKind.SEVEN_ZIP -> "7Z"
        PatchSourceKind.UNKNOWN -> "ARCHIVE"
    }

    private fun gigabytes(bytes: Long): String =
        String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)

    private fun documentName(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) name = cursor.getString(column)
            }
        } catch (_: Throwable) {
        }
        return (name ?: uri.lastPathSegment ?: getString(R.string.patch_import_unknown_source))
            .substringAfterLast('/')
    }

    private fun treeName(uri: Uri): String = uri.lastPathSegment
        ?.substringAfterLast(':')
        ?.substringAfterLast('/')
        ?.ifEmpty { getString(R.string.patch_import_folder_default) }
        ?: getString(R.string.patch_import_folder_default)

    private enum class Tone { OK, EMPTY, WARN }

    private fun pill(view: TextView, text: String, tone: Tone) {
        view.text = text
        when (tone) {
            Tone.OK -> {
                view.setBackgroundResource(R.drawable.bg_pill_badge)
                view.setTextColor(getColor(R.color.pes_gold))
            }

            Tone.EMPTY -> {
                view.setBackgroundResource(R.drawable.bg_setting_row)
                view.setTextColor(getColor(R.color.pes_muted))
            }

            Tone.WARN -> {
                view.setBackgroundResource(R.drawable.bg_pill_warning)
                view.setTextColor(getColor(R.color.pes_on_dark))
            }
        }
    }

    companion object {
        const val EXTRA_SLOT = "patch_import.slot"
        const val EXTRA_BACKUP = "patch_import.backup"

        /** Set by the install notification: open the result, never a blank picker. */
        const val EXTRA_FROM_NOTIFICATION = "patch_import.from_notification"

        private const val STATE_PENDING = "patch_import.pending_install"
        private const val STATE_STARTED_AT = "patch_import.started_at"

        /**
         * A start that has not reached the service yet is not an interruption.
         * A cold MIUI start can spend several seconds in startForegroundService
         * before onStartCommand runs, which is exactly the window the old 2 s
         * value called an interruption.
         */
        private const val START_GRACE_MS = 12_000L

        /** A bind whose onServiceConnected has not arrived yet is not a dead service. */
        private const val BIND_GRACE_MS = 1_500L

        /** After a disconnect, give the system a moment to rebind before resolving. */
        private const val DISCONNECT_GRACE_MS = 1_500L

        /** One liveness look per tick while the install step is on screen. */
        private const val LIVENESS_TICK_MS = 5_000L

        /** A live file untouched this long while no service runs is a finished run. */
        private const val LIVE_STALE_MS = 10_000L

        /** No snapshot or file update for this long means the run is not reporting. */
        private const val SILENT_RESOLVE_MS = 15_000L

        /** A running service that stays silent this long is worth one event. */
        private const val STALL_LOG_MS = 30_000L

        /** Same file name the installer/service writes in the game root. */
        private const val LIVE_FILE = "patch-install-live.properties"

        /** The live file is small; one read a second only enriches ui.progress. */
        private const val LIVE_READ_MS = 1_000L
    }
}

/**
 * A read of the install's live property file. Missing keys stay empty or -1,
 * because "unknown" must never be shown as a real zero.
 */
private class LiveFile(
    val exists: Boolean = false,
    val installId: String = "",
    val updatedAt: Long = 0L,
    val phase: String = "",
    val file: String = "",
    val processed: Long = -1L,
    val total: Long = -1L,
)

private fun LiveFile.terminalPhase(): Boolean =
    phase == InstallPhase.DONE.name ||
        phase == InstallPhase.CANCELLED.name ||
        phase == InstallPhase.FAILED.name

private fun readProps(file: File?): Properties? {
    if (file == null || !file.isFile) return null
    val props = Properties()
    val loaded = runCatching { file.inputStream().use { props.load(it) } }.isSuccess
    return if (loaded) props else null
}

/**
 * The new install keys are namespaced `install.*`; accepting the bare name
 * too keeps this reader working while the writer and the record change in
 * separate changes.
 */
private fun property(props: Properties?, key: String): String? =
    props?.getProperty("install.$key") ?: props?.getProperty(key)

private fun readLive(file: File?): LiveFile {
    val props = readProps(file) ?: return LiveFile()
    return LiveFile(
        exists = true,
        installId = property(props, "id").orEmpty(),
        updatedAt = property(props, "updatedAt")?.toLongOrNull() ?: 0L,
        phase = property(props, "phase").orEmpty(),
        file = property(props, "file").orEmpty(),
        processed = property(props, "processedBytes")?.toLongOrNull() ?: -1L,
        total = property(props, "totalBytes")?.toLongOrNull() ?: -1L,
    )
}
