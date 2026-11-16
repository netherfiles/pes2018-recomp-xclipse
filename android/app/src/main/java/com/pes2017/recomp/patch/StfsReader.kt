package com.pes2017.recomp.patch

import java.io.EOFException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Bounded random access to a package: the whole file or a scanned prefix. */
internal interface StfsReader {
    val size: Long

    /** Reads exactly [length] bytes at [offset] or throws [StfsException]. */
    fun read(offset: Long, length: Int): ByteArray
}

internal class ByteArrayStfsReader(private val bytes: ByteArray) : StfsReader {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset > Int.MAX_VALUE.toLong() ||
            length.toLong() > Int.MAX_VALUE.toLong() - offset
        ) {
            throw StfsException(
                "short read at 0x${offset.toString(16)} ($length bytes)",
                StfsErrorKind.TRUNCATED,
            )
        }
        val start = offset.toInt()
        val end = start + length
        if (end > bytes.size) {
            throw StfsException(
                "short read at 0x${offset.toString(16)} ($length bytes requested, " +
                    "${bytes.size - start} available)",
                StfsErrorKind.TRUNCATED,
            )
        }
        return bytes.copyOfRange(start, end)
    }
}

internal class FileStfsReader(private val file: RandomAccessFile) : StfsReader {
    override val size: Long = file.length()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > size) {
            throw StfsException(
                "short read at 0x${offset.toString(16)} ($length bytes requested, " +
                    "0x$size package size)",
                StfsErrorKind.TRUNCATED,
            )
        }
        val out = ByteArray(length)
        try {
            file.seek(offset)
            file.readFully(out)
        } catch (e: EOFException) {
            throw StfsException(
                "short read at 0x${offset.toString(16)} ($length bytes requested)",
                StfsErrorKind.TRUNCATED,
                e,
            )
        }
        return out
    }
}

internal fun sha1(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-1").digest(bytes)

internal fun sha1Hex(bytes: ByteArray): String = bytes.toHex()

internal fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xFF
        chars[i * 2] = HEX[value ushr 4]
        chars[i * 2 + 1] = HEX[value and 0x0F]
    }
    return String(chars)
}

private val HEX = "0123456789abcdef".toCharArray()

internal fun be16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

internal fun be16s(bytes: ByteArray, offset: Int): Int =
    be16(bytes, offset).let { if (it >= 0x8000) it - 0x10000 else it }

internal fun be24(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        (bytes[offset + 2].toInt() and 0xFF)

internal fun be32(bytes: ByteArray, offset: Int): Long =
    ((bytes[offset].toLong() and 0xFF) shl 24) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
        (bytes[offset + 3].toLong() and 0xFF)

internal fun le16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

internal fun le24(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16)

internal fun le32(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)
