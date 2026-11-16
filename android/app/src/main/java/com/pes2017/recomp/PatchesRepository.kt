package com.pes2017.recomp

import android.content.Context
import com.pes2017.recomp.patch.DpFileListParser
import com.pes2017.recomp.patch.PatchPlan
import com.pes2017.recomp.patch.PatchScanner
import com.pes2017.recomp.patch.PatchSource
import com.pes2017.recomp.patch.PatchesDashboard
import com.pes2017.recomp.patch.SavePayloadParser
import com.pes2017.recomp.patch.install.InstallBackupPolicy
import com.pes2017.recomp.patch.install.InstallFailure
import com.pes2017.recomp.patch.install.InstallResult
import com.pes2017.recomp.patch.install.InstallSelection
import com.pes2017.recomp.patch.install.InstallSlot
import com.pes2017.recomp.patch.install.InstallSpace
import com.pes2017.recomp.patch.install.PatchInstaller
import com.pes2017.recomp.patch.install.PatchStateStore
import com.pes2017.recomp.patch.install.RecoveryReport
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/**
 * The PATCHES screen's real state, read from the active game folder.
 *
 * Roots are the runtime's, not the app's: `dlc/` and `saves/` sit beside the
 * game folder (`prefs.gameDirectory.parent`), which is also what
 * SaveBackup.savesDir uses. Nothing is stored only in this object; a folder
 * pushed by hand is detected from its DpFileList/CPKs/data.bin, and a small
 * properties file only adds the provenance of an install this app made.
 *
 * [state] does file IO (it opens every CPK for its magic and parses the
 * DpFileList), so screens should read it through [PatchesDashboard], which
 * runs it on a worker thread and caches the answer. The operations below are
 * safe to call from a background thread; they invalidate that cache.
 */
object PatchesRepository {

    const val SLOT_PATCH = "patch"
    const val SLOT_EDIT = "edit"

    /** What happens to the data installed now when it is replaced. */
    enum class BackupPolicy {
        KEEP,
        FREE_OLD,
        NONE,
    }

    /**
     * Where the installed PATCH came from.
     *
     * [APP] means this app installed it and the files still match the CPK
     * manifest/DpFileList hash it recorded. [HAND] means there is no such
     * record: the folder was copied in, or predates the manifest. [STALE]
     * means the record exists but the folder no longer matches it (a CPK was
     * truncated, replaced, added or removed, or the DpFileList changed).
     */
    enum class Provenance {
        APP,
        HAND,
        STALE,
    }

    /** The shape of `dlc/SYSTEM00000000`, so "absent" and "empty" are not the same screen. */
    enum class PatchLayout {
        ABSENT,
        EMPTY,

        /** A DpFileList but no CPKs: the list points at files that are not there. */
        DP_ONLY,

        /** CPKs but no DpFileList: the game will not mount them. */
        NO_DP,

        /** Both present; completeness is judged by [PatchData.complete]. */
        WITH_DP,
    }

    data class PatchData(
        val name: String,
        val source: String,
        val date: String,
        val cpk: Int,
        val bytes: Long,
        val entries: Int,
        val complete: Boolean = true,
        /** Legacy alias of [sourceLabel]; kept because the current PATCHES card prints it. */
        val fileName: String = "",
        /** Size of the DpFileList on disk; 0 when it is missing. */
        val dpBytes: Long = 0L,
        /** Whether the DpFileList was small enough to read and parsed. */
        val dpReadable: Boolean = false,
        val provenance: Provenance = Provenance.HAND,
        /** `name:size`, one entry per CPK on disk. The identity a re-read compares. */
        val manifest: List<String> = emptyList(),
        /** SHA-256 of the DpFileList bytes; "" when it could not be read. */
        val dpSha256: String = "",
        /** What the install source was called ("ZIP", "folder"). */
        val sourceLabel: String = "",
        val layout: PatchLayout = PatchLayout.WITH_DP,
    )

    data class EditedData(
        val name: String,
        val date: String,
        val players: Int,
        val teams: Int,
        val bytes: Long,
        val complete: Boolean = true,
        val fileName: String = "EDIT00000000",
        val version: Int = 0,
    )

