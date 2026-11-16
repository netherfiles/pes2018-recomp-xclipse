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
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

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
    private lateinit var directLaunchSwitch: androidx.appcompat.widget.SwitchCompat
    private var detectedSource: GameSource? = null
    private var detectedSources: List<GameSource> = emptyList()
    private var nativeReady = false
    private var hasAutoLaunched = false

    /** The match asked to be started again with new settings (QuickGraphics). */
    private var relaunchRequested = false
    private var pendingInstallId: Long = -1
    private var pendingUpdateUrl: String? = null

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
        // The cursor starts on KICK OFF, and the strip at the foot scrolls
        // (a marquee runs only on a selected view).
        select(play)
        footer.isSelected = true

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
        checkForUpdates()
        predownloadDriver()
    }

    /**
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
        pendingUpdateUrl?.let { url ->
            pendingUpdateUrl = null
            if (packageManager.canRequestPackageInstalls()) startUpdateDownload(url)
        }
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
     * its text in gold. The one before gets its own colour back.
     */
    private fun select(entry: Button) {
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
    }

    /** A newer published APK, if there is one: silent when offline. */
    private fun checkForUpdates() {
        thread(name = "update-check", isDaemon = true) {
            try {
                if (RELEASES_API.isEmpty()) throw IllegalStateException("no release feed")
                val connection = (URL(RELEASES_API)
                    .openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                if (connection.responseCode == 200) {
                    val release = JSONObject(connection.inputStream.bufferedReader().readText())
                    val assets = release.optJSONArray("assets")
                    val apk = assets?.let { list ->
                        (0 until list.length()).map { list.getJSONObject(it) }
                            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                    }
                    val tag = release.optString("tag_name")
                    val code = Regex("APK versionCode:\\s*(\\d+)")
                        .find(release.optString("body"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val installed = packageManager.getPackageInfo(packageName, 0).let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toInt()
                        else it.versionCode
                    }
                    // The latest published release only: older ones stay buried.
                    if (apk != null && code > installed && tag.isNotEmpty() && tag != prefs.seenUpdateTag) {
                        val page = release.optString("html_url")
                        val apkUrl = apk.optString("browser_download_url")
                        val notes = release.optString("body").take(1500)
                        runOnUiThread { showUpdateDialog(tag, page, apkUrl, notes, installed, code) }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun showUpdateDialog(tag: String, page: String, apkUrl: String, notes: String, installed: Int, code: Int) {
        val body = TextView(this)
        body.setPadding(32, 8, 32, 8)
        body.textSize = 12f
        body.text = getString(R.string.update_message, installed, code) + "\n\n" + notes
        val scroll = ScrollView(this)
        scroll.addView(body)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title, tag))
            .setView(scroll)
            .setPositiveButton(R.string.update_download) { _, _ ->
                if (apkUrl.isNotEmpty()) downloadAndInstall(apkUrl)
                else openPage(page)
            }
            .setNeutralButton(R.string.skip_version) { _, _ -> prefs.seenUpdateTag = tag }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openPage(page: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)))
        } catch (_: Throwable) {
        }
    }

    private fun downloadAndInstall(apkUrl: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            // System sends the user to allow installs from this app; the
            // download starts when they come back (onResume).
            pendingUpdateUrl = apkUrl
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    )
                )
            } catch (_: Throwable) {
                pendingUpdateUrl = null
            }
            return
        }
        startUpdateDownload(apkUrl)
    }

    private fun startUpdateDownload(apkUrl: String) {
        try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("PES 2018 update")
                .setDescription("Downloading APK")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "pes18-update.apk")
                .setAllowedOverMetered(true)
            pendingInstallId = manager.enqueue(request)
            Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            if (RELEASES_PAGE.isNotEmpty()) openPage(RELEASES_PAGE)
        }
    }

    private fun installDownloadedApk(id: Long) {
        try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val uri = manager.getUriForDownloadedFile(id) ?: return
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
        // No release feed yet for this port: the 2017 repository's releases are a
        // different app and must never be offered as an update of this one.
        private const val RELEASES_PAGE = ""
        private const val RELEASES_API = ""
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
