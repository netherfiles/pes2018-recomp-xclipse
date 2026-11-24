package com.pes2017.recomp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What a control does when it is touched. */
enum class ControlKind {
    /** A round button that holds one of the pad's bits while it is down. */
    BUTTON,

    /** A round button that holds a trigger down (0..255). */
    TRIGGER,

    /** The left stick: a base with a knob that follows the finger. */
    STICK,

    /** The d-pad: four direction keys in a cross, read as one finger. */
    DPAD,

    /** The look area: a region where dragging turns the view. */
    LOOK,
}

/**
 * One thing on screen. Positions are fractions of the window so a layout
 * made on one phone still makes sense on another, and sizes are in
 * density-independent pixels so a button is the same size in millimetres on
 * every screen.
 */
data class ControlElement(
    val id: String,
    val kind: ControlKind,
    /** The pad bit (BUTTON), which trigger (TRIGGER: 0 left, 1 right), or the four direction bits (DPAD). */
    val value: Int,
    val label: String,
    var xFrac: Float,
    var yFrac: Float,
    var sizeDp: Float,
    var alpha: Float = 0.45f,
    var visible: Boolean = true,
    /** Tap to hold, tap again to let go. */
    var toggle: Boolean = false,
    /** What it does on the ball (or, for a stick, a short name), drawn on the control. */
    val sub: String = "",
    /** What it does without the ball -- defending -- drawn smaller beneath. */
    val sub2: String = "",
)

/**
 * The whole on-screen pad: what it is, where it came from, and how it is
 * kept. Saved as json in the app's own folder, so a layout survives an
 * update, and one bad file never stops the game starting -- it falls back to
 * the defaults instead.
 */
