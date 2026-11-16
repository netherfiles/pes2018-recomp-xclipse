package com.pes2017.recomp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.pes2017.recomp.patch.PatchFormat
import com.pes2017.recomp.patch.PatchesDashboard
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * PATCHES: the data pack, the edit save and the previous versions, on one
 * page. The approved layout has no tabs; the details header says what is
 * installed before the three sections do.
 *
 * The actions are wired to the real cleanup and restore operations. Their result
 * is re-read from the game folder, never held only in memory. No destructive
 * action is offered without its consequence spelled out.
 */
class PatchesActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var headerStatus: TextView
    private lateinit var headerGame: TextView
    private lateinit var headerSystem: TextView
    private lateinit var selectFolder: Button

    private lateinit var patchBadge: TextView
    private lateinit var patchName: TextView
    private lateinit var patchMeta: TextView
    private lateinit var patchStats: TextView
    private lateinit var patchNone: TextView
    private lateinit var patchIncomplete: TextView
    private lateinit var patchReplace: Button
    private lateinit var patchRestore: Button
    private lateinit var patchRemove: Button

    private lateinit var editBadge: TextView
    private lateinit var editName: TextView
    private lateinit var editMeta: TextView
    private lateinit var editStats: TextView
    private lateinit var editNone: TextView
    private lateinit var editIncomplete: TextView
    private lateinit var editReplace: Button
    private lateinit var editRestore: Button
    private lateinit var editRemove: Button

    private lateinit var restorePatchGroup: View
    private lateinit var restorePatchName: TextView
    private lateinit var restorePatchMeta: TextView
    private lateinit var restoreEditGroup: View
    private lateinit var restoreEditName: TextView
    private lateinit var restoreEditMeta: TextView
    private lateinit var restoreNone: View

    private var operationRunning = false

    /** The last reading drawn; the action dialogs reuse it instead of re-reading the folder. */
    private var lastState: PatchesRepository.State? = null

    /**
     * The game folder is chosen by GameFolderActivity, the same activity the
     * launcher starts; on the way back the page is read again from the folder.
     */
    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // A different folder is a different answer: drop the old snapshot first.
        PatchesDashboard.invalidate()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patches)
        applySystemBars()
        findViewById<View>(R.id.back_button).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        prefs = Prefs(this)
        title = getString(R.string.patches_title)

        headerStatus = findViewById(R.id.patches_header_status)
        headerGame = findViewById(R.id.patches_header_game)
        headerSystem = findViewById(R.id.patches_header_system)
        selectFolder = findViewById(R.id.patches_select_folder)
        selectFolder.setOnClickListener {
            folderPicker.launch(Intent(this, GameFolderActivity::class.java))
        }

        patchBadge = findViewById(R.id.patch_badge)
        patchName = findViewById(R.id.patch_name)
        patchMeta = findViewById(R.id.patch_meta)
        patchStats = findViewById(R.id.patch_stats)
        patchNone = findViewById(R.id.patch_none)
        patchIncomplete = findViewById(R.id.patch_incomplete)
        patchReplace = findViewById(R.id.patch_replace)
        patchRestore = findViewById(R.id.patch_restore)
        patchRemove = findViewById(R.id.patch_remove)

        editBadge = findViewById(R.id.edit_badge)
        editName = findViewById(R.id.edit_name)
        editMeta = findViewById(R.id.edit_meta)
        editStats = findViewById(R.id.edit_stats)
        editNone = findViewById(R.id.edit_none)
        editIncomplete = findViewById(R.id.edit_incomplete)
        editReplace = findViewById(R.id.edit_replace)
        editRestore = findViewById(R.id.edit_restore)
        editRemove = findViewById(R.id.edit_remove)

        restorePatchGroup = findViewById(R.id.restore_patch_group)
        restorePatchName = findViewById(R.id.restore_patch_name)
        restorePatchMeta = findViewById(R.id.restore_patch_meta)
        restoreEditGroup = findViewById(R.id.restore_edit_group)
        restoreEditName = findViewById(R.id.restore_edit_name)
        restoreEditMeta = findViewById(R.id.restore_edit_meta)
        restoreNone = findViewById(R.id.restore_none)

        patchReplace.setOnClickListener { replace(PatchesRepository.SLOT_PATCH) }
        editReplace.setOnClickListener { replace(PatchesRepository.SLOT_EDIT) }
        patchRemove.setOnClickListener { confirmRemove(PatchesRepository.SLOT_PATCH) }
        editRemove.setOnClickListener { confirmRemove(PatchesRepository.SLOT_EDIT) }
        patchRestore.setOnClickListener { confirmRestore(PatchesRepository.SLOT_PATCH) }
        editRestore.setOnClickListener { confirmRestore(PatchesRepository.SLOT_EDIT) }
        findViewById<Button>(R.id.restore_patch_action).setOnClickListener {
            confirmRestore(PatchesRepository.SLOT_PATCH)
        }
        findViewById<Button>(R.id.restore_edit_action).setOnClickListener {
            confirmRestore(PatchesRepository.SLOT_EDIT)
        }
        findViewById<Button>(R.id.restore_patch_delete).setOnClickListener {
            confirmDeleteBackup(PatchesRepository.SLOT_PATCH)
        }
        findViewById<Button>(R.id.restore_edit_delete).setOnClickListener {
            confirmDeleteBackup(PatchesRepository.SLOT_EDIT)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        PatchesDashboard.cached()?.let { render(it.state) }
        PatchesDashboard.load(this) { snapshot ->
            if (!isFinishing && !isDestroyed) render(snapshot.state)
        }
    }

    /**
     * The last drawn state, or the cached one: no folder walk from a click.
     * Before the first snapshot (a cold open, tapped within the first seconds)
     * there is nothing to draw from, so the one accurate read is taken then --
     * the action dialogs must not guess whether a backup exists.
     */
    private fun currentState(): PatchesRepository.State =
        lastState ?: PatchesDashboard.cached()?.state ?: PatchesRepository.state()

    private fun render(state: PatchesRepository.State) {
        lastState = state
        val path = prefs.gameDirectory
        val hasGame = path.isNotEmpty() && File(path, "default.xex").isFile

        headerStatus.setText(
            when {
                !hasGame -> R.string.patches_header_no_game
                state.patch != null && state.edited != null -> R.string.patches_header_ready
                state.patch != null -> R.string.patches_header_patch_only
                state.edited != null -> R.string.patches_header_edit_only
                else -> R.string.patches_header_empty
            },
        )
        headerStatus.setTextColor(getColor(if (hasGame) R.color.pes_blue else R.color.pes_red))
        selectFolder.visibility = if (hasGame) View.GONE else View.VISIBLE
        headerGame.text =
            if (hasGame) getString(R.string.patches_header_game, path)
            else getString(R.string.patches_header_no_game_body)
        headerSystem.text = getString(
            R.string.patches_header_system,
            getString(
                if (state.systemUpdated) R.string.patches_system_updated
                else R.string.patches_system_untouched,
            ),
        )

        refreshPatch(state)
        refreshEdited(state)
        refreshBackups(state)
    }

    private fun refreshPatch(state: PatchesRepository.State) {
        val patch = state.patch
        // Nothing installed yet: the action opens the import flow, so it says so.
        patchReplace.text = getString(
            if (patch == null) R.string.patch_import_install else R.string.patches_replace,
        )
        if (patch == null) {
            pill(patchBadge, getString(R.string.patches_status_none), Tone.EMPTY)
            patchName.text = getString(R.string.patches_patch_none_name)
            patchMeta.visibility = View.GONE
            patchStats.visibility = View.GONE
            patchNone.visibility = View.VISIBLE
            patchIncomplete.visibility = View.GONE
            patchRemove.visibility = View.GONE
        } else {
            if (patch.complete) {
                pill(patchBadge, getString(R.string.patches_status_installed), Tone.OK)
                patchIncomplete.visibility = View.GONE
            } else {
                pill(patchBadge, getString(R.string.patches_status_incomplete), Tone.WARN)
                patchIncomplete.visibility = View.VISIBLE
            }
            patchName.text = patch.name
            patchMeta.visibility = View.VISIBLE
            patchMeta.text = getString(
                R.string.patches_patch_meta,
                listOf(patch.source, patch.fileName).filter { it.isNotBlank() }.joinToString(" · "),
                patch.date,
            )
            patchStats.visibility = View.VISIBLE
            patchStats.text = getString(
                R.string.patches_patch_stats,
                patch.cpk,
                PatchFormat.formatBytes(patch.bytes),
                patch.entries,
            )
            patchNone.visibility = View.GONE
            patchRemove.visibility = View.VISIBLE
        }
        patchRestore.isEnabled = state.patchBackup != null
        patchRestore.alpha = if (state.patchBackup != null) 1f else 0.45f
    }

    private fun refreshEdited(state: PatchesRepository.State) {
        val edited = state.edited
        if (edited == null) {
            pill(editBadge, getString(R.string.patches_status_none), Tone.EMPTY)
            editName.text = getString(R.string.patches_edit_none_name)
            editMeta.visibility = View.GONE
            editStats.visibility = View.GONE
            editNone.visibility = View.VISIBLE
            editIncomplete.visibility = View.GONE
            editRemove.visibility = View.GONE
        } else {
            if (edited.complete) {
                pill(editBadge, getString(R.string.patches_status_installed), Tone.OK)
                editIncomplete.visibility = View.GONE
            } else {
                pill(editBadge, getString(R.string.patches_status_incomplete), Tone.WARN)
                editIncomplete.visibility = View.VISIBLE
            }
            editName.text = edited.name
            editMeta.visibility = View.VISIBLE
            editMeta.text = getString(R.string.patches_edit_meta, edited.fileName, edited.date)
            editStats.visibility = View.VISIBLE
            editStats.text = getString(
                R.string.patches_edit_stats,
                edited.version,
                String.format(Locale.US, "%,d", edited.players),
                edited.teams,
                PatchFormat.formatBytes(edited.bytes),
            )
            editNone.visibility = View.GONE
            editRemove.visibility = View.VISIBLE
        }
        editRestore.isEnabled = state.editBackup != null
        editRestore.alpha = if (state.editBackup != null) 1f else 0.45f
    }

    private fun refreshBackups(state: PatchesRepository.State) {
        val patchBackup = state.patchBackup
        restorePatchGroup.visibility = if (patchBackup == null) View.GONE else View.VISIBLE
        if (patchBackup != null) {
            restorePatchName.text = patchBackup.name
            restorePatchMeta.text = getString(
                R.string.patches_backup_meta,
                patchBackup.date,
                PatchFormat.formatBytes(patchBackup.bytes),
            )
        }

        val editBackup = state.editBackup
        restoreEditGroup.visibility = if (editBackup == null) View.GONE else View.VISIBLE
        if (editBackup != null) {
            restoreEditName.text = editBackup.name
            restoreEditMeta.text = getString(
                R.string.patches_backup_meta,
                editBackup.date,
                PatchFormat.formatBytes(editBackup.bytes),
            )
        }

        restoreNone.visibility = if (patchBackup == null && editBackup == null) View.VISIBLE else View.GONE
    }

    // ---- replacing ---------------------------------------------------------

    /**
     * At replace time the old data has three possible fates. They are named
     * here rather than assumed: keeping it as the one backup, freeing the
     * backup already there before keeping it, or giving up the undo.
     */
    private fun replace(slot: String) {
        if (SaveBackup.gameRunning(this)) {
            message(getString(R.string.patches_game_running))
            return
        }
        val state = currentState()
        val currentName = if (slot == PatchesRepository.SLOT_PATCH) state.patch?.name else state.edited?.name
        if (currentName == null) {
            startImport(slot, PatchesRepository.BackupPolicy.NONE)
            return
        }
        val title = if (slot == PatchesRepository.SLOT_PATCH) {
            R.string.patches_replace_patch_title
        } else {
            R.string.patches_replace_edit_title
        }
        val names = arrayOf(
            getString(R.string.patches_keep_backup),
            getString(R.string.patches_free_old),
            getString(R.string.patches_no_backup_option),
        )
        // AlertDialog drops the setItems list when a message is set, which left
        // this dialog with nothing but CANCEL. The explanation travels in a
        // custom title above the three choices instead.
        val pad = (20 * resources.displayMetrics.density).toInt()
        val header = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        header.addView(TextView(this, null, 0, R.style.DialogTitle).apply {
            text = getString(title)
        })
        header.addView(TextView(this, null, 0, R.style.DialogBody).apply {
            text = getString(R.string.patches_replace_body, currentName)
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        })
        AlertDialog.Builder(this)
            .setCustomTitle(header)
            .setItems(names) { _, which ->
                val policy = when (which) {
                    0 -> PatchesRepository.BackupPolicy.KEEP
                    1 -> PatchesRepository.BackupPolicy.FREE_OLD
                    else -> PatchesRepository.BackupPolicy.NONE
                }
                startImport(slot, policy)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startImport(slot: String, policy: PatchesRepository.BackupPolicy) {
        startActivity(
            Intent(this, PatchImportActivity::class.java)
                .putExtra(PatchImportActivity.EXTRA_SLOT, slot)
                .putExtra(PatchImportActivity.EXTRA_BACKUP, policy.name),
        )
    }

    // ---- destructive actions ----------------------------------------------

    private fun confirmRemove(slot: String) {
        if (SaveBackup.gameRunning(this)) {
            message(getString(R.string.patches_game_running))
            return
        }
        val state = currentState()
        val patch = slot == PatchesRepository.SLOT_PATCH
        val hasBackup = if (patch) state.patchBackup != null else state.editBackup != null
        val body = when {
            patch && hasBackup -> R.string.patches_remove_patch_backup
            patch -> R.string.patches_remove_patch_body
            hasBackup -> R.string.patches_remove_edit_backup
            else -> R.string.patches_remove_edit_body
        }
        AlertDialog.Builder(this)
            .setTitle(
                getString(
                    if (patch) R.string.patches_remove_patch_title
                    else R.string.patches_remove_edit_title,
                ),
            )
            .setMessage(getString(body))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(
                getString(if (patch) R.string.patches_remove_patch else R.string.patches_remove_edit),
            ) { _, _ ->
                runOperation(
                    message = getString(R.string.patches_working),
                    action = {
                        if (patch) {
                            PatchesRepository.removePatch(this)
                        } else {
                            PatchesRepository.removeEdited(this)
                        }
                    },
                    onSuccess = {
                        refresh()
                        Toast.makeText(this, R.string.patches_removed, Toast.LENGTH_SHORT).show()
                    },
                )
            }
            .show()
    }

    private fun confirmRestore(slot: String) {
        if (SaveBackup.gameRunning(this)) {
            message(getString(R.string.patches_game_running))
            return
        }
        val state = currentState()
        val patch = slot == PatchesRepository.SLOT_PATCH
        val backup = if (patch) state.patchBackup else state.editBackup
        if (backup == null) {
            message(getString(R.string.patches_no_backup))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(
                getString(
                    if (patch) R.string.patches_restore_patch_title
                    else R.string.patches_restore_edit_title,
                ),
            )
            .setMessage(
                getString(
                    if (patch) R.string.patches_restore_patch_body
                    else R.string.patches_restore_edit_body,
                    backup.name,
                ),
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.patches_restore) { _, _ ->
                runOperation(
                    message = getString(R.string.patches_working),
                    action = {
                        if (patch) {
                            PatchesRepository.restorePatch(this)
                        } else {
                            PatchesRepository.restoreEdited(this)
                        }
                    },
                )
            }
            .show()
    }

    private fun confirmDeleteBackup(slot: String) {
        if (SaveBackup.gameRunning(this)) {
            message(getString(R.string.patches_game_running))
            return
        }
        val state = currentState()
        val patch = slot == PatchesRepository.SLOT_PATCH
        val backup = if (patch) state.patchBackup else state.editBackup
        if (backup == null) {
            message(getString(R.string.patches_no_backup))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.patches_delete_backup_title)
            .setMessage(getString(R.string.patches_delete_backup_body, PatchFormat.formatBytes(backup.bytes)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.patches_delete_backup) { _, _ ->
                runOperation(
                    message = getString(R.string.patches_working),
                    action = {
                        if (patch) {
                            PatchesRepository.deletePatchBackup(this)
                        } else {
                            PatchesRepository.deleteEditBackup(this)
                        }
                    },
                )
            }
            .show()
    }

    private fun message(text: String) {
        AlertDialog.Builder(this).setMessage(text).setPositiveButton(R.string.close, null).show()
    }

    /** Explains a refused file operation; true when it succeeded. */
    private fun handle(result: PatchesRepository.OperationResult): Boolean {
        if (result.ok) return true
        val text = when (result.failure) {
            PatchesRepository.Failure.GAME_RUNNING -> getString(R.string.patches_game_running)
            PatchesRepository.Failure.BUSY -> getString(R.string.patch_import_busy)
            PatchesRepository.Failure.NO_BACKUP -> getString(R.string.patches_no_backup)
            PatchesRepository.Failure.NO_GAME_FOLDER -> getString(R.string.patches_header_no_game_body)
            else -> getString(R.string.patch_import_result_failed_body, result.detail ?: getString(R.string.unknown_reason))
        }
        message(text)
        return false
    }

    /**
     * REMOVE/RESTORE/DELETE walk tens of gigabytes. Doing that on the main
     * thread froze the page long enough for Android to show an ANR dialog, so
     * the operation runs on a small thread and the screen shows what it is
     * doing.
     */
    private fun runOperation(
        message: String,
        action: () -> PatchesRepository.OperationResult,
        onSuccess: () -> Unit = { refresh() },
    ) {
        if (operationRunning) return
        operationRunning = true
        val dialog = AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .create()
        dialog.show()
        thread(name = "patch-files", isDaemon = true) {
            val result = try {
                action()
            } catch (t: Throwable) {
                PatchesRepository.OperationResult(
                    PatchesRepository.Failure.FAILED,
                    t.message ?: t.toString(),
                )
            }
            runOnUiThread {
                operationRunning = false
                if (isFinishing || isDestroyed) {
                    runCatching { dialog.dismiss() }
                    return@runOnUiThread
                }
                runCatching { dialog.dismiss() }
                if (handle(result)) onSuccess()
            }
        }
    }

    // ---- small pieces ------------------------------------------------------

    private enum class Tone { OK, EMPTY, WARN }

    /** The word is the status; the colour only reinforces it. */
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
}
