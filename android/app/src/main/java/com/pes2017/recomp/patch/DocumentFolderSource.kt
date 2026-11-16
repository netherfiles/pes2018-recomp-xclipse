package com.pes2017.recomp.patch

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * An extracted Content tree chosen through the Storage Access Framework.
 *
 * This is the fallback for a tree whose provider has no real filesystem path
 * (for example a cloud provider). Entries are read through ContentResolver;
 * a save CON therefore goes through the scanner's temporary copy because it
 * has no [java.io.File] to open directly.
 */
class DocumentFolderSource(
    private val context: Context,
    treeUri: Uri,
    override val label: String,
) : PatchSource {

    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw PatchSourceException(
            "cannot open folder: $treeUri",
            PatchSourceFailure.IO,
        )

    override val kind: PatchSourceKind = PatchSourceKind.FOLDER
    override val sourceBytes: Long = 0
    override val partCount: Int = 1

    override fun openSession(): PatchSourceSession = Session()

    override fun close() = Unit

    private inner class Session : PatchSourceSession {
        private val queue = ArrayDeque<Pair<DocumentFile, String>>()
        private val visited = HashSet<String>()
        private var current: DocumentFile? = null

        init {
            queue.add(root to "")
        }

        override fun next(): PatchSourceEntry? {
            while (queue.isNotEmpty()) {
                val (document, parentPath) = queue.removeFirst()
                val name = document.name ?: continue
                val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
                if (document.isDirectory) {
                    if (!visited.add(document.uri.toString())) continue
                    val children = try {
                        document.listFiles()
                    } catch (e: Throwable) {
                        throw PatchSourceException(
                            "cannot list ${document.uri}: ${e.message}",
                            PatchSourceFailure.IO,
                            e,
                        )
                    }
                    children.sortedBy { it.name?.lowercase() ?: "" }
                        .forEach { queue.addLast(it to path) }
                    continue
                }
                if (!document.isFile) continue
                current = document
                return PatchSourceEntry(
                    path = normalizePatchPath(path),
                    size = document.length(),
                    isDirectory = false,
                    file = null,
                )
            }
            return null
        }

        override fun open(): InputStream {
            val document = current ?: throw PatchSourceException(
                "open() called before next()",
                PatchSourceFailure.IO,
            )
            val stream = try {
                context.contentResolver.openInputStream(document.uri)
            } catch (e: Throwable) {
                throw PatchSourceException(
                    "cannot read ${document.uri}: ${e.message}",
                    PatchSourceFailure.IO,
                    e,
                )
            } ?: throw PatchSourceException(
                "cannot read ${document.uri}: provider returned no stream",
                PatchSourceFailure.IO,
            )
            return BufferedInputStream(stream, 64 * 1024)
        }

        override fun close() {
            current = null
            queue.clear()
        }
    }
}
