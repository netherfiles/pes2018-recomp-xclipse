package com.pes2017.recomp.patch.install

import com.pes2017.recomp.patch.PatchPlan
import com.pes2017.recomp.patch.SaveSlot

/**
 * The three things an import can write. Their paths are relative to the game
 * root (the folder above the game folder, where the runtime puts `saves/` and
 * `dlc/`).
 *
 * PATCH is the DLC package the title mounts for the data pack. It holds the
 * inner CPK files and the inner DpFileList bytes, never the STFS CON wrappers.
 * EDITED_DATA and SYSTEM are save packages that the runtime mounts as
 * directories, so their inner files land inside the slot directory.
 */
enum class InstallSlot(val key: String, val relativePath: String) {
    PATCH("patch", "dlc/SYSTEM00000000"),
    EDITED_DATA("edit", "saves/EDIT00000000"),
    SYSTEM("system", "saves/SYSTEM00000000");

    val isSave: Boolean get() = this != PATCH

    val targetName: String
        get() = relativePath.substringAfterLast('/')

    companion object {
        fun forSave(slot: SaveSlot): InstallSlot = when (slot) {
            SaveSlot.EDIT -> EDITED_DATA
            SaveSlot.SYSTEM -> SYSTEM
        }

        fun forKey(key: String): InstallSlot? = values().firstOrNull { it.key == key }
    }
}

/** What the summary screen has ticked. */
data class InstallSelection(
    val patch: Boolean = true,
    val editedData: Boolean = true,
    val system: Boolean = false,
) {
    val any: Boolean get() = patch || editedData || system

    fun includes(slot: InstallSlot): Boolean = when (slot) {
        InstallSlot.PATCH -> patch
        InstallSlot.EDITED_DATA -> editedData
        InstallSlot.SYSTEM -> system
    }

    fun slots(): List<InstallSlot> = InstallSlot.values().filter { includes(it) }

    /** The selection restricted to what this plan actually carries. */
    fun availableIn(plan: PatchPlan): InstallSelection = copy(
        patch = patch && plan.cpks.isNotEmpty(),
        editedData = editedData && plan.saves.any { it.slot == SaveSlot.EDIT },
        system = system && plan.saves.any { it.slot == SaveSlot.SYSTEM },
    )
}

/** What happens to the data installed now when it is replaced. */
enum class InstallBackupPolicy {
    /** The active slot moves to patch-backups/<slot>/<timestamp>. */
    KEEP,

    /** The old backup is deleted before staging, then the active slot is kept. */
    FREE_OLD,

    /** The active slot is discarded. */
    NONE,
}

enum class InstallFailure {
    NOTHING_SELECTED,
    NO_GAME_FOLDER,
    BUSY,
    GAME_RUNNING,
    LOW_SPACE,
    NO_BACKUP,
    SOURCE,
    VERIFY,
    IO,
    CANCELLED,
}

sealed class InstallResult {
    data class Success(
        val slots: List<InstallSlot>,
        val bytes: Long,
        val backup: InstallBackupPolicy,
    ) : InstallResult()

    data class Cancelled(
        val restored: Boolean,
        val detail: String = "",
    ) : InstallResult()

    /**
     * [restored] is true when the active slots are known to be intact: either
     * nothing was swapped, or the rollback finished. It is false only when a
     * previous slot could not be put back, which the result screen must say
     * instead of claiming that nothing changed.
     */
    data class Failed(
        val failure: InstallFailure,
        val message: String,
        val restored: Boolean = true,
    ) : InstallResult()

    val isSuccess: Boolean get() = this is Success
}

enum class InstallPhase {
    PREPARING,
    SCANNING,
    STAGING,
    VERIFYING,
    SWAPPING,
    CLEANING,
    DONE,
    CANCELLED,
    FAILED,
}

data class InstallProgress(
    val phase: InstallPhase,
    val percent: Int,
    val detail: String = "",
    /**
     * The exact counters behind [percent]: bytes read from the source and
     * written so far, and the run's whole work total. They are carried
     * separately because [percent] is an overall 0-100 whose staging half is
     * capped at 85; reading bytes back out of the percentage is wrong exactly
     * when a staging run is killed near that cap.
     *
     * Trailing defaults only: every caller that predates them still compiles,
     * and 0/0 means "not known yet", not "nothing done".
     */
    val processedBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

/**
 * The space a run needs, with every component named.
 *
 * [stagingBytes] is what the selected payload writes under patch-staging,
 * [backupBytes] is the size of the active slots that move to patch-backups,
 * [spoolBytes] is the largest selected CON that must be copied to a seekable
 * file when the source is an archive, and [marginBytes] is headroom for the
 * filesystem. [freeBytes] is read before anything is written; for FREE_OLD
 * the backup that is deleted first is added as [freedOldBackupBytes].
 */
data class InstallSpace(
    val stagingBytes: Long,
    val backupBytes: Long,
    val spoolBytes: Long,
    val marginBytes: Long,
    val freeBytes: Long,
    val freedOldBackupBytes: Long = 0L,
) {
    val requiredBytes: Long = safeAdd(
        safeAdd(stagingBytes, backupBytes),
        safeAdd(spoolBytes, marginBytes),
    )
    val availableBytes: Long = safeAdd(freeBytes, freedOldBackupBytes)
    val enough: Boolean get() = availableBytes >= requiredBytes
    val shortfallBytes: Long get() = (requiredBytes - availableBytes).coerceAtLeast(0)

    companion object {
        const val MARGIN_BYTES: Long = 64L * 1024 * 1024

        fun safeAdd(a: Long, b: Long): Long = try {
            Math.addExact(a, b)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }
}

/**
 * A journal recovered at startup. An empty report means there was nothing to
 * do; a non-empty one explains what was put back.
 */
data class RecoveryReport(
    val busy: Boolean = false,
    val restoredSlots: List<InstallSlot> = emptyList(),
    val discardedStaging: Boolean = false,
    val committedInstallKept: Boolean = false,
    val problems: List<String> = emptyList(),
) {
    val changed: Boolean get() = restoredSlots.isNotEmpty() || discardedStaging || committedInstallKept
}

/** Thrown by the copy sink so a cancel unwinds through StfsPackage. */
class InstallCancelledException : RuntimeException("install cancelled")

/**
 * Test seam: thrown after a journal phase, and deliberately not rolled back,
 * so a test can prove recovery from a process that died at that point.
 */
class SimulatedProcessDeath(val point: String) : RuntimeException("simulated process death at $point")

enum class FaultPoint {
    STAGED,
    JOURNAL_READY,
    OLD_MOVED,
    NEW_MOVED,
    COMMITTED,

    /** The slot being removed has been renamed out of the active tree. */
    REMOVE_STAGED,
}
