package com.pes2017.recomp.patch

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * An extracted Content tree on a real filesystem.
 *
 * Entries are yielded deterministically (sorted at every directory level) so a
 * scan of the same folder always produces the same plan. Each entry carries
 * its [File], which lets the scanner hash a save without copying it first.
 */
class FolderSource(private val root: File) : PatchSource {

    init {
        if (!root.isDirectory) {
            throw PatchSourceException(
                "not a folder: ${root.path}",
                PatchSourceFailure.IO,
            )
        }
    }

    override val label: String = root.name.ifEmpty { "PATCH" }
    override val kind: PatchSourceKind = PatchSourceKind.FOLDER
    override val sourceBytes: Long = 0
    override val partCount: Int = 1

    override fun openSession(): PatchSourceSession = Session()

    override fun close() = Unit

    private inner class Session : PatchSourceSession {
        private val queue = ArrayDeque<File>()
        private val visited = HashSet<String>()
        private var current: File? = null

        init {
            queue.add(root)
        }

        override fun next(): PatchSourceEntry? {
            while (queue.isNotEmpty()) {
                val file = queue.removeFirst()
                if (file.isDirectory) {
                    val canonical = runCatching { file.canonicalPath }.getOrDefault(file.path)
                    if (!visited.add(canonical)) continue
                    val children = file.listFiles() ?: continue
                    children.sortedBy { it.name.lowercase() }.forEach { queue.addLast(it) }
                    continue
                }
                if (!file.isFile) continue
                val relative = runCatching { file.relativeTo(root).path }.getOrNull() ?: continue
                val path = normalizePatchPath(relative)
                if (path.isEmpty()) continue
                current = file
                return PatchSourceEntry(
                    path = path,
                    size = file.length(),
                    isDirectory = false,
                    file = file,
                )
            }
            return null
        }

        override fun open(): InputStream {
            val file = current ?: throw PatchSourceException(
                "open() called before next()",
                PatchSourceFailure.IO,
            )
            return BufferedInputStream(FileInputStream(file), BUFFER)
        }

        override fun close() {
            current = null
            queue.clear()
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
