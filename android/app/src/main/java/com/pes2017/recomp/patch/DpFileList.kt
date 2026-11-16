package com.pes2017.recomp.patch

/** One 48-byte DpFileList record. */
data class DpFileListEntry(
    val index: Int,
    val priority: Int,
    val unknown0: Int,
    val unknown1: Int,
    val name: String,
)

/**
 * Parser for PES 2018's DpFileList.bin.
 *
 * Records are 48 bytes: u32 BE magic 0x00000262, priority, 100, 101 and a
 * NUL-padded 32-byte Latin-1 name. Files are zero-padded after the last
 * record, so the parser stops at the first all-zero record instead of
 * dividing the file size by 48 (FL's is 2,222 bytes, not a multiple of 48).
 */
object DpFileListParser {

    const val RECORD_SIZE: Int = 48
    const val MAGIC: Long = 0x00000262
    const val UNKNOWN0: Long = 100
    const val UNKNOWN1: Long = 101

    fun parse(bytes: ByteArray): List<DpFileListEntry> {
        if (bytes.size < RECORD_SIZE) {
            throw StfsException(
                "DpFileList.bin is ${bytes.size} bytes, shorter than one $RECORD_SIZE-byte record",
                StfsErrorKind.TRUNCATED,
            )
        }
        val entries = ArrayList<DpFileListEntry>()
        var offset = 0
        while (offset + RECORD_SIZE <= bytes.size) {
            val magic = be32(bytes, offset)
            val priority = be32(bytes, offset + 4)
            val unknown0 = be32(bytes, offset + 8)
            val unknown1 = be32(bytes, offset + 12)
            if (magic == 0L && priority == 0L && unknown0 == 0L && unknown1 == 0L) {
                break
            }
            if (magic != MAGIC) {
                throw StfsException(
                    "DpFileList record ${entries.size} has magic " +
                        "0x${magic.toString(16)}, expected 0x${MAGIC.toString(16)}",
                    StfsErrorKind.MALFORMED,
                )
            }
            if (unknown0 != UNKNOWN0 || unknown1 != UNKNOWN1) {
                throw StfsException(
                    "DpFileList record ${entries.size} has fields " +
                        "$unknown0/$unknown1, expected $UNKNOWN0/$UNKNOWN1",
                    StfsErrorKind.MALFORMED,
                )
            }
            val nameBytes = bytes.copyOfRange(offset + 16, offset + RECORD_SIZE)
            val name = String(nameBytes, Charsets.ISO_8859_1).substringBefore('\u0000')
            if (name.isEmpty()) {
                throw StfsException(
                    "DpFileList record ${entries.size} has an empty name",
                    StfsErrorKind.MALFORMED,
                )
            }
            if (!isSafePatchFileName(name) || !name.lowercase().endsWith(".cpk")) {
                throw StfsException(
                    "DpFileList record ${entries.size} has an unsafe or non-CPK " +
                        "name: $name",
                    StfsErrorKind.MALFORMED,
                )
            }
            entries.add(
                DpFileListEntry(
                    index = entries.size,
                    priority = priority.toInt(),
                    unknown0 = unknown0.toInt(),
                    unknown1 = unknown1.toInt(),
                    name = name,
                ),
            )
            offset += RECORD_SIZE
        }
        if (entries.isEmpty()) {
            throw StfsException("DpFileList.bin has no records", StfsErrorKind.MALFORMED)
        }
        return entries
    }
}
