package com.pes2017.recomp.patch

import java.util.Locale

/**
 * One formatting of byte counts for every screen that shows patch storage.
 *
 * Binary units (1024-based) with fixed IEC labels, because a patch that is
 * 20,596,460,266 bytes is "19.18 GiB" and must read the same on the PATCHES
 * card, the restore row and the Settings storage page. The old screens each
 * divided by 1,000,000,000 and printed "GB"; this keeps one answer.
 *
 * [Locale.US] is deliberate: a decimal comma would make "19,18 GiB" ambiguous
 * beside thousands separators.
 */
object PatchFormat {

    /** "0 B", "512 B", "1.5 KiB", "48.2 MiB", "19.18 GiB", "2.00 TiB". */
    fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L)
        return when {
            value < KIB -> "$value B"
            value < MIB -> decimal(value, KIB.toDouble(), 1, "KiB")
            value < GIB -> decimal(value, MIB.toDouble(), 1, "MiB")
            value < TIB -> decimal(value, GIB.toDouble(), 2, "GiB")
            else -> decimal(value, TIB.toDouble(), 2, "TiB")
        }
    }

    /** A plain count with thousands separators: "2,900", "20,596,460,266". */
    fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

    private fun decimal(value: Long, unit: Double, places: Int, label: String): String =
        String.format(Locale.US, "%.${places}f %s", value / unit, label)

    private const val KIB = 1024L
    private const val MIB = KIB * 1024L
    private const val GIB = MIB * 1024L
    private const val TIB = GIB * 1024L
}