    sealed class Previous {
        abstract val name: String
        abstract val date: String
        abstract val bytes: Long

        data class Patch(val data: PatchData) : Previous() {
            override val name: String get() = data.name
            override val date: String get() = data.date
            override val bytes: Long get() = data.bytes
        }

        data class Edit(val data: EditedData) : Previous() {
            override val name: String get() = data.name
            override val date: String get() = data.date
            override val bytes: Long get() = data.bytes
        }
    }

    /**
     * One reading of the phone's patch state. All added fields have defaults so
     * existing callers and constructors keep compiling.
     */
    data class State(
        val patch: PatchData?,
        val edited: EditedData?,
        val patchBackup: Previous.Patch?,
        val editBackup: Previous.Edit?,
        /**
         * True only when this app installed `saves/SYSTEM00000000` and its
         * data.bin still matches the hash recorded then. Existence is NOT
         * provenance: the game recreates SYSTEM on every boot, so a plain
         * `isDirectory` check said "updated" on a phone that had never had a
         * SYSTEM install. Kept for the current header; [systemSaveBytes] and
         * [journalPending] are the useful new facts.
         */
        val systemUpdated: Boolean,
        val hasGameFolder: Boolean = false,
        /** The folder above the game folder (where dlc/, saves/ and backups live). */
        val root: File? = null,
        /** A patch operation is running in this process right now. */
        val installing: Boolean = false,
        /** An unfinished install journal (or its lock) is waiting for recovery. */
        val journalPending: Boolean = false,
        /** Bytes under `saves/SYSTEM00000000`, whether the game made it or this app did. */
        val systemSaveBytes: Long = 0L,
        val patchLayout: PatchLayout = PatchLayout.ABSENT,
    )

    enum class Failure {
        GAME_RUNNING,
        BUSY,
        NO_GAME_FOLDER,
        NO_BACKUP,
        FAILED,
    }

