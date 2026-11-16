package com.pes2017.recomp.patch

import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** What [StfsPackage.readEntry] produced. */
data class StfsRead(
    val bytes: Long,
    val blocks: Int,
    val sha1: String,
)

/**
 * A read-only STFS package backed by a file.
 *
 * Every block is checked against the SHA-1 stored in its level-0 hash table
 * before its bytes are written, so a wrong layout or corrupt package fails
 * instead of producing wrong output. Reads are streamed with a small block
 * cache; the package is never loaded into memory.
 */
class StfsPackage private constructor(
    private val file: RandomAccessFile,
    private val reader: FileStfsReader,
    val header: StfsHeader,
) : Closeable {

    private var closed = false

    private val blockCache = object : LinkedHashMap<Int, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, ByteArray>?,
        ): Boolean = size > 8
    }

    val layout: StfsLayout get() = header.layout
    val titleId: Long get() = header.titleId
    val displayName: String get() = header.displayName
    val entries: List<StfsEntry> get() = header.entries

    /** Function form for callers that read the API as a command list. */
    fun entries(): List<StfsEntry> = header.entries

    fun entryPath(entry: StfsEntry): String = header.pathOf(entry)

    fun find(path: String): StfsEntry? = header.find(path)

    fun findNamed(name: String): StfsEntry? = header.findNamed(name)

    /**
     * Streams [entry] into [sink], verifying every block against its L0 SHA-1.
     * The number of bytes written is exactly [StfsEntry.size].
     */
    fun readEntry(entry: StfsEntry, sink: OutputStream, verify: Boolean = true): StfsRead {
        if (closed) throw StfsException("package is closed", StfsErrorKind.MALFORMED)
        if (entry.directory) {
            throw StfsException("${entry.name} is a directory", StfsErrorKind.MALFORMED)
        }

        val digest = MessageDigest.getInstance("SHA-1")
        var remaining = entry.size
        var blocks = 0
        if (entry.blocks == 0) {
            if (entry.size != 0L) {
                throw StfsException(
                    "${entry.name}: ${entry.size} bytes but no data blocks",
                    StfsErrorKind.MALFORMED,
                )
            }
            return StfsRead(0, 0, sha1ByteArray(ByteArray(0)))
        }

        var block = entry.start
        val seen = HashSet<Int>()
        for (i in 0 until entry.blocks) {
            if (!seen.add(block)) {
                throw StfsException(
                    "${entry.name}: block chain loops at 0x${block.toString(16)}",
                    StfsErrorKind.MALFORMED,
                )
            }
            val data = readBlock(block, verify)
            val take = minOf(STFS_BLOCK_SIZE.toLong(), remaining).toInt()
            if (take > 0) {
                sink.write(data, 0, take)
                digest.update(data, 0, take)
            }
            blocks++
            remaining -= take
            if (remaining <= 0) break
            block = if (entry.consecutive) {
                block + 1
            } else {
                nextBlock(entry, block)
            }
        }
        if (remaining != 0L) {
            throw StfsException(
                "${entry.name}: block chain ended $remaining bytes early",
                StfsErrorKind.TRUNCATED,
            )
        }
        return StfsRead(entry.size, blocks, digest.digest().toHex())
    }

    /** Reads and verifies every block of [entry] without keeping the bytes. */
    fun verify(entry: StfsEntry): StfsRead = readEntry(entry, NULL_SINK, verify = true)

    /**
     * Reads at most [maxBytes] from the start of [entry], streaming from the
     * first block. Each block touched is verified. Used by the scanner so a
     * 1.5 GB CPK costs one block, not a copy.
     */
    fun readPrefix(entry: StfsEntry, maxBytes: Int, verify: Boolean = true): ByteArray {
        if (closed) throw StfsException("package is closed", StfsErrorKind.MALFORMED)
        if (entry.directory) {
            throw StfsException("${entry.name} is a directory", StfsErrorKind.MALFORMED)
        }
        val wanted = minOf(entry.size, maxBytes.toLong()).toInt()
        if (wanted <= 0) return ByteArray(0)
        val out = ByteArray(wanted)
        var written = 0
        var block = entry.start
        var index = 0
        while (written < wanted && index < entry.blocks) {
            val data = readBlock(block, verify)
            val take = minOf(STFS_BLOCK_SIZE, wanted - written)
            data.copyInto(out, written, 0, take)
            written += take
            index++
            if (written >= wanted) break
            block = if (entry.consecutive) block + 1 else nextBlock(entry, block)
        }
        if (written < wanted) {
            throw StfsException(
                "${entry.name}: only $written of $wanted bytes are addressable",
                StfsErrorKind.TRUNCATED,
            )
        }
        return out
    }

    private fun readBlock(block: Int, verify: Boolean): ByteArray {
        if (block < 0 || block > STFS_MAX_BLOCK) {
            throw StfsException(
                "data block ${block.toString(16)} is outside the 24-bit range",
                StfsErrorKind.MALFORMED,
            )
        }
        var data = blockCache[block]
        if (data == null) {
            data = reader.read(dataBlockOffset(reader, layout, block), STFS_BLOCK_SIZE)
            blockCache[block] = data
        }
        if (verify) {
            val l0 = l0Entry(block)
            val actual = sha1(data)
            if (!MessageDigest.isEqual(l0.sha1, actual)) {
                throw StfsException(
                    "block 0x${block.toString(16)} (offset 0x" +
                        dataBlockOffset(reader, layout, block).toString(16) +
                        ") SHA-1 mismatch: table ${l0.sha1.toHex()}, " +
                        "actual ${actual.toHex()}",
                    StfsErrorKind.HASH_MISMATCH,
                )
            }
        }
        return data
    }

    private fun nextBlock(entry: StfsEntry, block: Int): Int {
        val next = l0Entry(block).nextBlock
        if (next == STFS_MAX_BLOCK) {
            throw StfsException(
                "${entry.name}: chain ends early after block 0x${block.toString(16)}",
                StfsErrorKind.TRUNCATED,
            )
        }
        if (next.toLong() >= header.allocatedBlocks) {
            throw StfsException(
                "${entry.name}: next block 0x${next.toString(16)} is beyond " +
                    "${header.allocatedBlocks} allocated blocks",
                StfsErrorKind.MALFORMED,
            )
        }
        return next
    }

    private fun l0Entry(block: Int): L0Entry {
        val table = layout.l0Offset(block)
        val offset = table + (block % STFS_PER_TABLE).toLong() * 0x18
        val raw = reader.read(offset, 0x18)
        return L0Entry(
            sha1 = raw.copyOfRange(0, 20),
            status = raw[0x14].toInt() and 0xFF,
            nextBlock = be24(raw, 0x15),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        blockCache.clear()
        file.close()
    }

    private data class L0Entry(
        val sha1: ByteArray,
        val status: Int,
        val nextBlock: Int,
    )

    companion object {
        fun open(file: File): StfsPackage {
            if (!file.isFile) {
                throw StfsException("no such package: ${file.path}", StfsErrorKind.TRUNCATED)
            }
            val raf = RandomAccessFile(file, "r")
            try {
                val reader = FileStfsReader(raf)
                val header = StfsHeaderParser.parse(reader)
                return StfsPackage(raf, reader, header)
            } catch (t: Throwable) {
                try {
                    raf.close()
                } catch (_: Throwable) {
                }
                throw t
            }
        }

        private val NULL_SINK = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }

        private fun sha1ByteArray(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes).toHex()
    }
}

