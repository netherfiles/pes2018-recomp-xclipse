package com.pes2017.recomp.patch

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream

enum class PatchSourceKind {
    FOLDER,
    ZIP,
    RAR,
    SEVEN_ZIP,
    UNKNOWN,
}

/** A source-level failure, before any STFS parsing has happened. */
enum class PatchSourceFailure {
    PASSWORD,
    MISSING_PART,
    UNSUPPORTED_ARCHIVE,
    NOT_SEEKABLE,
    IO,
}

class PatchSourceException(
    message: String,
    val failure: PatchSourceFailure = PatchSourceFailure.IO,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * One file as the source sees it. [path] is relative, slash-separated and
 * stripped of leading "./"; [file] is set only for a real extracted folder
 * where the scanner can open the file directly (and, for a save, avoid a
 * temporary copy).
 */
data class PatchSourceEntry(
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
    val file: File? = null,
)

/**
 * A sequential reader over the source. Call [next] to advance to the next
 * file, then [open] to read the current one. A stream that is not consumed
 * completely is skipped by the next [next] call.
 */
interface PatchSourceSession : Closeable {
    fun next(): PatchSourceEntry?
    fun open(): InputStream
}

/**
 * The input of a patch scan: an extracted Content tree, a ZIP/ZIP64, a RAR5
 * (single or multi-part) or a single 7z.
 *
 * The source is deliberately sequential: libarchive reads an archive from
 * front to back, and a folder scan needs no random access either. The scanner
 * reads only the first ~0x20000 bytes of each CON entry, so a 20 GB patch is
 * not extracted to classify it.
 */
interface PatchSource : Closeable {
    val label: String
    val kind: PatchSourceKind

    /**
     * Bytes the source occupies on disk, including any parts copied to the app
     * cache for a non-seekable SAF descriptor. Zero for a folder tree.
     */
    val sourceBytes: Long
    val partCount: Int

    fun openSession(): PatchSourceSession
}

/**
 * Normalizes a path from an archive or folder: backslashes become slashes,
 * empty and "." segments disappear, and a ".." segment is refused so no
 * archive can steer a write outside its target directory.
 */
fun normalizePatchPath(raw: String): String {
    val slashed = raw.replace('\\', '/')
    val parts = ArrayList<String>()
    for (part in slashed.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> throw PatchSourceException(
                "unsafe path in source: $raw",
                PatchSourceFailure.UNSUPPORTED_ARCHIVE,
            )
            else -> parts.add(part)
        }
    }
    return parts.joinToString("/")
}

/** True when [name] is one plain filename, not a path. */
fun isSafePatchFileName(name: String): Boolean {
    if (name.isEmpty() || name.length > 200) return false
    if (name == "." || name == "..") return false
    for (ch in name) {
        if (ch == '/' || ch == '\\' || ch == ':' || ch.code < 0x20) return false
    }
    return true
}
