package com.pes2017.recomp.patch.install

import com.pes2017.recomp.patch.FolderSource
import com.pes2017.recomp.patch.PatchPlan
import com.pes2017.recomp.patch.PatchScanner
import com.pes2017.recomp.patch.PatchSourceKind
import com.pes2017.recomp.patch.SaveSlot
import com.pes2017.recomp.patch.SyntheticStfs
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The transaction without Android: synthetic STFS CONs, a temp game root and
 * a fake free-space probe. No game data, no device.
 */
class PatchInstallerTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private var unique = 0

    private data class Fixture(
        val source: File,
        val root: File,
        val plan: PatchPlan,
        val cpk: ByteArray,
        val dp: ByteArray,
        val editDataBin: ByteArray,
    )

    @Test
    fun installsSelectedSlotsAndKeepsOneBackup() {
        val f = fixture()
        val result = installer(f.root).install(
            FolderSource(f.source),
            f.plan,
            InstallSelection(patch = true, editedData = true, system = false),
            InstallBackupPolicy.KEEP,
        )
        val success = result as? InstallResult.Success
        assertNotNull(result.toString(), success)
        assertTrue(success!!.slots.contains(InstallSlot.PATCH))

        val patchDir = File(f.root, "dlc/SYSTEM00000000")
        assertArrayEquals(f.cpk, File(patchDir, "FL 1.cpk").readBytes())
        assertArrayEquals(f.dp, File(patchDir, "DpFileList.bin").readBytes())
        assertEquals(f.dp.size.toLong(), File(patchDir, "DpFileList.bin").length())
        assertFalse(File(patchDir, "EDIT00000000").exists())
        assertFalse(File(patchDir, "old.cpk").exists())

        val editDir = File(f.root, "saves/EDIT00000000")
        assertArrayEquals(f.editDataBin, File(editDir, "data.bin").readBytes())
        assertTrue(File(editDir, ".xcontent").isFile)

        // Sidecars (one .properties per backup) are metadata, not backups.
        val patchBackup = File(f.root, "patch-backups/patch").listFiles()!!
            .filter { it.isDirectory }
            .single()
        assertTrue(File(patchBackup, "old.cpk").isFile)
        assertTrue(File(patchBackup, "DpFileList.bin").isFile)
        assertTrue(
            File(f.root, "patch-backups/edit").listFiles()!!
                .filter { it.isDirectory }
                .single()
                .isDirectory,
        )

        assertFalse(File(f.root, "patch-journal.properties").exists())
        assertFalse(File(f.root, "patch-staging").exists())
        assertTrue(File(f.root, "patch-state.properties").isFile)
        assertEquals(1, f.plan.cpkListedCount)
    }

    @Test
    fun cancelBeforeSwapLeavesTheActiveSlotsUntouched() {
        val f = fixture()
        var cancel = false
        val result = installer(f.root).install(
            FolderSource(f.source),
            f.plan,
            InstallSelection(patch = true, editedData = true),
            InstallBackupPolicy.KEEP,
            cancelled = { cancel },
            progress = { progress ->
                if (progress.phase == InstallPhase.STAGING && progress.percent > 0) {
                    cancel = true
                }
            },
        )
        assertTrue(result is InstallResult.Cancelled)
        assertTrue(File(f.root, "dlc/SYSTEM00000000/old.cpk").isFile)
        assertFalse(File(f.root, "dlc/SYSTEM00000000/FL 1.cpk").exists())
        assertFalse(File(f.root, "patch-backups/patch").exists())
        assertFalse(File(f.root, "patch-staging").exists())
    }

    @Test
    fun lowSpaceFailsBeforeWritingAnything() {
        val f = fixture()
        val result = PatchInstaller(f.root, freeSpace = { 1024L }).install(
            FolderSource(f.source),
            f.plan,
            InstallSelection(patch = true, editedData = true),
            InstallBackupPolicy.KEEP,
        )
        val failed = result as? InstallResult.Failed
        assertNotNull(result.toString(), failed)
        assertEquals(InstallFailure.LOW_SPACE, failed!!.failure)
        assertTrue("nothing was written, so the slots are intact", failed.restored)
        assertTrue(File(f.root, "dlc/SYSTEM00000000/old.cpk").isFile)
        assertFalse(File(f.root, "patch-staging").exists())
    }

    @Test
    fun duplicateSaveUsesTheCanonicalCopyOnly() {
        val f = fixture()
        val edit = f.plan.saves.first { it.slot == SaveSlot.EDIT }
        assertTrue(f.plan.duplicates.isNotEmpty())
        assertTrue(edit.sourcePath.contains("/E000", ignoreCase = true))
        // Change the copy the scanner did not choose. If the installer read
        // it, the save hash would no longer match the plan.
        val other = File(
            f.source,
            "Content/0000000000000000/4A3007D3/00000001/EDIT00000000",
        )
        other.writeBytes(ByteArray(other.length().toInt()))

        val result = installer(f.root).install(
            FolderSource(f.source),
            f.plan,
            InstallSelection(patch = false, editedData = true, system = false),
            InstallBackupPolicy.KEEP,
        )
        assertTrue(result is InstallResult.Success)
        assertArrayEquals(
            f.editDataBin,
            File(f.root, "saves/EDIT00000000/data.bin").readBytes(),
        )
        assertFalse(File(f.root, "dlc/SYSTEM00000000/EDIT00000000").exists())
    }

    @Test
    fun wrongSlotRoutingIsRejectedBeforeStaging() {
        val f = fixture()
        val broken = f.plan.copy(
            saves = f.plan.saves.map {
                if (it.slot == SaveSlot.EDIT) it.copy(targetName = "SYSTEM00000000") else it
            },
        )
        val result = installer(f.root).install(
            FolderSource(f.source),
            broken,
            InstallSelection(patch = false, editedData = true, system = false),
            InstallBackupPolicy.KEEP,
        )
        val failed = result as? InstallResult.Failed
        assertNotNull(result.toString(), failed)
        assertEquals(InstallFailure.VERIFY, failed!!.failure)
        assertTrue(File(f.root, "saves/EDIT00000000/old.bin").isFile)
        assertFalse(File(f.root, "patch-staging").exists())

        assertTrue(InstallSlot.PATCH.relativePath.startsWith("dlc/"))
        assertTrue(InstallSlot.EDITED_DATA.relativePath.startsWith("saves/"))
        assertTrue(InstallSlot.SYSTEM.relativePath.startsWith("saves/"))
        assertEquals(InstallSlot.EDITED_DATA, InstallSlot.forSave(SaveSlot.EDIT))
    }

    @Test
    fun failedNoBackupInstallStillPutsTheActiveSlotBack() {
        val f = fixture()
        try {
            PatchInstaller(
                f.root,
                freeSpace = { Long.MAX_VALUE },
                fault = { where, _ ->
                    if (where == FaultPoint.NEW_MOVED) {
                        throw SimulatedProcessDeath(where.name)
                    }
                },
            ).install(
                FolderSource(f.source),
                f.plan,
                InstallSelection(patch = true, editedData = false),
                InstallBackupPolicy.NONE,
            )
            fail("expected process death")
        } catch (_: SimulatedProcessDeath) {
        }

        // With NONE the old slot must be moved into the work folder, not
        // deleted in place: recovery can then undo the half-finished swap.
        PatchInstaller(f.root, freeSpace = { Long.MAX_VALUE }).recover()
        assertTrue(File(f.root, "dlc/SYSTEM00000000/old.cpk").isFile)
        assertFalse(File(f.root, "dlc/SYSTEM00000000/FL 1.cpk").exists())
        assertFalse(File(f.root, "patch-journal.properties").exists())
        assertFalse(File(f.root, "patch-staging").exists())
    }

    @Test
    fun everyJournalPhaseRecovers() {
        for (point in FaultPoint.values().filter { it != FaultPoint.REMOVE_STAGED }) {
            val f = fixture()
            try {
                PatchInstaller(
                    f.root,
                    freeSpace = { Long.MAX_VALUE },
                    fault = { where, _ ->
                        if (where == point) throw SimulatedProcessDeath(where.name)
                    },
                ).install(
                    FolderSource(f.source),
                    f.plan,
                    InstallSelection(patch = true, editedData = true),
                    InstallBackupPolicy.KEEP,
                )
                fail("expected process death at $point")
            } catch (_: SimulatedProcessDeath) {
            }

            val report = PatchInstaller(f.root, freeSpace = { Long.MAX_VALUE }).recover()
            val patchDir = File(f.root, "dlc/SYSTEM00000000")
            when (point) {
                FaultPoint.STAGED,
                FaultPoint.JOURNAL_READY,
                -> {
                    assertTrue("$point: old patch", File(patchDir, "old.cpk").isFile)
                    assertFalse("$point: new patch", File(patchDir, "FL 1.cpk").exists())
                }

                FaultPoint.OLD_MOVED,
                FaultPoint.NEW_MOVED,
                -> {
                    assertTrue("$point: old patch", File(patchDir, "old.cpk").isFile)
                    assertFalse("$point: new patch", File(patchDir, "FL 1.cpk").exists())
                }

                FaultPoint.COMMITTED -> {
                    assertTrue("$point: committed", report.committedInstallKept)
                    assertArrayEquals(
                        f.cpk,
                        File(patchDir, "FL 1.cpk").readBytes(),
                    )
                    assertFalse(File(patchDir, "old.cpk").exists())
                }

                FaultPoint.REMOVE_STAGED -> Unit
            }
            assertFalse("$point: journal", File(f.root, "patch-journal.properties").exists())
            val staging = File(f.root, "patch-staging")
            assertFalse(
                "$point: staging",
                staging.isDirectory && staging.listFiles()?.isNotEmpty() == true,
            )
            assertFalse(File(f.root, ".patch-install.lock").exists())
        }
    }

    @Test
    fun failedKeepInstallRestoresThePreviousBackupToo() {
        val f = fixture()
        val oldBackup = File(f.root, "patch-backups/patch/20200101-000000")
        oldBackup.mkdirs()
        File(oldBackup, "old-restore.cpk").writeBytes("CPK previous".toByteArray())
        try {
            PatchInstaller(
                f.root,
                freeSpace = { Long.MAX_VALUE },
                fault = { where, _ ->
                    if (where == FaultPoint.NEW_MOVED) {
                        throw SimulatedProcessDeath(where.name)
                    }
                },
            ).install(
                FolderSource(f.source),
                f.plan,
                InstallSelection(patch = true, editedData = false),
                InstallBackupPolicy.KEEP,
            )
            fail("expected process death")
        } catch (_: SimulatedProcessDeath) {
        }
        PatchInstaller(f.root, freeSpace = { Long.MAX_VALUE }).recover()
        assertTrue(File(f.root, "dlc/SYSTEM00000000/old.cpk").isFile)
        assertTrue(File(oldBackup, "old-restore.cpk").isFile)
        assertFalse(File(f.root, "patch-backups/patch").listFiles().isNullOrEmpty())
    }

    @Test
    fun removeMovesTheSlotOutBeforeDeletingIt() {
        val f = fixture()
        assertTrue(
            installer(f.root).install(
                FolderSource(f.source),
                f.plan,
                InstallSelection(patch = true, editedData = true),
                InstallBackupPolicy.KEEP,
            ) is InstallResult.Success,
        )
        val target = File(f.root, "dlc/SYSTEM00000000")
        try {
            PatchInstaller(
                f.root,
                freeSpace = { Long.MAX_VALUE },
                fault = { where, _ ->
                    if (where == FaultPoint.REMOVE_STAGED) {
                        throw SimulatedProcessDeath(where.name)
                    }
                },
            ).remove(InstallSlot.PATCH)
            fail("expected process death")
        } catch (_: SimulatedProcessDeath) {
        }

        // The active name is already gone; what remains is staging, which the
        // next recovery removes. It must never be a half-deleted active slot.
        assertFalse(target.exists())
        assertTrue(File(f.root, "patch-staging").isDirectory)
        PatchInstaller(f.root, freeSpace = { Long.MAX_VALUE }).recover()
        assertFalse(target.exists())
        assertFalse(File(f.root, "patch-staging").exists())
        assertTrue(File(f.root, "patch-backups/patch").isDirectory)
    }

    @Test
    fun removeLeavesTheBackupAndDeletesTheRuntimeBak() {
        val f = fixture()
        assertTrue(
            installer(f.root).install(
                FolderSource(f.source),
                f.plan,
                InstallSelection(patch = false, editedData = true),
                InstallBackupPolicy.KEEP,
            ) is InstallResult.Success,
        )
        val bak = File(f.root, "saves/EDIT00000000.bak")
        bak.mkdirs()
        File(bak, "data.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(installer(f.root).remove(InstallSlot.EDITED_DATA) is InstallResult.Success)
        assertFalse(File(f.root, "saves/EDIT00000000").exists())
        assertFalse(bak.exists())
        assertFalse(File(f.root, "patch-staging").exists())
        assertTrue(File(f.root, "patch-backups/edit").isDirectory)
    }

    @Test
    fun restorePutsTheBackupBackAndClearsIt() {
        val f = fixture()
        assertTrue(
            installer(f.root).install(
                FolderSource(f.source),
                f.plan,
                InstallSelection(patch = true, editedData = true),
                InstallBackupPolicy.KEEP,
            ) is InstallResult.Success,
        )
        val restored = installer(f.root).restore(InstallSlot.PATCH)
        assertTrue(restored is InstallResult.Success)
        assertTrue(File(f.root, "dlc/SYSTEM00000000/old.cpk").isFile)
        assertFalse(File(f.root, "dlc/SYSTEM00000000/FL 1.cpk").exists())
        assertFalse(File(f.root, "patch-backups/patch").exists())
        assertFalse(File(f.root, "patch-journal.properties").exists())
    }

    @Test
    fun archiveSpaceIncludesTheSpool() {
        val f = fixture()
        val archivePlan = f.plan.copy(sourceKind = PatchSourceKind.ZIP)
        val space = PatchInstaller(f.root, freeSpace = { Long.MAX_VALUE })
            .space(archivePlan, InstallSelection(true, true, false), InstallBackupPolicy.KEEP)
        val largest = maxOf(
            f.plan.cpks.maxOf { it.outerSize },
            f.plan.saves.maxOf { it.outerSize },
        )
        assertEquals(largest, space.spoolBytes)
        assertTrue(space.requiredBytes >= space.stagingBytes + space.spoolBytes)
        assertTrue(space.enough)
    }

    // ---- fixture ---------------------------------------------------------

    private fun fixture(): Fixture {
        val n = unique++
        val source = temp.newFolder("source-$n")
        val cons = temp.newFolder("cons-$n")
        val title = File(source, "Content/0000000000000000/4A3007D3/00000001")
        title.mkdirs()
        val cpk = SyntheticStfs.cpkPayload(0x300)
        val cpkCon = SyntheticStfs.build(
            File(cons, "cpk-$n.con"),
            specs = listOf(SyntheticStfs.Spec("FL 1.cpk", cpk)),
        )
        cpkCon.copyTo(File(title, "FL 1"), overwrite = true)
        val dp = SyntheticStfs.dpFileList("FL 1.cpk")
        val dpCon = SyntheticStfs.build(
            File(cons, "dp-$n.con"),
            specs = listOf(SyntheticStfs.Spec("DpFileList.bin", dp)),
        )
        dpCon.copyTo(File(title, "DpFileList.bin"), overwrite = true)
        val editDataBin = SyntheticStfs.saveDataBin(14)
        val editCon = SyntheticStfs.saveCon(File(cons, "edit-$n.con"), version = 14)
        val profile = File(source, "Content/E00039D75217C032/4A3007D3/00000001")
        profile.mkdirs()
        editCon.copyTo(File(profile, "EDIT00000000"), overwrite = true)
        editCon.copyTo(File(title, "EDIT00000000"), overwrite = true)
        val systemCon = SyntheticStfs.saveCon(
            File(cons, "system-$n.con"),
            version = 16,
            dataInf = "Dados do sistema",
        )
        systemCon.copyTo(File(profile, "SYSTEM00000000"), overwrite = true)

        val plan = FolderSource(source).use { scanned ->
            PatchScanner(temp.newFolder("scratch-$n")).scan(scanned)
        }
        val root = temp.newFolder("root-$n")
        seed(root)
        return Fixture(source, root, plan, cpk, dp, editDataBin)
    }

    private fun seed(root: File) {
        val patch = File(root, "dlc/SYSTEM00000000")
        patch.mkdirs()
        File(patch, "old.cpk").writeBytes("CPK old-patch".toByteArray())
        File(patch, "DpFileList.bin").writeBytes(ByteArray(48))
        val edit = File(root, "saves/EDIT00000000")
        edit.mkdirs()
        File(edit, "data.bin").writeBytes(SyntheticStfs.saveDataBin(14))
        File(edit, "data.inf").writeBytes(ByteArray(384))
        File(edit, ".xcontent").writeBytes(ByteArray(8) { it.toByte() })
        File(edit, "old.bin").writeBytes(byteArrayOf(1, 2, 3))
        val system = File(root, "saves/SYSTEM00000000")
        system.mkdirs()
        File(system, "data.bin").writeBytes(SyntheticStfs.saveDataBin(16))
        File(system, "data.inf").writeBytes(ByteArray(384))
    }

    private fun installer(root: File): PatchInstaller =
        PatchInstaller(root, freeSpace = { Long.MAX_VALUE })
}
