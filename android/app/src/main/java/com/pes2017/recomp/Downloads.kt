package com.pes2017.recomp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Putting the diagnostics ZIP in `Download/PES18`, so it can be found by a file
 * manager or attached from a mail app instead of only from the share sheet.
 *
 * Android 10 and later: MediaStore, which needs no permission and writes
 * through a pending row so a half-copied file never appears in Downloads. The
 * pending flag is what makes it safe to kill the app mid-copy: the row is
 * invisible until the last byte is there, and deleted if it never arrives.
 *
 * Android 8 and 9: the public Downloads folder is only writable with the
 * storage permission, and the caller has to have asked for it (this app asks
 * for it for the game folder anyway). The copy goes to `.part` first for the
 * same reason.
 *
 * Returns null on success, or a sentence for the screen to show. Never throws:
 * "the ZIP is on the phone" is the point, and a save that crashes the settings
 * screen is worse than one that says why it did not work.
 */
object Downloads {

    private const val RELATIVE = "Download/PES18"
    private const val MIME = "application/zip"

    fun save(context: Context, file: File): String? = try {
        if (!file.isFile) {
            context.getString(R.string.diagnostics_downloads_failed, "the ZIP is not there")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveThroughMediaStore(context, file)
        } else {
            saveToPublicFolder(context, file)
        }
    } catch (t: Throwable) {
        reason(context, t)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveThroughMediaStore(context: Context, file: File): String? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrElse {
            return reason(context, it)
        } ?: return context.getString(R.string.diagnostics_downloads_failed, "Downloads did not accept the file")
        try {
            val target = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("the new file could not be opened")
            target.use { out -> file.inputStream().use { it.copyTo(out) } }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return null
        } catch (t: Throwable) {
            // A row left behind is a Downloads entry that opens to nothing.
            runCatching { resolver.delete(uri, null, null) }
            return reason(context, t)
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToPublicFolder(context: Context, file: File): String? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return context.getString(R.string.diagnostics_downloads_permission)
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PES18")
        if (!dir.isDirectory && !dir.mkdirs()) {
            return context.getString(R.string.diagnostics_downloads_failed, "the Downloads folder could not be created")
        }
        val part = File(dir, file.name + ".part")
        return try {
            file.inputStream().use { input -> part.outputStream().use { output -> input.copyTo(output) } }
            if (!part.renameTo(File(dir, file.name))) {
                part.delete()
                context.getString(R.string.diagnostics_downloads_failed, "the copy could not be renamed")
            } else {
                null
            }
        } catch (t: Throwable) {
            runCatching { part.delete() }
            reason(context, t)
        }
    }

    private fun reason(context: Context, error: Throwable): String {
        val text = error.message ?: error.toString()
        val full = text.contains("ENOSPC", ignoreCase = true) ||
            text.contains("No space", ignoreCase = true) ||
            text.contains("space left", ignoreCase = true)
        return if (full) {
            context.getString(R.string.diagnostics_no_space)
        } else {
            context.getString(R.string.diagnostics_downloads_failed, DiagnosticsReport.oneLine(text, 160))
        }
    }
}
