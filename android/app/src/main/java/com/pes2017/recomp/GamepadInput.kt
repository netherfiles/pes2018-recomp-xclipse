package com.pes2017.recomp

import android.content.Context
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * A physical controller, turned into the same state the touch pad produces.
 *
 * Android reports pads through two channels -- keys for the buttons, motion
 * events for the sticks and triggers -- and a device can appear on either
 * before the other. Both are funnelled here so the runtime sees one pad per
 * player, whichever way its parts arrived.
 *
 * Numbering: while an external pad (Bluetooth, USB) is present it takes the
 * first slot, and the pads a handheld reports for its own controls are let go.
 * Left alone, those take player 1 and push the pad in the player's hands down
 * to player 2 -- and only player 1 is signed in here, which is enough for the
 * game to refuse a save. A released device gets no slot and its events are
 * consumed, so its keys cannot walk the Activity either. With no external pad
 * present everything is numbered as it always was, and a slot already handed
 * out is never moved while its device is there.
 *
 * The whole thing can be switched off (Prefs.gamepadEnabled). That is not a
 * nicety: plenty of phones enumerate something as a gamepad that is not one,
 * and a stick reading half-left that nobody is touching makes the game
 * unplayable in a way nothing else explains.
 */
class GamepadInput(private val context: Context) {

    private var enabled = true

    /** First player slot a physical pad takes; 1 leaves slot 0 to the touch controls. */
    var firstSlot = 0
        set(value) {
            val v = value.coerceIn(0, 1)
            if (field == v) return
            field = v
            // Every pad is let go and numbered again from the new base.
            for (slot in 0 until 4) {
                if (slots.containsValue(slot)) NativeBridge.nativePadConnected(slot, false)
                buttons[slot] = 0; axes[slot].fill(0f); triggers[slot].fill(0)
                push(slot)
            }
            slots.clear()
            if (enabled) refreshDevices()
        }

