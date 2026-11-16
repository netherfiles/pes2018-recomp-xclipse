package com.pes2017.recomp

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Copies of the game's saves: Master League, Become a Legend, edit data, the
 * system data and the profile. The runtime keeps them all under `saves/`
 * beside the game folder (runtime/android/jni.cpp changes to the game folder's
 * parent; docs/saves.md), one directory per content package, so a backup is
 * that folder as a zip whose entries all start with `saves/`.
 *
 * Two kinds: one the player writes wherever they like (Settings > Game files),
 * and an automatic one taken in the app's own folder each time a match is
 * started from the launcher, the last [KEEP_AUTOMATIC] of them kept.
 */
object SaveBackup {

    private const val PREFIX = "saves/"
    private const val KEEP_AUTOMATIC = 5
    private const val MAX_PACKAGE_BYTES = 100L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private val NOT_SAVES = setOf("patch-backups", "patch-staging")

    /** The folder the runtime saves into for the chosen game folder. */
    fun savesDir(context: Context, prefs: Prefs): File {
        val game = prefs.gameDirectory.ifEmpty { null }?.let { File(it) }
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(game?.parentFile ?: base, "saves")
    }

    /** Where the automatic backups are kept: the app's own folder, so no permission is needed. */
    fun automaticDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "save_backups")

    /** The packages in the saves folder (one directory each), for showing what a backup holds. */
    fun packages(saves: File): List<String> =
        saves.listFiles()
            ?.filter {
                it.isDirectory &&
                    !it.name.endsWith(".bak", ignoreCase = true) &&
                    it.name !in NOT_SAVES
            }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    /** Whether the game's process is up: its saves must not be replaced under it. */
    fun gameRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val game = context.packageName + ":game"
        return manager.runningAppProcesses?.any { it.processName == game } == true
    }

    /** Writes the save packages as a zip. Returns the number of files written. */
    fun write(saves: File, out: OutputStream): Int {
        val dirs = packages(saves).map { File(saves, it) }
        return writePackages(dirs, out)
    }

    private fun writePackages(packages: List<File>, out: OutputStream): Int {
        var count = 0
        ZipOutputStream(out.buffered()).use { zip ->
            packages.forEach { packageDir ->
                if (!packageDir.isDirectory) return@forEach
                packageDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val name = PREFIX + file.relativeTo(packageDir).invariantSeparatorsPath
                    val entryName = PREFIX + packageDir.name + "/" + name.removePrefix(PREFIX)
                    zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                }
            }
        }
        return count
    }

    /**
     * Replaces the saves folder with a backup's. The backup is unpacked beside
     * it first and swapped in only once it is whole; the saves it replaces are
     * kept as `saves.before-restore-<time>`, so a wrong choice can be undone by
     * hand. Throws if the zip is not one of these backups.
     */
    fun restore(saves: File, input: InputStream): Int {
        val parent = saves.absoluteFile.parentFile ?: throw IllegalStateException("no folder above $saves")
        val staging = File(parent, "saves.restoring")
        staging.deleteRecursively()
        staging.mkdirs()
        var count = 0
        var total = 0L
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    if (!name.startsWith(PREFIX)) throw IllegalArgumentException("not a save backup: $name")
                    if (entry.size > MAX_PACKAGE_BYTES) {
                        throw IllegalArgumentException("a save package in the backup is too large")
                    }
                    val target = File(staging, name.removePrefix(PREFIX)).canonicalFile
                    if (!target.path.startsWith(staging.canonicalPath + File.separator)) {
                        throw IllegalArgumentException("a path outside the saves: $name")
                    }
                    if (entry.isDirectory) { target.mkdirs(); continue }
                    target.parentFile?.mkdirs()
                    var fileBytes = 0L
                    target.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            fileBytes += read
                            total += read
                            if (fileBytes > MAX_PACKAGE_BYTES || total > MAX_TOTAL_BYTES) {
                                throw IllegalArgumentException("the backup is larger than a save can be")
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                    if (entry.time > 0) target.setLastModified(entry.time)
                    count++
                }
            }
            if (count == 0) throw IllegalArgumentException("the backup is empty")
            var kept: File? = null
            if (saves.exists()) {
                kept = File(parent, "saves.before-restore-" + stamp())
                if (!saves.renameTo(kept)) throw IllegalStateException("could not move the current saves aside")
            }
            if (!staging.renameTo(saves)) {
                kept?.renameTo(saves)   // put the player's own saves back rather than leave none
                throw IllegalStateException("could not put the restored saves in place")
            }
            return count
        } finally {
            staging.deleteRecursively()
        }
    }

    /** The automatic backup before a match; quiet, and only when there is something to keep. */
    fun automatic(context: Context, prefs: Prefs) {
        val saves = savesDir(context, prefs)
        if (packages(saves).isEmpty()) return
        val dir = automaticDir(context)
        dir.mkdirs()
        val file = File(dir, "auto-${stamp()}.zip")
        try {
            val chosen = ArrayList<File>()
            var total = 0L
            for (name in packages(saves)) {
                val packageDir = File(saves, name)
                val bytes = sizeOf(packageDir)
                // A misrouted saves folder must never try to zip a 13 GB
                // patch package. The caps are per package and for the whole
                // archive; a skipped package is simply left out.
                if (bytes <= 0 || bytes > MAX_PACKAGE_BYTES) continue
                if (total + bytes > MAX_TOTAL_BYTES) continue
                chosen.add(packageDir)
                total += bytes
            }
            if (chosen.isEmpty()) return
            file.outputStream().use { writePackages(chosen, it) }
        } catch (_: Throwable) {
            file.delete()
            return
        }
        automaticBackups(context).drop(KEEP_AUTOMATIC).forEach { it.delete() }
    }

    private fun sizeOf(file: File): Long =
        if (file.isFile) file.length()
        else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** The automatic backups, newest first. */
    fun automaticBackups(context: Context): List<File> =
        automaticDir(context).listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** The file name offered for a backup the player saves. */
    fun suggestedName(): String = "pes2018-saves-${stamp()}.zip"

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
