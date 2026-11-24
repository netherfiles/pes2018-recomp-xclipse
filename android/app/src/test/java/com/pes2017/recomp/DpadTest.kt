package com.pes2017.recomp

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DPAD = "dpad"

/** The d-pad's zones and sizes, where its default sits, and its separation, without Android. */
class DpadTest {

    private val half = 60f   // a 120 dp cross at density 1

    @Test
    fun theMiddleOfTheCrossPressesNothing() {
        assertEquals(0, dpadBitFor(0f, 0f, half))
        // The dead zone is 0.14 of the whole size (0.28 of half): 16.8 here.
        assertEquals(0, dpadBitFor(5f, 5f, half))     // well inside
        assertEquals(0, dpadBitFor(15f, 5f, half))    // just inside: 15.8
        // and just outside it
        assertEquals(NativeBridge.RIGHT, dpadBitFor(17f, 0f, half))
    }

    @Test
    fun eachQuarterGivesItsDirection() {
        assertEquals(NativeBridge.UP, dpadBitFor(0f, -20f, half))
        assertEquals(NativeBridge.DOWN, dpadBitFor(0f, 20f, half))
        assertEquals(NativeBridge.LEFT, dpadBitFor(-20f, 0f, half))
        assertEquals(NativeBridge.RIGHT, dpadBitFor(20f, 0f, half))
    }

    @Test
    fun diagonalsFollowTheLongerAxis() {
        assertEquals(NativeBridge.RIGHT, dpadBitFor(30f, -10f, half))
        assertEquals(NativeBridge.UP, dpadBitFor(10f, -30f, half))
        assertEquals(NativeBridge.LEFT, dpadBitFor(-30f, 10f, half))
        assertEquals(NativeBridge.DOWN, dpadBitFor(-10f, 30f, half))
        // a perfect diagonal is not a diagonal direction: sideways wins
        assertEquals(NativeBridge.RIGHT, dpadBitFor(20f, -20f, half))
    }

    /**
     * A key's nearest point to the middle is its inner edge, at
     * 0.5 - (EDGE + KEY) = 0.17 of the size, which is outside the 0.14 dead
     * zone. A tap on any drawn key, even on that edge, gives a direction.
     */
    @Test
    fun everyDrawnKeyLiesOutsideTheDeadZone() {
        val size = 120f
        val inner = (0.5f - (DpadGeometry.EDGE + DpadGeometry.KEY)) * size
        assertTrue(
            "dead radius ${DpadGeometry.DEAD * size} reaches the keys' inner edge $inner",
            DpadGeometry.DEAD * size < inner,
        )
        assertEquals(NativeBridge.UP, dpadBitFor(0f, -inner, size / 2f))
        assertEquals(NativeBridge.DOWN, dpadBitFor(0f, inner, size / 2f))
        assertEquals(NativeBridge.LEFT, dpadBitFor(-inner, 0f, size / 2f))
        assertEquals(NativeBridge.RIGHT, dpadBitFor(inner, 0f, size / 2f))
    }

    @Test
    fun theKeysHaveAGapBetweenThem() {
        // The up/down column ends at EDGE + KEY; the left/right row starts at
        // OFFSET, leaving the 0.03 of the size gap the pressed key needs.
        val gap = DpadGeometry.OFFSET - (DpadGeometry.EDGE + DpadGeometry.KEY)
        assertTrue("keys touch: gap $gap", gap > 0.01f)
    }

    @Test
    fun theSizeRangeStopsTheDpadAtThumbSize() {
        assertEquals(120f to 900f, ControlLayout.sizeRange(ControlKind.LOOK))
        assertEquals(96f to 200f, ControlLayout.sizeRange(ControlKind.DPAD))
        assertEquals(36f to 200f, ControlLayout.sizeRange(ControlKind.BUTTON))
        assertEquals(36f to 200f, ControlLayout.sizeRange(ControlKind.TRIGGER))
        assertEquals(36f to 200f, ControlLayout.sizeRange(ControlKind.STICK))
    }

    @Test
    fun theDefaultDpadIsShownWithAllFourBits() {
        val layout = ControlLayout.defaults()
        val dpad = layout.find(DPAD)
        assertNotNull(dpad)
        dpad!!
        assertEquals(ControlKind.DPAD, dpad.kind)
        assertTrue(dpad.visible)
        assertEquals(120f, dpad.sizeDp, 0.01f)
        assertEquals(
            NativeBridge.UP or NativeBridge.DOWN or NativeBridge.LEFT or NativeBridge.RIGHT,
            dpad.value,
        )
    }

    /**
     * The default place sits between the move stick and the actions. On the
     * narrowest window in use the two sticks just clear (2 dp), the move
     * stick clears by 15 dp; nothing may be drawn on top of another control.
     */
    @Test
    fun theDefaultDpadDoesNotSitOnAnotherControl() {
        for (width in floatArrayOf(568f, 592f, 640f, 720f, 800f, 872f)) {
            for (height in floatArrayOf(320f, 336f, 360f, 411f)) {
                val layout = ControlLayout.defaults(width, height)
                val dpad = layout.find(DPAD)!!
                for (other in layout.elements) {
                    if (other === dpad || !other.visible) continue
                    val dx = (dpad.xFrac - other.xFrac) * width
                    val dy = (dpad.yFrac - other.yFrac) * height
                    val distance = hypot(dx, dy)
                    val needed = (dpad.sizeDp + other.sizeDp) * 0.5f
                    assertTrue(
                        "dpad sits on ${other.id} at ${width}x$height: $distance < $needed",
                        distance >= needed,
                    )
                }
            }
        }
    }

