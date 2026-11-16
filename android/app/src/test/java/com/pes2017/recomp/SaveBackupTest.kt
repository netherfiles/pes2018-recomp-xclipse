package com.pes2017.recomp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The save-restore path, without Android: a real zip, a temp saves folder. */
class SaveBackupTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun restoreReplacesTheSavesFolderWithTheArchive() {
        val saves = temp.newFolder("saves")
        File(saves, "OLD00000000").mkdirs()
        File(saves, "OLD00000000/data.bin").writeBytes(byteArrayOf(9))

        val restored = SaveBackup.restore(
            saves,
            ByteArrayInputStream(
                zip(
                    "saves/EDIT00000000/data.bin" to byteArrayOf(1, 2, 3),
                    "saves/SYSTEM00000000/data.inf" to byteArrayOf(4, 5),
                ),
            ),
        )

        assertEquals(2, restored)
        assertTrue(File(saves, "EDIT00000000/data.bin").isFile)
        assertTrue(File(saves, "SYSTEM00000000/data.inf").isFile)
        assertFalse(File(saves, "OLD00000000").exists())
        assertFalse(File(saves.parentFile, "saves.restoring").exists())
    }

    @Test
    fun restoreRejectsAPathOutsideTheSavesFolder() {
        val saves = temp.newFolder("saves-zip-slip")
        val outside = File(saves.parentFile, "outside")
        try {
            SaveBackup.restore(
                saves,
                ByteArrayInputStream(zip("saves/../outside/owned.bin" to byteArrayOf(1))),
            )
            fail("a path outside the saves folder must be refused")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(File(outside, "owned.bin").exists())
        assertFalse(File(saves.parentFile, "saves.restoring").exists())
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
