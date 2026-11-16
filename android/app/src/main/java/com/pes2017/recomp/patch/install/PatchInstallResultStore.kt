package com.pes2017.recomp.patch.install

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * The durable outcome of the last PATCH import, stored next to
 * `patch-state.properties` in the game root.
 *
 * The service that owns an import can go away without returning from
 * [PatchInstaller.install]: MIUI/HyperOS stops a dataSync foreground service
 * after a few minutes ("Stop FGS timeout"), Android 15 calls
 * `Service.onTimeout`, and a force-stop or an OOM kill calls
 * nothing at all. The static snapshot in the service dies with the process, and
 * an activity that re-applies it shows a percentage for a run that is over.
 *
 * This file is written atomically before the service stops, so the import
 * screen can state what the run actually did. A missing record is meaningful
 * too: it means no terminal state was ever published, and the caller must fall
 * back to the journal and the active slots.
 *
 * A record that says `state=RUNNING` is the start marker, not an outcome:
 * [read] returns null for it on purpose, so a screen that asks "what happened"
 * is never handed a run that has not reported yet. The seconds inside a run are
 * in [PatchInstallLiveStore], next to it in the same folder.
 *
 * Only the outcome is stored: no source URI, no archive password, no folder
 * path beyond the file's own location.
 */
object PatchInstallResultStore {

    enum class State {
        SUCCESS,
        FAILED,
        CANCELLED,

        /**
         * The run stopped while it was still swapping or staging: the previous
         * slots are the ones on disk, unless recovery says the swap committed.
         */
        INTERRUPTED,
    }

    /**
     * A terminal outcome. Every field after [timestamp] describes the run that
     * produced it, so a record read after a process death still says which
     * install it was and how long it had been going. All of them have defaults
     * so a record written by an older build, or by a test, is still valid.
     */
    data class Record(
        val state: State,
        val phase: InstallPhase,
        val message: String,
        val slots: List<InstallSlot>,
        val bytes: Long,
        val timestamp: Long,
        /** The id ACTION_START generated; also the journal's id. */
        val installId: String = "",
        val startedAt: Long = 0L,
        val updatedAt: Long = 0L,
        val finishedAt: Long = 0L,
        val durationMs: Long = 0L,
        val pid: Long = 0L,
        val percent: Int = 0,
        val processedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val file: String = "",
        val policy: String = "",
        val selection: String = "",
        val sourceKind: String = "",
        val planCpk: Int = 0,
        val planBytes: Long = 0L,
        val dpBytes: Long = 0L,
        val dpEntries: Int = 0,
    )

    /**
     * The start marker of a run that is still going. It is written before the
     * source is even scanned, so a hard kill at any point leaves enough to say
     * "this install started at ... with this policy" instead of an orphaned
     * percentage.
     */
    data class Running(
        val installId: String,
        val startedAt: Long,
        val updatedAt: Long,
        val pid: Long,
        val phase: InstallPhase,
        val percent: Int,
        val selection: String,
        val policy: String,
        val sourceKind: String,
        val slots: List<InstallSlot>,
        val planCpk: Int = 0,
        val planBytes: Long = 0L,
        val dpBytes: Long = 0L,
        val dpEntries: Int = 0,
        val processedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val file: String = "",
    )

    fun file(root: File): File = File(root, FILE_NAME)