    /**
     * `load` separates the d-pad on a fresh install too. With the 6 dp touch
     * slack counted, 568 x 320 is the smallest window tested that still fits
     * a 120 dp cross; 480 x 320 is below it (the next test).
     */
    @Test
    fun aFreshInstallHasTheDpadClearOnAWindowItFits() {
        for (width in floatArrayOf(568f, 592f, 640f, 720f, 800f, 872f)) {
            for (height in floatArrayOf(320f, 336f, 360f, 411f)) {
                val layout = ControlLayout.defaults(width, height)
                layout.separateFromOthers(DPAD)
                assertDpadClears(layout, width, height, gap = 6f)
            }
        }
    }

    /**
     * A 480 x 320 window is too small for the 120 dp cross plus the 6 dp touch
     * slack: no place clears every control that far (or even with an 8 dp
     * margin). Without the slack it just fits, so the separation still leaves
     * no real overlap. The documented minimum for the slack is 568 x 320.
     */
    @Test
    fun a480x320WindowStillClearsTheDpadWithoutTheSlack() {
        val width = 480f
        val height = 320f
        val layout = ControlLayout.defaults(width, height)
        layout.separateFromOthers(DPAD)
        assertDpadClears(layout, width, height, gap = 0f)
    }

    /**
     * 320 x 240 is below even the no-slack minimum: a 120 dp cross cannot
     * clear the move stick and the four actions there. The smallest windows
     * that do are 480 x 320 without the touch slack and 568 x 320 with it.
     * Here the cross takes the place with the smallest worst overlap, and
     * stays there on a second run.
     */
    @Test
    fun a320x240WindowTakesTheSafestPlaceItCan() {
        val width = 320f
        val height = 240f
        val layout = ControlLayout.defaults(width, height)
        val before = worstOverlap(layout, width, height)
        layout.separateFromOthers(DPAD)
        val after = worstOverlap(layout, width, height)
        assertTrue("separation made the worst overlap worse: $before -> $after", after <= before + 0.01f)
        val dpad = layout.find(DPAD)!!
        assertTrue("dpad left the window", dpad.xFrac in 0f..1f && dpad.yFrac in 0f..1f)

        val x = dpad.xFrac
        val y = dpad.yFrac
        layout.separateFromOthers(DPAD)
        assertEquals("dpad moved on a second pass", x, dpad.xFrac, 0.0001f)
        assertEquals("dpad moved on a second pass", y, dpad.yFrac, 0.0001f)
    }

    /**
     * Two of the player's controls on the same line pinch the d-pad: pushing
     * it straight off one pushes it into the other, so the radial passes
     * stall. The vertical escape must still find a clear place, the player's
     * controls must not move, and a second pass must not drift.
     */
    @Test
    fun theDpadEscapesAUserLayoutThatPinchesIt() {
        val width = 640f
        val height = 360f
        val layout = ControlLayout.defaults(width, height)
        val rightStick = layout.find("right_stick")!!
        rightStick.xFrac = 300f / width
        rightStick.yFrac = (height - 85f) / height

        layout.separateFromOthers(DPAD)

        assertDpadClears(layout, width, height, gap = 6f)
        assertEquals(300f / width, rightStick.xFrac, 0.0001f)
        assertEquals((height - 85f) / height, rightStick.yFrac, 0.0001f)
        assertEquals(95f / width, layout.find("stick")!!.xFrac, 0.0001f)

        val dpad = layout.find(DPAD)!!
        val x = dpad.xFrac
        val y = dpad.yFrac
        layout.separateFromOthers(DPAD)
        assertEquals("dpad moved on a second pass", x, dpad.xFrac, 0.0001f)
        assertEquals("dpad moved on a second pass", y, dpad.yFrac, 0.0001f)
    }

    /**
     * The d-pad clears every shown control by [gap] dp. The half dp is the
     * scan's grid: at 480 x 320 the clear place is exactly tangent, and a
     * 2 dp grid can sit half a dp inside it.
     */
    private fun assertDpadClears(layout: ControlLayout, width: Float, height: Float, gap: Float) {
        val dpad = layout.find(DPAD) ?: error("no d-pad in the layout")
        for (other in layout.elements) {
            if (other === dpad || !other.visible || other.kind == ControlKind.LOOK) continue
            val distance = hypot(
                (dpad.xFrac - other.xFrac) * width,
                (dpad.yFrac - other.yFrac) * height,
            )
            val needed = (dpad.sizeDp + other.sizeDp) * 0.5f + gap
            assertTrue(
                "dpad sits on ${other.id} at ${width}x$height: $distance < $needed (gap $gap)",
                distance >= needed - 0.51f,
            )
        }
    }

    /** The deepest single overlap the d-pad has with any shown control, in dp. */
    private fun worstOverlap(layout: ControlLayout, width: Float, height: Float): Float {
        val dpad = layout.find(DPAD) ?: error("no d-pad in the layout")
        var worst = 0f
        for (other in layout.elements) {
            if (other === dpad || !other.visible || other.kind == ControlKind.LOOK) continue
            val distance = hypot(
                (dpad.xFrac - other.xFrac) * width,
                (dpad.yFrac - other.yFrac) * height,
            )
            val overlap = (dpad.sizeDp + other.sizeDp) * 0.5f - distance
            if (overlap > worst) worst = overlap
        }
        return worst
    }
}