    data class OperationResult(
        val failure: Failure? = null,
        val detail: String? = null,
    ) {
        val ok: Boolean get() = failure == null
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastRecoveryReport: RecoveryReport? = null

    /**
     * The root a live install is writing to. While it is set, [gameRoot] returns
     * it instead of reading the preference again: a folder change mid-run would
     * otherwise make every screen look in a folder the journal, the heartbeat
     * and the terminal record are not in, and the import would sit on a
     * percentage that can never move again.
     */
    @Volatile
    private var pinnedRoot: File? = null

    private val recoveryLock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Refuses a folder change for the length of a run: reads keep the run's root. */
    fun pinRoot(root: File) {
        pinnedRoot = root
    }

    fun unpinRoot() {
        pinnedRoot = null
    }

    /**
     * The folder above the game folder. Null until a real game folder is
     * chosen; PATCHES shows its no-game state rather than a sample.
     */
    fun gameRoot(context: Context? = appContext): File? {
        pinnedRoot?.let { return it }
        val ctx = context ?: return null
        val prefs = Prefs(ctx)
        val configured = prefs.gameDirectory
        if (configured.isEmpty()) return null
        val game = File(configured)
        if (!game.isDirectory || !File(game, GameFolder.XEX).isFile) return null
        return game.parentFile ?: SaveBackup.savesDir(ctx, prefs).parentFile
    }

    /** The state PATCHES shows when no game folder is configured. */
    fun emptyState(): State = State(null, null, null, null, false)

    fun state(): State = stateOrEmpty()

    private fun stateOrEmpty(): State {
        val context = appContext ?: return emptyState()
        val root = gameRoot(context) ?: return emptyState()
        val meta = runCatching { PatchStateStore.read(root) }.getOrDefault(Properties())
        val patchDir = File(root, InstallSlot.PATCH.relativePath)
        val patch = inspectPatch(patchDir, meta)
        val edited = inspectEdit(File(root, InstallSlot.EDITED_DATA.relativePath), meta)
        val patchBackup = patchBackup(root)
        val editBackup = editBackup(root)
        val journal = File(root, "patch-journal.properties").isFile
        return State(
            patch = patch,
            edited = edited,
            patchBackup = patchBackup,
            editBackup = editBackup,
            systemUpdated = systemIsOurs(root, meta),
            hasGameFolder = true,
            root = root,
            installing = PatchInstaller.busy(),
            journalPending = journal || PatchInstaller.lockFile(root).isFile,
            systemSaveBytes = sizeOf(File(root, SYSTEM_SAVE)),
            patchLayout = patchLayout(patchDir),
        )
    }

    /** Runs the scanner with the app's cache as its small save-CON scratch. */
    @Suppress("UNUSED_PARAMETER")
    fun scan(
        source: PatchSource,
        password: String? = null,
        backupBytes: Long = 0L,
    ): PatchPlan {
        val context = appContext ?: throw IllegalStateException(
            "PatchesRepository.init(context) was not called",
        )
        val scratch = File(context.cacheDir, "patch-scan")
        return PatchScanner(scratch).scan(source, backupBytes)
    }

    fun space(
        plan: PatchPlan,
        selection: InstallSelection,
        policy: BackupPolicy,
        context: Context? = appContext,
    ): InstallSpace {
        val ctx = context ?: return InstallSpace(0, 0, 0, 0, 0)
        val root = gameRoot(ctx) ?: return InstallSpace(0, 0, 0, 0, 0)
        return PatchInstaller(root).space(plan, selection, policy.install())
    }

    /** The bytes of the active slots the selection would move to a backup. */
    fun backupBytes(
        selection: InstallSelection,
        context: Context? = appContext,
    ): Long {
        val ctx = context ?: return 0L
        val root = gameRoot(ctx) ?: return 0L
        var total = 0L
        for (slot in selection.slots()) {
            total = InstallSpace.safeAdd(total, sizeOf(File(root, slot.relativePath)))
        }
        return total
    }

    fun isInstalling(context: Context): Boolean {
        val root = gameRoot(context)
        if (root == null) return PatchInstaller.busy()
        return PatchInstaller.pending(root)
    }

    /** Called from the Application on a background thread. */
    fun recover() {
        val context = appContext ?: return
        val root = gameRoot(context) ?: return
        // A running game may be inside RotatePackage, which temporarily keeps
        // the active slot under "<name>.bak"; recovery must not move it back.
        if (SaveBackup.gameRunning(context)) return
        val report = synchronized(recoveryLock) {
            runCatching { PatchInstaller(root).recover() }.getOrNull()
        }
        lastRecoveryReport = report
        // The installer wrote the same facts to recovery.properties in the game
        // root; the log line is what makes them findable without adb.
        if (report != null && (report.changed || report.problems.isNotEmpty())) {
            EventLog.note(
                context,
                "install.recovery",
                "restored" to report.restoredSlots.joinToString(",") { it.key },
                "staging" to report.discardedStaging.toString(),
                "committed" to report.committedInstallKept.toString(),
                "problems" to report.problems.size.toString(),
            )
        }
        PatchesDashboard.invalidate()
    }

    fun recover(context: Context) {
        init(context)
        recover()
    }

    fun recoveryReport(): RecoveryReport? = lastRecoveryReport

    // ---- operations ------------------------------------------------------

    fun removePatch(context: Context? = appContext): OperationResult {
        val ctx = context ?: return OperationResult(Failure.NO_GAME_FOLDER)
        return remove(ctx, InstallSlot.PATCH)
    }

    fun removeEdited(context: Context? = appContext): OperationResult {
        val ctx = context ?: return OperationResult(Failure.NO_GAME_FOLDER)
        return remove(ctx, InstallSlot.EDITED_DATA)
    }

    private fun remove(context: Context, slot: InstallSlot): OperationResult {
        if (SaveBackup.gameRunning(context)) {
            return OperationResult(Failure.GAME_RUNNING, "quit the game first")
        }
        val root = gameRoot(context)
            ?: return OperationResult(Failure.NO_GAME_FOLDER, "no game folder")
        return map(PatchInstaller(root).remove(slot))
    }

    fun restorePatch(context: Context? = appContext): OperationResult {
        val ctx = context ?: return OperationResult(Failure.NO_GAME_FOLDER)
        return restore(ctx, InstallSlot.PATCH)
    }

    fun restoreEdited(context: Context? = appContext): OperationResult {
        val ctx = context ?: return OperationResult(Failure.NO_GAME_FOLDER)
        return restore(ctx, InstallSlot.EDITED_DATA)
    }

    private fun restore(context: Context, slot: InstallSlot): OperationResult {
        if (SaveBackup.gameRunning(context)) {
            return OperationResult(Failure.GAME_RUNNING, "quit the game first")
        }
        val root = gameRoot(context)
            ?: return OperationResult(Failure.NO_GAME_FOLDER, "no game folder")
        return map(PatchInstaller(root).restore(slot))
    }

    fun deletePatchBackup(context: Context? = appContext): OperationResult {
        val ctx = context ?: return OperationResult(Failure.NO_GAME_FOLDER)
        return deleteBackup(ctx, InstallSlot.PATCH)
    }

    fun deleteEditBackup(context: Context? = appContext): OperationResult {
        val ctx = context ?: return OperationResult(Failure.NO_GAME_FOLDER)
        return deleteBackup(ctx, InstallSlot.EDITED_DATA)
    }

    private fun deleteBackup(context: Context, slot: InstallSlot): OperationResult {
        if (SaveBackup.gameRunning(context)) {
            return OperationResult(Failure.GAME_RUNNING, "quit the game first")
        }
        val root = gameRoot(context)
            ?: return OperationResult(Failure.NO_GAME_FOLDER, "no game folder")
        return map(PatchInstaller(root).deleteBackup(slot))
    }

    private fun map(result: InstallResult): OperationResult {
        if (result is InstallResult.Success) PatchesDashboard.invalidate()
        return when (result) {
            is InstallResult.Success -> OperationResult()
            is InstallResult.Cancelled -> OperationResult(Failure.FAILED, "cancelled")
            is InstallResult.Failed -> OperationResult(
                when (result.failure) {
                    InstallFailure.BUSY -> Failure.BUSY
                    InstallFailure.NO_BACKUP -> Failure.NO_BACKUP
                    InstallFailure.NO_GAME_FOLDER -> Failure.NO_GAME_FOLDER
                    InstallFailure.GAME_RUNNING -> Failure.GAME_RUNNING
                    else -> Failure.FAILED
                },
                if (result.restored) {
                    result.message
                } else {
                    "the previous data could not be fully restored: ${result.message}"
                },
            )
        }
    }

    // ---- inspection ------------------------------------------------------
    //
    // Everything below is pure File/Properties work: no Context, no Android,
    // so the temp-dir tests drive it directly.

    private data class PatchContent(
        val layout: PatchLayout,
        val cpks: Int,
        val bytes: Long,
        val entries: Int,
        val dpBytes: Long,
        val dpReadable: Boolean,
        val dpSha256: String,
        val manifest: List<String>,
        val sizes: Map<String, Long>,
        val structural: Boolean,
        val modified: Long,
    )

    private data class SaveInfo(
        val version: Int,
        val players: Int,
        val teams: Int,
        val bytes: Long,
        val infName: String?,
        val complete: Boolean,
        val modified: Long,
    )

    /** The shape of a slot directory: absent, empty, list-only, CPKs-only or both. */
    fun patchLayout(dir: File): PatchLayout {
        if (!dir.isDirectory) return PatchLayout.ABSENT
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) return PatchLayout.EMPTY
        val hasDp = files.any { it.name.equals(DP_FILE_LIST, ignoreCase = true) }
        val hasCpk = files.any { it.name.endsWith(".cpk", ignoreCase = true) }
        return when {
            hasDp && hasCpk -> PatchLayout.WITH_DP
            hasDp -> PatchLayout.DP_ONLY
            else -> PatchLayout.NO_DP
        }
    }

