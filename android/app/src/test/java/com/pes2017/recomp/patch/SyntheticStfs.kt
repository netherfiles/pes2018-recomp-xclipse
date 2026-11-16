package com.pes2017.recomp.patch

import java.io.File
import java.security.MessageDigest

/**
 * Builds small, valid STFS packages for JVM tests. No game data is used.
 *
 * The fixture has a file table in data block 0, consecutive or explicitly
 * chained data blocks, and every level-0 hash table (plus the matching
 * level-1 tables when a fixture crosses the 0xAA boundary). The header hash
 * and the L0 entry for block 0 are real, so the production parser accepts it.
 */
internal object SyntheticStfs {

    private const val BLOCK = 0x1000
    private const val TITLE = 0x4A3007D3L

    data class Spec(
        val name: String,
        val data: ByteArray,
        val consecutive: Boolean = true,
        val chain: List<Int>? = null,
        val directory: Boolean = false,
    )

    fun build(
        path: File,
        layout: StfsLayout = StfsLayout.PERSISTENT,
        contentType: Long = 1,
        titleId: Long = TITLE,
        specs: List<Spec>,
    ): File {
        val blocks = HashMap<Int, ByteArray>()
        val next = HashMap<Int, Int>()
        val tableEntries = ArrayList<TableEntry>()
        var cursor = 1

        for (spec in specs) {
            if (spec.directory) {
                tableEntries.add(TableEntry(spec.name, true, false, 0, 0, 0))
                continue
            }
            val count = if (spec.data.isEmpty()) 0 else (spec.data.size + BLOCK - 1) / BLOCK
            val assigned = spec.chain ?: (0 until count).map { cursor + it }
            if (assigned.size != count) {
                throw IllegalArgumentException("chain size ${assigned.size} != $count")
            }
            if (spec.chain == null) {
                cursor += count
            } else {
                cursor = maxOf(cursor, (assigned.maxOrNull() ?: 0) + 1)
            }
            for (i in 0 until count) {
                val chunk = ByteArray(BLOCK)
                val from = i * BLOCK
                val length = minOf(BLOCK, spec.data.size - from)
                spec.data.copyInto(chunk, 0, from, from + length)
                blocks[assigned[i]] = chunk
                if (i + 1 < count) next[assigned[i]] = assigned[i + 1]
            }
            tableEntries.add(
                TableEntry(
                    name = spec.name,
                    directory = false,
                    consecutive = spec.consecutive,
                    blocks = count,
                    start = assigned.firstOrNull() ?: 0,
                    size = spec.data.size.toLong(),
                ),
            )
        }

        val maxBlock = maxOf(0, blocks.keys.maxOrNull() ?: 0)
        val fileSize = (layout.offset(maxBlock) + BLOCK).toInt()
        val data = ByteArray(fileSize)

        // Data block 0 is the file table.
        val table = ByteArray(BLOCK)
        tableEntries.forEachIndexed { index, entry ->
            val raw = ByteArray(0x40)
            val name = entry.name.toByteArray(Charsets.ISO_8859_1)
            require(name.size in 1..0x28) { "name too long: ${entry.name}" }
            name.copyInto(raw, 0)
            var flags = name.size
            if (entry.consecutive) flags = flags or 0x40
            if (entry.directory) flags = flags or 0x80
            raw[0x28] = flags.toByte()
            writeLe24(raw, 0x29, entry.blocks)
            writeLe24(raw, 0x2C, entry.blocks)
            writeLe24(raw, 0x2F, entry.start)
            raw[0x32] = 0xFF.toByte()
            raw[0x33] = 0xFF.toByte()
            writeBe32(raw, 0x34, entry.size)
            raw.copyInto(table, index * 0x40)
        }
        blocks[0] = table
        blocks.forEach { (block, chunk) ->
            chunk.copyInto(data, layout.offset(block).toInt())
        }

        writeHashTables(data, layout, blocks, next, maxBlock)

        "CON ".toByteArray(Charsets.ISO_8859_1).copyInto(data, 0)
        writeBe32(data, 0x340, layout.headerSize.toLong())
        writeBe32(data, 0x344, contentType)
        writeBe32(data, 0x348, 2)
        writeBe32(data, 0x360, titleId and 0xFFFFFFFFL)
        data[0x379] = 0x24
        data[0x37B] = if (layout == StfsLayout.SAVE) 2 else 1
        data[0x37C] = 1
        data[0x37D] = 0
        data[0x37E] = 0
        data[0x37F] = 0
        data[0x380] = 0
        writeBe32(data, 0x395, (maxBlock + 1).toLong())
        "Synthetic".toByteArray(Charsets.UTF_16BE).copyInto(data, 0x411)

        val firstTable = layout.dataBase - layout.topTables.toLong() * BLOCK
        val master = sha1(data.copyOfRange(0x344, firstTable.toInt()))
        master.copyInto(data, 0x32C)

        path.parentFile?.mkdirs()
        path.writeBytes(data)
        return path
    }

