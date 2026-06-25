package aquapad.app.input

import android.view.KeyEvent

/**
 * DualSense buttons -> AquaPad actions. The controller drives the robot directly; the phone
 * only forwards button actions. Data-driven so re-binding never touches logic.
 *
 * PS5 button / Android keycode / action:
 *   Options   BUTTON_START -> ARM_TOGGLE
 *   Triangle  BUTTON_Y     -> MISSION_START
 *   Circle    BUTTON_B     -> MISSION_STOP
 *   Cross     BUTTON_A     -> HOLD_DEPTH
 *   Square    BUTTON_X     -> MARKER
 *   PS        BUTTON_MODE  -> ESTOP
 */
enum class PadAction { ARM_TOGGLE, MISSION_START, MISSION_STOP, HOLD_DEPTH, MARKER, ESTOP }

object GamepadMapper {
    private val keyMap: Map<Int, PadAction> = mapOf(
        KeyEvent.KEYCODE_BUTTON_START to PadAction.ARM_TOGGLE,
        KeyEvent.KEYCODE_BUTTON_Y to PadAction.MISSION_START,
        KeyEvent.KEYCODE_BUTTON_B to PadAction.MISSION_STOP,
        KeyEvent.KEYCODE_BUTTON_A to PadAction.HOLD_DEPTH,
        KeyEvent.KEYCODE_BUTTON_X to PadAction.MARKER,
        KeyEvent.KEYCODE_BUTTON_MODE to PadAction.ESTOP,
    )

    fun actionFor(keyCode: Int): PadAction? = keyMap[keyCode]
}