    /**
     * Reads one PATCH slot. Null when the folder is absent or empty (the same
     * "nothing installed" screen); otherwise every measured field plus the
     * completeness/provenance verdict.
     *
     * [meta] is `patch-state.properties` for the active slot, or the sidecar
     * for a backup. Passing an empty Properties is how a hand-installed folder
     * is judged.
     */
    fun inspectPatch(dir: File, meta: Properties = Properties()): PatchData? {
        val content = scanPatch(dir)
        if (content.layout == PatchLayout.ABSENT || content.layout == PatchLayout.EMPTY) {
            return null
        }

        val storedName = meta.getProperty("patch.name")
        val storedManifest = meta.getProperty("patch.manifest")
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.let { parseManifest(it) }
            ?.takeIf { it.isNotEmpty() }
        val storedCount = meta.getProperty("patch.cpks")?.toIntOrNull()
        val storedBytes = meta.getProperty("patch.bytes")?.toLongOrNull()
        val storedDpBytes = meta.getProperty("patch.dpBytes")?.toLongOrNull()
        val storedDpSha = meta.getProperty("patch.dpSha256")?.takeIf { it.isNotBlank() }

        var identityMatches = storedName != null
        if (storedName != null) {
            if (storedManifest != null) {
                identityMatches = sameManifest(storedManifest, content.sizes)
            } else if (storedCount != null || storedBytes != null) {
                identityMatches = (storedCount == null || storedCount == content.cpks) &&
                    (storedBytes == null || storedBytes == content.bytes)
            }
            if (storedDpBytes != null) {
                identityMatches = identityMatches && storedDpBytes == content.dpBytes
            }
            if (storedDpSha != null) {
                identityMatches = identityMatches && content.dpReadable &&
                    storedDpSha.equals(content.dpSha256, ignoreCase = true)
            }
        }

        // A hand-installed folder has no recorded identity to compare; judge it
    // on its files. A folder that this app installed must still match its
    // manifest/hash before it is called complete.
    val complete = content.structural && (storedName == null || identityMatches)
        val provenance = when {
            storedName == null -> Provenance.HAND
            complete -> Provenance.APP
            else -> Provenance.STALE
        }
        val sourceLabel = meta.getProperty("patch.sourceLabel") ?: ""
        return PatchData(
            name = storedName ?: "Installed data pack",
            source = meta.getProperty("patch.source") ?: "GAME FOLDER",
            date = storedDate(meta, "patch.installedAt", content.modified),
            cpk = content.cpks,
            bytes = content.bytes,
            entries = content.entries,
            complete = complete,
            fileName = sourceLabel,
            dpBytes = content.dpBytes,
            dpReadable = content.dpReadable,
            provenance = provenance,
            manifest = content.manifest,
            dpSha256 = content.dpSha256,
            sourceLabel = sourceLabel,
            layout = content.layout,
        )
    }