    fun cpkPayload(size: Int, variant: Int = 0xFF): ByteArray {
        val data = ByteArray(size)
        "CPK ".toByteArray(Charsets.ISO_8859_1).copyInto(data, 0)
        data[4] = variant.toByte()
        return data
    }

    fun dpFileList(vararg names: String): ByteArray {
        val data = ByteArray((names.size + 1) * 48)
        names.forEachIndexed { index, name ->
            val offset = index * 48
            writeBe32(data, offset, 0x00000262)
            writeBe32(data, offset + 4, (100 - index).toLong())
            writeBe32(data, offset + 8, 100)
            writeBe32(data, offset + 12, 101)
            name.toByteArray(Charsets.ISO_8859_1).copyInto(data, offset + 16)
        }
        return data
    }

    fun saveDataBin(version: Int, payloadSize: Int = 16, teams: Int = 401): ByteArray {
        val data = ByteArray(80 + payloadSize)
        writeLe32(data, 0, version.toLong())
        writeBe32(data, 4, 80)
        writeBe32(data, 8, payloadSize.toLong())
        writeBe32(data, 12, 10_502)
        writeBe32(data, 16, teams.toLong())
        writeBe32(data, 20, teams.toLong())
        data[0x20] = if (version == 14) 54 else 67
        return data
    }

    fun saveCon(
        path: File,
        version: Int = 14,
        titleId: Long = TITLE,
        contentType: Long = 1,
        dataInf: String = "Dados Editados",
    ): File = build(
        path = path,
        layout = StfsLayout.SAVE,
        contentType = contentType,
        titleId = titleId,
        specs = listOf(
            Spec("data.inf", dataInf.toByteArray(Charsets.ISO_8859_1).copyOf(384)),
            Spec("data.bin", saveDataBin(version)),
        ),
    )

    private fun writeHashTables(
        data: ByteArray,
        layout: StfsLayout,
        blocks: Map<Int, ByteArray>,
        next: Map<Int, Int>,
        maxBlock: Int,
    ) {
        val groups = maxBlock / STFS_PER_TABLE + 1
        val l0 = ArrayList<ByteArray>(groups)
        for (group in 0 until groups) {
            val table = ByteArray(BLOCK)
            for (i in 0 until STFS_PER_TABLE) {
                val block = group * STFS_PER_TABLE + i
                val chunk = blocks[block] ?: continue
                val offset = i * 0x18
                sha1(chunk).copyInto(table, offset)
                table[offset + 0x14] = 0xC0.toByte()
                writeBe24(table, offset + 0x15, next[block] ?: STFS_MAX_BLOCK)
            }
            l0.add(table)
            val primary = if (group == 0) {
                layout.headerBlocks.toLong() * BLOCK
            } else {
                layout.offset(group * STFS_PER_TABLE) -
                    layout.duplicateTables.toLong() * BLOCK
            }
            table.copyInto(data, primary.toInt())
            if (layout == StfsLayout.SAVE) {
                table.copyInto(data, (primary + BLOCK).toInt())
            }
        }

        if (maxBlock >= STFS_PER_TABLE) {
            val l1Primary = if (layout == StfsLayout.SAVE) {
                layout.offset(STFS_PER_TABLE) - 4L * BLOCK
            } else {
                layout.offset(STFS_PER_TABLE) - 2L * BLOCK
            }
            val l1 = ByteArray(BLOCK)
            l0.forEachIndexed { group, table ->
                sha1(table).copyInto(l1, group * 0x18)
                l1[group * 0x18 + 0x14] = 0xC0.toByte()
            }
            l1.copyInto(data, l1Primary.toInt())
            if (layout == StfsLayout.SAVE) {
                l1.copyInto(data, (l1Primary + BLOCK).toInt())
            }
        }
    }

    private data class TableEntry(
        val name: String,
        val directory: Boolean,
        val consecutive: Boolean,
        val blocks: Int,
        val start: Int,
        val size: Long,
    )

    private fun sha1(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(bytes)

    private fun writeBe24(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = (value and 0xFF).toByte()
    }

    private fun writeLe24(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }

    private fun writeBe32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeLe32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
