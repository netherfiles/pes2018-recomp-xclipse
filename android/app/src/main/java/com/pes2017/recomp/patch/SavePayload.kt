package com.pes2017.recomp.patch

/**
 * The first 0x24 bytes of a PES 2018 save's data.bin.
 *
 * Layout: u32 LE version at 0x00, u32 BE header size at 0x04, u32 BE payload
 * size at 0x08, three u32 BE counts at 0x0C/0x10/0x14, u8 kind at 0x20.
 *
 * Every verified save has 10,502 players. The team capacity depends on the
 * patch installed on the console: 401 in ONFIRE/FL/SIMPLE, 700 in KING and
 * 740 in GEMBOX. The count at 0x14 repeats the team capacity, so the parser
 * accepts the supported range and requires both counts to agree. EDIT saves
 * are version 14 and SYSTEM saves version 16 in every package this importer
 * supports.
 */
data class SavePayload(
    val version: Int,
    val headerSize: Int,
    val payloadSize: Long,
    val players: Int,
    val teams: Int,
    val field0x14: Int,
    val kindByte: Int,
)

object SavePayloadParser {

    const val HEADER_SIZE: Int = 80
    const val PLAYERS: Int = 10_502
    const val TEAMS_MIN: Int = 401
    const val TEAMS_MAX: Int = 740
    const val MIN_HEADER_BYTES: Int = 0x24

    private val VERSIONS = setOf(14, 16)

    fun parse(header: ByteArray, fileSize: Long): SavePayload {
        if (header.size < MIN_HEADER_BYTES) {
            throw StfsException(
                "save data.bin is ${header.size} bytes, need at least " +
                    "$MIN_HEADER_BYTES to read its header",
                StfsErrorKind.TRUNCATED,
            )
        }
        val version = le32(header, 0).toInt()
        if (version !in VERSIONS) {
            throw StfsException(
                "unsupported save data.bin version $version (supported: " +
                    VERSIONS.sorted().joinToString(", ") + ")",
                StfsErrorKind.MALFORMED,
            )
        }
        val headerSize = be32(header, 4)
        if (headerSize != HEADER_SIZE.toLong()) {
            throw StfsException(
                "save data.bin header size $headerSize, expected $HEADER_SIZE",
                StfsErrorKind.MALFORMED,
            )
        }
        val payloadSize = be32(header, 8)
        if (fileSize < HEADER_SIZE) {
            throw StfsException(
                "save data.bin is $fileSize bytes, shorter than its $HEADER_SIZE-byte header",
                StfsErrorKind.TRUNCATED,
            )
        }
        if (payloadSize != fileSize - HEADER_SIZE) {
            throw StfsException(
                "save data.bin payload size $payloadSize does not equal " +
                    "$fileSize - $HEADER_SIZE = ${fileSize - HEADER_SIZE}",
                StfsErrorKind.MALFORMED,
            )
        }
        val players = be32(header, 0x0C).toInt()
        val teams = be32(header, 0x10).toInt()
        val field0x14 = be32(header, 0x14).toInt()
        if (players != PLAYERS) {
            throw StfsException(
                "save data.bin players $players, expected $PLAYERS",
                StfsErrorKind.MALFORMED,
            )
        }
        if (teams !in TEAMS_MIN..TEAMS_MAX) {
            throw StfsException(
                "save data.bin teams $teams, expected $TEAMS_MIN..$TEAMS_MAX " +
                    "(team capacity varies by patch: 401 ONFIRE, 700 KING, 740 GEMBOX)",
                StfsErrorKind.MALFORMED,
            )
        }
        if (field0x14 != teams) {
            throw StfsException(
                "save data.bin teams $teams does not match its 0x14 count $field0x14",
                StfsErrorKind.MALFORMED,
            )
        }
        return SavePayload(
            version = version,
            headerSize = headerSize.toInt(),
            payloadSize = payloadSize,
            players = players,
            teams = teams,
            field0x14 = field0x14,
            kindByte = header[0x20].toInt() and 0xFF,
        )
    }
}
