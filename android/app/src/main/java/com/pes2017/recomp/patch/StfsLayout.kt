package com.pes2017.recomp.patch

import java.io.IOException

/** STFS block size. */
const val STFS_BLOCK_SIZE: Int = 0x1000

/** Data blocks covered by one level-0 hash table. */
const val STFS_PER_TABLE: Int = 0xAA

/** Data blocks covered by one level-1 hash table. */
const val STFS_PER_LEVEL1: Int = 0x70E4

/** Largest 24-bit data block number. */
const val STFS_MAX_BLOCK: Int = 0xFFFFFF

/**
 * Why a package could not be read.
 *
 * [UNKNOWN_LAYOUT] and [TRUNCATED] are detection failures; [HASH_MISMATCH]
 * means the file is corrupt or the layout does not match the bytes.
 */
enum class StfsErrorKind {
    UNKNOWN_LAYOUT,
    TRUNCATED,
    HASH_MISMATCH,
    MALFORMED,
    UNSUPPORTED_LIMIT,
}

class StfsException(
    message: String,
    val kind: StfsErrorKind = StfsErrorKind.MALFORMED,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * The two STFS hash-table layouts used by PES 2018 content.
 *
 * Data block 0 is at 0xC000 in both. Hash tables are inserted between data
 * blocks and are not part of the block numbering, so the file offset of a
 * data block depends on its layout and on how many group boundaries it has
 * crossed.
 *
 * [PERSISTENT] (0xAD0E) is used by CPK and DpFileList CONs: one table per
 * group of 0xAA data blocks.
 *
 * [SAVE] (0x971A) is used by EDIT/SYSTEM saves: two tables per group
 * (primary + backup) and two top tables at 0xA000 and 0xB000.
 *
 * The 0x70E4 term below is verified for PERSISTENT. For SAVE it is inferred
 * by doubling the verified term; no available save reaches it, so access at
 * or beyond 0x70E4 is rejected instead of guessed.
 */
enum class StfsLayout(
    val headerSize: Int,
    val headerBlocks: Int,
    val topTables: Int,
    val duplicateTables: Int,
) {
    PERSISTENT(0xAD0E, 11, 1, 1),
    SAVE(0x971A, 10, 2, 2),
    ;

    /** First data block (block 0) offset. */
    val dataBase: Long
        get() = (headerBlocks + topTables).toLong() * STFS_BLOCK_SIZE

    /**
     * Hash-table blocks inserted between data block 0 and [block].
     *
     * The PERSISTENT 0x70E4 term is verified against the real packages. The
     * SAVE term is inferred and therefore guarded in [offset].
     */
    fun tablesBefore(block: Int): Long {
        if (block < 0 || block > STFS_MAX_BLOCK) {
            throw StfsException(
                "data block ${block.toString(16)} is outside the 24-bit range",
                StfsErrorKind.MALFORMED,
            )
        }
        var tables = 0L
        if (block >= STFS_PER_TABLE) {
            tables += (block / STFS_PER_TABLE + 1).toLong() * duplicateTables
        }
        if (block >= STFS_PER_LEVEL1) {
            if (this == SAVE) {
                throw unsupportedSaveLimit(block)
            }
            tables += (block / STFS_PER_LEVEL1 + 1).toLong() * duplicateTables
        }
        return tables
    }

    /** File offset of data block [block]. */
    fun offset(block: Int): Long {
        if (this == SAVE && block >= STFS_PER_LEVEL1) {
            throw unsupportedSaveLimit(block)
        }
        return dataBase + (block + tablesBefore(block)) * STFS_BLOCK_SIZE
    }

    /**
     * File offset of the level-0 table that hashes [block].
     *
     * Group 0's primary table is the last top table. Later groups are
     * immediately preceded by their L0 tables; the primary is the first of
     * [duplicateTables] tables before the group's data.
     */
    fun l0Offset(block: Int): Long {
        if (block < 0 || block > STFS_MAX_BLOCK) {
            throw StfsException(
                "data block ${block.toString(16)} is outside the 24-bit range",
                StfsErrorKind.MALFORMED,
            )
        }
        val group = block / STFS_PER_TABLE
        return if (group == 0) {
            headerBlocks.toLong() * STFS_BLOCK_SIZE
        } else {
            offset(group * STFS_PER_TABLE) - duplicateTables.toLong() * STFS_BLOCK_SIZE
        }
    }

    private fun unsupportedSaveLimit(block: Int): StfsException = StfsException(
        "0x971A data block ${block.toString(16)} is at or beyond 0x70E4 " +
            "($STFS_PER_LEVEL1); the 0x971A supergroup term is inferred and " +
            "no save this large has been verified",
        StfsErrorKind.UNSUPPORTED_LIMIT,
    )

    companion object {
        fun forHeader(headerSize: Int): StfsLayout =
            entries.firstOrNull { it.headerSize == headerSize } ?: throw StfsException(
                "unknown STFS hash layout header 0x${headerSize.toString(16)} " +
                    "(supported: " + entries.joinToString { "0x${it.headerSize.toString(16)}" } +
                    "); refusing to guess a table structure",
                StfsErrorKind.UNKNOWN_LAYOUT,
            )
    }
}

internal fun dataBlockOffset(reader: StfsReader, layout: StfsLayout, block: Int): Long {
    val offset = layout.offset(block)
    if (offset < 0 || offset + STFS_BLOCK_SIZE > reader.size) {
        throw StfsException(
            "data block ${block.toString(16)} at 0x${offset.toString(16)} is past " +
                "the end of the package (0x${reader.size.toString(16)})",
            StfsErrorKind.TRUNCATED,
        )
    }
    return offset
}
