package com.pes2017.recomp

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Where the d-pad's four keys sit, as fractions of the whole cross's size.
 * Shared by the drawing and the tests, so the hole at the middle, the gaps
 * between keys, and the painted keys cannot drift apart.
 */
object DpadGeometry {
    /** A key's side, as a fraction of the cross's size. */
    const val KEY = 0.28f

    /** A key's corner radius, as a fraction of [KEY]. */
    const val CORNER = 0.18f

    /** The gap between the cross's square and its keys, as a fraction of its size. */
    const val EDGE = 0.05f

    /** Where the up/down keys' column and the left/right keys' row start. */
    const val OFFSET = 0.36f

    /**
     * The dead zone's radius, as a fraction of the whole cross's size (0.28
     * of its half). It is smaller than the nearest edge of every key, so a
     * finger on a drawn key always gives a direction.
     */
    const val DEAD = 0.14f
}

/**
 * Which way the d-pad's one finger is pointing.
 *
 * [dx] and [dy] are the finger's offset from the middle of the cross in
 * pixels, and [half] is half the cross's size. A finger in the small circle
 * at the middle (radius [DpadGeometry.DEAD] of the whole size, 0.28 of
 * [half]) presses nothing: the finger has to leave the resting spot first.
 * Everywhere else the axis it has moved furthest along wins, so the four
 * keys are quarters rather than eight directions.
 *
 * Pure arithmetic, no Android: the JVM test uses it directly.
 */
fun dpadBitFor(dx: Float, dy: Float, half: Float): Int {
    if (hypot(dx, dy) < 2f * DpadGeometry.DEAD * half) return 0
    return if (abs(dx) >= abs(dy)) {
        if (dx < 0f) NativeBridge.LEFT else NativeBridge.RIGHT
    } else {
        // The screen's y points down; the pad's up is towards the top.
        if (dy < 0f) NativeBridge.UP else NativeBridge.DOWN
    }
}
