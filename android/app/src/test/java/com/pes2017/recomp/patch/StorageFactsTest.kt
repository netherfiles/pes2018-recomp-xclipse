package com.pes2017.recomp.patch

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The storage numbers PATCHES shows, summed over a temp folder tree. The byte
 * counts are the assertion: a slot the screen counts twice, or misses, is a
 * wrong number on the page.
 */
class StorageFactsTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun everySlotIsSummedFromItsOwnFiles() {
        val root = temp.newFolder("root")
        val game = File(root, "PES2018").apply { mkdirs() }
        File(game, "default.xex").writeBytes(ByteArray(100))
        write(File(root, "dlc/SYSTEM00000000/a.cpk"), 200)
        write(File(root, "saves/EDIT00000000/data.bin"), 300)
        write(File(root, "saves/EDIT00000000/data.inf"), 50)
        write(File(root, "patch-backups/patch/20240101-000000/a.cpk"), 400)
        write(File(root, "patch-staging/id/a.cpk"), 500)
        val saveBackups = File(root, "save_backups").apply { mkdirs() }
        write(File(saveBackups, "auto-1.zip"), 600)
        val shader = File(root, "app/shader_cache.bin").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(700))
        }
        val pipeline = File(root, "app/pipeline.cache").apply { writeBytes(ByteArray(70)) }
        val log = File(root, "app/game.log").apply { writeBytes(ByteArray(30)) }
        val captures = File(root, "app/captures").apply { mkdirs() }
        write(File(captures, "c1/shot.png"), 80)

        val facts = StorageFacts.measure(
            root = root,
            game = game,
            saveBackups = saveBackups,
            shaders = listOf(shader, pipeline),
            logs = listOf(log),
            captures = captures,
            measuredAt = 1234L,
        )

        assertEquals(100L, facts.game.bytes)
        assertEquals(1, facts.game.files)
        assertEquals(200L, facts.dlc.bytes)
        assertEquals(350L, facts.saves.bytes)
        assertEquals(2, facts.saves.files)
        assertEquals(600L, facts.saveBackups.bytes)
        assertEquals(400L, facts.patchBackups.bytes)
        assertEquals(500L, facts.staging.bytes)
        assertEquals(770L, facts.shaders.bytes)
        assertEquals(2, facts.shaders.files)
        assertEquals(30L, facts.logs.bytes)
        assertEquals(80L, facts.captures.bytes)
        assertEquals(1234L, facts.measuredAt)
        assertNull(facts.problem)
        assertEquals(
            100L + 200L + 350L + 600L + 400L + 500L + 770L + 30L + 80L,
            facts.usedBytes,
        )
        assertTrue(facts.freeBytes >= 0L)
        assertTrue(facts.totalBytes >= 0L)
    }

    @Test
    fun aMissingSlotIsZeroAndAnUnreadableRootSaysSo() {
        val root = temp.newFolder("bare")
        val game = File(root, "missing-game")

        val facts = StorageFacts.measure(
            root = root,
            game = game,
            saveBackups = File(root, "none"),
            shaders = emptyList(),
            logs = emptyList(),
            captures = File(root, "none"),
            measuredAt = 1L,
        )
        assertEquals(SlotSize.EMPTY, facts.dlc)
        assertEquals(SlotSize.EMPTY, facts.patchBackups)
        assertEquals(SlotSize.EMPTY, facts.staging)
        assertEquals(0L, facts.usedBytes)
        assertTrue(facts.problem != null)
    }

    @Test
    fun singleFilesAndMissingPathsAreCountedWithoutThrowing() {
        val file = temp.newFile("one.bin").apply { writeBytes(ByteArray(42)) }
        assertEquals(SlotSize(42L, 1), StorageFacts.slotSize(file))
        assertEquals(SlotSize.EMPTY, StorageFacts.slotSize(File(temp.root, "gone")))
        assertEquals(SlotSize.EMPTY, StorageFacts.slotSize(null))
    }

    @Test
    fun formatBytesUsesStableIecLabels() {
        assertEquals("0 B", PatchFormat.formatBytes(0))
        assertEquals("1023 B", PatchFormat.formatBytes(1023))
        assertEquals("1.0 KiB", PatchFormat.formatBytes(1024))
        assertEquals("1.0 MiB", PatchFormat.formatBytes(1024L * 1024))
        assertEquals("1.00 GiB", PatchFormat.formatBytes(1024L * 1024 * 1024))
        assertEquals("19.18 GiB", PatchFormat.formatBytes(20_596_460_266L))
        assertEquals("0 B", PatchFormat.formatBytes(-1))
        assertEquals("20,596,460,266", PatchFormat.formatCount(20_596_460_266L))
    }

    private fun write(file: File, bytes: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
    }
}
