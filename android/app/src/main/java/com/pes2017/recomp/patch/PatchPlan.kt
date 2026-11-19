package com.pes2017.recomp.patch

import java.io.IOException

enum class PatchScanFailure {
    NO_PACKAGES,
    WRONG_TITLE,
    NO_DPFILELIST,
    SINGLE_CPK_WITHOUT_LIST,
    UNKNOWN_STFS_HEADER,
    TRUNCATED_CON,
    MISSING_LISTED_CPK,
    UNSAFE_NAME,
    DUPLICATE_CPK,
    DUPLICATE_DPFILELIST,
    CONFLICTING_SAVES,
    CORRUPT,
    PASSWORD,
    MISSING_PART,
    UNSUPPORTED,
    IO,
}

class PatchScanException(
    val failure: PatchScanFailure,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

enum class SaveSlot(val targetName: String) {
    EDIT("EDIT00000000"),
    SYSTEM("SYSTEM00000000"),
}

/** A CPK that would be written under its inner name. */
data class PlannedCpk(
    val innerName: String,
    val sourcePath: String,
    val outerSize: Long,
    val size: Long,
    val blocks: Int,
    val startBlock: Int,
    val consecutive: Boolean,
    val variant: Int,
    val priority: Int?,
    val listed: Boolean,
)

data class PlannedDpFileList(
    val sourcePath: String,
    val innerName: String,
    val size: Long,
    val sha256: String,
    val blocks: Int,
    val startBlock: Int,
    val consecutive: Boolean,
    val entries: List<DpFileListEntry>,
)

/**
 * A unique save slot. The installer copies the outer CON to
 * `saves/<targetName>`; [payload] and [dataBinSha256] describe the inner
 * data.bin, which the scanner read in full and checked block by block.
 */
data class PlannedSave(
    val slot: SaveSlot,
    val sourcePath: String,
    val targetName: String,
    val outerSize: Long,
    val outerSha256: String,
    val dataBinSize: Long,
    val dataBinSha256: String,
    val payload: SavePayload,
    val dataInfName: String?,
    val dataPngSize: Long?,
    val copies: List<String>,
)

/** Save entries that are byte-identical modulo their path. */
data class DuplicateSave(
    val slot: SaveSlot,
    val sha256: String,
    val canonicalPath: String,
    val copyPaths: List<String>,
)

data class SkippedItem(
    val path: String,
    val size: Long,
    val reason: String,
)

/**
 * Everything the installer needs and everything the summary screen shows.
 *
 * Space:
 *  - [cpkBytes] and [dataBinBytes] are the logical payload;
 *  - [rawBytes] = CPKs + DpFileList + unique save CONs, the bytes that will
 *    land in the game folder;
 *  - [stagingBytes] = [rawBytes], because the installer writes a temporary
 *    copy before swapping;
 *  - [sourcePartsBytes] = the archive/patch files that stay where they are
 *    (zero for a folder), including any cache copy made for a pipe;
 *  - [backupBytes] is supplied by the caller from the installed state;
 *  - [peakBytes] = source + staging + backup.
 */
data class PatchPlan(
    val name: String,
    val sourceKind: PatchSourceKind,
    val sourceLabel: String,
    val titleId: String,
    val cpks: List<PlannedCpk>,
    val dpFileList: PlannedDpFileList,
    val saves: List<PlannedSave>,
    val duplicates: List<DuplicateSave>,
    val skipped: List<SkippedItem>,
    val warnings: List<String>,
    val cpkBytes: Long,
    val dataBinBytes: Long,
    val dpFileListBytes: Long,
    val saveBytes: Long,
    val rawBytes: Long,
    val stagingBytes: Long,
    val sourcePartsBytes: Long,
    val backupBytes: Long,
    val peakBytes: Long,
) {
    val cpkCount: Int get() = cpks.size
    val cpkListedCount: Int get() = cpks.count { it.listed }
    val saveCount: Int get() = saves.size

    fun withBackup(bytes: Long): PatchPlan {
        val safe = bytes.coerceAtLeast(0)
        return copy(
            backupBytes = safe,
            peakBytes = safeAdd(sourcePartsBytes, safeAdd(stagingBytes, safe)),
        )
    }

    companion object {
        fun safeAdd(a: Long, b: Long): Long =
            try {
                Math.addExact(a, b)
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
            }
    }
}