    /** Null when nothing was published, when the run has not finished, or when the record is unreadable. */
    fun read(root: File?): Record? {
        val props = load(root) ?: return null
        val state = props.getProperty(KEY_STATE)
            ?.let { runCatching { State.valueOf(it) }.getOrNull() }
            ?: return null
        return Record(
            state = state,
            phase = phaseOf(props.getProperty(KEY_PHASE), InstallPhase.DONE),
            message = props.getProperty(KEY_MESSAGE).orEmpty(),
            slots = slotsOf(props.getProperty(KEY_SLOTS)),
            bytes = props.getProperty(KEY_BYTES)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            timestamp = props.getProperty(KEY_TIMESTAMP)?.toLongOrNull() ?: 0L,
            installId = props.getProperty(KEY_INSTALL_ID).orEmpty(),
            startedAt = props.getProperty(KEY_STARTED_AT)?.toLongOrNull() ?: 0L,
            updatedAt = props.getProperty(KEY_UPDATED_AT)?.toLongOrNull() ?: 0L,
            finishedAt = props.getProperty(KEY_FINISHED_AT)?.toLongOrNull() ?: 0L,
            durationMs = props.getProperty(KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            pid = props.getProperty(KEY_PID)?.toLongOrNull() ?: 0L,
            percent = props.getProperty(KEY_PERCENT)?.toIntOrNull()?.coerceIn(0, 100) ?: 0,
            processedBytes = props.getProperty(KEY_PROCESSED)?.toLongOrNull() ?: 0L,
            totalBytes = props.getProperty(KEY_TOTAL)?.toLongOrNull() ?: 0L,
            file = props.getProperty(KEY_FILE).orEmpty(),
            policy = props.getProperty(KEY_POLICY).orEmpty(),
            selection = props.getProperty(KEY_SELECTION).orEmpty(),
            sourceKind = props.getProperty(KEY_SOURCE_KIND).orEmpty(),
            planCpk = props.getProperty(KEY_PLAN_CPK)?.toIntOrNull() ?: 0,
            planBytes = props.getProperty(KEY_PLAN_BYTES)?.toLongOrNull() ?: 0L,
            dpBytes = props.getProperty(KEY_DP_BYTES)?.toLongOrNull() ?: 0L,
            dpEntries = props.getProperty(KEY_DP_ENTRIES)?.toIntOrNull() ?: 0,
        )
    }

    /**
     * Replaces the record in one rename. Callers wrap this in runCatching: a
     * folder the app cannot write must not fail an install that already
     * committed.
     */
    fun write(root: File?, record: Record) {
        if (root == null || !root.isDirectory) return
        val updated = record.updatedAt.takeIf { it > 0 } ?: record.timestamp
        val finished = record.finishedAt.takeIf { it > 0 } ?: record.timestamp
        val props = Properties()
        props[KEY_VERSION] = VERSION
        // The old keys stay, so every reader written before the install id
        // existed keeps working against the same file.
        props[KEY_STATE] = record.state.name
        props[KEY_PHASE] = record.phase.name
        props[KEY_MESSAGE] = oneLine(record.message)
        props[KEY_SLOTS] = record.slots.joinToString(",") { it.key }
        props[KEY_BYTES] = record.bytes.toString()
        props[KEY_TIMESTAMP] = record.timestamp.toString()
        props[KEY_INSTALL_ID] = record.installId
        props[KEY_STARTED_AT] = record.startedAt.toString()
        props[KEY_UPDATED_AT] = updated.toString()
        props[KEY_FINISHED_AT] = finished.toString()
        props[KEY_DURATION] = record.durationMs.toString()
        props[KEY_PID] = record.pid.toString()
        props[KEY_INSTALL_PHASE] = record.phase.name
        props[KEY_PERCENT] = record.percent.toString()
        props[KEY_PROCESSED] = record.processedBytes.toString()
        props[KEY_TOTAL] = record.totalBytes.toString()
        props[KEY_FILE] = oneLine(record.file, MAX_FILE)
        props[KEY_INSTALL_SLOTS] = record.slots.joinToString(",") { it.key }
        props[KEY_POLICY] = record.policy
        props[KEY_SELECTION] = record.selection
        props[KEY_SOURCE_KIND] = record.sourceKind
        props[KEY_PLAN_CPK] = record.planCpk.toString()
        props[KEY_PLAN_BYTES] = record.planBytes.toString()
        props[KEY_DP_BYTES] = record.dpBytes.toString()
        props[KEY_DP_ENTRIES] = record.dpEntries.toString()
        writeAtomically(file(root), props, "PES 2018 last patch import result")
    }

    /** Writes the start marker; a terminal record from the previous run is replaced. */
    fun writeRunning(root: File?, record: Running) {
        if (root == null || !root.isDirectory) return
        val props = Properties()
        props[KEY_VERSION] = VERSION
        props[KEY_STATE] = STATE_RUNNING
        props[KEY_PHASE] = record.phase.name
        props[KEY_MESSAGE] = ""
        props[KEY_SLOTS] = record.slots.joinToString(",") { it.key }
        props[KEY_BYTES] = "0"
        props[KEY_TIMESTAMP] = record.updatedAt.toString()
        props[KEY_INSTALL_ID] = record.installId
        props[KEY_STARTED_AT] = record.startedAt.toString()
        props[KEY_UPDATED_AT] = record.updatedAt.toString()
        props[KEY_PID] = record.pid.toString()
        props[KEY_INSTALL_PHASE] = record.phase.name
        props[KEY_PERCENT] = record.percent.toString()
        props[KEY_PROCESSED] = record.processedBytes.toString()
        props[KEY_TOTAL] = record.totalBytes.toString()
        props[KEY_FILE] = oneLine(record.file, MAX_FILE)
        props[KEY_INSTALL_SLOTS] = record.slots.joinToString(",") { it.key }
        props[KEY_POLICY] = record.policy
        props[KEY_SELECTION] = record.selection
        props[KEY_SOURCE_KIND] = record.sourceKind
        props[KEY_PLAN_CPK] = record.planCpk.toString()
        props[KEY_PLAN_BYTES] = record.planBytes.toString()
        props[KEY_DP_BYTES] = record.dpBytes.toString()
        props[KEY_DP_ENTRIES] = record.dpEntries.toString()
        writeAtomically(file(root), props, "PES 2018 patch import in progress")
    }

    /** Called when a new install starts; the old outcome no longer applies. */
    fun clear(root: File?) {
        if (root == null) return
        runCatching { file(root).delete() }
        runCatching { File(root, FILE_NAME + TEMP_SUFFIX).delete() }
    }

    private fun load(root: File?): Properties? {
        if (root == null) return null
        val source = file(root)
        if (!source.isFile) return null
        val props = Properties()
        runCatching { source.inputStream().use { props.load(it) } }.getOrElse { return null }
        if (props.getProperty(KEY_VERSION) != VERSION) return null
        return props
    }

    private fun oneLine(message: String, limit: Int = MAX_MESSAGE): String = message
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .take(limit)

    const val FILE_NAME = "patch-install-result.properties"

    private const val VERSION = "1"
    private const val STATE_RUNNING = "RUNNING"
    private const val MAX_MESSAGE = 400
    private const val MAX_FILE = 200

    private const val KEY_VERSION = "version"
    private const val KEY_STATE = "state"
    private const val KEY_PHASE = "phase"
    private const val KEY_MESSAGE = "message"
    private const val KEY_SLOTS = "slots"
    private const val KEY_BYTES = "bytes"
    private const val KEY_TIMESTAMP = "timestamp"

    // `phase` and `slots` already exist; these are the same facts under the
    // install namespace, where a reader can find everything with one prefix.
    private const val KEY_INSTALL_ID = "install.id"
    private const val KEY_STARTED_AT = "install.startedAt"
    private const val KEY_UPDATED_AT = "install.updatedAt"
    private const val KEY_FINISHED_AT = "install.finishedAt"
    private const val KEY_DURATION = "install.durationMs"
    private const val KEY_PID = "install.pid"
    private const val KEY_INSTALL_PHASE = "install.phase"
    private const val KEY_PERCENT = "install.percent"
    private const val KEY_PROCESSED = "install.processedBytes"
    private const val KEY_TOTAL = "install.totalBytes"
    private const val KEY_FILE = "install.file"
    private const val KEY_INSTALL_SLOTS = "install.slots"
    private const val KEY_POLICY = "install.policy"
    private const val KEY_SELECTION = "install.selection"
    private const val KEY_SOURCE_KIND = "install.sourceKind"
    private const val KEY_PLAN_CPK = "install.planCpk"
    private const val KEY_PLAN_BYTES = "install.planBytes"
    private const val KEY_DP_BYTES = "install.dpBytes"
    private const val KEY_DP_ENTRIES = "install.dpEntries"
}

/**
 * The seconds of a run that is still going: `patch-install-live.properties` in
 * the game root, replaced at most once a second and at least every five.
 *
 * It exists because the terminal record can only be written by a process that
 * is still alive. A hard kill leaves this file behind, and that leftover is the
 * proof the run did not finish: the service deletes it in [finish] and on every
 * other terminal path, so a file found after a restart means the process was
 * taken inside the install.
 *
 * Not a journal: recovery never reads it, and losing a write costs one second
 * of detail, never the transaction.
 */
object PatchInstallLiveStore {

