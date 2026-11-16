package com.pes2017.recomp.patch

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PatchScannerTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private val titleRoot = "content/0000000000000000/4A3007D3/00000001"

    @Test
    fun scansZipWithCpkDpFileListAndDedupedEdit() {
        val cpkCon = SyntheticStfs.build(
            temp.newFile("cpk-con"),
            specs = listOf(SyntheticStfs.Spec("test.cpk", SyntheticStfs.cpkPayload(0x300))),
        ).readBytes()
        val dpCon = SyntheticStfs.build(
            temp.newFile("dp-con"),
            specs = listOf(
                SyntheticStfs.Spec("DpFileList.bin", SyntheticStfs.dpFileList("test.cpk")),
            ),
        ).readBytes()
        val saveFile = SyntheticStfs.saveCon(temp.newFile("EDIT00000000"))
        val saveBytes = saveFile.readBytes()
        val tu = SyntheticStfs.build(
            temp.newFile("tu"),
            contentType = 0xB0000,
            specs = listOf(SyntheticStfs.Spec("default.xexp", ByteArray(16))),
        ).readBytes()
        val profile = SyntheticStfs.build(
            temp.newFile("profile"),
            layout = StfsLayout.SAVE,
            contentType = 0x10000,
            titleId = 0xFFFE07D1L,
            specs = listOf(SyntheticStfs.Spec("Account", ByteArray(16))),
        ).readBytes()
        val cache = SyntheticStfs.build(
            temp.newFile("cache"),
            layout = StfsLayout.SAVE,
            contentType = 0x40000,
            titleId = 0,
            specs = listOf(SyntheticStfs.Spec("CDIs.bin", ByteArray(16))),
        ).readBytes()

        val zip = zip(
            "$titleRoot/TEST.cpk" to cpkCon,
            "$titleRoot/DpFileList.bin" to dpCon,
            "$titleRoot/EDIT00000000" to saveBytes,
            "content/E000000000000000/4A3007D3/00000001/EDIT00000000" to saveBytes,
            "$titleRoot/000B0000/tu0000000E_00000000" to tu,
            "content/E000000000000000/FFFE07D1/00010000/E000000000000000" to profile,
            "content/FFFE07DF/00040000/ContentCache.pkg" to cache,
            "readme.txt" to "not a patch".toByteArray(),
        )
        val scratch = temp.newFolder("scratch")
        val plan = TestZipSource(zip, "Patch FL 2026.zip").use { source ->
            PatchScanner(scratch).scan(source, backupBytes = 1234)
        }

        assertEquals("Patch FL 2026", plan.name)
        assertEquals(1, plan.cpkCount)
        assertEquals(1, plan.cpkListedCount)
        assertEquals("test.cpk", plan.cpks[0].innerName)
        assertEquals(0xFF, plan.cpks[0].variant)
        assertEquals(100, plan.cpks[0].priority)
        assertTrue(plan.cpks[0].listed)
        assertEquals(1, plan.dpFileList.entries.size)
        assertEquals("test.cpk", plan.dpFileList.entries[0].name)

        assertEquals(1, plan.saves.size)
        val save = plan.saves[0]
        assertEquals(SaveSlot.EDIT, save.slot)
        assertEquals("EDIT00000000", save.targetName)
        assertEquals(sha256(saveBytes), save.outerSha256)
        assertEquals(96L, save.dataBinSize)
        assertEquals(14, save.payload.version)
        assertEquals(10_502, save.payload.players)
        assertEquals(401, save.payload.teams)
        assertEquals("Dados Editados", save.dataInfName)
        assertEquals(2, save.copies.size)
        assertTrue(save.sourcePath.startsWith("content/E000"))
        assertEquals(1, plan.duplicates.size)
        assertEquals(save.outerSha256, plan.duplicates[0].sha256)
        assertTrue(plan.duplicates[0].copyPaths.single().contains("/0000000000000000/"))

        assertEquals(4, plan.skipped.size)
        assertEquals(0x300L, plan.cpkBytes)
        assertEquals(96L, plan.dpFileListBytes)
        assertEquals(saveBytes.size.toLong(), plan.saveBytes)
        assertEquals(0x300L + 96L + saveBytes.size, plan.rawBytes)
        assertEquals(plan.rawBytes, plan.stagingBytes)
        assertEquals(zip.length(), plan.sourcePartsBytes)
        assertEquals(1234L, plan.backupBytes)
        assertEquals(zip.length() + plan.rawBytes + 1234L, plan.peakBytes)
        assertTrue(plan.warnings.any { it.contains("appears 2 times") })

        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun lowerCaseContentAndBackslashPathsAreAccepted() {
        val cpkCon = SyntheticStfs.build(
            temp.newFile("cpk-con"),
            specs = listOf(SyntheticStfs.Spec("test.cpk", SyntheticStfs.cpkPayload(0x300))),
        ).readBytes()
        val dpCon = SyntheticStfs.build(
            temp.newFile("dp-con"),
            specs = listOf(
                SyntheticStfs.Spec("DpFileList.bin", SyntheticStfs.dpFileList("test.cpk")),
            ),
        ).readBytes()
        val zip = zip(
            "content\\0000000000000000\\4A3007D3\\00000001\\TEST.cpk" to cpkCon,
            "content\\0000000000000000\\4A3007D3\\00000001\\DpFileList.bin" to dpCon,
        )
        val plan = TestZipSource(zip).use { source ->
            PatchScanner(temp.newFolder("scratch2")).scan(source)
        }
        assertEquals(1, plan.cpkCount)
        assertEquals(1, plan.cpkListedCount)
    }

    @Test
    fun singleCpkWithoutListIsRejected() {
        val cpkCon = SyntheticStfs.build(
            temp.newFile("cpk-con"),
            specs = listOf(SyntheticStfs.Spec("test.cpk", SyntheticStfs.cpkPayload(0x300))),
        ).readBytes()
        val zip = zip("$titleRoot/TEST.cpk" to cpkCon)
        expectFailure(PatchScanFailure.SINGLE_CPK_WITHOUT_LIST, zip)
    }

    @Test
    fun missingListedCpkIsRejected() {
        val cpkCon = SyntheticStfs.build(
            temp.newFile("cpk-con"),
            specs = listOf(SyntheticStfs.Spec("listed.cpk", SyntheticStfs.cpkPayload(0x300))),
        ).readBytes()
        val dpCon = SyntheticStfs.build(
            temp.newFile("dp-con"),
            specs = listOf(
                SyntheticStfs.Spec("DpFileList.bin", SyntheticStfs.dpFileList("missing.cpk")),
            ),
        ).readBytes()
        val zip = zip(
            "$titleRoot/LISTED.cpk" to cpkCon,
            "$titleRoot/DpFileList.bin" to dpCon,
        )
        expectFailure(PatchScanFailure.MISSING_LISTED_CPK, zip)
    }

    @Test
    fun wrongTitleIsRejected() {
        val cpkCon = SyntheticStfs.build(
            temp.newFile("cpk-con"),
            titleId = 0x5A3007D3L,
            specs = listOf(SyntheticStfs.Spec("test.cpk", SyntheticStfs.cpkPayload(0x300))),
        ).readBytes()
        val zip = zip(
            "content/0000000000000000/5A3007D3/00000001/TEST.cpk" to cpkCon,
        )
        expectFailure(PatchScanFailure.WRONG_TITLE, zip)
    }

    @Test
    fun unknownHeaderInsideTitleIsRejected() {
        val bytes = ByteArray(0x4000)
        "CON ".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0)
        ByteBuffer.wrap(bytes).putInt(0x340, 0x1234)
        val zip = zip("$titleRoot/BAD.cpk" to bytes)
        expectFailure(PatchScanFailure.UNKNOWN_STFS_HEADER, zip)
    }

    @Test
    fun unsafeInnerNameIsRejected() {
        val cpkCon = SyntheticStfs.build(
            temp.newFile("cpk-con"),
            specs = listOf(SyntheticStfs.Spec("../evil.cpk", SyntheticStfs.cpkPayload(0x300))),
        ).readBytes()
        val zip = zip("$titleRoot/EVIL.cpk" to cpkCon)
        expectFailure(PatchScanFailure.UNSAFE_NAME, zip)
    }

    @Test
    fun unlistedCpkIsReportedButKept() {
        val listed = SyntheticStfs.build(
            temp.newFile("listed"),
            specs = listOf(SyntheticStfs.Spec("listed.cpk", SyntheticStfs.cpkPayload(0x300))),
        ).readBytes()
        val extra = SyntheticStfs.build(
            temp.newFile("extra"),
            specs = listOf(SyntheticStfs.Spec("extra.cpk", SyntheticStfs.cpkPayload(0x300))),
        ).readBytes()
        val dpCon = SyntheticStfs.build(
            temp.newFile("dp-con"),
            specs = listOf(
                SyntheticStfs.Spec("DpFileList.bin", SyntheticStfs.dpFileList("listed.cpk")),
            ),
        ).readBytes()
        val zip = zip(
            "$titleRoot/LISTED.cpk" to listed,
            "$titleRoot/EXTRA.cpk" to extra,
            "$titleRoot/DpFileList.bin" to dpCon,
        )
        val plan = TestZipSource(zip).use { source ->
            PatchScanner(temp.newFolder("scratch3")).scan(source)
        }
        assertEquals(2, plan.cpkCount)
        assertEquals(1, plan.cpkListedCount)
        assertEquals(0x300L * 2, plan.cpkBytes)
        assertTrue(plan.warnings.any { it.contains("extra.cpk") })
    }

    private fun expectFailure(failure: PatchScanFailure, zip: File) {
        try {
            TestZipSource(zip).use { source ->
                PatchScanner(temp.newFolder()).scan(source)
            }
            fail("expected $failure")
        } catch (e: PatchScanException) {
            assertEquals(failure, e.failure)
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val file = temp.newFile("patch-${System.nanoTime()}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}
