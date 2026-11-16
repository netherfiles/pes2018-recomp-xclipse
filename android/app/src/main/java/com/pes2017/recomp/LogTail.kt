package com.pes2017.recomp

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * The end of a possibly huge log, copied into the ZIP without ever holding the
 * file in memory.
 *
 * A 2 MB game.log has one fault in it and a player's phone has 6 GB of free
 * space; a report that reads the whole log into a String first is how a
 * diagnostic build turns an out-of-memory kill into "the export failed". So the
 * copy is a seek to the part that matters, then 64 KB at a time through the
 * same scrubber the rest of the zip uses.
 *
 * The boundary rules are what make the tail readable rather than truncated
 * mid-line: the first partial line after the seek point is dropped, and the
 * last line is flushed even though it has no newline yet.
 */
object LogTail {

    /** Read size. Also the size of the buffer the zip's own stream uses. */
    const val CHUNK = 64 * 1024

    /**
     * A line longer than this is cut and marked. Log lines are short; a corrupt
     * or hand-made file must not be able to make one line as large as the log.
     */
    private const val MAX_LINE = 8 * 1024

    /** `\n` as an int: compared against a byte and used as `OutputStream.write`. */
    private const val NEWLINE = 0x0A

    /**
     * Copies the last [maxBytes] of [file] into [out], optionally scrubbing it
     * line by line. Returns the bytes actually written, which is what the
     * manifest records (the entry's size, not the source's).
     */
    fun copyTail(
        file: File,
        out: OutputStream,
        maxBytes: Int,
        scrub: ((String) -> String)? = null,
    ): Long {
        if (maxBytes <= 0 || !file.isFile) return 0L
        val writer = LineWriter(out, scrub)
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val seek = (length - maxBytes).coerceAtLeast(0L)
            var start = seek
            if (seek > 0L) {
                // The first thing a tail sees is usually half a line; drop it.
                // The scan is buffered: a file whose tail is one enormous line
                // must not cost a native read per byte.
                input.seek(seek)
                val scan = ByteArray(8 * 1024)
                while (true) {
                    val base = input.filePointer
                    val n = input.read(scan)
                    if (n <= 0) break
                    var at = 0
                    while (at < n && scan[at].toInt() != NEWLINE) at++
                    if (at < n) {
                        start = base + at + 1
                        break
                    }
                }
                // No newline in the whole tail: one enormous line, and copying
                // from the cut is better than copying nothing.
            }
            input.seek(start)
            val buffer = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                writer.feed(buffer, n)
            }
        }
        writer.finish()
        return writer.written
    }

    /** The same line-by-line copy for a process's output (`logcat -d`). */
    fun copyStream(
        input: InputStream,
        out: OutputStream,
        scrub: ((String) -> String)? = null,
    ): Long {
        val writer = LineWriter(out, scrub)
        val buffer = ByteArray(CHUNK)
        while (true) {
            val n = try {
                input.read(buffer)
            } catch (_: Throwable) {
                -1
            }
            if (n <= 0) break
            writer.feed(buffer, n)
        }
        writer.finish()
        return writer.written
    }

    /** The tail as text, for the report and the summary. Bounded by [maxBytes]. */
    fun tailText(file: File, maxBytes: Int): String {
        if (maxBytes <= 0 || !file.isFile) return ""
        val buffer = ByteArrayOutputStream(minOf(maxBytes, CHUNK))
        return try {
            copyTail(file, buffer, maxBytes)
            String(buffer.toByteArray(), Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    /** The last [maxLines] non-blank lines of the tail. */
    fun tailLines(file: File, maxBytes: Int, maxLines: Int): List<String> {
        if (maxLines <= 0) return emptyList()
        val text = tailText(file, maxBytes)
        if (text.isEmpty()) return emptyList()
        val lines = text.lines().filter { it.isNotBlank() }
        return if (lines.size <= maxLines) lines else lines.takeLast(maxLines)
    }

    /**
     * Line assembly for a byte stream that may split a character across reads:
     * complete lines are decoded and scrubbed, the rest waits for the next
     * chunk. A single overlong line is emitted once, cut, and its remainder is
     * discarded rather than buffered.
     */
    private class LineWriter(
        private val out: OutputStream,
        private val scrub: ((String) -> String)?,
    ) {
        private var pending = ByteArrayOutputStream(1024)
        private var discarding = false

        var written = 0L
            private set

        fun feed(buffer: ByteArray, length: Int) {
            var from = 0
            for (at in 0 until length) {
                // NEWLINE is an Int so it also serves OutputStream.write; a byte
                // from the array is widened to compare with it.
                if (buffer[at].toInt() != NEWLINE) continue
                if (!discarding) {
                    pending.write(buffer, from, at - from)
                    emit(pending.toByteArray(), cut = false)
                }
                pending = ByteArrayOutputStream(256)
                discarding = false
                from = at + 1
            }
            if (from >= length || discarding) return
            pending.write(buffer, from, length - from)
            if (pending.size() >= MAX_LINE) {
                emit(pending.toByteArray().copyOf(MAX_LINE), cut = true)
                pending = ByteArrayOutputStream(256)
                discarding = true
            }
        }

        fun finish() {
            if (!discarding && pending.size() > 0) emit(pending.toByteArray(), cut = false)
            pending = ByteArrayOutputStream(0)
            runCatching { out.flush() }
        }

        private fun emit(bytes: ByteArray, cut: Boolean) {
            var text = String(bytes, Charsets.UTF_8).trimEnd('\r')
            if (cut) text = "$text …[line cut]"
            val body = if (scrub == null) text else scrub(text)
            val data = body.toByteArray(Charsets.UTF_8)
            out.write(data)
            out.write(NEWLINE)
            written += data.size + 1
        }
    }
}