    /** Reads one EDIT slot; [meta] may be the active state or a backup sidecar. */
    fun inspectEdit(dir: File, meta: Properties = Properties()): EditedData? {
        val info = scanSave(dir) ?: return null
        return EditedData(
            name = meta.getProperty("edit.name") ?: info.infName ?: "Edit save",
            date = storedDate(meta, "edit.installedAt", info.modified),
            players = info.players,
            teams = info.teams,
            bytes = info.bytes,
            complete = info.complete,
            fileName = "EDIT00000000",
            version = info.version,
        )
    }

    /**
     * The newest PATCH backup, named from its sidecar when one exists.
     * Without a sidecar (an older backup, or a folder copied in) the name says
     * so and the date is the folder's modification time, never a hardcoded
     * "Previous data pack".
     */
    fun previousPatch(dir: File): PatchData? {
        val props = PatchStateStore.readSidecar(dir)
        val data = inspectPatch(dir, props) ?: return null
        val modified = formatDate(dir.lastModified())
        val name = props.getProperty("patch.name")
            ?: "Previous patch · modified $modified"
        return data.copy(
            name = name,
            source = props.getProperty("patch.source")
                ?: data.source,
            date = storedDate(props, "patch.installedAt", dir.lastModified()),
            complete = data.complete,
        )
    }

    /** The newest EDIT backup, named the same way. */
    fun previousEdit(dir: File): EditedData? {
        val props = PatchStateStore.readSidecar(dir)
        val data = inspectEdit(dir, props) ?: return null
        val modified = formatDate(dir.lastModified())
        val name = props.getProperty("edit.name")
            ?: data.name.takeIf { it != "Edit save" }
            ?: "Previous edit save · modified $modified"
        return data.copy(
            name = name,
            date = storedDate(props, "edit.installedAt", dir.lastModified()),
        )
    }

