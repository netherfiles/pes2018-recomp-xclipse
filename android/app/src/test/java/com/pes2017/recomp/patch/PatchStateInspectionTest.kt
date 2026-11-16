package com.pes2017.recomp.patch

import com.pes2017.recomp.PatchesRepository
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The PATCHES completeness and provenance judgement, on temp folders only.
 * No game data and no Android: every case is a few small files whose
 * DpFileList and manifest this test wrote itself.
 */
class PatchStateInspectionTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    // ---- patch -----------------------------------------------------------

    @Test
    fun completeInstallWithMatchingManifestIsApp() {
        val dir = temp.newFolder("complete")
        val cpk = SyntheticStfs.cpkPayload(1024)
        File(dir, "FL 1.cpk").writeBytes(cpk)
        val dp = SyntheticStfs.dpFileList("FL 1.cpk")
        File(dir, "DpFileList.bin").writeBytes(dp)

        val data = PatchesRepository.inspectPatch(dir, props("FL 1.cpk" to cpk.size.toLong(), dp = dp))

        assertNotNull(data)
        data!!
        assertTrue(data.complete)
        assertEquals(PatchesRepository.Provenance.APP, data.provenance)
        assertTrue(data.dpReadable)
        assertEquals(1, data.cpk)
        assertEquals(1, data.entries)
        assertEquals(cpk.size.toLong(), data.bytes)
        assertEquals(dp.size.toLong(), data.dpBytes)
        assertEquals(PatchesRepository.PatchLayout.WITH_DP, data.layout)
        assertEquals(listOf("FL 1.cpk:${cpk.size}"), data.manifest)
        assertEquals("ONFIRE", data.name)
        assertEquals("ZIP", data.source)
    }

    @Test
    fun cpkTruncatedAfterItsMagicIsIncompleteEvenWithTheRightFourBytes() {
        val dir = temp.newFolder("truncated")
        // "CPK " then nothing: the magic check alone would accept this.
        val cut = SyntheticStfs.cpkPayload(64).copyOf(12)
        File(dir, "FL 1.cpk").writeBytes(cut)
        val dp = SyntheticStfs.dpFileList("FL 1.cpk")
        File(dir, "DpFileList.bin").writeBytes(dp)

        val hand = PatchesRepository.inspectPatch(dir)
        assertNotNull(hand)
        assertFalse(hand!!.complete)
        assertEquals(PatchesRepository.Provenance.HAND, hand.provenance)

        // With a recorded size the truncation is provable and the props stale.
        val recorded = PatchesRepository.inspectPatch(
            dir,
            props("FL 1.cpk" to 1024L, dp = dp),
        )!!
        assertFalse(recorded.complete)
        assertEquals(PatchesRepository.Provenance.STALE, recorded.provenance)
    }

    @Test
    fun anExtraCpkMakesAProvenancedInstallStaleButNotAHandFolder() {
        val dir = temp.newFolder("extra")
        val a = SyntheticStfs.cpkPayload(512)
        val b = SyntheticStfs.cpkPayload(256)
        File(dir, "a.cpk").writeBytes(a)
        File(dir, "b.cpk").writeBytes(b)
        val dp = SyntheticStfs.dpFileList("a.cpk")
        File(dir, "DpFileList.bin").writeBytes(dp)

        val hand = PatchesRepository.inspectPatch(dir)!!
        assertEquals(PatchesRepository.Provenance.HAND, hand.provenance)
        assertTrue("a hand folder with a valid list is usable", hand.complete)
        assertEquals(2, hand.cpk)

        val recorded = PatchesRepository.inspectPatch(dir, props("a.cpk" to a.size.toLong(), dp = dp))!!
        assertEquals(PatchesRepository.Provenance.STALE, recorded.provenance)
        assertFalse("the manifest no longer matches", recorded.complete)
    }

    @Test
    fun aChangedDpFileListMakesThePropsStale() {
        val dir = temp.newFolder("stale-dp")
        val cpk = SyntheticStfs.cpkPayload(256)
        File(dir, "FL 1.cpk").writeBytes(cpk)
        val dp = SyntheticStfs.dpFileList("FL 1.cpk")
        File(dir, "DpFileList.bin").writeBytes(dp)

        val wrong = props("FL 1.cpk" to cpk.size.toLong(), dp = dp)
        wrong["patch.dpSha256"] = "00".repeat(32)

        val data = PatchesRepository.inspectPatch(dir, wrong)!!
        assertEquals(PatchesRepository.Provenance.STALE, data.provenance)
        assertFalse(data.complete)
    }

    @Test
    fun handInstalledFolderHasNoProvenanceAndIsJudgedOnItsFiles() {
        val dir = temp.newFolder("hand")
        val cpk = SyntheticStfs.cpkPayload(700)
        File(dir, "a.cpk").writeBytes(cpk)
        File(dir, "DpFileList.bin").writeBytes(SyntheticStfs.dpFileList("a.cpk"))

        val data = PatchesRepository.inspectPatch(dir)!!
        assertEquals(PatchesRepository.Provenance.HAND, data.provenance)
        assertTrue(data.complete)
        assertEquals("Installed data pack", data.name)
        assertEquals("GAME FOLDER", data.source)
    }

    @Test
    fun absentEmptyListOnlyAndCpkOnlyAreDifferentStates() {
        val absent = File(temp.root, "nothing")
        assertEquals(PatchesRepository.PatchLayout.ABSENT, PatchesRepository.patchLayout(absent))
        assertNull(PatchesRepository.inspectPatch(absent))

        val empty = temp.newFolder("empty")
        assertEquals(PatchesRepository.PatchLayout.EMPTY, PatchesRepository.patchLayout(empty))
        assertNull(PatchesRepository.inspectPatch(empty))

        val listOnly = temp.newFolder("list-only")
        File(listOnly, "DpFileList.bin").writeBytes(SyntheticStfs.dpFileList("a.cpk"))
        assertEquals(PatchesRepository.PatchLayout.DP_ONLY, PatchesRepository.patchLayout(listOnly))
        val listData = PatchesRepository.inspectPatch(listOnly)!!
        assertEquals(PatchesRepository.PatchLayout.DP_ONLY, listData.layout)
        assertTrue(listData.dpReadable)
        assertEquals(1, listData.entries)
        assertEquals(0, listData.cpk)
        assertFalse(listData.complete)

        val cpkOnly = temp.newFolder("cpk-only")
        File(cpkOnly, "a.cpk").writeBytes(SyntheticStfs.cpkPayload(64))
        assertEquals(PatchesRepository.PatchLayout.NO_DP, PatchesRepository.patchLayout(cpkOnly))
        val cpkData = PatchesRepository.inspectPatch(cpkOnly)!!
        assertEquals(PatchesRepository.PatchLayout.NO_DP, cpkData.layout)
        assertFalse(cpkData.complete)
        assertEquals(0, cpkData.entries)
    }

    @Test
    fun aDpFileListEntryWithoutItsCpkIsIncomplete() {
        val dir = temp.newFolder("missing-cpk")
        File(dir, "a.cpk").writeBytes(SyntheticStfs.cpkPayload(128))
        File(dir, "DpFileList.bin").writeBytes(SyntheticStfs.dpFileList("a.cpk", "b.cpk"))

        val data = PatchesRepository.inspectPatch(dir)!!
        assertFalse(data.complete)
        assertEquals(2, data.entries)
    }

    // ---- edit ------------------------------------------------------------

    @Test
    fun editWithAValidDataBinIsComplete() {
        val dir = temp.newFolder("edit")
        val dataBin = SyntheticStfs.saveDataBin(14)
        File(dir, "data.bin").writeBytes(dataBin)
        File(dir, "data.inf").writeBytes("Dados Editados".toByteArray(Charsets.ISO_8859_1))

        val data = PatchesRepository.inspectEdit(dir)!!
        assertTrue(data.complete)
        assertEquals(14, data.version)
        assertEquals(10_502, data.players)
        assertEquals(401, data.teams)
        assertEquals(dataBin.size.toLong(), data.bytes)
    }

    @Test
    fun editWithTheGemboxTeamCapacityReportsItsRealCount() {
        val dir = temp.newFolder("edit-gembox")
        val dataBin = SyntheticStfs.saveDataBin(14, teams = 740)
        File(dir, "data.bin").writeBytes(dataBin)
        File(dir, "data.inf").writeBytes("Dados Editados".toByteArray(Charsets.ISO_8859_1))

        val data = PatchesRepository.inspectEdit(dir)!!
        assertTrue(data.complete)
        assertEquals(14, data.version)
        assertEquals(10_502, data.players)
        assertEquals(740, data.teams)
        assertEquals(dataBin.size.toLong(), data.bytes)
    }

    @Test
    fun editWithoutDataBinOrWithATruncatedOneIsIncomplete() {
        val infOnly = temp.newFolder("inf-only")
        File(infOnly, "data.inf").writeBytes("Dados Editados".toByteArray(Charsets.ISO_8859_1))
        val noData = PatchesRepository.inspectEdit(infOnly)!!
        assertFalse(noData.complete)
        assertEquals(0, noData.version)
        assertEquals(0L, noData.bytes)

        val cut = temp.newFolder("cut")
        File(cut, "data.bin").writeBytes(SyntheticStfs.saveDataBin(14).copyOf(10))
        assertFalse(PatchesRepository.inspectEdit(cut)!!.complete)
    }

    // ---- backup naming ---------------------------------------------------

    @Test
    fun aBackupUsesItsSidecarNameAndFallsBackWhenItHasNone() {
        val dir = temp.newFolder("previous")
        File(dir, "FL 1.cpk").writeBytes(SyntheticStfs.cpkPayload(64))
        File(dir, "DpFileList.bin").writeBytes(SyntheticStfs.dpFileList("FL 1.cpk"))

        val bare = PatchesRepository.previousPatch(dir)!!
        assertTrue(bare.name, bare.name.startsWith("Previous patch · modified "))

        val props = Properties()
        props["patch.name"] = "ONFIRE"
        props["patch.source"] = "ZIP"
        props["patch.installedAt"] = "1700000000000"
        sidecar(dir).outputStream().use { props.store(it, "test") }

        val named = PatchesRepository.previousPatch(dir)!!
        assertEquals("ONFIRE", named.name)
        assertEquals("ZIP", named.source)
    }

    // ---- helpers ---------------------------------------------------------

    private fun props(vararg sizes: Pair<String, Long>, dp: ByteArray? = null): Properties {
        val props = Properties()
        props["patch.name"] = "ONFIRE"
        props["patch.source"] = "ZIP"
        props["patch.cpks"] = sizes.size.toString()
        props["patch.bytes"] = sizes.sumOf { it.second }.toString()
        props["patch.manifest"] = sizes.joinToString("\n") { "${it.first}:${it.second}" }
        if (dp != null) {
            props["patch.dpBytes"] = dp.size.toString()
            props["patch.dpSha256"] = sha256(dp)
        }
        return props
    }

    private fun sidecar(dir: File): File = File(dir.parentFile, dir.name + ".properties")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }
}
