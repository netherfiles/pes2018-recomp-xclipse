package com.pes2017.recomp.patch

import android.content.Context
import com.pes2017.recomp.GameFolder
import com.pes2017.recomp.PatchesRepository
import com.pes2017.recomp.Prefs
import com.pes2017.recomp.SaveBackup
import java.io.File

/** Bytes and file count for one folder (or one loose file, counted as 1). */
data class SlotSize(val bytes: Long = 0L, val files: Int = 0) {

    val isEmpty: Boolean get() = bytes == 0L && files == 0

    operator fun plus(other: SlotSize): SlotSize = SlotSize(
        bytes = safeAdd(bytes, other.bytes),
        files = files + other.files,
    )

    companion object {
        val EMPTY = SlotSize()

        fun safeAdd(a: Long, b: Long): Long = try {
            Math.addExact(a, b)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }
}

/**
 * What the patch machinery is using on this phone, measured from the same
 * roots every other screen uses ([PatchesRepository.gameRoot] and
 * [Prefs.gameDirectory]) instead of a second guess at them.
 *
 * Every field is a size, not a path: the paths are fixed names under [root]
 * (except `saveBackups`, `shaders`, `logs` and `captures`, which live in the
 * app's own folders because that is where the app writes them).
 *
 * Building this walks several 20 GB trees. [PatchesDashboard] does that on its
 * worker thread; never call [measure] from `onResume` or a click handler.
 */
data class StorageFacts(
    val root: File,
    val game: SlotSize,
    val dlc: SlotSize,
    val saves: SlotSize,
    val saveBackups: SlotSize,
    val patchBackups: SlotSize,
    val staging: SlotSize,
    val shaders: SlotSize,
    val logs: SlotSize,
    val captures: SlotSize,
    val freeBytes: Long,
    val totalBytes: Long,
    val measuredAt: Long,
    val problem: String? = null,
) {

    /** Everything measured above, added: the patch machinery's own footprint. */
    val usedBytes: Long
        get() = listOf(game, dlc, saves, saveBackups, patchBackups, staging, shaders, logs, captures)
            .fold(0L) { total, slot -> SlotSize.safeAdd(total, slot.bytes) }

    companion object {

        /**
         * The phone's real storage facts, or null when no usable game folder
         * is configured (PATCHES then shows its no-game state, not zeros).
         */
        fun measure(context: Context?): StorageFacts? {
            val ctx = context ?: return null
            val root = PatchesRepository.gameRoot(ctx) ?: return null
            val prefs = Prefs(ctx)
            val appBase = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val shaderFiles = listOf(
                File(appBase, "shader_cache.bin"),
                File(ctx.filesDir, "shader_cache.bin"),
                File(ctx.filesDir, "pipeline.cache"),
            )
            // Every file the log screen and the diagnostics report read, the
            // rotated (.1) runs included, plus the crash history folder: this
            // slot is meant to be what clearing the logs would free.
            val logFiles = listOf(
                "game.log",
                "game.log.1",
                "launcher.log",
                "launcher.log.1",
                "last-crash-game.txt",
                "last-crash-launcher.txt",
                "events-launcher.txt",
                "events-launcher.txt.1",
                "events-game.txt",
                "events-game.txt.1",
                "crash-history",
            ).map { File(ctx.filesDir, it) }
            return measure(
                root = root,
                game = File(prefs.gameDirectory),
                saveBackups = SaveBackup.automaticDir(ctx),
                shaders = shaderFiles,
                logs = logFiles,
                captures = File(appBase, "captures"),
            )
        }

        /** The same measurement over explicit paths, so a temp-dir test needs no phone. */
        fun measure(
            root: File,
            game: File,
            saveBackups: File,
            shaders: List<File>,
            logs: List<File>,
            captures: File,
            measuredAt: Long = System.currentTimeMillis(),
        ): StorageFacts {
            val problem = when {
                !root.isDirectory -> "the folder above the game folder is not readable"
                !File(game, GameFolder.XEX).isFile -> "the game folder has no ${GameFolder.XEX}"
                else -> null
            }
            return StorageFacts(
                root = root,
                game = slotSize(game),
                dlc = slotSize(File(root, "dlc")),
                saves = slotSize(File(root, "saves")),
                saveBackups = slotSize(saveBackups),
                patchBackups = slotSize(File(root, "patch-backups")),
                staging = slotSize(File(root, "patch-staging")),
                shaders = slots(shaders),
                logs = slots(logs),
                captures = slotSize(captures),
                freeBytes = runCatching { root.usableSpace }.getOrDefault(0L),
                totalBytes = runCatching { root.totalSpace }.getOrDefault(0L),
                measuredAt = measuredAt,
                problem = problem,
            )
        }

        /** Bytes and files under [file]; a missing or unreadable path is 0/0, never a throw. */
        fun slotSize(file: File?): SlotSize {
            if (file == null || !file.exists()) return SlotSize.EMPTY
            return try {
                if (file.isFile) {
                    SlotSize(file.length(), 1)
                } else {
                    val files = file.walkTopDown().filter { it.isFile }.toList()
                    SlotSize(files.sumOf { it.length() }, files.size)
                }
            } catch (_: Throwable) {
                SlotSize.EMPTY
            }
        }

        private fun slots(files: List<File>): SlotSize =
            files.fold(SlotSize.EMPTY) { total, file -> total + slotSize(file) }
    }
}