    data class Live(
        val installId: String,
        val pid: Long,
        val startedAt: Long,
        val updatedAt: Long,
        val phase: InstallPhase,
        val percent: Int,
        val processedBytes: Long,
        val totalBytes: Long,
        val file: String,
        val slots: List<InstallSlot>,
    )

    fun file(root: File): File = File(root, FILE_NAME)

    fun write(root: File?, live: Live) {
        if (root == null || !root.isDirectory) return
        val props = Properties()
        props[KEY_VERSION] = VERSION
        props[KEY_INSTALL_ID] = live.installId
        props[KEY_PID] = live.pid.toString()
        props[KEY_STARTED_AT] = live.startedAt.toString()
        props[KEY_UPDATED_AT] = live.updatedAt.toString()
        props[KEY_PHASE] = live.phase.name
        props[KEY_PERCENT] = live.percent.toString()
        props[KEY_PROCESSED] = live.processedBytes.toString()
        props[KEY_TOTAL] = live.totalBytes.toString()
        props[KEY_FILE] = live.file
        props[KEY_SLOTS] = live.slots.joinToString(",") { it.key }
        writeAtomically(file(root), props, "PES 2018 patch import heartbeat")
    }

    fun read(root: File?): Live? {
        if (root == null) return null
        val source = file(root)
        if (!source.isFile) return null
        val props = Properties()
        runCatching { source.inputStream().use { props.load(it) } }.getOrElse { return null }
        if (props.getProperty(KEY_VERSION) != VERSION) return null
        val installId = props.getProperty(KEY_INSTALL_ID).orEmpty()
        if (installId.isEmpty()) return null
        return Live(
            installId = installId,
            pid = props.getProperty(KEY_PID)?.toLongOrNull() ?: 0L,
            startedAt = props.getProperty(KEY_STARTED_AT)?.toLongOrNull() ?: 0L,
            updatedAt = props.getProperty(KEY_UPDATED_AT)?.toLongOrNull() ?: 0L,
            phase = phaseOf(props.getProperty(KEY_PHASE), InstallPhase.PREPARING),
            percent = props.getProperty(KEY_PERCENT)?.toIntOrNull()?.coerceIn(0, 100) ?: 0,
            processedBytes = props.getProperty(KEY_PROCESSED)?.toLongOrNull() ?: 0L,
            totalBytes = props.getProperty(KEY_TOTAL)?.toLongOrNull() ?: 0L,
            file = props.getProperty(KEY_FILE).orEmpty(),
            slots = slotsOf(props.getProperty(KEY_SLOTS)),
        )
    }