/**
 * A parsed STFS prefix: the header and file table read from the first
 * ~0x20000 bytes of a CON. Enough to classify CPK/DpFileList/save packages
 * without extracting a 1.5 GB CPK.
 */
class StfsPrefix private constructor(
    private val reader: ByteArrayStfsReader,
    val header: StfsHeader,
) {
    val layout: StfsLayout get() = header.layout
    val titleId: Long get() = header.titleId
    val entries: List<StfsEntry> get() = header.entries

    fun blockOffset(block: Int): Long = dataBlockOffset(reader, layout, block)

    fun readAt(offset: Long, length: Int): ByteArray = reader.read(offset, length)

    /**
     * Reads at most [maxBytes] of [entry] from its first data block. The
     * scanner only asks for the CPK magic (0x40), a DpFileList (one block)
     * or a save header (0x24), so a contiguous first-block read is enough.
     */
    fun readInner(entry: StfsEntry, maxBytes: Int): ByteArray {
        if (entry.directory) {
            throw StfsException("${entry.name} is a directory", StfsErrorKind.MALFORMED)
        }
        val wanted = minOf(entry.size, maxBytes.toLong(), STFS_BLOCK_SIZE.toLong()).toInt()
        if (wanted <= 0) return ByteArray(0)
        if (entry.blocks == 0) {
            throw StfsException(
                "${entry.name}: ${entry.size} bytes but no data blocks",
                StfsErrorKind.MALFORMED,
            )
        }
        val offset = blockOffset(entry.start)
        return reader.read(offset, wanted)
    }

    companion object {
        /**
         * Parses [prefix] (the first bytes read from a CON). The prefix must
         * contain the header, the file table and data block 0; 0x20000 bytes
         * is enough for every package this title produces.
         */
        fun parse(prefix: ByteArray): StfsPrefix {
            val reader = ByteArrayStfsReader(prefix)
            val header = StfsHeaderParser.parse(reader)
            return StfsPrefix(reader, header)
        }
    }
}
