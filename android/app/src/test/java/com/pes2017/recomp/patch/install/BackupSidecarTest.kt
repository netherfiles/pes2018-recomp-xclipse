package com.pes2017.recomp.patch.install

import com.pes2017.recomp.patch.FolderSource
import com.pes2017.recomp.patch.PatchPlan
import com.pes2017.recomp.patch.PatchScanner
import com.pes2017.recomp.patch.SyntheticStfs
import java.io.File
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
 * The backup sidecar: written before the swap, read by RESTORE, removed with
 * the backup. A backup made before sidecars existed must still restore, with
 * its provenance simply unknown.
 */
class BackupSidecarTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private var unique = 0

    @Test
    fun theSidecarNamesTheBackupAndRestorePutsThatNameBack() {
        val f = fixture()
        assertTrue(
            installer(f.root).install(
                FolderSource(f.source),
                f.plan,
                InstallSelection(patch = true, editedData = false, system = false),
                InstallBackupPolicy.KEEP,
            ) is InstallResult.Success,
        )

        val slotBackups = File(f.root, "patch-backups/patch")
        val backup = slotBackups.listFiles()!!.filter { it.isDirectory }.single()
        val sidecar = File(slotBackups, backup.name + ".properties")
        assertTrue("every backup has a sidecar", sidecar.isFile)

        // The install itself records the identity a later read verifies.
        val installed = read(File(f.root, "patch-state.properties"))
        assertEquals(f.plan.dpFileList.size.toString(), installed.getProperty("patch.dpBytes"))
        assertEquals(f.plan.dpFileList.sha256, installed.getProperty("patch.dpSha256"))
        assertTrue(
            installed.getProperty("patch.manifest").orEmpty().contains("FL 1.cpk:"),
        )

        val props = read(sidecar)
        assertEquals("patch", props.getProperty("backup.slot"))
        assertEquals("Old pack", props.getProperty("patch.name"))
        assertEquals("ZIP", props.getProperty("patch.source"))
        assertEquals(f.oldBytes.toString(), props.getProperty("backup.bytes"))
        assertTrue((props.getProperty("backup.dpBytes")?.toLongOrNull() ?: 0L) > 0L)
        assertNotNull(props.getProperty("backup.dpSha256"))

        assertTrue(installer(f.root).restore(InstallSlot.PATCH) is InstallResult.Success)
        assertTrue(File(f.root, "dlc/SYSTEM00000000/old.cpk").isFile)
        assertFalse("the consumed backup is gone", slotBackups.exists())

        val state = read(File(f.root, "patch-state.properties"))
        assertEquals("Old pack", state.getProperty("patch.name"))
        assertEquals("ZIP", state.getProperty("patch.source"))
        assertEquals("1", state.getProperty("patch.cpks"))
        assertNotNull(state.getProperty("patch.manifest"))
        assertNotNull(state.getProperty("patch.dpBytes"))
    }

    @Test
    fun aBackupWithoutASidecarStillRestoresAndSimplyForgetsItsName() {
        val f = fixture()
        assertTrue(
            installer(f.root).install(
                FolderSource(f.source),
                f.plan,
                InstallSelection(patch = true, editedData = false, system = false),
                InstallBackupPolicy.KEEP,
            ) is InstallResult.Success,
        )

        val slotBackups = File(f.root, "patch-backups/patch")
        val backup = slotBackups.listFiles()!!.filter { it.isDirectory }.single()
        assertTrue(File(slotBackups, backup.name + ".properties").delete())

        assertTrue(installer(f.root).restore(InstallSlot.PATCH) is InstallResult.Success)
        assertTrue(File(f.root, "dlc/SYSTEM00000000/old.cpk").isFile)

        val state = read(File(f.root, "patch-state.properties"))
        assertNull("no sidecar means no recorded provenance", state.getProperty("patch.name"))
        assertNull(state.getProperty("patch.manifest"))
    }

    @Test
    fun deleteRemovesTheSidecarWithTheBackup() {
        val f = fixture()
        assertTrue(
            installer(f.root).install(
                FolderSource(f.source),
                f.plan,
                InstallSelection(patch = true, editedData = false, system = false),
                InstallBackupPolicy.KEEP,
            ) is InstallResult.Success,
        )
        assertTrue(installer(f.root).deleteBackup(InstallSlot.PATCH) is InstallResult.Success)
        assertFalse(File(f.root, "patch-backups/patch").exists())
    }

    // ---- fixture ---------------------------------------------------------

    private data class Fixture(
        val source: File,
        val root: File,
        val plan: PatchPlan,
        val oldBytes: Long,
    )

    private fun fixture(): Fixture {
        val n = unique++
        val source = temp.newFolder("source-$n")
        val cons = temp.newFolder("cons-$n")
        val title = File(source, "Content/0000000000000000/4A3007D3/00000001")
        title.mkdirs()
        val cpk = SyntheticStfs.cpkPayload(0x300)
        SyntheticStfs.build(
            File(cons, "cpk-$n.con"),
            specs = listOf(SyntheticStfs.Spec("FL 1.cpk", cpk)),
        ).copyTo(File(title, "FL 1"), overwrite = true)
        val dp = SyntheticStfs.dpFileList("FL 1.cpk")
        SyntheticStfs.build(
            File(cons, "dp-$n.con"),
            specs = listOf(SyntheticStfs.Spec("DpFileList.bin", dp)),
        ).copyTo(File(title, "DpFileList.bin"), overwrite = true)

        val plan = FolderSource(source).use { scanned ->
            PatchScanner(temp.newFolder("scratch-$n")).scan(scanned)
        }

        val root = temp.newFolder("root-$n")
        val patch = File(root, "dlc/SYSTEM00000000").apply { mkdirs() }
        val oldCpk = SyntheticStfs.cpkPayload(900)
        File(patch, "old.cpk").writeBytes(oldCpk)
        val oldDp = SyntheticStfs.dpFileList("old.cpk")
        File(patch, "DpFileList.bin").writeBytes(oldDp)

        val props = Properties()
        props["patch.name"] = "Old pack"
        props["patch.source"] = "ZIP"
        props["patch.installedAt"] = "1700000000000"
        props["patch.cpks"] = "1"
        props["patch.bytes"] = oldCpk.size.toString()
        props["patch.manifest"] = "old.cpk:${oldCpk.size}"
        props["patch.dpBytes"] = oldDp.size.toString()
        File(root, "patch-state.properties").outputStream().use { props.store(it, "test") }

        return Fixture(source, root, plan, oldCpk.size + oldDp.size.toLong())
    }

    private fun installer(root: File): PatchInstaller =
        PatchInstaller(root, freeSpace = { Long.MAX_VALUE })

    private fun read(file: File): Properties = Properties().apply {
        file.inputStream().use { load(it) }
    }
}