    /** Deletes the heartbeat: the run is over, whatever the outcome. */
    fun clear(root: File?) {
        if (root == null) return
        runCatching { file(root).delete() }
        runCatching { File(root, FILE_NAME + TEMP_SUFFIX).delete() }
    }

    const val FILE_NAME = "patch-install-live.properties"

    private const val VERSION = "1"
    private const val KEY_VERSION = "version"
    private const val KEY_INSTALL_ID = "install.id"
    private const val KEY_PID = "pid"
    private const val KEY_STARTED_AT = "startedAt"
    private const val KEY_UPDATED_AT = "updatedAt"
    private const val KEY_PHASE = "phase"
    private const val KEY_PERCENT = "percent"
    private const val KEY_PROCESSED = "processedBytes"
    private const val KEY_TOTAL = "totalBytes"
    private const val KEY_FILE = "file"
    private const val KEY_SLOTS = "slots"
}

private fun slotsOf(value: String?): List<InstallSlot> = value
    .orEmpty()
    .split(',')
    .mapNotNull { InstallSlot.forKey(it.trim()) }

private fun phaseOf(value: String?, fallback: InstallPhase): InstallPhase =
    value?.let { runCatching { InstallPhase.valueOf(it) }.getOrNull() } ?: fallback

private const val TEMP_SUFFIX = ".tmp"

/**
 * Replace a properties file in one rename, so a process death leaves either the
 * old file or the new one, never half of either.
 */
private fun writeAtomically(file: File, props: Properties, comment: String) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
    FileOutputStream(temp).use { out ->
        props.store(out, comment)
        out.fd.sync()
    }
    try {
        Files.move(
            temp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