    /** Android device id -> player slot, 0..3. */
    private val slots = HashMap<Int, Int>()
    private val buttons = IntArray(4)
    private val axes = Array(4) { FloatArray(4) }       // lx, ly, rx, ry
    private val triggers = Array(4) { IntArray(2) }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        NativeBridge.nativePadEnabled(value)
        if (!value) {
            // Everything let go, or whatever was held stays held forever.
            for (slot in 0 until 4) {
                buttons[slot] = 0
                axes[slot].fill(0f)
                triggers[slot][0] = 0
                triggers[slot][1] = 0
                push(slot)
            }
        } else {
            refreshDevices()
        }
    }

    fun isEnabled(): Boolean = enabled

    /**
     * Called at start-up, on resume, and whenever a device is added, removed or
     * changed (the InputDeviceListener in GameActivity).
     */
    fun refreshDevices() {
        val pads = ArrayList<InputDevice>()
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (isPad(device)) pads.add(device)
        }
        // isExternal is Android 10; older releases keep the old numbering.
        val external = Build.VERSION.SDK_INT >= 29 && pads.any { isExternal(it) }
        val keep = if (external) pads.filter { isExternal(it) } else pads
        val keepIds = keep.map { it.id }

        // Let go of everything that is no longer a candidate first, so the slot
        // it held is free before anything is numbered. A slot already handed
        // out is never moved: the device that holds it loses it or keeps it.
        for (id in slots.keys.toList()) {
            if (id in keepIds) continue
            val why = if (InputDevice.getDevice(id) == null) "gone" else "internal, external pad present"
            releaseSlot(id, why)
        }

        // Then number only the devices that have no slot yet.
        for (device in keep) {
            if (slots.containsKey(device.id)) continue
            val slot = (firstSlot until 4).firstOrNull { free -> !slots.containsValue(free) } ?: continue
            slots[device.id] = slot
            if (enabled) NativeBridge.nativePadConnected(slot, true)
            notePad(device.id, slot, null)
        }
    }

    fun hasAnyPad(): Boolean = slots.isNotEmpty()

    private fun isPad(device: InputDevice): Boolean {
        if (device.isVirtual) return false
        val sources = device.sources
        val joystick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        val gamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        return joystick || gamepad
    }

    private fun isExternal(device: InputDevice): Boolean =
        Build.VERSION.SDK_INT >= 29 && device.isExternal

    /**
     * Whether a device may hold a slot at all: an internal pad only while no
     * external pad is present.
     */
    private fun isCandidate(device: InputDevice?, externalPresent: Boolean): Boolean =
        device != null && (!externalPresent || isExternal(device))

    private fun externalPadPresent(): Boolean =
        Build.VERSION.SDK_INT >= 29 && InputDevice.getDeviceIds().any { id ->
            InputDevice.getDevice(id)?.let { isPad(it) && isExternal(it) } == true
        }

    /** Lets go of one device's slot, along with whatever it was holding. */
    private fun releaseSlot(id: Int, why: String) {
        val slot = slots.remove(id) ?: return
        buttons[slot] = 0
        axes[slot].fill(0f)
        triggers[slot][0] = 0
        triggers[slot][1] = 0
        NativeBridge.nativePadConnected(slot, false)
        notePad(id, slot, why)
    }

    /**
     * One line per device that gains or loses a slot. An export then names the
     * pad and its sources, which is how a phantom is told from the pad in the
     * player's hands.
     */
    private fun notePad(id: Int, slot: Int?, why: String?) {
        val device = InputDevice.getDevice(id)
        val fields = mutableListOf(
            "id" to id.toString(),
            "name" to (device?.name ?: "gone"),
            "external" to (device?.let { isExternal(it) } ?: false).toString(),
            "sources" to "0x" + Integer.toHexString(device?.sources ?: 0),
            "slot" to (slot?.toString() ?: "none"),
        )
        if (why != null) fields.add("released" to why)
        EventLog.note(context, "pad", *fields.toTypedArray())
    }

    private fun slotFor(deviceId: Int): Int {
        slots[deviceId]?.let { return it }
        // A device that sent an event before the enumeration saw it, or one
        // that arrived since. It is numbered the way refreshDevices() would
        // have numbered it, and refused when it is not a candidate.
        val external = externalPadPresent()
        if (!isCandidate(InputDevice.getDevice(deviceId), external)) return -1
        // A slot held by a device that is no longer a candidate belongs to no
        // one; let go of it before looking for a free one.
        for (id in slots.keys.toList()) {
            val held = InputDevice.getDevice(id)
            if (isCandidate(held, external)) continue
            releaseSlot(id, if (held == null) "gone" else "internal, external pad present")
        }
        val slot = (firstSlot until 4).firstOrNull { free -> !slots.containsValue(free) } ?: return -1
        slots[deviceId] = slot
        NativeBridge.nativePadConnected(slot, true)
        notePad(deviceId, slot, null)
        return slot
    }

    /** True when the event was a pad's and has been dealt with. */
    fun onKey(event: KeyEvent): Boolean {
        if (!enabled) return false
        // A macro is being played: the pad is not heard, so a run repeats exactly.
        if (Macro.playing) return false
        val source = event.source
        val fromPad = source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (!fromPad) return false
        val bit = bitFor(event.keyCode)
        if (bit == 0) return false
        val slot = slotFor(event.deviceId)
        // A device with no slot has no buttons to index; it is consumed all the
        // same, so nothing else treats it as a key press.
        if (slot < 0) return true
        when (event.action) {
            KeyEvent.ACTION_DOWN -> buttons[slot] = buttons[slot] or bit
            KeyEvent.ACTION_UP -> buttons[slot] = buttons[slot] and bit.inv()
            else -> return false
        }
        push(slot)
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (!enabled) return false
        if (Macro.playing) return false
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false
        val slot = slotFor(event.deviceId)
        // Consumed like a key from a device without a slot: the stick of a
        // phantom must not scroll or focus anything either.
        if (slot < 0) return true

        val lx = axis(event, MotionEvent.AXIS_X)
        val ly = axis(event, MotionEvent.AXIS_Y)
        val rx = if (has(event, MotionEvent.AXIS_Z)) axis(event, MotionEvent.AXIS_Z)
        else axis(event, MotionEvent.AXIS_RX)
        val ry = if (has(event, MotionEvent.AXIS_RZ)) axis(event, MotionEvent.AXIS_RZ)
        else axis(event, MotionEvent.AXIS_RY)

        axes[slot][0] = lx
        // The guest's sticks point up; Android's point down.
        axes[slot][1] = -ly
        axes[slot][2] = rx
        axes[slot][3] = -ry

        val lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER).takeIf { it > 0f }
            ?: event.getAxisValue(MotionEvent.AXIS_BRAKE)
        val rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER).takeIf { it > 0f }
            ?: event.getAxisValue(MotionEvent.AXIS_GAS)
        triggers[slot][0] = (lt.coerceIn(0f, 1f) * 255f).toInt()
        triggers[slot][1] = (rt.coerceIn(0f, 1f) * 255f).toInt()

        // Some pads report the d-pad as a hat rather than as keys.
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        var bits = buttons[slot] and
            (NativeBridge.UP or NativeBridge.DOWN or NativeBridge.LEFT or NativeBridge.RIGHT).inv()
        if (hatX < -0.5f) bits = bits or NativeBridge.LEFT
        if (hatX > 0.5f) bits = bits or NativeBridge.RIGHT
        if (hatY < -0.5f) bits = bits or NativeBridge.UP
        if (hatY > 0.5f) bits = bits or NativeBridge.DOWN
        buttons[slot] = bits

        push(slot)
        return true
    }

    private fun has(event: MotionEvent, axis: Int): Boolean =
        event.device?.getMotionRange(axis, event.source) != null

    /**
     * One axis, with the device's own flat zone taken out. A worn stick that
     * rests at 0.12 would otherwise walk the player into a wall; the device
     * knows how far it drifts and says so.
     */
    private fun axis(event: MotionEvent, which: Int): Float {
        val range = event.device?.getMotionRange(which, event.source)
        val raw = event.getAxisValue(which)
        val flat = range?.flat ?: 0.05f
        val dead = maxOf(flat, 0.08f)
        if (abs(raw) <= dead) return 0f
        val sign = if (raw < 0) -1f else 1f
        return sign * ((abs(raw) - dead) / (1f - dead)).coerceIn(0f, 1f)
    }

    private fun push(slot: Int) {
        Macro.record(
            slot, buttons[slot], triggers[slot][0], triggers[slot][1],
            axes[slot][0], axes[slot][1], axes[slot][2], axes[slot][3],
        )
        NativeBridge.nativePadState(
            slot, buttons[slot], triggers[slot][0], triggers[slot][1],
            axes[slot][0], axes[slot][1], axes[slot][2], axes[slot][3],
        )
    }

    private fun bitFor(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> NativeBridge.A
        KeyEvent.KEYCODE_BUTTON_B -> NativeBridge.B
        KeyEvent.KEYCODE_BUTTON_X -> NativeBridge.X
        KeyEvent.KEYCODE_BUTTON_Y -> NativeBridge.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> NativeBridge.LB
        KeyEvent.KEYCODE_BUTTON_R1 -> NativeBridge.RB
        KeyEvent.KEYCODE_BUTTON_THUMBL -> NativeBridge.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> NativeBridge.R3
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> NativeBridge.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> NativeBridge.BACK
        KeyEvent.KEYCODE_DPAD_UP -> NativeBridge.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> NativeBridge.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> NativeBridge.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> NativeBridge.RIGHT
        else -> 0
    }
}
