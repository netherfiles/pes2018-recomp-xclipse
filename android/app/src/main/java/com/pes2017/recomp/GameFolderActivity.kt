package com.pes2017.recomp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

/**
 * Where the game runs from: chosen once, for every screen that needs it.
 *
 * The system picker hands over a document tree, but the runtime opens the game
 * by path, so only the real path behind the tree is kept. When Android has not
 * yet let the app read that path, the all-files screen is shown and the folder
 * waits in Prefs.pendingFolder until the player comes back.
 *
 * The contract for a caller (the main menu now, Settings' game files later):
 *
 *     folderPicker.launch(Intent(this, GameFolderActivity::class.java))
 *
 * and, in the activity result, RESULT_OK with EXTRA_PATH -- the folder that
 * holds default.xex -- or RESULT_CANCELED. Prefs.gameDirectory is written
 * before it returns, so the caller only has to redraw what it shows.
 *
 * It is also the verifier for a folder the launcher found by itself: put
 * EXTRA_START_PATH in the intent and the picker is skipped.
 */
class GameFolderActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    /** The tree the player picked, kept while the permission is asked for. */
    private var pendingTree: Uri? = null

    /** A check of the folder is running: one at a time. */
    private var working = false

    /**
     * The permission screen (or prompt) is open. Only a resume after it may
     * resume the waiting folder: a resume caused by the picker's own result
     * arrives while the access dialog is still to be answered.
     */
    private var awaitingAccess = false

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        // Cancelled in the picker: the app keeps whatever folder it had.
        if (uri == null) {
            cancel()
            return@registerForActivityResult
        }
        val picked = GameFolder.pathOf(uri)
        if (picked == null) {
            unreadable(uri)
            return@registerForActivityResult
        }
        if (GameFolder.hasAccess(this)) check(picked, uri) else askForAccess(picked, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = getString(R.string.folder_title)
        if (savedInstanceState != null) {
            // Rebuilt after the system reclaimed us: there is nothing to come
            // back to, so the choice starts again.
            try {
                pickTree.launch(null)
            } catch (_: Throwable) {
                cancel()
            }
            return
        }

        val startPath = intent?.getStringExtra(EXTRA_START_PATH)
        if (!startPath.isNullOrEmpty()) {
            // A folder the launcher found on its own: verify it, do not ask again.
            prefs.pendingFolder = ""
            check(File(startPath), null)
        } else {
            try {
                pickTree.launch(null)
            } catch (_: Throwable) {
                unreadable(null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumePending()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE) resumePending()
    }

    // ---- the folder, once it is known ---------------------------------------

    /** Back from the storage permission screen with the folder that was waiting. */
    private fun resumePending() {
        if (!awaitingAccess) return
        val path = prefs.pendingFolder
        if (working || path.isEmpty()) return
        awaitingAccess = false
        if (!GameFolder.hasAccess(this)) {
            // Came back without allowing it: nothing more to do here.
            Toast.makeText(this, R.string.storage_not_granted, Toast.LENGTH_LONG).show()
            cancel()
            return
        }
        val tree = pendingTree
        prefs.pendingFolder = ""
        pendingTree = null
        check(File(path), tree)
    }

    /**
     * Looks for default.xex in the folder (or one level down) and verifies the
     * executable against the one this app was recompiled from. Both are off the
     * main thread: the file is large.
     */
    private fun check(folder: File, tree: Uri?) {
        if (working) return
        working = true
        thread(name = "folder-check", isDaemon = true) {
            val game = try {
                GameFolder.locate(folder)
            } catch (_: Throwable) {
                null
            }
            val readable = try {
                folder.listFiles() != null
            } catch (_: Throwable) {
                false
            }
            var matches = false
            if (game != null) {
                matches = try {
                    GameFolder.sha256(File(game, GameFolder.XEX)).equals(BuildConfig.PES_XEX_SHA256, ignoreCase = true)
                } catch (_: Throwable) {
                    false
                }
            }
            runOnUiThread {
                working = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    game != null && matches -> adopt(game)
                    game != null -> wrongGame(game)
                    tree != null && !readable -> unreadable(tree)
                    !readable -> noAccess(folder)
                    else -> noXex()
                }
            }
        }
    }

    /** The folder is the game: keep it and tell the caller where it is. */
    private fun adopt(game: File) {
        prefs.gameDirectory = game.absolutePath
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, game.absolutePath))
        finish()
    }

    // ---- saying what is wrong ------------------------------------------------

    private fun wrongGame(game: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.folder_title)
            .setMessage(getString(R.string.folder_wrong_game) + "\n\n" + game.absolutePath)
            .setNegativeButton(R.string.cancel) { _, _ -> cancel() }
            .setPositiveButton(R.string.folder_use_anyway) { _, _ -> adopt(game) }
            .setOnCancelListener { cancel() }
            .show()
    }

    private fun noXex() {
        AlertDialog.Builder(this)
            .setTitle(R.string.folder_title)
            .setMessage(R.string.folder_no_xex)
            .setPositiveButton(R.string.close) { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .show()
    }

    /** A path that cannot be listed: the permission did not take, or it is not a folder. */
    private fun noAccess(folder: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.folder_title)
            .setMessage(getString(R.string.status_needs_access, folder.absolutePath))
            .setPositiveButton(R.string.close) { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .show()
    }

    /** No path behind the tree, or the folder cannot be read: only a copy would run. */
    private fun unreadable(tree: Uri?) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.folder_title)
            .setMessage(R.string.folder_unreadable_path)
            .setNegativeButton(R.string.cancel) { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
        if (tree != null) dialog.setPositiveButton(R.string.copy_instead) { _, _ -> copyIn(tree) }
        dialog.show()
    }

    // ---- the storage permission ---------------------------------------------

    private fun askForAccess(picked: File, tree: Uri?) {
        pendingTree = tree
        prefs.pendingFolder = picked.absolutePath
        AlertDialog.Builder(this)
            .setTitle(R.string.access_title)
            .setMessage(R.string.access_explained)
            .setNegativeButton(R.string.cancel) { _, _ -> cancel() }
            .setNeutralButton(R.string.copy_instead) { _, _ -> copyIn(tree) }
            .setPositiveButton(R.string.allow_storage) { _, _ -> requestAccess() }
            .setOnCancelListener { cancel() }
            .show()
    }

    private fun requestAccess() {
        awaitingAccess = true
        val intent = GameFolder.accessIntent(this)
        if (intent == null) {
            requestPermissions(GameFolder.legacyPermissions, REQUEST_STORAGE)
            return
        }
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Throwable) {
                awaitingAccess = false
                cancel()
            }
        }
    }

    // ---- giving up -----------------------------------------------------------

    /** A provider with no path behind it: the only way in is a copy into the app. */
    private fun copyIn(tree: Uri?) {
        if (tree != null) {
            startActivity(
                Intent(this, InstallActivity::class.java).apply {
                    data = tree
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }
        cancel()
    }

    /** Nothing chosen: the folder the app already had is left as it is. */
    private fun cancel() {
        prefs.pendingFolder = ""
        pendingTree = null
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        /** The folder that holds default.xex, as an absolute path. */
        const val EXTRA_PATH = "game_folder.path"

        /** A folder to verify instead of asking the player to pick one. */
        const val EXTRA_START_PATH = "game_folder.start_path"

        private const val REQUEST_STORAGE = 1
    }
}
