package com.pes2017.recomp.patch

import java.security.MessageDigest

/** One 0x40-byte entry of the STFS file table. */
data class StfsEntry(
    val index: Int,
    val name: String,
    val flags: Int,
    val consecutive: Boolean,
    val directory: Boolean,
    val blocks: Int,
    val start: Int,
    val parent: Int,
    val size: Long,
)

/**
 * A parsed and sanity-checked STFS header and file table.
 *
 * [entries] are exactly the package's file table in package order. Paths are
 * built by following parent indexes; a directory entry's children are not
 * flattened here.
 */
data class StfsHeader(
    val magic: String,
    val layout: StfsLayout,
    val contentType: Long,
    val metadataVersion: Long,
    val titleId: Long,
    val displayName: String,
    val blockSeparation: Int,
    val fileTableBlocks: Int,
    val fileTableBlock: Int,
    val allocatedBlocks: Long,
    val entries: List<StfsEntry>,
) {
    /** Full path of [entry], following parent indexes. */
    fun pathOf(entry: StfsEntry): String {
        val parts = ArrayList<String>()
        val seen = HashSet<Int>()
        var current: StfsEntry? = entry
        while (current != null) {
            if (!seen.add(current.index)) {
                throw StfsException(
                    "bad parent chain at entry ${current.index} (${current.name})",
                    StfsErrorKind.MALFORMED,
                )
            }
            parts.add(current.name)
            current = if (current.parent >= 0) {
                entries.getOrNull(current.parent) ?: throw StfsException(
                    "bad parent index ${current.parent} on ${current.name}",
                    StfsErrorKind.MALFORMED,
                )
            } else {
                null
            }
        }
        parts.reverse()
        return parts.joinToString("/")
    }

    fun find(path: String): StfsEntry? =
        entries.firstOrNull { !it.directory && pathOf(it) == path }

    fun findNamed(name: String): StfsEntry? =
        entries.firstOrNull { !it.directory && it.name.equals(name, ignoreCase = true) }
}

internal object StfsHeaderParser {

    private const val HEADER_READ = 0x1000
    private const val ENTRY_SIZE = 0x40
    private const val MAX_NAME = 0x28
    private const val MAX_FILE_TABLE_BLOCKS = 16

    fun parse(reader: StfsReader): StfsHeader {
        val head = reader.read(0, HEADER_READ)
        val magic = String(head, 0, 4, Charsets.ISO_8859_1)
        if (magic != "CON " && magic != "LIVE" && magic != "PIRS") {
            throw StfsException(
                "not an STFS package (magic $magic)",
                StfsErrorKind.MALFORMED,
            )
        }

        val headerSize = be32(head, 0x340).toInt()
        val layout = StfsLayout.forHeader(headerSize)
        val expectedHeaderBlocks = (layout.headerSize + STFS_BLOCK_SIZE - 1) / STFS_BLOCK_SIZE
        if (expectedHeaderBlocks != layout.headerBlocks) {
            throw StfsException(
                "STFS header 0x${headerSize.toString(16)} needs " +
                    "$expectedHeaderBlocks header blocks, layout says ${layout.headerBlocks}",
                StfsErrorKind.MALFORMED,
            )
        }

        val contentType = be32(head, 0x344)
        val metadataVersion = be32(head, 0x348)
        val titleId = be32(head, 0x360)
        val displayName = parseDisplayName(head)
        val volumeDescriptorSize = head[0x379].toInt() and 0xFF
        if (volumeDescriptorSize != 0x24) {
            throw StfsException(
                "volume descriptor size 0x${volumeDescriptorSize.toString(16)}, expected 0x24",
                StfsErrorKind.MALFORMED,
            )
        }
        val blockSeparation = head[0x37B].toInt() and 0xFF
        val fileTableBlocks = le16(head, 0x37C)
        val fileTableBlock = le24(head, 0x37E)
        val allocatedBlocks = be32(head, 0x395)
        if (fileTableBlocks == 0) {
            throw StfsException("file table block count is zero", StfsErrorKind.MALFORMED)
        }
        if (fileTableBlocks > MAX_FILE_TABLE_BLOCKS) {
            throw StfsException(
                "file table block count $fileTableBlocks exceeds the " +
                    "$MAX_FILE_TABLE_BLOCKS-block sanity limit",
                StfsErrorKind.MALFORMED,
            )
        }
        if (allocatedBlocks == 0L) {
            throw StfsException("allocated block count is zero", StfsErrorKind.MALFORMED)
        }
        if (layout == StfsLayout.SAVE && allocatedBlocks > STFS_PER_LEVEL1) {
            throw StfsException(
                "0x971A package has $allocatedBlocks data blocks; the layout is only " +
                    "verified below 0x70E4 ($STFS_PER_LEVEL1)",
                StfsErrorKind.UNSUPPORTED_LIMIT,
            )
        }

        // SHA1(0x344 .. first hash table) proves the header extent and the
        // number of top tables (one for 0xAD0E, two for 0x971A).
        val firstTable = layout.dataBase - layout.topTables.toLong() * STFS_BLOCK_SIZE
        val storedMaster = reader.read(0x32C, 20)
        val actualMaster = sha1(reader.read(0x344, (firstTable - 0x344).toInt()))
        if (!MessageDigest.isEqual(storedMaster, actualMaster)) {
            throw StfsException(
                "header SHA-1 at 0x32C does not cover 0x344..0x" +
                    firstTable.toString(16) + " (stored ${storedMaster.toHex()}, " +
                    "actual ${actualMaster.toHex()}); wrong layout or corrupt header",
                StfsErrorKind.HASH_MISMATCH,
            )
        }

        if (fileTableBlock.toLong() + fileTableBlocks > allocatedBlocks) {
            throw StfsException(
                "file table blocks $fileTableBlock+$fileTableBlocks exceed " +
                    "$allocatedBlocks allocated blocks",
                StfsErrorKind.MALFORMED,
            )
        }
        val tableBytes = ByteArray(fileTableBlocks * STFS_BLOCK_SIZE)
        for (i in 0 until fileTableBlocks) {
            val blockOffset = dataBlockOffset(reader, layout, fileTableBlock + i)
            reader.read(blockOffset, STFS_BLOCK_SIZE)
                .copyInto(tableBytes, i * STFS_BLOCK_SIZE)
        }
        val entries = parseFileTable(tableBytes, allocatedBlocks)
        if (entries.isEmpty()) {
            throw StfsException("STFS file table is empty", StfsErrorKind.MALFORMED)
        }
        validateEntries(entries, allocatedBlocks)

        // Prove the table positions by hashing data block 0. This is the same
        // sanity check the Python harness performs before reading anything.
        val l0Offset = layout.l0Offset(0)
        val storedBlock0 = reader.read(l0Offset, 0x18)
        val block0 = reader.read(dataBlockOffset(reader, layout, 0), STFS_BLOCK_SIZE)
        val actualBlock0 = sha1(block0)
        if (!MessageDigest.isEqual(storedBlock0.copyOfRange(0, 20), actualBlock0)) {
            throw StfsException(
                "level-0 table at 0x${l0Offset.toString(16)} does not hash data " +
                    "block 0 (stored ${storedBlock0.copyOfRange(0, 20).toHex()}, " +
                    "actual ${actualBlock0.toHex()}); layout 0x" +
                    headerSize.toString(16) + " does not match this package",
                StfsErrorKind.HASH_MISMATCH,
            )
        }

        return StfsHeader(
            magic = magic,
            layout = layout,
            contentType = contentType,
            metadataVersion = metadataVersion,
            titleId = titleId,
            displayName = displayName,
            blockSeparation = blockSeparation,
            fileTableBlocks = fileTableBlocks,
            fileTableBlock = fileTableBlock,
            allocatedBlocks = allocatedBlocks,
            entries = entries,
        )
    }

