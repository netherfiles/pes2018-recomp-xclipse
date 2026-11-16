package com.pes2017.recomp.patch.install

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Provenance for the installed slots: the name and source the player approved,
 * so PATCHES can show "PES 2018 Patch v3.1 · ZIP" rather than only "data pack".
 *
 * It is deliberately never consulted to decide whether a slot exists. A folder
 * pushed by hand, or a backup made before this file was written, is detected
 * from its DpFileList/CPKs/data.bin alone.
 *
 * The same idea covers backups: `patch-backups/<slot>/<stamp>.properties`
 * describes the data that was moved into `<stamp>` before the swap, so
 * RESTORE can say what it will put back (and restore that name afterwards)
 * instead of calling every backup "Previous data pack".
 */
internal object PatchStateStore {

    fun file(root: File): File = File(root, "patch-state.properties")

    /** The sidecar of one backup directory: `patch-backups/<slot>/<stamp>.properties`. */
    fun sidecar(backup: File): File = File(backup.parentFile, backup.name + ".properties")

    fun read(root: File): Properties = readProperties(file(root))

    fun readSidecar(backup: File): Properties = readProperties(sidecar(backup))

    fun update(root: File, values: Map<String, String>, remove: Set<String> = emptySet()) {
        val props = read(root)
        remove.forEach { props.remove(it) }
        values.forEach { (key, value) -> props[key] = value }
        write(file(root), props, "PES 2018 installed patch state")
    }

    fun writeSidecar(backup: File, props: Properties) {
        write(sidecar(backup), props, "PES 2018 patch backup")
    }

    fun clearSlot(root: File, slot: InstallSlot) {
        val prefix = prefix(slot)
        val props = read(root)
        val keys = props.stringPropertyNames().filter { it.startsWith(prefix) }.toSet()
        if (keys.isNotEmpty()) update(root, emptyMap(), keys)
    }

    /** The property prefix one slot owns in the state file and in a sidecar. */
    fun prefix(slot: InstallSlot): String = when (slot) {
        InstallSlot.PATCH -> "patch."
        InstallSlot.EDITED_DATA -> "edit."
        InstallSlot.SYSTEM -> "system."
    }

    private fun readProperties(file: File): Properties {
        val props = Properties()
        if (file.isFile) {
            runCatching { file.inputStream().use { props.load(it) } }
        }
        return props
    }

    /**
     * Replace [file] in one rename, so a process death leaves either the old
     * file or the new one. The caller must own the install lock.
     */
    private fun write(file: File, props: Properties, comment: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            props.store(out, comment)
            out.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
