package com.pes2017.recomp

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The launcher: is the game installed, which driver will be used, and the
 * button that starts it. It runs in its own process, separate from the game
 * (see the manifest), so that ending a run -- which means killing the
 * game's process -- leaves this one standing.
 *
 * It is written so that it cannot fail to appear. The native library is not
 * needed to draw this screen and is loaded in a guarded step afterwards: a
 * library that will not load leaves a launcher that says so, with the log
 * and the last crash still reachable, rather than an app that vanishes.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var footer: TextView
    private lateinit var play: Button
    private lateinit var patches: Button
    private lateinit var quitButton: Button
    private lateinit var detailTitle: TextView
    private lateinit var detailText: TextView
    private lateinit var versionTag: TextView
    private lateinit var checkUpdate: Button
    private lateinit var directLaunchSwitch: androidx.appcompat.widget.SwitchCompat
    private var menuScroll: ScrollView? = null
    private var detectedSource: GameSource? = null
    private var detectedSources: List<GameSource> = emptyList()
    private var nativeReady = false
    private var hasAutoLaunched = false

    /** The match asked to be started again with new settings (QuickGraphics). */
    private var relaunchRequested = false
    private var pendingInstallId: Long = -1
    private var pendingUpdateUrl: String? = null
    private var pendingUpdateTag: String = ""
    private var pendingUpdateSha: String = ""
    private var pendingDownloadName: String = ""

    /** One release check at a time; a second tap waits for the first answer. */
    private val updateCheckRunning = AtomicBoolean(false)

    /** A manual tap arrived while a check was in flight: answer with its result. */
    @Volatile
    private var manualCheckQueued = false

    /** The tag the entry's badge currently stands for, restored on a failed check. */
    private var shownUpdateTag: String = ""

    /**
     * A diagnostics report is being written. The export walks every log file, so
     * the button that started it is held down until it answers: two taps must not
     * write two zips over each other.
     */
    private var exporting = false

    /**
     * Choosing the game folder lives in GameFolderActivity: the picker, the
     * all-files permission and the check of default.xex. It comes back having
     * written Prefs.gameDirectory, so the only job here is to show the result.
     */
    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val path = result.data?.getStringExtra(GameFolderActivity.EXTRA_PATH)
        if (!path.isNullOrEmpty()) {
            showDetail(getString(R.string.folder_title), getString(R.string.folder_set, path))
        }
        refresh()
    }

    private val downloadDone = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != pendingInstallId) return
            pendingInstallId = -1
            installDownloadedApk(id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBars()
        prefs = Prefs(this)
        // A rotation while an update download or the launch of the game is in
        // flight: the new instance must carry on rather than start over.
        if (savedInstanceState != null) {
            pendingInstallId = savedInstanceState.getLong(STATE_INSTALL_ID, -1)
            pendingUpdateUrl = savedInstanceState.getString(STATE_UPDATE_URL)
            pendingUpdateTag = savedInstanceState.getString(STATE_UPDATE_TAG) ?: ""
            pendingUpdateSha = savedInstanceState.getString(STATE_UPDATE_SHA) ?: ""
            pendingDownloadName = savedInstanceState.getString(STATE_DOWNLOAD_NAME) ?: ""
            hasAutoLaunched = savedInstanceState.getBoolean(STATE_AUTO_LAUNCHED, false)
        }
        relaunchRequested = QuickGraphics.takeRelaunch(intent, prefs)
        // The performance preset this phone starts from, once (DeviceProfile).
        try { DeviceProfile.ensurePreset(this, prefs) } catch (_: Throwable) {}

        status = findViewById(R.id.status)
        footer = findViewById(R.id.footer)
        play = findViewById(R.id.play)
        patches = findViewById(R.id.patches)
        quitButton = findViewById(R.id.entry_quit)
        detailTitle = findViewById(R.id.detail_title)
        detailText = findViewById(R.id.detail_text)
        versionTag = findViewById(R.id.version_tag)
        directLaunchSwitch = findViewById(R.id.direct_launch_switch)
        // Present only in the portrait layout, where the column scrolls.
        menuScroll = findViewById(R.id.menu_scroll)
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            val name = info.versionName ?: ""
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
            else info.versionCode
            versionTag.text = getString(R.string.version_tag, name, code)
        } catch (_: Throwable) {
        }

        directLaunchSwitch.isChecked = prefs.autoLaunch
        directLaunchSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoLaunch = isChecked
        }

        play.setOnClickListener {
            select(play)
            showDetail(getString(R.string.play), status.text.toString())
            val gameDirectory = prefs.gameDirectory
            val installed = gameDirectory.isNotEmpty() && safely(false) {
                NativeBridge.nativeGameInstalled(gameDirectory)
            }
            if (installed) {
                startGame()
            } else if (detectedSource?.isFolder == true) {
                // Already extracted: verified where it lies, nothing copied.
                folderPicker.launch(
                    Intent(this, GameFolderActivity::class.java)
                        .putExtra(GameFolderActivity.EXTRA_START_PATH, detectedSource!!.file.absolutePath),
                )
            } else if (detectedSource != null) {
                // A disc image (or a folder to copy from) the launcher found:
                // the installer opens on it and starts by itself.
                installGame()
            } else {
                // Nothing to play yet: the natural next step is choosing the folder.
                selectGameFolder()
            }
        }
        patches.setOnClickListener {
            select(patches)
            showDetail(getString(R.string.entry_patches), getString(R.string.patches_menu_hint))
            startActivity(Intent(this, PatchesActivity::class.java))
        }
        findViewById<Button>(R.id.driver).setOnClickListener {
            select(it as Button)
            showDetail(getString(R.string.graphics_driver), getString(R.string.driver_hint))
            startActivity(Intent(this, DriverActivity::class.java))
        }
        findViewById<Button>(R.id.settings).setOnClickListener {
            select(it as Button)
            showDetail(getString(R.string.entry_settings), getString(R.string.settings_hint))
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.log).setOnClickListener {
            select(it as Button)
            showDetail(getString(R.string.view_log), "")
            showLog()
        }
        quitButton.setOnClickListener {
            select(quitButton)
            finishAndRemoveTask()
        }
        checkUpdate = findViewById(R.id.check_update)
        checkUpdate.setOnClickListener {
            select(checkUpdate)
            showDetail(getString(R.string.check_for_updates), getString(R.string.update_checking))
            checkForUpdates(manual = true)
        }

        // Independent links for both developers.
        findViewById<android.view.View>(R.id.credit_xclipse)?.setOnClickListener {
            openPage("https://github.com/netherfiles")
        }
        findViewById<android.view.View>(R.id.credit_original)?.setOnClickListener {
            openPage("https://github.com/sharifhidayatulloh")
        }

        // The cursor starts on KICK OFF, and the strip at the foot scrolls
        // (a marquee runs only on a selected view). A restored selection is
        // the same programmatic move: no detail auto-scroll for either.
        select(play, reveal = false)
        footer.isSelected = true
        val restored = savedInstanceState?.getInt(STATE_SELECTED, 0) ?: 0
        if (restored != 0 && restored != play.id) findViewById<Button>(restored)?.let { select(it, reveal = false) }

        // Whatever killed the app last time, shown before anything else is
        // attempted -- it may well be about to happen again.
        val crash = Trail.lastCrash(this)
        if (crash.isNotEmpty()) showLastCrash(crash)

        // The library is loaded after the screen exists, so a failure here
        // is reported rather than fatal. The breadcrumb is what identifies a
        // native crash inside the load itself, which no handler can catch.
        Trail.note(this, "launcher: loading the runtime library")
        nativeReady = NativeBridge.load()
        if (nativeReady) {
            Trail.note(this, "launcher: library loaded, initialising")
            try {
                NativeBridge.nativeInit(
                    filesDir.absolutePath,
                    (getExternalFilesDir(null) ?: filesDir).absolutePath,
                    cacheDir.absolutePath,
                    applicationInfo.nativeLibraryDir,
                    null,
                )
            } catch (error: Throwable) {
                nativeReady = false
                NativeBridge.noteInitFailure(error.message ?: error.toString())
            }
        }
        Trail.clear(this)
        ContextCompat.registerReceiver(
            this, downloadDone,
            android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        autoUpdateCheck()
        predownloadDriver()
    }    /**
     * First run on an Adreno phone: fetch the matching Turnip build in the
     * background so it is in the driver list. It is only stored; choosing it
     * is the player's call (Graphics driver).
     */
    private fun predownloadDriver() {
        if (prefs.driverAutoTried) return
        thread(name = "driver-predownload", isDaemon = true) {
            val renderer = try { DeviceProfile.detect(this, prefs).specs.gpu } catch (_: Throwable) { "" }
            if (!DriverDownloader.suitable(renderer)) {
                prefs.driverAutoTried = true
                return@thread
            }
            val store = DriverStore(this)
            if (store.list().isNotEmpty()) {
                prefs.driverAutoTried = true
                return@thread
            }
            // Offline now is not a verdict: try again next launch.
            val result = DriverDownloader.download(this, renderer, store)
            if (result is DriverDownloader.Result.Done) prefs.driverAutoTried = true
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadDone)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (QuickGraphics.takeRelaunch(intent, prefs)) relaunchRequested = true
    }

    override fun onResume() {
        super.onResume()
        recoverPatchesIfIdle()
        refresh()
        refreshDetail()
        // The download can finish while this activity is not listening (a
        // killed process loses the broadcast): ask the manager directly.
        reconcilePendingDownload()
        // Back from the "install unknown apps" screen: the download the player
        // already asked for starts now, with the hash to verify it against.
        val waiting = pendingUpdateUrl
        if (waiting != null && packageManager.canRequestPackageInstalls()) {
            pendingUpdateUrl = null
            startUpdateDownload(waiting, pendingUpdateTag, pendingUpdateSha)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_INSTALL_ID, pendingInstallId)
        outState.putString(STATE_UPDATE_URL, pendingUpdateUrl)
        outState.putString(STATE_UPDATE_TAG, pendingUpdateTag)
        outState.putString(STATE_UPDATE_SHA, pendingUpdateSha)
        outState.putString(STATE_DOWNLOAD_NAME, pendingDownloadName)
        outState.putBoolean(STATE_AUTO_LAUNCHED, hasAutoLaunched)
        outState.putInt(STATE_SELECTED, selectedEntry?.id ?: 0)
    }

    /**
     * The detail panel of the entry the cursor is on, drawn again so that a
     * return from Settings or PATCHES does not leave the old figures there.
     * KICK OFF's detail is the menu's own status line.
     */
    private fun refreshDetail() {
        if (selectedEntry === play) showDetail(getString(R.string.play), status.text.toString())
    }

    /**
     * The Application skips journal recovery while `:game` is alive, because a
     * running game may be between RotatePackage's two renames. When the player
     * comes back to the launcher, that reason is gone; run the recovery now so
     * a stale journal does not keep Play and PATCHES disabled until restart.
     */
    private fun recoverPatchesIfIdle() {
        val context = applicationContext
        thread(name = "patch-recover", isDaemon = true) {
            runCatching { PatchesRepository.recover(context) }
        }
    }

    // ---- choosing the game folder ------------------------------------------------

    /**
     * Select Game Folder: the system picker, the all-files permission and the
     * check of default.xex all live in GameFolderActivity, because Settings
     * needs the same thing. It comes back having written Prefs.gameDirectory;
     * the folder picker result shows the outcome.
     */
    private fun selectGameFolder() {
        showDetail(getString(R.string.entry_select_folder), getString(R.string.select_folder_hint))
        try {
            folderPicker.launch(Intent(this, GameFolderActivity::class.java))
        } catch (_: Throwable) {
            message(getString(R.string.folder_unreadable_path))
        }
    }

    private fun message(text: String) {
        AlertDialog.Builder(this).setMessage(text).setPositiveButton(R.string.close, null).show()
    }

    // ---- the menu's state ------------------------------------------------------------

    private fun refresh() {
        if (!nativeReady) {
            status.text = getString(
                R.string.status_library_missing,
                NativeBridge.loadError() ?: getString(R.string.unknown_reason),
            )
            play.isEnabled = false
            footer.text = getString(
                R.string.footer, device(), getString(R.string.driver_system),
                BuildConfig.BUILD_SHA, BuildConfig.BUILD_STAMP,
            )
            return
        }

        // The chosen folder first; when there is none, or it has gone, look for
        // the game ourselves. Only when that finds nothing does the player
        // have to point at it.
        var gameDirectory = prefs.gameDirectory
        var autoFound = false
        if (gameDirectory.isEmpty() || !safely(false) { NativeBridge.nativeGameInstalled(gameDirectory) }) {
            val found = GameFolder.autoDetect(this)?.absolutePath
            if (found != null && safely(false) { NativeBridge.nativeGameInstalled(found) }) {
                gameDirectory = found
                prefs.gameDirectory = found
                autoFound = true
            }
        }
        val installed = gameDirectory.isNotEmpty() && safely(false) {
            NativeBridge.nativeGameInstalled(gameDirectory)
        }
        val installing = PatchesRepository.isInstalling(this)

        if (installed) {
            play.isEnabled = !installing
            play.text = getString(R.string.play)
            status.text = if (installing) {
                getString(R.string.patch_import_busy)
            } else {
                getString(if (autoFound) R.string.status_ready_auto else R.string.status_ready, gameDirectory)
            }
            directLaunchSwitch.visibility = android.view.View.VISIBLE

            if (relaunchRequested) {
                relaunchRequested = false
                hasAutoLaunched = true
                startGameWhenPreviousRunIsGone()
                return
            }
            if (prefs.autoLaunch && !hasAutoLaunched) {
                hasAutoLaunched = true
                startGame()
                return
            }
        } else {
            // Whatever disc images and extracted folders can be seen. The one the
            // player last picked wins; otherwise the likeliest (GameSources.find).
            detectedSources = GameSources.find(this, File(gameDirectory.ifEmpty { "/nonexistent" }))
            val chosen = detectedSources.firstOrNull { it.file.absolutePath == prefs.lastSource }
                ?: detectedSources.firstOrNull()
            detectedSource = chosen
            directLaunchSwitch.visibility = android.view.View.GONE

            if (gameDirectory.isNotEmpty()) {
                // A folder was chosen but is no longer usable: say why.
                play.isEnabled = false
                play.text = getString(R.string.play)
                status.text = getString(
                    if (!GameFolder.hasAccess(this) && !gameDirectory.startsWith(filesDirParent()))
                        R.string.status_needs_access else R.string.status_folder_missing,
                    gameDirectory,
                )
            } else if (chosen != null) {
                play.isEnabled = true
                play.text = getString(if (chosen.isFolder) R.string.play else R.string.install_and_play)
                val more = if (detectedSources.size > 1) "\n${detectedSources.size - 1} other source(s) found." else ""
                val ready = if (chosen.isFolder) "Plays from this folder, nothing copied." else "Ready for one-click setup."
                status.text = "Game found (${chosen.kind}):\n${chosen.file.name}\n$ready$more"
            } else {
                play.isEnabled = true
                play.text = getString(R.string.play)
                status.text = getString(R.string.status_not_installed)
            }
        }

        applyDriverChoice()
        footer.text = getString(
            R.string.footer, device(), currentDriverName(),
            BuildConfig.BUILD_SHA, BuildConfig.BUILD_STAMP,
        )
    }

    private fun filesDirParent(): String = (getExternalFilesDir(null) ?: filesDir).absolutePath

    /**
     * INSTALL GAME, from KICK OFF: the source the launcher found. When the phone
     * offers more than one, [chooseSource] asks which. The installer opens on
     * the source and starts by itself; on the way back, onResume redraws the menu.
     */
    private fun installGame() {
        showDetail(getString(R.string.install_game), getString(R.string.install_hint))
        if (detectedSources.size > 1) {
            chooseSource()
            return
        }
        val source = detectedSource
        if (source == null) {
            startActivity(Intent(this, InstallActivity::class.java))
        } else {
            installFrom(source)
        }
    }

    /** The installer, already pointed at the source and starting by itself. */
    private fun installFrom(source: GameSource) {
        startActivity(
            Intent(this, InstallActivity::class.java).apply {
                putExtra(InstallActivity.EXTRA_INITIAL_PATH, source.file.absolutePath)
                putExtra(InstallActivity.EXTRA_AUTO_START, true)
            },
        )
    }

    /** Pick which disc image or folder to install from, or go and look for another. */
    private fun chooseSource() {
        val names = detectedSources.map {
            "${it.file.name}  (${it.kind}, ${android.text.format.Formatter.formatFileSize(this, it.bytes)})"
        } + getString(R.string.choose_source)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.install_game)
            .setItems(names.toTypedArray()) { _, which ->
                if (which < detectedSources.size) {
                    val chosen = detectedSources[which]
                    prefs.lastSource = chosen.file.absolutePath
                    detectedSource = chosen
                    installFrom(chosen)
                } else {
                    startActivity(Intent(this, InstallActivity::class.java))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun device(): String {
        val vkVersion = packageManager.systemAvailableFeatures
            .firstOrNull { it.name == "android.hardware.vulkan.version" }?.version ?: 0
        val vkMajor = vkVersion shr 22
        val vkMinor = (vkVersion shr 12) and 0x3ff
        val vkStr = if (vkVersion > 0) "Vulkan $vkMajor.$vkMinor" else "No Vulkan"
        return "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} ($vkStr), " +
            "Android ${android.os.Build.VERSION.RELEASE}"
    }

    /** A native call that must not take the launcher with it if it fails. */
    private fun <T> safely(fallback: T, call: () -> T): T = try {
        call()
    } catch (_: Throwable) {
        fallback
    }

    private fun applyDriverChoice() {
        val directory = prefs.driverDirectory
        val library = prefs.driverLibrary
        if (directory.isNotEmpty() && library.isNotEmpty() && File(directory, library).exists()) {
            safely(Unit) { NativeBridge.nativeSelectDriver(directory, library) }
        } else {
            // A driver that was deleted from underneath the setting.
            if (directory.isNotEmpty()) {
                prefs.driverDirectory = ""
                prefs.driverLibrary = ""
            }
            safely(Unit) { NativeBridge.nativeSelectDriver("", "") }
        }
    }

    private fun currentDriverName(): String {
        val directory = prefs.driverDirectory
        if (directory.isEmpty()) return getString(R.string.driver_system)
        return File(directory).name
    }

    /**
     * The run that asked to be restarted is being killed as this is called: a
     * start now would find its activity still there (it is single task), bring
     * it forward and lose it a moment later. Wait for its process to be gone.
     */
    private fun startGameWhenPreviousRunIsGone(attempt: Int = 0) {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val alive = manager.runningAppProcesses?.any { it.processName == "$packageName:game" } == true
        if (alive && attempt < 40) {
            window.decorView.postDelayed({ startGameWhenPreviousRunIsGone(attempt + 1) }, 100)
        } else {
            startGame()
        }
    }

    private fun startGame() {
        if (!nativeReady) return
        if (PatchesRepository.isInstalling(this)) {
            message(getString(R.string.patch_import_busy))
            return
        }
        // A copy of the saves before every run (SaveBackup), taken before the
        // game's process exists, so nothing is writing them while they are read.
        val context = applicationContext
        thread(name = "save-backup", isDaemon = true) {
            try { SaveBackup.automatic(context, prefs) } catch (_: Throwable) {}
        }.join(3000)
        GameActivity.start(this)
    }

    private fun showDetail(title: String, text: String) {
        detailTitle.text = title
        detailText.text = text
    }

    private var selectedEntry: Button? = null
    private var selectedColors: android.content.res.ColorStateList? = null

    /**
     * PES 6's cursor on the chosen entry: the ring beside it, the band under it,
     * its text in gold. The one before gets its own colour back. In portrait a
     * tap on an entry below the fold brings the detail panel into view.
     */
    private fun select(entry: Button, reveal: Boolean = true) {
        selectedEntry?.let { previous ->
            previous.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            previous.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_menu_cursor_space, 0, 0, 0)
            selectedColors?.let { previous.setTextColor(it) }
        }
        selectedEntry = entry
        selectedColors = entry.textColors
        entry.setBackgroundResource(R.drawable.bg_entry_selected)
        entry.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_menu_cursor, 0, 0, 0)
        entry.setTextColor(getColor(R.color.pes_gold))
        if (reveal) revealDetailPanel()
    }

    /**
     * Portrait only, and only when the panel is off screen already: scroll the
     * column so the chosen entry's detail panel follows the cursor. A
     * landscape layout has no menu_scroll, so this is a no-op there.
     */
    private fun revealDetailPanel() {
        val scroller = menuScroll ?: return
        val panel = (detailTitle.parent as? android.view.View) ?: detailTitle
        scroller.post {
            if (isFinishing || isDestroyed) return@post
            val visible = android.graphics.Rect()
            if (!panel.getGlobalVisibleRect(visible)) {
                scroller.smoothScrollTo(0, panel.top - scroller.paddingTop)
            }
        }
    }

    // ---- the update check -------------------------------------------------------

    /**
     * The automatic check at launch: it answers from the six-hour cache when it
     * can, so a rotation shows the badge again without asking GitHub, and it
     * asks at most once per process. It never opens a dialog; a newer release
     * is a line on the CHECK FOR UPDATES entry.
     */
    private fun autoUpdateCheck() {
        checkForUpdates(manual = false)
    }

    /**
     * Reads the release feed, or answers from the six-hour cache when this is
     * not the player asking. [manual] is the entry's own click: it ignores the
     * cache and the skipped version, and always ends with an answer -- the
     * update dialog, the newest-build toast, or the failure toast.
     *
     * A tap while a check is in flight is not dropped: it is queued, the entry
     * says a check is running, and the result now on its way answers it. Every
     * end of a check puts an answer in the detail panel, so it cannot stay on
     * "Checking...".
     */
    private fun checkForUpdates(manual: Boolean) {
        val installed = installedCode()
        val installedVersion = installedName()
        if (!manual) {
            val last = prefs.lastUpdateCheckAt
            if (last > 0 && System.currentTimeMillis() - last in 0 until UPDATE_CACHE_MS) {
                showUpdateBadge(cachedUpdateTag(installed, installedVersion))
                return
            }
            // A failed check left no cache to answer from: the process asked
            // once already and does not ask again from a recreated activity.
            if (autoCheckedThisProcess) return
        }
        if (!updateCheckRunning.compareAndSet(false, true)) {
            if (manual) {
                // The running check's result will be presented as the answer.
                manualCheckQueued = true
                checkUpdate.text = getString(R.string.update_checking)
                Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
            }
            return
        }
        manualCheckQueued = false
        autoCheckedThisProcess = true
        if (manual) checkUpdate.text = getString(R.string.update_checking)
        thread(name = "update-check", isDaemon = true) {
            var release: UpdateFeed.Release? = null
            var failed = false
            try {
                if (RELEASES_API.isEmpty()) throw IllegalStateException("no release feed")
                val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "pes2018-recomp/$installedVersion")
                }
                try {
                    if (connection.responseCode != 200) {
                        throw IllegalStateException("HTTP ${connection.responseCode}")
                    }
                    val releases = UpdateFeed.parseReleases(
                        connection.inputStream.bufferedReader().readText(),
                    )
                    if (releases.isEmpty()) throw IllegalStateException("empty feed")
                    release = UpdateFeed.pickUpdate(
                        releases, installed, installedVersion,
                        UpdateFeed.allowPrerelease(installedVersion),
                    )
                } finally {
                    connection.disconnect()
                }
                // Only an answer that was read is cached; a failed check is not,
                // so the next launch asks again (never this one).
                prefs.lastUpdateCheckAt = System.currentTimeMillis()
                prefs.lastUpdateTag = release?.tag ?: ""
                prefs.lastUpdateCode = release?.code ?: 0
            } catch (_: Throwable) {
                failed = true
            }
            updateCheckRunning.set(false)
            val queued = manualCheckQueued
            manualCheckQueued = false
            val found = release
            val answer = manual || queued
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (failed) {
                    // The badge is whatever it was before this check: a failed
                    // check is not evidence that the cached answer went away.
                    showUpdateBadge(shownUpdateTag)
                    showUpdateDetail(getString(R.string.update_check_failed))
                    if (answer) {
                        Toast.makeText(this, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                    }
                    return@runOnUiThread
                }
                if (found != null) {
                    showUpdateBadge(found.tag)
                    val detail = if (found.code > 0) {
                        getString(R.string.update_available_entry, found.tag) + "\n\n" +
                            getString(R.string.update_message, installed, found.code)
                    } else {
                        getString(R.string.update_available_entry, found.tag) + "\n\n" +
                            getString(R.string.update_no_version)
                    }
                    showUpdateDetail(detail)
                    if (answer) showUpdateDialog(found, installed)
                } else {
                    showUpdateBadge("")
                    showUpdateDetail(getString(R.string.update_up_to_date, installed))
                    if (answer) {
                        Toast.makeText(
                            this, getString(R.string.update_up_to_date, installed), Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    /** The detail panel follows the check only while CHECK FOR UPDATES is selected. */
    private fun showUpdateDetail(text: String) {
        if (selectedEntry === checkUpdate) {
            showDetail(getString(R.string.check_for_updates), text)
        }
    }

    /** The entry says a newer release exists, unless it is the skipped one. */
    private fun showUpdateBadge(tag: String) {
        shownUpdateTag = tag
        checkUpdate.text = if (tag.isNotEmpty() && tag != prefs.seenUpdateTag) {
            getString(R.string.update_available_entry, tag)
        } else {
            getString(R.string.check_for_updates)
        }
    }

    /**
     * The tag the last check pointed at, if it is still newer than what this
     * phone runs: after the update is installed, the cached answer is stale
     * for up to six hours and must not keep offering the build now installed.
     * A name-only answer is judged with the feed's own version rules, so it is
     * never compared as a plain string.
     */
    private fun cachedUpdateTag(installed: Int, installedVersion: String): String {
        val tag = prefs.lastUpdateTag
        if (tag.isEmpty()) return ""
        val code = prefs.lastUpdateCode
        if (code > 0) return if (code > installed) tag else ""
        return if (UpdateFeed.isNewerName(installedVersion, tag)) tag else ""
    }    /** The installed build number, or 0 if the package manager will not say. */
    private fun installedCode(): Int = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
        else info.versionCode
    } catch (_: Throwable) {
        0
    }

    private fun installedName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Throwable) {
        ""
    }

    /**
     * What the release offers, in the player's words: the build it carries, a
     * test-build warning, the hash the download is checked against, and the
     * notes with their markdown taken out. A release whose body names no build
     * number cannot be compared, so it is offered as a page to open only.
     */
    private fun showUpdateDialog(release: UpdateFeed.Release, installed: Int) {
        if (isFinishing || isDestroyed) return
        val sections = mutableListOf<String>()
        if (release.code > 0) sections += getString(R.string.update_message, installed, release.code)
        else sections += getString(R.string.update_no_version)
        if (release.prerelease) sections += getString(R.string.update_prerelease_warning)
        if (release.sha256.isNotEmpty()) sections += getString(R.string.update_sha, release.sha256)
        val notes = cleanNotes(release.notes)
        if (notes.isNotEmpty()) sections += notes

        val body = TextView(this)
        body.setPadding(32, 8, 32, 8)
        body.textSize = 12f
        body.text = sections.joinToString("\n\n")
        val scroll = ScrollView(this)
        scroll.addView(body)

        val page = release.pageUrl.ifEmpty { RELEASES_PAGE }
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title, release.tag))
            .setView(scroll)
        if (release.code > 0) {
            if (release.apkUrl.isNotEmpty()) {
                builder.setPositiveButton(R.string.update_download) { _, _ ->
                    downloadAndInstall(release)
                }
            } else {
                builder.setPositiveButton(R.string.update_open_page) { _, _ -> openPage(page) }
            }
            builder.setNeutralButton(R.string.skip_version) { _, _ ->
                prefs.seenUpdateTag = release.tag
                showUpdateBadge(release.tag)
            }
        } else {
            builder.setPositiveButton(R.string.update_open_page) { _, _ -> openPage(page) }
        }
        builder.setNegativeButton(R.string.cancel, null).show()
    }

    /**
     * The release notes as text: markdown tables dropped, headings unhashed,
     * bold markers gone, runs of blank lines closed up, and the whole thing
     * cut where a dialog can still scroll through it.
     */
    private fun cleanNotes(notes: String): String = notes.lineSequence()
        .map { it.trim() }
        .filterNot { it.startsWith("|") }
        .map { it.trimStart('#').trim().replace("**", "").trim() }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
        .take(1200)

    private fun openPage(page: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)))
        } catch (_: Throwable) {
        }
    }

    /**
     * The APK the player asked for: still the repository's own download path,
     * over https, nothing else. The permission screen is asked for first when
     * the app may not install packages; the URL is kept so onResume starts the
     * download the player already agreed to.
     */
    private fun downloadAndInstall(release: UpdateFeed.Release) {
        val page = release.pageUrl.ifEmpty { RELEASES_PAGE }
        val apkUrl = release.apkUrl
        if (!isReleaseDownload(apkUrl)) {
            openPage(page)
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            pendingUpdateUrl = apkUrl
            pendingUpdateTag = release.tag
            pendingUpdateSha = release.sha256
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    )
                )
            } catch (_: Throwable) {
                pendingUpdateUrl = null
                pendingUpdateTag = ""
                pendingUpdateSha = ""
                openPage(page)
            }
            return
        }
        startUpdateDownload(apkUrl, release.tag, release.sha256)
    }

    /** github.com, https, and this repository's release download folder only. */
    private fun isReleaseDownload(url: String): Boolean = try {
        val parsed = java.net.URI(url)
        parsed.scheme.equals("https", ignoreCase = true) &&
            parsed.host.equals("github.com", ignoreCase = true) &&
            (parsed.path ?: "").startsWith("/sharifhidayatulloh/pes2018-recomp/releases/download/")
    } catch (_: Throwable) {
        false
    }

    private fun startUpdateDownload(apkUrl: String, tag: String, sha256: String) {
        if (!isReleaseDownload(apkUrl)) {
            openPage(RELEASES_PAGE)
            return
        }
        try {
            val name = "PES2018-recomp-" + sanitizedTag(tag) + ".apk"
            // The manager refuses a destination that exists, so a retry of the
            // same version first drops the file the last attempt left.
            try {
                File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name).delete()
            } catch (_: Throwable) {
            }
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("PES 2018 update")
                .setDescription("Downloading APK")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
                .setAllowedOverMetered(true)
            pendingInstallId = manager.enqueue(request)
            pendingUpdateSha = sha256
            pendingDownloadName = name
            Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            openPage(RELEASES_PAGE)
        }
    }

    private fun sanitizedTag(tag: String): String =
        tag.replace(Regex("[^A-Za-z0-9._-]"), "-").ifEmpty { "update" }

    /**
     * The end of a download can be missed: the broadcast is not kept for a
     * process that was killed, and a launcher resumed later would otherwise
     * wait forever. Ask the manager itself; a finished file takes the same
     * hash-check path as the broadcast, and a failed one is cleaned up. The
     * expected hash is left in place until the result is known.
     */
    private fun reconcilePendingDownload() {
        val id = pendingInstallId
        if (id < 0) return
        val manager = getSystemService(DOWNLOAD_SERVICE) as? DownloadManager ?: return
        var status = -1
        try {
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (column >= 0) status = cursor.getInt(column)
                }
            }
        } catch (_: Throwable) {
            return
        }
        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                pendingInstallId = -1
                installDownloadedApk(id)
            }
            DownloadManager.STATUS_FAILED -> {
                pendingInstallId = -1
                discardDownload(manager, id)
                pendingDownloadName = ""
                pendingUpdateSha = ""
                Toast.makeText(this, R.string.update_failed, Toast.LENGTH_LONG).show()
            }
            // PENDING / RUNNING / PAUSED: the broadcast still brings the end.
        }
    }

    /**
     * The download the player started has finished: check it against the
     * release's own SHA-256 before anything is offered to the installer, on a
     * thread because it reads the whole file. A hash that does not match is a
     * file that is thrown away, never installed.
     */
    private fun installDownloadedApk(id: Long) {
        val expected = pendingUpdateSha
        pendingUpdateSha = ""
        if (expected.isEmpty()) {
            offerInstall(id)
            return
        }
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        thread(name = "update-verify", isDaemon = true) {
            val actual = try {
                sha256Of(manager, id)
            } catch (_: Throwable) {
                ""
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (actual.equals(expected, ignoreCase = true)) {
                    offerInstall(id)
                } else {
                    discardDownload(manager, id)
                    AlertDialog.Builder(this)
                        .setMessage(R.string.update_failed)
                        .setPositiveButton(R.string.open_page) { _, _ -> openPage(RELEASES_PAGE) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    /** The installed file's SHA-256, from the URI the manager hands out. */
    private fun sha256Of(manager: DownloadManager, id: Long): String {
        val uri = manager.getUriForDownloadedFile(id) ?: return ""
        val digest = MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        } ?: return ""
        return digest.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    /** A file that failed its check: removed from Downloads and from the list. */
    private fun discardDownload(manager: DownloadManager, id: Long) {
        try {
            manager.remove(id)
        } catch (_: Throwable) {
        }
        if (pendingDownloadName.isEmpty()) return
        try {
            File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), pendingDownloadName).delete()
        } catch (_: Throwable) {
        }
    }

    /** Hands the file to the system installer, which asks its own questions. */
    private fun offerInstall(id: Long) {
        if (isFinishing || isDestroyed) return
        try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val uri = manager.getUriForDownloadedFile(id)
                ?: throw IllegalStateException("the download is gone")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Throwable) {
            AlertDialog.Builder(this)
                .setMessage(R.string.update_failed)
                .setPositiveButton(R.string.open_page) { _, _ -> openPage(RELEASES_PAGE) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    companion object {
        private const val RELEASES_API =
            "https://api.github.com/repos/sharifhidayatulloh/pes2018-recomp/releases?per_page=10"
        private const val RELEASES_PAGE =
            "https://github.com/sharifhidayatulloh/pes2018-recomp"
        private const val UPDATE_CACHE_MS = 6 * 60 * 60 * 1000L

        private const val STATE_INSTALL_ID = "pes17.installId"
        private const val STATE_UPDATE_URL = "pes17.updateUrl"
        private const val STATE_UPDATE_TAG = "pes17.updateTag"
        private const val STATE_UPDATE_SHA = "pes17.updateSha"
        private const val STATE_DOWNLOAD_NAME = "pes17.downloadName"
        private const val STATE_AUTO_LAUNCHED = "pes17.autoLaunched"
        private const val STATE_SELECTED = "pes17.selected"

        /** Set once this process has asked GitHub: a recreated activity must not ask again. */
        @Volatile
        private var autoCheckedThisProcess = false
    }

    private fun showLastCrash(crash: String) {
        // The report is filed under the crash's own title: the stamp and the
        // exception line, which is what identifies it without typing.
        val title = crash.lines().firstOrNull { it.isNotBlank() }?.take(120) ?: ""
        AlertDialog.Builder(this)
            .setTitle(R.string.last_crash)
            .setMessage(crash.take(4000))
            .setPositiveButton(R.string.close) { _, _ -> Trail.forgetCrash(this) }
            .setNeutralButton(R.string.share) { _, _ -> share(crash) }
            .setNegativeButton(R.string.diagnostics_export) { _, _ -> exportReport(title, null) }
            .show()
    }

    /**
     * The log screen: a summary of it, with the whole thing one button away
     * (LogSummary says what is kept and what is only counted). Share sends the
     * whole log as text, scrubbed; the report is the diagnostics export, which
     * carries every file rather than 60 KB of each.
     */
    private fun showLog() {
        val session = if (nativeReady) safely("") { NativeBridge.nativeLogTail() } else ""
        val renderer = if (nativeReady) safely("") { NativeBridge.nativeRendererInfo() } else ""
        showLogText(LogSummary.build(this, renderer, session), 11f)
    }

    /** Everything, as it was, for when the summary is not enough. */
    private fun showWholeLog() {
        val session = if (nativeReady) safely("") { NativeBridge.nativeLogTail() } else ""
        showLogText(LogSummary.whole(this, session), 10f)
    }

    private fun showLogText(text: String, textSize: Float) {
        val body = TextView(this)
        body.setPadding(0, 24, 0, 24)
        body.textSize = textSize
        body.setTextIsSelectable(true)
        body.text = text
        val scroll = ScrollView(this)
        scroll.addView(body)
        // The report is filed under one line: what went wrong, starting from the
        // last crash so a bug report is never an empty subject.
        val note = EditText(this).apply {
            hint = getString(R.string.diagnostics_note_hint)
            setText(lastCrashLine())
            maxLines = 1
        }
        // The log takes a share of the screen and the note sits under it, so both
        // fit a landscape phone as well as a portrait one. A height in pixels
        // rather than a weight, because a dialog's content is measured with a
        // wrap_content window and a weighted child can come out with nothing.
        val tall = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 0, 32, 0)
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, tall))
            addView(note, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.view_log)
            .setView(column)
            .setPositiveButton(R.string.close, null)
            .setNegativeButton(R.string.log_everything) { _, _ -> showWholeLog() }
            .setNeutralButton(R.string.diagnostics_export, null)
            .create()
        // The third button exports instead of sharing (the builder's callback
        // cannot hold the button it must disable while the zip is written).
        dialog.setOnShowListener {
            val export = dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
            export.setOnClickListener { exportReport(note.text.toString(), export) }
        }
        dialog.show()
    }

    /** The first line of the last crash, which is where the note field starts. */
    private fun lastCrashLine(): String =
        Trail.lastCrash(this).lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: ""

    /**
     * Writes the diagnostics report and offers it. [button] is the action that
     * asked for it, held down until the answer arrives because the export reads
     * every log file on the way.
     */
    private fun exportReport(note: String, button: Button?) {
        if (exporting) return
        exporting = true
        button?.isEnabled = false
        Diagnostics.export(this, note) { done ->
            runOnUiThread {
                exporting = false
                try { button?.isEnabled = true } catch (_: Throwable) {}
                if (!isFinishing && !isDestroyed) showReportResult(this, done)
            }
        }
    }

    private fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_SUBJECT, "PES 2018 log")
        // A log carries the folder the game was installed from and every path
        // the runtime opened; it is shared around, so the paths come out first.
        intent.putExtra(Intent.EXTRA_TEXT, Scrub.text(this, text).takeLast(200_000))
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }
}