    private fun parseDisplayName(head: ByteArray): String {
        if (head.size < 0x512) return ""
        val raw = head.copyOfRange(0x411, 0x511)
        val text = String(raw, Charsets.UTF_16BE)
        return text.substringBefore('\u0000').trim()
    }

    private fun parseFileTable(table: ByteArray, allocatedBlocks: Long): List<StfsEntry> {
        val entries = ArrayList<StfsEntry>()
        var offset = 0
        while (offset + ENTRY_SIZE <= table.size) {
            val raw = table.copyOfRange(offset, offset + ENTRY_SIZE)
            if (raw[0].toInt() == 0) break
            val flags = raw[0x28].toInt() and 0xFF
            val nameLength = flags and 0x3F
            if (nameLength == 0 || nameLength > MAX_NAME) {
                throw StfsException(
                    "invalid name length $nameLength at file table entry " +
                        "${entries.size}",
                    StfsErrorKind.MALFORMED,
                )
            }
            val name = String(raw, 0, nameLength, Charsets.ISO_8859_1)
            entries.add(
                StfsEntry(
                    index = entries.size,
                    name = name,
                    flags = flags,
                    consecutive = (flags and 0x40) != 0,
                    directory = (flags and 0x80) != 0,
                    blocks = le24(raw, 0x29),
                    start = le24(raw, 0x2F),
                    parent = be16s(raw, 0x32),
                    size = be32(raw, 0x34),
                ),
            )
            offset += ENTRY_SIZE
        }
        return entries
    }

    private fun validateEntries(entries: List<StfsEntry>, allocatedBlocks: Long) {
        entries.forEach { entry ->
            if (entry.parent < -1 || entry.parent >= entries.size) {
                throw StfsException(
                    "bad parent index ${entry.parent} on ${entry.name}",
                    StfsErrorKind.MALFORMED,
                )
            }
            if (entry.directory) return@forEach
            if (entry.blocks == 0 && entry.size > 0) {
                throw StfsException(
                    "${entry.name}: ${entry.size} bytes but no data blocks",
                    StfsErrorKind.MALFORMED,
                )
            }
            if (entry.blocks > 0) {
                if (entry.start.toLong() >= allocatedBlocks) {
                    throw StfsException(
                        "${entry.name}: start block ${entry.start} is beyond " +
                            "$allocatedBlocks allocated blocks",
                        StfsErrorKind.MALFORMED,
                    )
                }
                if (entry.consecutive &&
                    entry.start.toLong() + entry.blocks > allocatedBlocks
                ) {
                    throw StfsException(
                        "${entry.name}: blocks ${entry.start}.." +
                            "${entry.start + entry.blocks - 1} exceed " +
                            "$allocatedBlocks allocated blocks",
                        StfsErrorKind.MALFORMED,
                    )
                }
                if (entry.size > entry.blocks.toLong() * STFS_BLOCK_SIZE) {
                    throw StfsException(
                        "${entry.name}: size ${entry.size} exceeds ${entry.blocks} " +
                            "blocks (${entry.blocks * STFS_BLOCK_SIZE} bytes)",
                        StfsErrorKind.MALFORMED,
                    )
                }
            }
        }
    }
}