class ControlLayout private constructor(
    val elements: MutableList<ControlElement>,
    /** The window the defaults were laid out for, so a reset puts things back there. */
    private val widthDp: Float,
    private val heightDp: Float,
) {

    fun find(id: String): ControlElement? = elements.firstOrNull { it.id == id }

    fun resetAll() {
        val fresh = defaults(widthDp, heightDp)
        elements.clear()
        elements.addAll(fresh.elements)
    }

    fun reset(id: String) {
        val fresh = defaults(widthDp, heightDp).find(id) ?: return
        val index = elements.indexOfFirst { it.id == id }
        if (index >= 0) elements[index] = fresh
    }

    /**
     * Pushes [id] off any shown control it covers, keeping a visible gap where
     * the window allows, and leaves every other control where it is. Used after
     * loading a layout that predates the control, and for the defaults on a
     * window too small for them.
     */
    internal fun separateFromOthers(id: String) {
        val element = find(id) ?: return
        val (x, y) = placeClearOf(
            element.xFrac * widthDp, element.yFrac * heightDp, element.sizeDp,
            elements.filter { it !== element }, widthDp, heightDp,
        )
        element.xFrac = x / max(1f, widthDp)
        element.yFrac = y / max(1f, heightDp)
    }

    fun toJson(): String {
        val array = JSONArray()
        for (element in elements) {
            val item = JSONObject()
            item.put("id", element.id)
            item.put("x", element.xFrac.toDouble())
            item.put("y", element.yFrac.toDouble())
            item.put("size", element.sizeDp.toDouble())
            item.put("alpha", element.alpha.toDouble())
            item.put("visible", element.visible)
            item.put("toggle", element.toggle)
            array.put(item)
        }
        val root = JSONObject()
        root.put("version", FORMAT_VERSION)
        root.put("elements", array)
        return root.toString(2)
    }

    fun save(context: Context) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            // Written beside and moved into place: a layout being saved as
            // the process is killed must not leave a half-written file that
            // the next start cannot read.
            val temporary = File(context.filesDir, "$FILE_NAME.part")
            temporary.writeText(toJson())
            if (!temporary.renameTo(file)) {
                file.writeText(toJson())
                temporary.delete()
            }
        } catch (_: Throwable) {
            // A layout that cannot be saved is a small loss; crashing over
            // it would be a large one.
        }
    }

    companion object {
        private const val FILE_NAME = "controls.json"
        private const val FORMAT_VERSION = 6

        fun load(context: Context): ControlLayout {
            // The whole screen (the match is full screen, into the cutout), in
            // landscape whichever way the phone was held when asked.
            val metrics = context.resources.displayMetrics
            var widthPx = metrics.widthPixels
            var heightPx = metrics.heightPixels
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                try {
                    val bounds = context.getSystemService(android.view.WindowManager::class.java)
                        .maximumWindowMetrics.bounds
                    widthPx = bounds.width()
                    heightPx = bounds.height()
                } catch (_: Throwable) {
                }
            }
            val widthDp = max(widthPx, heightPx) / metrics.density
            val heightDp = min(widthPx, heightPx) / metrics.density
            val layout = defaults(widthDp, heightDp)
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) {
                // A fresh install gets the same separation as a migrated
                // layout: on a short window the default place of the d-pad
                // can sit on the right stick.
                layout.separateFromOthers(DPAD_ID)
                return layout
            }
            try {
                val root = JSONObject(file.readText())
                val version = root.optInt("version", 1)
                if (version < FORMAT_VERSION) {
                    // A much older layout (the shooter's look area, an older
                    // d-pad, the old places): start over. The d-pad is back,
                    // as one of the default controls.
                    try { file.delete() } catch (_: Throwable) {}
                    layout.separateFromOthers(DPAD_ID)
                    return layout
                }
                val array = root.optJSONArray("elements") ?: run {
                    layout.separateFromOthers(DPAD_ID)
                    return layout
                }
                var hasDpad = false
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    // Only what the app still knows about: an element dropped
                    // in a later version is ignored rather than resurrected,
                    // and a new one keeps its default.
                    val element = layout.find(item.optString("id")) ?: continue
                    if (element.kind == ControlKind.DPAD) hasDpad = true
                    element.xFrac = item.optDouble("x", element.xFrac.toDouble())
                        .toFloat().coerceIn(0.02f, 0.98f)
                    element.yFrac = item.optDouble("y", element.yFrac.toDouble())
                        .toFloat().coerceIn(0.02f, 0.98f)
                    element.sizeDp = item.optDouble("size", element.sizeDp.toDouble())
                        .toFloat().coerceIn(32f, 900f)
                    element.alpha = item.optDouble("alpha", element.alpha.toDouble())
                        .toFloat().coerceIn(0.05f, 1f)
                    element.visible = item.optBoolean("visible", element.visible)
                    element.toggle = item.optBoolean("toggle", element.toggle)
                    // The d-pad is one finger read as four directions, so a
                    // held direction would stick: a hand-edited or imported
                    // file cannot bring its toggle back.
                    if (element.kind == ControlKind.DPAD) element.toggle = false
                }
                // A layout saved before the d-pad came back: its default
                // place is used, then stepped off any control the player had
                // already arranged. The other controls never move.
                if (!hasDpad) layout.separateFromOthers(DPAD_ID)
            } catch (_: Throwable) {
                val fallback = defaults(widthDp, heightDp)
                fallback.separateFromOthers(DPAD_ID)
                return fallback
            }
            return layout
        }

        private const val DPAD_ID = "dpad"

        /** Gaps tried for a new place, in dp: a visible gap, the touch slack, then none. */
        private val SEPARATION_GAPS = floatArrayOf(14f, 6f, 0f)

        /**
         * Where a control of [sizeDp] at ([startX], [startY]) dp can sit without
         * covering any shown control in [others]. Its own place when that already
         * clears them; otherwise the nearest place clear of them, preferring a
         * visible gap over the 6 dp touch slack over merely not touching. When
         * the window is too small for even that, the place with the smallest
         * overlap wins, so the control sits as safely as it can. Only the moving
         * control changes.
         *
         * Pure arithmetic, no Android: the JVM test calls it.
         */
        internal fun placeClearOf(
            startX: Float, startY: Float, sizeDp: Float,
            others: List<ControlElement>, widthDp: Float, heightDp: Float,
        ): Pair<Float, Float> {
            var result = startX to startY
            for (gap in SEPARATION_GAPS) {
                result = clearPosition(startX, startY, sizeDp, others, widthDp, heightDp, gap)
                val (x, y) = result
                if (isClear(x, y, sizeDp, others, widthDp, heightDp, gap)) return result
            }
            return result
        }

        /**
         * The nearest place clear of every blocker at [gap], or the place with
         * the smallest overlap when no clear place exists. The scan at the end
         * is in a fixed order and ignores where the control started, so running
         * this again with the result leaves it exactly there.
         */
        private fun clearPosition(
            startX: Float, startY: Float, sizeDp: Float,
            others: List<ControlElement>, widthDp: Float, heightDp: Float, gap: Float,
        ): Pair<Float, Float> {
            val active = others.filter { it.visible && it.kind != ControlKind.LOOK }
            if (active.isEmpty()) return startX to startY
            // The drawn cross sits 5% inside its square, so its touch circle may
            // reach the window edge and still be fully visible.
            val limit = sizeDp * 0.5f
            val minX = limit
            val maxX = max(limit, widthDp - limit)
            val minY = limit
            val maxY = max(limit, heightDp - limit)

            fun needed(other: ControlElement) = (sizeDp + other.sizeDp) * 0.5f + gap
            fun otherX(other: ControlElement) = other.xFrac * widthDp
            fun otherY(other: ControlElement) = other.yFrac * heightDp

            var x = startX.coerceIn(minX, maxX)
            var y = startY.coerceIn(minY, maxY)

            fun clearAt(px: Float, py: Float) = active.all {
                hypot(px - otherX(it), py - otherY(it)) >= needed(it) - 0.01f
            }
            if (clearAt(x, y)) return x to y

            // Straight away from every overlap first: the usual case, and the
            // shortest move. Two controls at the same height can pinch a control
            // between them, so stopping after 24 passes is not the end.
            for (pass in 0 until 24) {
                var moved = false
                for (other in active) {
                    val dx = x - otherX(other)
                    val dy = y - otherY(other)
                    val distance = hypot(dx, dy)
                    val neededHere = needed(other)
                    if (distance >= neededHere) continue
                    // Exactly on top: push towards the middle of the screen.
                    val ux = if (distance > 0.5f) dx / distance else if (x > widthDp * 0.5f) -1f else 1f
                    val uy = if (distance > 0.5f) dy / distance else 0f
                    val push = neededHere - distance
                    val nx = (x + ux * push).coerceIn(minX, maxX)
                    val ny = (y + uy * push).coerceIn(minY, maxY)
                    if (nx != x || ny != y) moved = true
                    x = nx
                    y = ny
                }
                if (!moved) break
            }
            if (clearAt(x, y)) return x to y

            // Escapes that clear one blocker at a time: up then down (sliding
            // along a row of controls), then left and right.
            var bestX = x
            var bestY = y
            var bestMove = Float.MAX_VALUE
            var found = false
            fun consider(px: Float, py: Float) {
                if (!clearAt(px, py)) return
                val move = hypot(px - startX, py - startY)
                if (move < bestMove) {
                    bestMove = move
                    bestX = px
                    bestY = py
                    found = true
                }
            }
            for (other in active) {
                val ox = otherX(other)
                val oy = otherY(other)
                val neededHere = needed(other)
                val dx = x - ox
                val dy = y - oy
                if (abs(dx) < neededHere) {
                    val reach = sqrt(max(0f, neededHere * neededHere - dx * dx))
                    consider(x, (oy - reach).coerceIn(minY, maxY))
                    consider(x, (oy + reach).coerceIn(minY, maxY))
                }
                if (abs(dy) < neededHere) {
                    val reach = sqrt(max(0f, neededHere * neededHere - dy * dy))
                    consider((ox - reach).coerceIn(minX, maxX), y)
                    consider((ox + reach).coerceIn(minX, maxX), y)
                }
            }
            if (found) return bestX to bestY

            // No clear place (a 320 x 240 window): the place with the smallest
            // overlap, then the least total, scanning in a fixed order. The
            // result does not depend on where the control started, so a second
            // pass leaves it in place.
            bestX = minX
            bestY = minY
            var bestWorst = Float.MAX_VALUE
            var bestTotal = Float.MAX_VALUE
            var gx = minX
            while (gx <= maxX + 0.01f) {
                var gy = minY
                while (gy <= maxY + 0.01f) {
                    var worst = 0f
                    var total = 0f
                    for (other in active) {
                        val overlap = needed(other) - hypot(gx - otherX(other), gy - otherY(other))
                        if (overlap > worst) worst = overlap
                        if (overlap > 0f) total += overlap
                    }
                    if (worst < bestWorst - 0.001f ||
                        (abs(worst - bestWorst) <= 0.001f && total < bestTotal - 0.001f)
                    ) {
                        bestWorst = worst
                        bestTotal = total
                        bestX = gx
                        bestY = gy
                    }
                    gy += 2f
                }
                gx += 2f
            }
            return bestX to bestY
        }

        /** True when a control of [sizeDp] there clears every shown control in [others] by [gap] dp. */
        private fun isClear(
            x: Float, y: Float, sizeDp: Float,
            others: List<ControlElement>, widthDp: Float, heightDp: Float, gap: Float,
        ): Boolean {
            for (other in others) {
                if (!other.visible || other.kind == ControlKind.LOOK) continue
                val needed = (sizeDp + other.sizeDp) * 0.5f + gap
                if (hypot(x - other.xFrac * widthDp, y - other.yFrac * heightDp) < needed - 0.01f) {
                    return false
                }
            }
            return true
        }

        /**
         * Where everything starts: a football pad laid out for thumbs, the way
         * PES is played on a phone. The left thumb has the move stick, with the
         * d-pad to its right, level with it. The right thumb has everything
         * else in an arc around the four actions:
         *
         *  - Cross / A (pass) at the bottom, where the thumb rests, with
         *    Square / X (lob, cross) beside it: close enough to roll between,
         *    with a gap a thumb does not bridge by accident.
         *  - R1 / RB (sprint, "run") bigger and left of the actions, so the
         *    thumb can hold it and slide onto pass or shoot.
         *  - L1 / LB (change player) above the actions, the next thing the thumb
         *    reaches for when defending.
         *  - The skill stick above the sprint button; L2 / R2 small, under the
         *    HUD and the MENU button at the top; start and select top centre.
         *
         * Positions are in dp from the nearest edge, so the thumb distances are
         * the same on every phone and a wider screen only opens up the middle.
         * Nothing touches (with the touch slack) from a 592 x 336 dp window up.
         *
         * The game's default binding, for reference: Cross pass, Circle shoot,
         * Square lob / cross, Triangle through pass, R1 sprint, L1 change player.
         */
        fun defaults(widthDp: Float = 800f, heightDp: Float = 360f): ControlLayout {
            val w = max(widthDp, 320f)
            val h = max(heightDp, 240f)

            /** A control at x, y dp: positive from the left / top, negative from the right / bottom. */
            fun place(
                id: String, kind: ControlKind, value: Int, label: String,
                x: Float, y: Float, size: Float, alpha: Float = 0.45f, visible: Boolean = true,
            ) = ControlElement(
                id, kind, value, label,
                (if (x >= 0f) x else w + x) / w, (if (y >= 0f) y else h + y) / h,
                size, alpha, visible,
            )

            val centre = w / 2f
            return ControlLayout(
                mutableListOf(
                    // Left thumb: movement.
                    place("stick", ControlKind.STICK, 0, "LS", 95f, -95f, 150f, 0.40f),

                    // The d-pad, in the gap between the move stick and the
                    // actions: out of both sticks' way from 592 dp up.
                    place(
                        DPAD_ID, ControlKind.DPAD,
                        NativeBridge.UP or NativeBridge.DOWN or NativeBridge.LEFT or NativeBridge.RIGHT,
                        "D-PAD", 245f, -85f, 120f, 0.40f,
                    ),

                    // Right thumb: the four actions as the pad's diamond.
                    place("a", ControlKind.BUTTON, NativeBridge.A, "A", -110f, -52f, 68f),
                    place("b", ControlKind.BUTTON, NativeBridge.B, "B", -52f, -110f, 68f),
                    place("x", ControlKind.BUTTON, NativeBridge.X, "X", -168f, -110f, 68f),
                    place("y", ControlKind.BUTTON, NativeBridge.Y, "Y", -110f, -168f, 68f),

                    // The shoulders where a pad has them: L2 / L1 along the top left,
                    // R1 / R2 along the top right, triggers outermost.
                    place("lt", ControlKind.TRIGGER, 0, "LT", 44f, 76f, 56f),
                    place("lb", ControlKind.BUTTON, NativeBridge.LB, "LB", 110f, 76f, 56f),
                    place("rb", ControlKind.BUTTON, NativeBridge.RB, "RB", -110f, 76f, 56f),
                    place("rt", ControlKind.TRIGGER, 1, "RT", -44f, 76f, 56f),

                    // The skill stick, left of the actions.
                    place("right_stick", ControlKind.STICK, 1, "RS", -250f, -170f, 100f, 0.40f),

                    // Stick clicks: kept for the editor, hidden until wanted.
                    place("l3", ControlKind.BUTTON, NativeBridge.L3, "L3", 200f, -40f, 52f, visible = false),
                    place("r3", ControlKind.BUTTON, NativeBridge.R3, "R3", -320f, -45f, 52f, visible = false),

                    // Menus have to be found at a glance in a football game.
                    place("start", ControlKind.BUTTON, NativeBridge.START, "START", centre + 60f, 37f, 54f, 0.75f),
                    place("back", ControlKind.BUTTON, NativeBridge.BACK, "SELECT", centre - 40f, 37f, 54f, 0.75f),
                ),
                widthDp, heightDp,
            )
        }

        /**
         * How small and how large a control of [kind] may be, in dp. The look
         * area is a whole region; the d-pad's keys stop being thumb-sized
         * outside 96..200, so the editor's slider stops there.
         */
        fun sizeRange(kind: ControlKind): Pair<Float, Float> = when (kind) {
            ControlKind.LOOK -> 120f to 900f
            ControlKind.DPAD -> 96f to 200f
            ControlKind.BUTTON, ControlKind.TRIGGER, ControlKind.STICK -> 36f to 200f
        }

        /** The name printed on a control: its Xbox name, or the PlayStation one. */
        fun labelFor(element: ControlElement, playStation: Boolean): String {
            if (!playStation) return element.label
            return when (element.id) {
                "a" -> "CROSS"
                "b" -> "CIRCLE"
                "x" -> "SQUARE"
                "y" -> "TRIANGLE"
                "lb" -> "L1"
                "rb" -> "R1"
                "lt" -> "L2"
                "rt" -> "R2"
                "stick" -> "L"
                "right_stick" -> "R"
                "start" -> "START"
                "back" -> "SELECT"
                else -> element.label
            }
        }
    }
}