    private fun scanPatch(dir: File): PatchContent {
        val layout = patchLayout(dir)
        val files = if (dir.isDirectory) {
            dir.listFiles()?.filter { it.isFile } ?: emptyList()
        } else {
            emptyList()
        }
        val cpks = files.filter { it.name.endsWith(".cpk", ignoreCase = true) }
        val dp = files.firstOrNull { it.name.equals(DP_FILE_LIST, ignoreCase = true) }
        val dpData = dp?.let { readCapped(it, MAX_DP_FILE_BYTES) }
        val entries = dpData?.let {
            runCatching { DpFileListParser.parse(it) }.getOrNull()
        }
        val sizes = LinkedHashMap<String, Long>()
        cpks.forEach { sizes[it.name.lowercase(Locale.US)] = it.length() }
        val manifest = cpks.map { "${it.name}:${it.length()}" }.sorted()

        // Structural integrity: the list parses, every file it names is here,
        // and every CPK is at least a header with the right magic. A CPK cut
        // short keeps its "CPK " magic, which is why size is checked against
        // the recorded manifest below instead of trusting the four bytes.
        var structural = layout == PatchLayout.WITH_DP &&
            dpData != null && entries != null && entries.isNotEmpty()
        if (structural) {
            structural = entries!!.all { record ->
                sizes.containsKey(record.name.lowercase(Locale.US))
            }
        }
        if (structural) {
            structural = cpks.all {
                it.length() >= MIN_CPK_BYTES && hasCpkMagic(it)
            }
        }

        return PatchContent(
            layout = layout,
            cpks = cpks.size,
            bytes = cpks.sumOf { it.length() },
            entries = entries?.size ?: 0,
            dpBytes = dp?.length() ?: 0L,
            dpReadable = entries != null,
            dpSha256 = dpData?.let { sha256(it) } ?: "",
            manifest = manifest,
            sizes = sizes,
            structural = structural,
            modified = (dp ?: dir).lastModified(),
        )
    }

    private fun scanSave(dir: File): SaveInfo? {
        if (!dir.isDirectory) return null
        val data = File(dir, "data.bin")
        val inf = File(dir, "data.inf")
        if (!data.isFile && !inf.isFile && dir.listFiles().isNullOrEmpty()) return null
        val infName = if (inf.isFile) {
            val bytes = readPrefix(inf, INF_PREFIX_BYTES)
            bytes?.let {
                String(it, 0, minOf(it.size, INF_PREFIX_BYTES), Charsets.ISO_8859_1)
                    .substringBefore('\u0000')
                    .ifBlank { null }
            }
        } else {
            null
        }
        if (!data.isFile) {
            return SaveInfo(0, 0, 0, 0, infName, complete = false, modified = dir.lastModified())
        }
        val header = ByteArray(SavePayloadParser.MIN_HEADER_BYTES)
        val read = runCatching {
            data.inputStream().use { input ->
                var offset = 0
                while (offset < header.size) {
                    val n = input.read(header, offset, header.size - offset)
                    if (n <= 0) break
                    offset += n
                }
                offset
            }
        }.getOrDefault(0)
        val payload = if (read == header.size) {
            runCatching { SavePayloadParser.parse(header, data.length()) }.getOrNull()
        } else {
            null
        }
        return SaveInfo(
            version = payload?.version ?: 0,
            players = payload?.players ?: 0,
            teams = payload?.teams ?: 0,
            bytes = data.length(),
            infName = infName,
            complete = payload != null,
            modified = data.lastModified(),
        )
    }

    private fun patchBackup(root: File): Previous.Patch? {
        val dir = newestBackup(root, InstallSlot.PATCH) ?: return null
        val data = previousPatch(dir) ?: return null
        return Previous.Patch(data)
    }

    private fun editBackup(root: File): Previous.Edit? {
        val dir = newestBackup(root, InstallSlot.EDITED_DATA) ?: return null
        val data = previousEdit(dir) ?: return null
        return Previous.Edit(data)
    }

