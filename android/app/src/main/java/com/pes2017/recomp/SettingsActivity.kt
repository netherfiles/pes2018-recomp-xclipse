package com.pes2017.recomp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.pes2017.recomp.patch.PatchFormat
import com.pes2017.recomp.patch.PatchesDashboard
import com.pes2017.recomp.patch.SlotSize
import kotlin.math.roundToInt

/**
 * Everything the player can change outside a match. Each setting is written
 * the moment it is touched: there is no "apply", because there is nothing
 * to apply to -- the values are read when a match starts (GameActivity).
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TAB = "pes17.tab"
        const val TAB_GRAPHICS = 0
        const val TAB_CONTROLS = 1
        const val TAB_ADVANCED = 2
        const val TAB_FILES = 3
    }

    private lateinit var prefs: Prefs
    private val tabButtons = mutableListOf<Button>()
    private val tabPages = mutableListOf<android.view.View>()
    private lateinit var storageAccess: TextView
    private lateinit var grantStorage: Button
    private lateinit var savesSummary: TextView
    private lateinit var paths: TextView

    // The patch summary and the storage rows, measured off the main thread.
    private lateinit var patchState: TextView
    private lateinit var patchDetail: TextView
    private lateinit var editState: TextView
    private lateinit var editDetail: TextView
    private lateinit var previousState: TextView
    private lateinit var previousDetail: TextView
    private lateinit var storageText: TextView

    /** A diagnostics report is being written: the button stays down until it answers. */
    private var exporting = false

    // Save backups (SaveBackup): written where the player picks, read from a file they pick.
    private val createBackup = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) backupTo(uri) }
    private val openBackup = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) confirmRestore { contentResolver.openInputStream(uri) } }

    /**
     * GameFolderActivity writes prefs.gameDirectory before it returns, so the
     * result is only "a folder was taken": redraw the paths and measure again.
     */
    private val folderPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            paths.text = pathsText()
            refreshStorageAccess()
            refreshDashboard(force = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applySystemBars()
        // BACK on the strip along the top; through the dispatcher, so a screen's own back handling (the install's
        // "stop copying?") applies to it too.
        findViewById<android.view.View>(R.id.back_button).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        prefs = Prefs(this)
        title = getString(R.string.entry_settings)

        // ---- tab bar (plain views, no new deps): one page visible at a time ----
        listOf(R.id.tab_graphics, R.id.tab_controls, R.id.tab_advanced, R.id.tab_files)
            .forEach { tabButtons.add(findViewById(it)) }
        listOf(R.id.page_graphics, R.id.page_controls, R.id.page_advanced, R.id.page_files)
            .forEach { tabPages.add(findViewById(it)) }
        // Performance is part of the graphics tab: shown and hidden with it (selectTab).
        tabButtons.forEachIndexed { index, button -> button.setOnClickListener { selectTab(index) } }
        selectTab(intent.getIntExtra(EXTRA_TAB, TAB_GRAPHICS).coerceIn(0, tabPages.size - 1))

        // ---- graphics: preset, display, image quality ----
        presets()
        bar(R.id.resolution, R.id.resolution_label, 65, ((prefs.resolutionScale * 100f) - 35f).roundToInt(),
            { getString(R.string.resolution, it + 35) }) { prefs.resolutionScale = (it + 35) / 100f }
        switch(R.id.widescreen, prefs.widescreen) { prefs.widescreen = it }
        bar(R.id.ball_scale, R.id.ball_scale_label, 20, (prefs.ballScalePercent - 100) / 10,
            { getString(R.string.ball_scale, 100 + it * 10) }) { prefs.ballScalePercent = 100 + it * 10 }
        bar(R.id.scene_scale, R.id.scene_scale_label, 10, (prefs.sceneScalePercent - 50) / 5,
            { getString(R.string.scene_scale, 50 + it * 5) }) { prefs.sceneScalePercent = 50 + it * 5; presetTouched() }
        switch(R.id.msaa, prefs.multisampling) { prefs.multisampling = it; presetTouched() }
        switch(R.id.fsr, prefs.fsr) { prefs.fsr = it; presetTouched() }
        switch(R.id.frame_gen, prefs.frameGeneration) { prefs.frameGeneration = it }
        switch(R.id.black_guard, prefs.blackGuard) { prefs.blackGuard = it }
        // Experiments: arena size (Auto, 128, 192, 256, 320 MB) and five switches the runtime already has.
        val arenaIds = intArrayOf(R.id.arena_auto, R.id.arena_128, R.id.arena_192, R.id.arena_256, R.id.arena_320)
        val arenaSizes = intArrayOf(0, 128, 192, 256, 320)
        val arena: android.widget.RadioGroup = findViewById(R.id.arena_size)
        arena.check(arenaIds[arenaSizes.indexOf(prefs.arenaMB).coerceAtLeast(0)])
        arena.setOnCheckedChangeListener { _, id -> prefs.arenaMB = arenaSizes[arenaIds.indexOf(id).coerceAtLeast(0)] }
        switch(R.id.exp_legacy, prefs.expLegacy) { prefs.expLegacy = it }
        switch(R.id.exp_no_table, prefs.expNoTable) { prefs.expNoTable = it }
        switch(R.id.exp_bc_hw, prefs.expBcHw) { prefs.expBcHw = it }
        switch(R.id.exp_dynamic_depth, prefs.expDynamicDepth) { prefs.expDynamicDepth = it }
        switch(R.id.exp_sync_large, prefs.syncLargePipelines) { prefs.syncLargePipelines = it }
        bar(R.id.fsr_sharp, R.id.fsr_sharp_label, 20, (prefs.fsrSharpness * 10f).roundToInt(),
            { getString(R.string.fsr_sharp, it / 10f) }) { prefs.fsrSharpness = it / 10f }

        // ---- performance: frame rate, heat and battery ----
        bar(R.id.fps_limit, R.id.fps_limit_label, 6, (prefs.fpsLimit - 30) / 5,
            { if (it == 6) getString(R.string.fps_limit_off) else getString(R.string.fps_limit, 30 + it * 5) }) {
            prefs.fpsLimit = 30 + it * 5; presetTouched()
        }
        switch(R.id.prefer60, prefs.prefer60Hz) { prefs.prefer60Hz = it }
        // Off / Minimal / Normal / Complete, in Prefs.HUD_* order.
        val hudChoices = intArrayOf(R.id.hud_off, R.id.hud_minimal, R.id.hud_normal, R.id.hud_complete)
        val hudLevel: android.widget.RadioGroup = findViewById(R.id.hud_level)
        hudLevel.check(hudChoices[prefs.hudLevel])
        hudLevel.setOnCheckedChangeListener { _, id -> prefs.hudLevel = hudChoices.indexOf(id).coerceAtLeast(0) }
        switch(R.id.heat_scale, prefs.heatScale) { prefs.heatScale = it; presetTouched() }
        switch(R.id.perf_hint, prefs.perfHint) { prefs.perfHint = it }
        switch(R.id.crowd, prefs.crowd) { prefs.crowd = it }

        // ---- controls and sound ----
        switch(R.id.touch, prefs.touchControls) { prefs.touchControls = it }
        switch(R.id.gamepad, prefs.gamepadEnabled) { prefs.gamepadEnabled = it }
        switch(R.id.vibration, prefs.vibration) { prefs.vibration = it }
        val buttonStyle: android.widget.RadioGroup = findViewById(R.id.button_style)
        buttonStyle.check(if (prefs.playStationButtons) R.id.button_style_playstation else R.id.button_style_xbox)
        buttonStyle.setOnCheckedChangeListener { _, id -> prefs.playStationButtons = id == R.id.button_style_playstation }
        findViewById<Button>(R.id.arrange_controls).setOnClickListener {
            startActivity(Intent(this, ControlsEditorActivity::class.java))
        }
        switch(R.id.audio, prefs.audioEnabled) { prefs.audioEnabled = it }

        // ---- advanced: rendering, memory, testing ----
        bar(R.id.render_scale, R.id.render_scale_label, 2, prefs.renderScale - 1,
            { getString(R.string.render_scale, it + 1) }) { prefs.renderScale = it + 1 }
        switch(R.id.load_scale, prefs.loadScale) { prefs.loadScale = it; presetTouched() }
        switch(R.id.low_memory, prefs.lowMemory) { prefs.lowMemory = it; presetTouched() }
        // 0 means "let the runtime decide"; then 128 MB steps to 2 GB.
        bar(R.id.texture_budget, R.id.texture_label, 15,
            if (prefs.textureBudgetMB <= 0) 0 else (prefs.textureBudgetMB / 128).coerceIn(1, 15),
            { textureText(it) }) { prefs.textureBudgetMB = if (it == 0) 0 else it * 128 }
        switch(R.id.prewarm_all, prefs.prewarmAllShaders) { prefs.prewarmAllShaders = it }
        switch(R.id.gamepad_p2, prefs.padAsPlayer2) { prefs.padAsPlayer2 = it }
        // The test options only exist for someone who turned the developer tools on.
        // The bug-hunting experiments (arena size, render path, the black-frame guard) are the same: hidden
        // until then, and all off by default.
        val testing = listOf<android.view.View>(
            findViewById(R.id.testing_heading), findViewById(R.id.testing_group),
            findViewById(R.id.experiments_heading), findViewById(R.id.experiments_group),
            findViewById(R.id.black_guard), findViewById(R.id.black_guard_hint),
        )
        fun showTesting(on: Boolean) = testing.forEach { it.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE }
        showTesting(prefs.developerTools)
        switch(R.id.developer_tools, prefs.developerTools) { prefs.developerTools = it; showTesting(it) }

        // ---- the game files ----
        paths = findViewById(R.id.paths)
        storageAccess = findViewById(R.id.storage_access)
        grantStorage = findViewById(R.id.grant_storage)
        grantStorage.setOnClickListener {
            val intent = GameFolder.accessIntent(this)
            if (intent != null) {
                try {
                    startActivity(intent)
                } catch (_: Throwable) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Throwable) {
                    }
                }
            } else {
                requestPermissions(GameFolder.legacyPermissions, 1)
            }
        }
        findViewById<Button>(R.id.install_game).setOnClickListener {
            startActivity(Intent(this, InstallActivity::class.java))
        }
        findViewById<Button>(R.id.change_folder).setOnClickListener {
            // The picker checks default.xex and writes prefs.gameDirectory; the
            // result only says whether a folder was taken.
            folderPicker.launch(Intent(this, GameFolderActivity::class.java))
        }
        findViewById<Button>(R.id.forget).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.forget_install)
                .setMessage(R.string.forget_explained)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reset) { _, _ ->
                    prefs.gameDirectory = ""
                    paths.text = getString(R.string.paths_none)
                    refreshDashboard(force = true)
                }
                .show()
        }
        paths.text = pathsText()

        // ---- save data ----
        savesSummary = findViewById(R.id.saves_summary)
        refreshSaves()
        findViewById<Button>(R.id.backup_saves).setOnClickListener {
            createBackup.launch(SaveBackup.suggestedName())
        }
        findViewById<Button>(R.id.restore_saves).setOnClickListener { chooseRestore() }

        // ---- the patch summary and the storage: read from the cached snapshot
        // at once, then measured once per resume by PatchesDashboard. ----
        patchState = findViewById(R.id.patch_state)
        patchDetail = findViewById(R.id.patch_detail)
        editState = findViewById(R.id.edit_state)
        editDetail = findViewById(R.id.edit_detail)
        previousState = findViewById(R.id.previous_state)
        previousDetail = findViewById(R.id.previous_detail)
        storageText = findViewById(R.id.storage)
        val cached = PatchesDashboard.cached()
        if (cached != null) render(cached) else renderMeasuring()

        findViewById<Button>(R.id.clear_shaders).setOnClickListener {
            shaderCacheFiles().forEach { try { it.delete() } catch (_: Throwable) {} }
            android.widget.Toast.makeText(this, R.string.shaders_cleared, android.widget.Toast.LENGTH_SHORT).show()
            refreshDashboard(force = true)
        }
        findViewById<Button>(R.id.manage_captures).setOnClickListener {
            startActivity(Intent(this, CapturesActivity::class.java))
        }
        findViewById<Button>(R.id.export_diagnostics).setOnClickListener {
            exportDiagnostics(it as Button)
        }
        findViewById<Button>(R.id.clear_logs).setOnClickListener { clearLogs() }
        // ABOUT sits outside the tabs, so it is reachable from every one of them.
        findViewById<Button>(R.id.about_row).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the permission screen, the folder picker or the installer.
        refreshStorageAccess()
        paths.text = pathsText()
        refreshDashboard()
        // An export that finished after its dialog was gone: say where the zip
        // went, once, then take the note down so it is not said again.
        Diagnostics.lastExport(this)?.let {
            android.widget.Toast.makeText(
                this, getString(R.string.captures_saved_to, it), android.widget.Toast.LENGTH_LONG,
            ).show()
            Diagnostics.clearLastExport(this)
        }
    }

    /**
     * The same report the LOG screen writes: a zip of the logs, the last crash,
     * the device and the settings. Nothing to say about it here, so the note is
     * empty; the export walks every log file, so the button is held down while
     * it is written.
     */
    private fun exportDiagnostics(button: Button) {
        if (exporting) return
        exporting = true
        button.isEnabled = false
        Diagnostics.export(this, "") { done ->
            runOnUiThread {
                exporting = false
                try { button.isEnabled = true } catch (_: Throwable) {}
                if (!isFinishing && !isDestroyed) showReportResult(this, done)
            }
        }
    }

    /**
     * Everything the app writes into its own folder, the rotated (.1) runs and
     * the event logs included, so the next start begins with an empty log.
     * crash-history is kept: a crash deleted here is a crash nobody can report.
     */
    private fun clearLogs() {
        for (name in listOf(
            "game.log", "game.log.1", "launcher.log", "launcher.log.1",
            "events-launcher.txt", "events-launcher.txt.1",
            "events-game.txt", "events-game.txt.1",
        )) {
            try { java.io.File(filesDir, name).delete() } catch (_: Throwable) {}
        }
        try {
            filesDir.listFiles()?.filter { it.isFile && it.name.startsWith("last-crash-") && it.name.endsWith(".txt") }
                ?.forEach { try { it.delete() } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
        // Written after the clear, so the report says the player asked for it
        // rather than looking like the logs went missing on their own.
        EventLog.note(this, "logs.cleared")
        android.widget.Toast.makeText(this, R.string.logs_cleared, android.widget.Toast.LENGTH_SHORT).show()
        refreshDashboard(force = true)
    }

    private fun refreshStorageAccess() {
        val granted = GameFolder.hasAccess(this)
        storageAccess.setText(if (granted) R.string.storage_granted else R.string.storage_not_granted)
        grantStorage.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
    }

    /**
     * Low / Medium / High (DeviceProfile): a button writes its tier's settings and
     * the page is built again, so every slider and switch shows what it set.
     */
    private fun presets() {
        DeviceProfile.ensurePreset(this, prefs)
        val detected = DeviceProfile.detect(this, prefs)
        val specs = detected.specs
        findViewById<TextView>(R.id.preset_detected).text = getString(
            R.string.preset_detected,
            tierName(detected.tier.name), detected.reason,
            specs.gpu.ifEmpty { getString(R.string.preset_gpu_unknown) },
            specs.fastCores, specs.fastestMHz, specs.totalMemoryMB / 1024.0,
        )
        presetLabel = findViewById(R.id.preset_label)
        presetLabel.text = getString(R.string.preset_label, tierName(prefs.preset))
        listOf(
            R.id.preset_low to DeviceProfile.Tier.LOW,
            R.id.preset_medium to DeviceProfile.Tier.MEDIUM,
            R.id.preset_high to DeviceProfile.Tier.HIGH,
        ).forEach { (id, tier) ->
            findViewById<Button>(id).setOnClickListener {
                DeviceProfile.apply(prefs, tier)
                intent.putExtra(EXTRA_TAB, TAB_GRAPHICS)
                recreate()
            }
        }
    }

    private lateinit var presetLabel: TextView


    /** One of a preset's settings moved by hand: it is the player's own now. */
    private fun presetTouched() {
        if (prefs.preset == DeviceProfile.PRESET_CUSTOM) return
        prefs.preset = DeviceProfile.PRESET_CUSTOM
        presetLabel.text = getString(R.string.preset_label, tierName(prefs.preset))
    }

    private fun tierName(name: String): String = getString(
        when (name) {
            DeviceProfile.Tier.LOW.name -> R.string.preset_low
            DeviceProfile.Tier.MEDIUM.name -> R.string.preset_medium
            DeviceProfile.Tier.HIGH.name -> R.string.preset_high
            else -> R.string.preset_custom
        },
    )

    private fun refreshSaves() {
        val saves = SaveBackup.savesDir(this, prefs)
        val packages = SaveBackup.packages(saves)
        savesSummary.text =
            if (packages.isEmpty()) getString(R.string.saves_none, saves.absolutePath)
            else getString(R.string.saves_summary, packages.size, saves.absolutePath, packages.joinToString(", "))
    }

    private fun backupTo(uri: android.net.Uri) {
        val saves = SaveBackup.savesDir(this, prefs)
        Thread {
            val message = try {
                val count = contentResolver.openOutputStream(uri)?.use { SaveBackup.write(saves, it) }
                    ?: throw IllegalStateException("cannot write there")
                getString(R.string.backup_done, count)
            } catch (t: Throwable) {
                getString(R.string.backup_failed, t.message ?: t.javaClass.simpleName)
            }
            runOnUiThread { android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show() }
        }.start()
    }

    /** A file the player picks, or one of the automatic backups, newest first. */
    private fun chooseRestore() {
        val automatic = SaveBackup.automaticBackups(this)
        val names = listOf(getString(R.string.restore_from_file)) +
            automatic.map { getString(R.string.restore_auto_entry, it.name.removePrefix("auto-").removeSuffix(".zip")) }
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_saves)
            .setItems(names.toTypedArray()) { _, which ->
                if (which == 0) openBackup.launch(arrayOf("application/zip", "application/octet-stream"))
                else confirmRestore { automatic[which - 1].inputStream() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRestore(open: () -> java.io.InputStream?) {
        if (SaveBackup.gameRunning(this)) {
            AlertDialog.Builder(this).setMessage(R.string.restore_game_running).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_confirm_title)
            .setMessage(R.string.restore_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.restore) { _, _ ->
                val saves = SaveBackup.savesDir(this, prefs)
                Thread {
                    val message = try {
                        val count = open()?.use { SaveBackup.restore(saves, it) }
                            ?: throw IllegalStateException("cannot read the backup")
                        getString(R.string.restore_done, count)
                    } catch (t: Throwable) {
                        getString(R.string.restore_failed, t.message ?: t.javaClass.simpleName)
                    }
                    runOnUiThread {
                        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                        refreshSaves()
                    }
                }.start()
            }
            .show()
    }

    private fun pathsText(): String =
        if (prefs.gameDirectory.isEmpty()) getString(R.string.paths_none)
        else getString(R.string.paths_line, prefs.gameDirectory)

    /** Custom tab bar: highlight bar on the chosen tab, one page shown. */
    private fun selectTab(index: Int) {
        tabPages.forEachIndexed { i, page ->
            page.visibility = if (i == index) android.view.View.VISIBLE else android.view.View.GONE
        }
        findViewById<android.view.View>(R.id.page_performance).visibility =
            if (index == TAB_GRAPHICS) android.view.View.VISIBLE else android.view.View.GONE
        tabButtons.forEachIndexed { i, button ->
            if (i == index) {
                button.setBackgroundResource(R.drawable.bg_tab_selected)
                button.setTextColor(getColor(R.color.pes_gold))
            } else {
                button.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                button.setTextColor(getColor(R.color.pes_on_dark))
            }
        }
    }

    /** A slider whose label follows it; `onChange` only for the player's own touches. */
    private fun bar(
        barId: Int, labelId: Int, max: Int, initial: Int,
        label: (Int) -> String, onChange: (Int) -> Unit,
    ) {
        val text: TextView = findViewById(labelId)
        val seek: SeekBar = findViewById(barId)
        seek.max = max
        seek.progress = initial.coerceIn(0, max)
        text.text = label(seek.progress)
        seek.setOnSeekBarChangeListener(object : Simple() {
            override fun onProgressChanged(view: SeekBar, value: Int, fromUser: Boolean) {
                text.text = label(value)
                if (fromUser) onChange(value)
            }
        })
    }

    private fun gameBase(): java.io.File = getExternalFilesDir(null) ?: filesDir

    private fun shaderCacheFiles(): List<java.io.File> {
        val base = gameBase()
        return listOf(
            java.io.File(base, "shader_cache.bin"),
            java.io.File(filesDir, "shader_cache.bin"),
            java.io.File(filesDir, "pipeline.cache"),
        )
    }

    /**
     * Cached snapshot first (an instant paint), then one measurement per
     * resume. [force] is for the moments that change the folder itself: a
     * chosen folder, a forgotten one, a cleared cache. The callback is dropped
     * if the screen is gone, so a slow 19 GB walk cannot touch dead views.
     */
    private fun refreshDashboard(force: Boolean = false) {
        PatchesDashboard.cached()?.let(::render)
        val onResult: (PatchesDashboard.Snapshot) -> Unit = { snapshot ->
            if (!isFinishing && !isDestroyed) render(snapshot)
        }
        if (force) PatchesDashboard.refresh(this, onResult)
        else PatchesDashboard.load(this, onResult = onResult)
    }

    private fun render(snapshot: PatchesDashboard.Snapshot) {
        renderPatch(snapshot.state.patch)
        renderEdited(snapshot.state.edited)
        renderPrevious(snapshot.state)
        renderStorage(snapshot)
    }

    /** Before the first snapshot: the measurement has not finished yet. */
    private fun renderMeasuring() {
        rowTitle(patchState, R.string.settings_row_patch, null, R.color.pes_muted)
        patchDetail.text = getString(R.string.storage_measuring)
        rowTitle(editState, R.string.settings_row_edited, null, R.color.pes_muted)
        editDetail.text = getString(R.string.storage_measuring)
        rowTitle(previousState, R.string.settings_row_previous, null, R.color.pes_text)
        previousDetail.text = getString(R.string.storage_measuring)
        storageText.text = getString(R.string.storage_measuring)
    }

    private fun renderPatch(patch: PatchesRepository.PatchData?) {
        if (patch == null) {
            rowTitle(patchState, R.string.settings_row_patch, getString(R.string.patches_status_none), R.color.pes_muted)
            patchDetail.text = getString(R.string.patch_summary_none)
            return
        }
        if (patch.complete) {
            rowTitle(patchState, R.string.settings_row_patch, getString(R.string.patches_status_installed), R.color.pes_blue)
        } else {
            rowTitle(patchState, R.string.settings_row_patch, getString(R.string.patches_status_incomplete), R.color.pes_red)
        }
        val meta = listOf(patch.name, patch.sourceLabel, patch.date)
            .filter { it.isNotBlank() }
            .joinToString(" \u00b7 ")
        val stats = if (patch.dpBytes > 0) {
            getString(
                R.string.patch_summary_stats_dp,
                patch.cpk, PatchFormat.formatBytes(patch.bytes),
                PatchFormat.formatBytes(patch.dpBytes), patch.entries,
            )
        } else {
            getString(R.string.patch_summary_stats_no_dp, patch.cpk, PatchFormat.formatBytes(patch.bytes))
        }
        // The provenance note never claims a source the app cannot know.
        val note = when (patch.provenance) {
            PatchesRepository.Provenance.HAND -> getString(R.string.patch_summary_hand)
            PatchesRepository.Provenance.STALE -> getString(R.string.patch_summary_stale)
            PatchesRepository.Provenance.APP -> null
        }
        patchDetail.text = listOfNotNull(meta, stats, note).joinToString("\n")
    }

    private fun renderEdited(edited: PatchesRepository.EditedData?) {
        if (edited == null) {
            rowTitle(editState, R.string.settings_row_edited, getString(R.string.patches_status_none), R.color.pes_muted)
            editDetail.text = getString(R.string.edit_summary_none)
            return
        }
        if (edited.complete) {
            rowTitle(editState, R.string.settings_row_edited, getString(R.string.patches_status_installed), R.color.pes_blue)
        } else {
            rowTitle(editState, R.string.settings_row_edited, getString(R.string.patches_status_incomplete), R.color.pes_red)
        }
        editDetail.text = getString(
            R.string.edit_summary_stats,
            edited.name, edited.fileName, edited.version,
            PatchFormat.formatCount(edited.players.toLong()), edited.teams,
            PatchFormat.formatBytes(edited.bytes),
        )
    }

    private fun renderPrevious(state: PatchesRepository.State) {
        rowTitle(previousState, R.string.settings_row_previous, null, R.color.pes_text)
        val lines = mutableListOf<String>()
        state.patchBackup?.let {
            lines.add(
                getString(
                    R.string.previous_patch_line,
                    it.name, it.date, PatchFormat.formatBytes(it.bytes),
                ),
            )
        }
        state.editBackup?.let {
            lines.add(
                getString(
                    R.string.previous_edit_line,
                    it.name, it.date, PatchFormat.formatBytes(it.bytes),
                ),
            )
        }
        previousDetail.text =
            if (lines.isEmpty()) getString(R.string.previous_none) else lines.joinToString("\n")
    }

    private fun renderStorage(snapshot: PatchesDashboard.Snapshot) {
        val facts = snapshot.storage
        if (facts == null) {
            storageText.text = getString(R.string.storage_unavailable)
            return
        }
        val appData = SlotSize.safeAdd(
            facts.shaders.bytes,
            SlotSize.safeAdd(facts.logs.bytes, facts.captures.bytes),
        )
        val rows = listOf(
            R.string.storage_row_game to facts.game.bytes,
            R.string.storage_row_patch_data to facts.dlc.bytes,
            R.string.storage_row_saves to facts.saves.bytes,
            // The game recreates SYSTEM00000000 on every boot; the size is a
            // fact, who last wrote it is not claimed here.
            R.string.storage_row_system to snapshot.state.systemSaveBytes,
            R.string.storage_row_save_backups to facts.saveBackups.bytes,
            R.string.storage_row_patch_backups to facts.patchBackups.bytes,
            R.string.storage_row_staging to facts.staging.bytes,
            R.string.storage_row_app_data to appData,
            R.string.storage_row_free to facts.freeBytes,
        )
        storageText.text = rows.joinToString("\n") { (label, bytes) ->
            getString(R.string.storage_line, getString(label), PatchFormat.formatBytes(bytes))
        }
    }

    private fun rowTitle(view: TextView, label: Int, status: String?, color: Int) {
        view.text = if (status == null) {
            getString(label)
        } else {
            getString(R.string.settings_row_title, getString(label), status)
        }
        view.setTextColor(getColor(color))
    }

    private fun textureText(step: Int): String =
        if (step == 0) getString(R.string.texture_budget_auto)
        else getString(R.string.texture_budget, step * 128)

    private fun switch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val view: SwitchCompat = findViewById(id)
        view.isChecked = initial
        view.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    /** The two methods nobody ever needs, out of the way. */
    private abstract class Simple : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(bar: SeekBar) = Unit
        override fun onStopTrackingTouch(bar: SeekBar) = Unit
    }
}
