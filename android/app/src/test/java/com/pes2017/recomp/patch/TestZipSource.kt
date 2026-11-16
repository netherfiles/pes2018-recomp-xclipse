package com.pes2017.recomp.patch

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * JVM-only [PatchSource] over a synthetic ZIP. The production archive source
 * needs libarchive's native library and therefore cannot run in a unit test;
 * this stand-in exercises the scanner's source-independent path.
 */
internal class TestZipSource(
    private val file: File,
    override val label: String = file.name,
) : PatchSource {

    private val zip = ZipFile(file)

    override val kind: PatchSourceKind = PatchSourceKind.ZIP
    override val sourceBytes: Long = file.length()
    override val partCount: Int = 1

    override fun openSession(): PatchSourceSession = object : PatchSourceSession {
        private val entries = zip.entries()
        private var current: java.util.zip.ZipEntry? = null

        override fun next(): PatchSourceEntry? {
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                current = entry
                return PatchSourceEntry(
                    path = normalizePatchPath(entry.name),
                    size = entry.size,
                    isDirectory = false,
                    file = null,
                )
            }
            return null
        }

        override fun open(): InputStream = zip.getInputStream(
            current ?: error("open() before next()"),
        )

        override fun close() {
            current = null
        }
    }

    override fun close() {
        zip.close()
    }
}