    private fun newestBackup(root: File, slot: InstallSlot): File? =
        File(root, "patch-backups/${slot.key}").listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.name }

    /** Whether `saves/SYSTEM00000000` still matches what this app installed. */
    private fun systemIsOurs(root: File, meta: Properties): Boolean {
        val storedSha = meta.getProperty("system.dataSha256")?.takeIf { it.isNotBlank() }
            ?: return false
        val data = File(root, "$SYSTEM_SAVE/data.bin")
        if (!data.isFile) return false
        val storedBytes = meta.getProperty("system.bytes")?.toLongOrNull()
        if (storedBytes != null && storedBytes != data.length()) return false
        // SYSTEM is small (a header plus a few tables). If it ever is not, the
        // recorded size is the best a cheap state read can say.
        if (data.length() > MAX_SYSTEM_HASH_BYTES) return true
        return sha256(data)?.equals(storedSha, ignoreCase = true) == true
    }

    private fun hasCpkMagic(file: File): Boolean {
        if (file.length() < 4) return false
        val magic = ByteArray(4)
        val read = runCatching {
            file.inputStream().use { input ->
                var offset = 0
                while (offset < 4) {
                    val n = input.read(magic, offset, 4 - offset)
                    if (n <= 0) break
                    offset += n
                }
                offset
            }
        }.getOrDefault(0)
        return read == 4 && String(magic, Charsets.ISO_8859_1) == "CPK "
    }

    private fun sizeOf(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun parseManifest(lines: List<String>): Map<String, Long> {
        val sizes = LinkedHashMap<String, Long>()
        for (line in lines) {
            val at = line.lastIndexOf(':')
            if (at <= 0) continue
            val name = line.substring(0, at).trim()
            val size = line.substring(at + 1).trim().toLongOrNull() ?: continue
            if (name.isNotEmpty()) sizes[name.lowercase(Locale.US)] = size
        }
        return sizes
    }

    private fun sameManifest(stored: Map<String, Long>, live: Map<String, Long>): Boolean {
        if (stored.size != live.size) return false
        return stored.all { (name, size) -> live[name] == size }
    }

    /**
     * The DpFileList is a few KB; a corrupt or hand-made file must not make
     * the PATCHES screen allocate its whole length on the main thread.
     */
    private fun readCapped(file: File, max: Int): ByteArray? {
        if (!file.isFile || file.length() > max) return null
        return readPrefix(file, max)
    }

    private fun readPrefix(file: File, max: Int): ByteArray? {
        if (!file.isFile) return null
        return runCatching {
            file.inputStream().use { input ->
                val out = ByteArray(minOf(file.length(), max.toLong()).toInt())
                var offset = 0
                while (offset < out.size) {
                    val read = input.read(out, offset, out.size - offset)
                    if (read <= 0) break
                    offset += read
                }
                if (offset == out.size) out else out.copyOf(offset)
            }
        }.getOrNull()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().toHex()
    }.getOrNull()

    private fun storedDate(meta: Properties, key: String, fallback: Long): String {
        val epoch = meta.getProperty(key)?.toLongOrNull() ?: fallback
        return formatDate(epoch)
    }

    private fun formatDate(epoch: Long): String {
        if (epoch <= 0) return ""
        return SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epoch))
    }

    private fun BackupPolicy.install(): InstallBackupPolicy = when (this) {
        BackupPolicy.KEEP -> InstallBackupPolicy.KEEP
        BackupPolicy.FREE_OLD -> InstallBackupPolicy.FREE_OLD
        BackupPolicy.NONE -> InstallBackupPolicy.NONE
    }

    private const val MAX_DP_FILE_BYTES = 64 * 1024
    private const val INF_PREFIX_BYTES = 384
    private const val MIN_CPK_BYTES = 16L
    private const val DP_FILE_LIST = "DpFileList.bin"
    private const val SYSTEM_SAVE = "saves/SYSTEM00000000"
    private const val MAX_SYSTEM_HASH_BYTES = 8L * 1024 * 1024
}

private fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(HEX_DIGITS[value ushr 4])
        out.append(HEX_DIGITS[value and 0x0F])
    }
    return out.toString()
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()
