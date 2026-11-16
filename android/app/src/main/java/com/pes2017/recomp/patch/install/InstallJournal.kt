package com.pes2017.recomp.patch.install

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

internal enum class JournalOp { INSTALL, RESTORE, REMOVE }

internal enum class JournalPhase { STAGING_READY, SWAPPING, COMMITTED }

internal enum class SlotState { PENDING, OLD_MOVING, OLD_MOVED, NEW_MOVING, SWAPPED }

internal data class JournalSlot(
    val slot: InstallSlot,
    var state: SlotState,
    val hadActive: Boolean,
    val target: String,
    val staged: String?,
    val backup: String?,
    val previousBackup: String? = null,
    val previousBackupTemp: String? = null,
)

/**
 * The small file that survives a process death. It is written before each
 * rename, so recovery can tell an unfinished movement from a finished one
 * without guessing.
 *
 * Paths are relative to the game root and are validated by PatchInstaller
 * before any of them is touched.
 */
internal class InstallJournal(
    val id: String,
    val op: JournalOp,
    var phase: JournalPhase,
    val slots: MutableList<JournalSlot>,
    val createdAt: Long,
    var work: String? = null,
) {

    fun write(file: File) {
        val props = Properties()
        props["version"] = "1"
        props["id"] = id
        // The same id the service's result/live records carry. `id` stays for
        // journals written by older builds; recovery only needs one of them.
        props["install.id"] = id
        props["op"] = op.name
        props["phase"] = phase.name
        props["created"] = createdAt.toString()
        work?.let { props["work"] = it }
        props["slot.count"] = slots.size.toString()
        slots.forEachIndexed { index, slot ->
            val prefix = "slot.$index."
            props[prefix + "slot"] = slot.slot.key
            props[prefix + "state"] = slot.state.name
            props[prefix + "hadActive"] = slot.hadActive.toString()
            props[prefix + "target"] = slot.target
            slot.staged?.let { props[prefix + "staged"] = it }
            slot.backup?.let { props[prefix + "backup"] = it }
            slot.previousBackup?.let { props[prefix + "previousBackup"] = it }
            slot.previousBackupTemp?.let { props[prefix + "previousBackupTemp"] = it }
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            props.store(out, "PES 2018 patch transaction")
            out.fd.sync()
        }
        // Replace the old journal atomically. Deleting it first opened a
        // window in which a process death left no journal at all, exactly
        // when recovery needed the previous state.
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

    companion object {
        fun read(file: File): InstallJournal {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            if (props.getProperty("version") != "1") {
                throw IllegalArgumentException("unknown journal version")
            }
            val id = props.getProperty("id")
                ?: props.getProperty("install.id")
                ?: throw IllegalArgumentException("journal has no id")
            val op = JournalOp.valueOf(
                props.getProperty("op") ?: throw IllegalArgumentException("journal has no op"),
            )
            val phase = JournalPhase.valueOf(
                props.getProperty("phase") ?: throw IllegalArgumentException("journal has no phase"),
            )
            val created = props.getProperty("created")?.toLongOrNull() ?: 0L
            val count = props.getProperty("slot.count")?.toIntOrNull() ?: 0
            val slots = ArrayList<JournalSlot>(count)
            for (index in 0 until count) {
                val prefix = "slot.$index."
                val slot = InstallSlot.forKey(
                    props.getProperty(prefix + "slot")
                        ?: throw IllegalArgumentException("journal slot $index has no kind"),
                ) ?: throw IllegalArgumentException("journal slot $index has an unknown kind")
                val target = props.getProperty(prefix + "target") ?: slot.relativePath
                if (target != slot.relativePath) {
                    throw IllegalArgumentException("journal routes ${slot.key} to $target")
                }
                slots.add(
                    JournalSlot(
                        slot = slot,
                        state = SlotState.valueOf(
                            props.getProperty(prefix + "state") ?: SlotState.PENDING.name,
                        ),
                        hadActive = props.getProperty(prefix + "hadActive")?.toBoolean() ?: false,
                        target = target,
                        staged = props.getProperty(prefix + "staged"),
                        backup = props.getProperty(prefix + "backup"),
                        previousBackup = props.getProperty(prefix + "previousBackup"),
                        previousBackupTemp = props.getProperty(prefix + "previousBackupTemp"),
                    ),
                )
            }
            return InstallJournal(
                id = id,
                op = op,
                phase = phase,
                slots = slots,
                createdAt = created,
                work = props.getProperty("work"),
            )
        }
    }
}
