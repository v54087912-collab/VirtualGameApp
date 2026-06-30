package top.niunaijun.blackboxa.data.input

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.data.network.model.GameInfo
import top.niunaijun.blackboxa.widget.VirtualButton

class TouchForwarder(private val context: Context) {

    private var isTouchGame = false
    private var targetPackage: String = ""

    fun configureForGame(gameInfo: GameInfo) {
        isTouchGame = gameInfo.controlType == "touch"
        targetPackage = gameInfo.gameId
    }

    fun isTouchMode(): Boolean = isTouchGame

    fun forwardTouchEvent(event: MotionEvent, surfaceBounds: Rect): Boolean {
        if (!isTouchGame || targetPackage.isEmpty()) return false
        return try {
            val scaledX = event.x / surfaceBounds.width()
            val scaledY = event.y / surfaceBounds.height()
            val mappedEvent = MotionEvent.obtain(
                event.downTime,
                event.eventTime,
                event.action,
                scaledX,
                scaledY,
                event.pressure,
                event.size,
                event.metaState,
                event.xPrecision,
                event.yPrecision,
                event.deviceId,
                event.edgeFlags
            )
            mappedEvent.source = InputDevice.SOURCE_TOUCHSCREEN
            BlackBoxCore.get().onBeforeMainLaunchApk(targetPackage, resolveUserId())
            mappedEvent.recycle()
            true
        } catch (e: Exception) {
            android.util.Log.e("TouchForwarder", "Touch forward failed: ${e.message}")
            false
        }
    }

    fun forwardKeyEvent(button: VirtualButton, isDown: Boolean): Boolean {
        if (targetPackage.isEmpty()) return false
        return try {
            val keyCode = KeyCodeMapper.mapToKeyCode(button)
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return false
            val action = if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            val event = KeyEvent(
                android.os.SystemClock.uptimeMillis(),
                android.os.SystemClock.uptimeMillis(),
                action,
                keyCode,
                0,
                0,
                0,
                InputDevice.SOURCE_KEYBOARD,
                0,
                KeyEvent.FLAG_FROM_SYSTEM
            )
            BlackBoxCore.get().onBeforeMainLaunchApk(targetPackage, resolveUserId())
            true
        } catch (e: Exception) {
            android.util.Log.e("TouchForwarder", "Key forward failed: ${e.message}")
            false
        }
    }

    private fun resolveUserId(): Int {
        return try {
            val users = BlackBoxCore.get().users
            if (users.isNotEmpty()) users[0].id else 0
        } catch (_: Exception) { 0 }
    }
}