/**
 * Shows what the diagnostics export produced: where the zip is, a Share action
 * and a copy into Download/PES18, or the reason there is none. Shared by the
 * launcher, the crash dialog and Settings' Storage card.
 */
fun showReportResult(activity: android.app.Activity, done: Diagnostics.Done) {
    val file = done.zip
    if (file == null) {
        AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.diagnostics_export_failed, done.error ?: activity.getString(R.string.unknown_reason)))
            .setPositiveButton(R.string.close, null)
            .show()
        return
    }
    val size = android.text.format.Formatter.formatFileSize(activity, done.bytes)
    val dialog = AlertDialog.Builder(activity)
        .setMessage(activity.getString(R.string.captures_saved_to, file.absolutePath + " ($size)"))
        .setPositiveButton(R.string.share) { _, _ -> shareReportFile(activity, file) }
        .setNeutralButton(R.string.capture_save, null)
        .setNegativeButton(R.string.close, null)
        .create()
    // The copy button is held down while it runs, so a second tap cannot write
    // the same file twice; the work itself is off the main thread because it
    // opens and fills a MediaStore row.
    dialog.setOnShowListener {
        val save = dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
        var saving = false
        save.setOnClickListener {
            if (saving) return@setOnClickListener
            saving = true
            save.isEnabled = false
            val context = activity.applicationContext
            thread(name = "report-save", isDaemon = true) {
                // Downloads.save answers with null on success and a sentence on
                // failure, so nothing here has to invent a reason.
                val problem = try { Downloads.save(context, file) } catch (error: Throwable) {
                    error.message ?: error.toString()
                }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    saving = false
                    try { save.isEnabled = true } catch (_: Throwable) {}
                    val message = if (problem == null)
                        activity.getString(R.string.captures_saved_to, "Download/PES18/" + file.name)
                    else problem
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    dialog.show()
}

/**
 * Hands the exported zip to the share sheet through the provider the export
 * registers for it (`<package>.diagnostics`), so no storage permission is asked
 * for and the receiver can read exactly this one file.
 */
fun shareReportFile(activity: android.app.Activity, file: File) {
    try {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".diagnostics", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.diagnostics_share_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share)))
    } catch (error: Throwable) {
        Toast.makeText(activity, activity.getString(R.string.capture_failed, error.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}

/** The end of a file, which is the part of a log anyone wants. */
fun File.readTextTail(bytes: Int): String = try {
    val length = length()
    if (length <= bytes) readText()
    else inputStream().use { stream ->
        stream.skip(length - bytes)
        stream.readBytes().toString(Charsets.UTF_8)
    }
} catch (_: Throwable) {
    ""
}