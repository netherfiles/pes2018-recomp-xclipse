package com.pes2017.recomp.patch

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StfsPackageTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun persistentConsecutiveReadsExactSize() {
        val data = pattern(5000)
        val file = SyntheticStfs.build(
            temp.newFile("cpk-con"),
            specs = listOf(SyntheticStfs.Spec("a.bin", data)),
        )
        StfsPackage.open(file).use { pkg ->
            assertEquals(StfsLayout.PERSISTENT, pkg.layout)
            assertEquals(0x4A3007D3L, pkg.titleId)
            assertEquals("Synthetic", pkg.displayName)
            val entry = pkg.findNamed("a.bin")
            assertNotNull(entry)
            val sink = ByteArrayOutputStream()
            val read = pkg.readEntry(entry!!, sink)
            assertEquals(5000L, read.bytes)
            assertEquals(data.size.toLong(), sink.size().toLong())
            assertArrayEquals(data, sink.toByteArray())
            assertEquals(sha1Hex(data), read.sha1)
            assertArrayEquals(data.copyOfRange(0, 100), pkg.readPrefix(entry, 100))
        }
    }

    @Test
    fun persistentChainedReadsAcrossGroupBoundary() {
        val chain = (5..169).toList() + (205 downTo 171).toList()
        val data = pattern(chain.size * 0x1000 - 123)
        val file = SyntheticStfs.build(
            temp.newFile("chained-con"),
            specs = listOf(
                SyntheticStfs.Spec(
                    name = "chain.bin",
                    data = data,
                    consecutive = false,
                    chain = chain,
                ),
            ),
        )
        StfsPackage.open(file).use { pkg ->
            val entry = pkg.findNamed("chain.bin")!!
            assertFalse(entry.consecutive)
            assertEquals(200, entry.blocks)
            val sink = ByteArrayOutputStream()
            val read = pkg.readEntry(entry, sink)
            assertEquals(data.size.toLong(), read.bytes)
            assertEquals(200, read.blocks)
            assertArrayEquals(data, sink.toByteArray())
            assertArrayEquals(data.copyOfRange(0, 5000), pkg.readPrefix(entry, 5000))
        }
    }

    @Test
    fun saveConsecutiveReadsDataBin() {
        val file = SyntheticStfs.saveCon(temp.newFile("EDIT00000000"))
        StfsPackage.open(file).use { pkg ->
            assertEquals(StfsLayout.SAVE, pkg.layout)
            val dataBin = pkg.findNamed("data.bin")!!
            val sink = ByteArrayOutputStream()
            val read = pkg.readEntry(dataBin, sink)
            assertEquals(dataBin.size, read.bytes)
            assertEquals(96L, read.bytes)
            val payload = SavePayloadParser.parse(sink.toByteArray(), read.bytes)
            assertEquals(14, payload.version)
            assertEquals(80, payload.headerSize)
            assertEquals(10_502, payload.players)
            assertEquals(401, payload.teams)
            assertEquals(16L, payload.payloadSize)
        }
    }

    @Test
    fun saveChainedReadsAcrossGroupBoundary() {
        val chain = (5..169).toList() + (205 downTo 171).toList()
        val data = pattern(chain.size * 0x1000 - 123)
        val file = SyntheticStfs.build(
            temp.newFile("chained-save"),
            layout = StfsLayout.SAVE,
            specs = listOf(
                SyntheticStfs.Spec(
                    name = "data.bin",
                    data = data,
                    consecutive = false,
                    chain = chain,
                ),
            ),
        )
        StfsPackage.open(file).use { pkg ->
            assertEquals(StfsLayout.SAVE, pkg.layout)
            val entry = pkg.findNamed("data.bin")!!
            val sink = ByteArrayOutputStream()
            val read = pkg.readEntry(entry, sink)
            assertEquals(data.size.toLong(), read.bytes)
            assertArrayEquals(data, sink.toByteArray())
        }
    }

    @Test
    fun sha1MismatchIsRejected() {
        val data = pattern(9000)
        val file = SyntheticStfs.build(
            temp.newFile("corrupt-con"),
            specs = listOf(SyntheticStfs.Spec("a.bin", data)),
        )
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(StfsLayout.PERSISTENT.offset(1) + 5)
            raf.write(byteArrayOf(0))
        }
        StfsPackage.open(file).use { pkg ->
            val entry = pkg.findNamed("a.bin")!!
            try {
                pkg.readEntry(entry, ByteArrayOutputStream())
                fail("expected a SHA-1 mismatch")
            } catch (e: StfsException) {
                assertEquals(StfsErrorKind.HASH_MISMATCH, e.kind)
                assertTrue(e.message.orEmpty().contains("SHA-1 mismatch"))
            }
        }
    }

    @Test
    fun unknownHeaderIsRejected() {
        val file = temp.newFile("unknown-header")
        val bytes = ByteArray(0x4000)
        "CON ".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0)
        ByteBuffer.wrap(bytes).putInt(0x340, 0x1234)
        file.writeBytes(bytes)
        try {
            StfsPackage.open(file)
            fail("expected an unknown-layout failure")
        } catch (e: StfsException) {
            assertEquals(StfsErrorKind.UNKNOWN_LAYOUT, e.kind)
            assertTrue(e.message.orEmpty().contains("unknown STFS hash layout"))
        }
    }

    @Test
    fun saveLimitGuardRejectsLarge971a() {
        try {
            StfsLayout.SAVE.offset(STFS_PER_LEVEL1)
            fail("expected the 0x971A 0x70E4 guard")
        } catch (e: StfsException) {
            assertEquals(StfsErrorKind.UNSUPPORTED_LIMIT, e.kind)
        }

        // The PERSISTENT term is verified; the same block is only limited by
        // the 24-bit block number.
        assertTrue(StfsLayout.PERSISTENT.offset(STFS_PER_LEVEL1) > 0)

        val file = temp.newFile("large-971a")
        val bytes = ByteArray(0x1000)
        "CON ".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0)
        ByteBuffer.wrap(bytes).apply {
            putInt(0x340, 0x971A)
            putInt(0x344, 1)
            putInt(0x360, 0x4A3007D3)
        }
        bytes[0x379] = 0x24
        bytes[0x37C] = 1
        ByteBuffer.wrap(bytes).putInt(0x395, 0x70E5)
        file.writeBytes(bytes)
        try {
            StfsPackage.open(file)
            fail("expected the 0x971A allocated-block guard")
        } catch (e: StfsException) {
            assertEquals(StfsErrorKind.UNSUPPORTED_LIMIT, e.kind)
        }
    }

    @Test
    fun truncatedPackageIsRejected() {
        val file = SyntheticStfs.build(
            temp.newFile("truncated-con"),
            specs = listOf(SyntheticStfs.Spec("a.bin", pattern(5000))),
        )
        RandomAccessFile(file, "rw").use { it.setLength(0xC800) }
        try {
            StfsPackage.open(file)
            fail("expected a truncated-package failure")
        } catch (e: StfsException) {
            assertEquals(StfsErrorKind.TRUNCATED, e.kind)
        }
    }

    @Test
    fun savePayloadValidation() {
        val good = SyntheticStfs.saveDataBin(14)
        val payload = SavePayloadParser.parse(good, 96)
        assertEquals(10_502, payload.players)
        assertEquals(401, payload.teams)
        assertEquals(401, payload.field0x14)
        assertEquals(54, payload.kindByte)

        val v16 = SavePayloadParser.parse(SyntheticStfs.saveDataBin(16), 96)
        assertEquals(16, v16.version)
        assertEquals(67, v16.kindByte)

        val king = SavePayloadParser.parse(SyntheticStfs.saveDataBin(14, teams = 700), 96)
        assertEquals(700, king.teams)
        assertEquals(700, king.field0x14)

        val gemboxData = SyntheticStfs.saveDataBin(14, teams = 740)
        val gembox = SavePayloadParser.parse(gemboxData, 96)
        assertEquals(740, gembox.teams)
        assertEquals(740, gembox.field0x14)

        expectMalformed(SyntheticStfs.saveDataBin(15))

        val wrongPlayers = good.copyOf()
        ByteBuffer.wrap(wrongPlayers).putInt(0x0C, 1)
        expectMalformed(wrongPlayers)

        val belowRange = gemboxData.copyOf()
        ByteBuffer.wrap(belowRange).putInt(0x10, 400)
        ByteBuffer.wrap(belowRange).putInt(0x14, 400)
        expectMalformed(belowRange)

        val aboveRange = gemboxData.copyOf()
        ByteBuffer.wrap(aboveRange).putInt(0x10, 741)
        ByteBuffer.wrap(aboveRange).putInt(0x14, 741)
        expectMalformed(aboveRange)

        val mismatchedField = gemboxData.copyOf()
        ByteBuffer.wrap(mismatchedField).putInt(0x14, 739)
        try {
            SavePayloadParser.parse(mismatchedField, 96)
            fail("expected a 0x14 count mismatch")
        } catch (e: StfsException) {
            assertEquals(StfsErrorKind.MALFORMED, e.kind)
            assertTrue(e.message.orEmpty().contains("0x14"))
        }

        try {
            SavePayloadParser.parse(good, 97)
            fail("expected a payload-size failure")
        } catch (e: StfsException) {
            assertEquals(StfsErrorKind.MALFORMED, e.kind)
        }
    }

    private fun expectMalformed(data: ByteArray) {
        try {
            SavePayloadParser.parse(data, 96)
            fail("expected a save payload failure")
        } catch (e: StfsException) {
            assertEquals(StfsErrorKind.MALFORMED, e.kind)
        }
    }

    private fun pattern(size: Int): ByteArray =
        ByteArray(size) { index -> ((index * 37 + 11) and 0xFF).toByte() }

    private fun sha1Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).toHex()
}
