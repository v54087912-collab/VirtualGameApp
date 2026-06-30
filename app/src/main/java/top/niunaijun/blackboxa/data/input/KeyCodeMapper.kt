package top.niunaijun.blackboxa.data.input

import android.view.KeyEvent
import top.niunaijun.blackboxa.widget.VirtualButton

enum class KeyLayout(val displayName: String) {
    NOKIA("Nokia Legacy"),
    GAMEBOY("Game Boy"),
    KEYPAD("Standard Keypad")
}

object KeyCodeMapper {

    private data class KeyMapping(
        val keyCode: Int,
        val metaState: Int = 0
    )

    private val nokiaMap: Map<VirtualButton, KeyMapping> = mapOf(
        VirtualButton.UP      to KeyMapping(KeyEvent.KEYCODE_DPAD_UP, 0),
        VirtualButton.DOWN    to KeyMapping(KeyEvent.KEYCODE_DPAD_DOWN, 0),
        VirtualButton.LEFT    to KeyMapping(KeyEvent.KEYCODE_DPAD_LEFT, 0),
        VirtualButton.RIGHT   to KeyMapping(KeyEvent.KEYCODE_DPAD_RIGHT, 0),
        VirtualButton.ACTION_A to KeyMapping(19, 0),
        VirtualButton.ACTION_B to KeyMapping(20, 0),
        VirtualButton.ACTION_X to KeyMapping(21, 0),
        VirtualButton.ACTION_Y to KeyMapping(22, 0),
        VirtualButton.SELECT   to KeyMapping(KeyEvent.KEYCODE_BUTTON_SELECT, 0),
        VirtualButton.START    to KeyMapping(KeyEvent.KEYCODE_BUTTON_START, 0)
    )

    private val gameBoyMap: Map<VirtualButton, KeyMapping> = mapOf(
        VirtualButton.UP      to KeyMapping(KeyEvent.KEYCODE_DPAD_UP, 0),
        VirtualButton.DOWN    to KeyMapping(KeyEvent.KEYCODE_DPAD_DOWN, 0),
        VirtualButton.LEFT    to KeyMapping(KeyEvent.KEYCODE_DPAD_LEFT, 0),
        VirtualButton.RIGHT   to KeyMapping(KeyEvent.KEYCODE_DPAD_RIGHT, 0),
        VirtualButton.ACTION_A to KeyMapping(KeyEvent.KEYCODE_BUTTON_A, 0),
        VirtualButton.ACTION_B to KeyMapping(KeyEvent.KEYCODE_BUTTON_B, 0),
        VirtualButton.ACTION_X to KeyMapping(KeyEvent.KEYCODE_BUTTON_X, 0),
        VirtualButton.ACTION_Y to KeyMapping(KeyEvent.KEYCODE_BUTTON_Y, 0),
        VirtualButton.SELECT   to KeyMapping(KeyEvent.KEYCODE_BUTTON_SELECT, 0),
        VirtualButton.START    to KeyMapping(KeyEvent.KEYCODE_BUTTON_START, 0)
    )

    private val keypadMap: Map<VirtualButton, KeyMapping> = mapOf(
        VirtualButton.UP      to KeyMapping(KeyEvent.KEYCODE_DPAD_UP, 0),
        VirtualButton.DOWN    to KeyMapping(KeyEvent.KEYCODE_DPAD_DOWN, 0),
        VirtualButton.LEFT    to KeyMapping(KeyEvent.KEYCODE_DPAD_LEFT, 0),
        VirtualButton.RIGHT   to KeyMapping(KeyEvent.KEYCODE_DPAD_RIGHT, 0),
        VirtualButton.ACTION_A to KeyMapping(KeyEvent.KEYCODE_ENTER, 0),
        VirtualButton.ACTION_B to KeyMapping(KeyEvent.KEYCODE_BACK, 0),
        VirtualButton.ACTION_X to KeyMapping(KeyEvent.KEYCODE_TAB, 0),
        VirtualButton.ACTION_Y to KeyMapping(KeyEvent.KEYCODE_SPACE, 0),
        VirtualButton.SELECT   to KeyMapping(KeyEvent.KEYCODE_BUTTON_SELECT, 0),
        VirtualButton.START    to KeyMapping(KeyEvent.KEYCODE_BUTTON_START, 0)
    )

    private val layoutMap: Map<KeyLayout, Map<VirtualButton, KeyMapping>> = mapOf(
        KeyLayout.NOKIA to nokiaMap,
        KeyLayout.GAMEBOY to gameBoyMap,
        KeyLayout.KEYPAD to keypadMap
    )

    private var activeLayout: KeyLayout = KeyLayout.GAMEBOY

    fun setLayout(layout: KeyLayout) {
        activeLayout = layout
    }

    fun getActiveLayout(): KeyLayout = activeLayout

    fun mapToKeyCode(virtualButton: VirtualButton): Int {
        val mapping = layoutMap[activeLayout]?.get(virtualButton)
        return mapping?.keyCode ?: KeyEvent.KEYCODE_UNKNOWN
    }

    fun mapToMetaState(virtualButton: VirtualButton): Int {
        val mapping = layoutMap[activeLayout]?.get(virtualButton)
        return mapping?.metaState ?: 0
    }

    fun injectKeyPress(
        virtualButton: VirtualButton,
        onInject: (keyCode: Int, metaState: Int, isDown: Boolean) -> Unit
    ) {
        val keyCode = mapToKeyCode(virtualButton)
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return
        val meta = mapToMetaState(virtualButton)
        onInject(keyCode, meta, true)
        onInject(keyCode, meta, false)
    }

    fun injectKeyDown(
        virtualButton: VirtualButton,
        onInject: (keyCode: Int, metaState: Int, isDown: Boolean) -> Unit
    ) {
        val keyCode = mapToKeyCode(virtualButton)
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return
        onInject(keyCode, mapToMetaState(virtualButton), true)
    }

    fun injectKeyUp(
        virtualButton: VirtualButton,
        onInject: (keyCode: Int, metaState: Int, isDown: Boolean) -> Unit
    ) {
        val keyCode = mapToKeyCode(virtualButton)
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return
        onInject(keyCode, mapToMetaState(virtualButton), false)
    }
}
