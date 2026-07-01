package io.twoyi.input

import android.content.Context
import android.graphics.PointF
import org.json.JSONObject

/**
 * OverlayLayoutProfile - Defines layout configurations for virtual keypad
 *
 * Supports:
 * - Classic Nokia Layout (numeric keypad style)
 * - Retro GamePad Layout (D-Pad + action buttons)
 * - Custom user-modified layouts
 */
sealed class OverlayLayoutProfile(val name: String, val displayName: String) {

    // ─────────────────────────────────────────────────────────────────────
    // Region: Layout Definitions
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Classic Nokia Layout - Traditional numeric keypad
     * Layout:
     *   [1] [2] [3]
     *   [4] [5] [6]
     *   [7] [8] [9]
     *   [*] [0] [#]
     *   [Call] [End]
     */
    object ClassicNokia : OverlayLayoutProfile("classic_nokia", "Classic Nokia") {

        override fun getButtons(): List<VirtualButton> = listOf(
            // D-Pad (centered top)
            VirtualButton("dpad_up", "▲", VirtualKeyType.DPAD_UP,
                PointF(0.5f, 0.15f), 0.08f, ButtonShape.CIRCLE),
            VirtualButton("dpad_down", "▼", VirtualKeyType.DPAD_DOWN,
                PointF(0.5f, 0.30f), 0.08f, ButtonShape.CIRCLE),
            VirtualButton("dpad_left", "◀", VirtualKeyType.DPAD_LEFT,
                PointF(0.42f, 0.225f), 0.08f, ButtonShape.CIRCLE),
            VirtualButton("dpad_right", "▶", VirtualKeyType.DPAD_RIGHT,
                PointF(0.58f, 0.225f), 0.08f, ButtonShape.CIRCLE),
            VirtualButton("dpad_center", "OK", VirtualKeyType.DPAD_CENTER,
                PointF(0.5f, 0.225f), 0.06f, ButtonShape.CIRCLE),

            // Numeric Keypad
            VirtualButton("key_1", "1", VirtualKeyType.KEY_NUM_1,
                PointF(0.25f, 0.45f), 0.07f, ButtonShape.ROUNDED_RECT),
            VirtualButton("key_2", "2", VirtualKeyType.KEY_NUM_2,
                PointF(0.375f, 0.45f), 0.07f, ButtonShape.ROUNDED_RECT),
            VirtualButton("key_3", "3", VirtualKeyType.KEY_NUM_3,
                PointF(0.5f, 0.45f), 0.07f, ButtonShape.ROUNDED_RECT),

            VirtualButton("key_4", "4", VirtualKeyType.KEY_NUM_4,
                PointF(0.25f, 0.55f), 0.07f, ButtonShape.ROUNDED_RECT),
            VirtualButton("key_5", "5", VirtualKeyType.KEY_NUM_5,
                PointF(0.375f, 0.55f), 0.07f, ButtonShape.ROUNDED_RECT),
            VirtualButton("key_6", "6", VirtualKeyType.KEY_NUM_6,
                PointF(0.5f, 0.55f), 0.07f, ButtonShape.ROUNDED_RECT),

            VirtualButton("key_7", "7", VirtualKeyType.KEY_NUM_7,
                PointF(0.25f, 0.65f), 0.07f, ButtonShape.ROUNDED_RECT),
            VirtualButton("key_8", "8", VirtualKeyType.KEY_NUM_8,
                PointF(0.375f, 0.65f), 0.07f, ButtonShape.ROUNDED_RECT),
            VirtualButton("key_9", "9", VirtualKeyType.KEY_NUM_9,
                PointF(0.5f, 0.65f), 0.07f, ButtonShape.ROUNDED_RECT),

            VirtualButton("key_star", "*", VirtualKeyType.KEY_STAR,
                PointF(0.25f, 0.75f), 0.07f, ButtonShape.ROUNDED_RECT),
            VirtualButton("key_0", "0", VirtualKeyType.KEY_NUM_0,
                PointF(0.375f, 0.75f), 0.07f, ButtonShape.ROUNDED_RECT),
            VirtualButton("key_hash", "#", VirtualKeyType.KEY_HASH,
                PointF(0.5f, 0.75f), 0.07f, ButtonShape.ROUNDED_RECT),

            // Side buttons
            VirtualButton("btn_call", "📞", VirtualKeyType.CALL,
                PointF(0.15f, 0.85f), 0.06f, ButtonShape.CIRCLE),
            VirtualButton("btn_end", "⛔", VirtualKeyType.END_CALL,
                PointF(0.6f, 0.85f), 0.06f, ButtonShape.CIRCLE),

            // Soft keys
            VirtualButton("soft_left", "L1", VirtualKeyType.SOFT_LEFT,
                PointF(0.15f, 0.92f), 0.05f, ButtonShape.ROUNDED_RECT),
            VirtualButton("soft_right", "R1", VirtualKeyType.SOFT_RIGHT,
                PointF(0.6f, 0.92f), 0.05f, ButtonShape.ROUNDED_RECT)
        )
    }

    /**
     * Retro GamePad Layout - Console-style controller
     * Layout:
     *        [Y]  [X]
     *      [D-Pad]  [B] [A]
     *   [L1]              [R1]
     *   [Start]    [Select]
     */
    object RetroGamePad : OverlayLayoutProfile("retro_gamepad", "Retro GamePad") {

        override fun getButtons(): List<VirtualButton> = listOf(
            // D-Pad (left side)
            VirtualButton("dpad_up", "▲", VirtualKeyType.DPAD_UP,
                PointF(0.18f, 0.20f), 0.09f, ButtonShape.CIRCLE),
            VirtualButton("dpad_down", "▼", VirtualKeyType.DPAD_DOWN,
                PointF(0.18f, 0.38f), 0.09f, ButtonShape.CIRCLE),
            VirtualButton("dpad_left", "◀", VirtualKeyType.DPAD_LEFT,
                PointF(0.10f, 0.29f), 0.09f, ButtonShape.CIRCLE),
            VirtualButton("dpad_right", "▶", VirtualKeyType.DPAD_RIGHT,
                PointF(0.26f, 0.29f), 0.09f, ButtonShape.CIRCLE),
            VirtualButton("dpad_center", "OK", VirtualKeyType.DPAD_CENTER,
                PointF(0.18f, 0.29f), 0.06f, ButtonShape.CIRCLE),

            // Action buttons (right side)
            VirtualButton("btn_a", "A", VirtualKeyType.BUTTON_A,
                PointF(0.72f, 0.25f), 0.08f, ButtonShape.CIRCLE),
            VirtualButton("btn_b", "B", VirtualKeyType.BUTTON_B,
                PointF(0.62f, 0.35f), 0.08f, ButtonShape.CIRCLE),
            VirtualButton("btn_x", "X", VirtualKeyType.BUTTON_X,
                PointF(0.72f, 0.15f), 0.07f, ButtonShape.CIRCLE),
            VirtualButton("btn_y", "Y", VirtualKeyType.BUTTON_Y,
                PointF(0.62f, 0.15f), 0.07f, ButtonShape.CIRCLE),

            // Shoulder buttons
            VirtualButton("btn_l1", "L1", VirtualKeyType.SHOULDER_L1,
                PointF(0.10f, 0.08f), 0.08f, ButtonShape.ROUNDED_RECT),
            VirtualButton("btn_r1", "R1", VirtualKeyType.SHOULDER_R1,
                PointF(0.72f, 0.08f), 0.08f, ButtonShape.ROUNDED_RECT),

            // Center buttons
            VirtualButton("btn_start", "START", VirtualKeyType.START,
                PointF(0.55f, 0.50f), 0.06f, ButtonShape.ROUNDED_RECT),
            VirtualButton("btn_select", "SELECT", VirtualKeyType.SELECT,
                PointF(0.35f, 0.50f), 0.06f, ButtonShape.ROUNDED_RECT),

            // Volume (bottom)
            VirtualButton("vol_up", "V+", VirtualKeyType.VOLUME_UP,
                PointF(0.85f, 0.45f), 0.05f, ButtonShape.CIRCLE),
            VirtualButton("vol_down", "V-", VirtualKeyType.VOLUME_DOWN,
                PointF(0.85f, 0.55f), 0.05f, ButtonShape.CIRCLE)
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Abstract Methods
    // ─────────────────────────────────────────────────────────────────────

    abstract fun getButtons(): List<VirtualButton>

    // ─────────────────────────────────────────────────────────────────────
    // Region: Serialization
    // ─────────────────────────────────────────────────────────────────────

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("name", name)
        json.put("display_name", displayName)

        val buttonsArray = org.json.JSONArray()
        getButtons().forEach { button ->
            buttonsArray.put(button.toJson())
        }
        json.put("buttons", buttonsArray)

        return json
    }

    companion object {
        fun fromName(name: String): OverlayLayoutProfile {
            return when (name) {
                "classic_nokia" -> ClassicNokia
                "retro_gamepad" -> RetroGamePad
                else -> ClassicNokia
            }
        }

        fun fromJson(json: JSONObject): OverlayLayoutProfile {
            val name = json.optString("name", "classic_nokia")
            return fromName(name)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Region: Data Classes
// ─────────────────────────────────────────────────────────────────────────

/**
 * VirtualButton - Represents a single button in the overlay
 */
data class VirtualButton(
    val id: String,
    val label: String,
    val keyType: VirtualKeyType,
    val normalizedPosition: PointF,  // 0.0 - 1.0 relative to screen
    val normalizedSize: Float,       // 0.0 - 1.0 relative to screen width
    val shape: ButtonShape,
    var isPressed: Boolean = false,
    var customPosition: PointF? = null  // User-customized position
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("label", label)
        json.put("key_type", keyType.name)
        json.put("normalized_x", normalizedPosition.x)
        json.put("normalized_y", normalizedPosition.y)
        json.put("normalized_size", normalizedSize)
        json.put("shape", shape.name)
        customPosition?.let {
            json.put("custom_x", it.x)
            json.put("custom_y", it.y)
        }
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): VirtualButton {
            val keyType = try {
                VirtualKeyType.valueOf(json.getString("key_type"))
            } catch (e: Exception) {
                VirtualKeyType.UNKNOWN
            }

            val shape = try {
                ButtonShape.valueOf(json.getString("shape"))
            } catch (e: Exception) {
                ButtonShape.CIRCLE
            }

            val customX = json.optDouble("custom_x", Double.NaN).toFloat()
            val customY = json.optDouble("custom_y", Double.NaN).toFloat()
            val customPos = if (!customX.isNaN() && !customY.isNaN()) {
                PointF(customX, customY)
            } else null

            return VirtualButton(
                id = json.getString("id"),
                label = json.getString("label"),
                keyType = keyType,
                normalizedPosition = PointF(
                    json.getDouble("normalized_x").toFloat(),
                    json.getDouble("normalized_y").toFloat()
                ),
                normalizedSize = json.getDouble("normalized_size").toFloat(),
                shape = shape,
                customPosition = customPos
            )
        }
    }
}

/**
 * VirtualKeyType - All supported virtual key types
 */
enum class VirtualKeyType {
    // D-Pad
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER,

    // Numeric keys (Nokia)
    KEY_NUM_0, KEY_NUM_1, KEY_NUM_2, KEY_NUM_3, KEY_NUM_4,
    KEY_NUM_5, KEY_NUM_6, KEY_NUM_7, KEY_NUM_8, KEY_NUM_9,
    KEY_STAR, KEY_HASH,

    // GamePad buttons
    BUTTON_A, BUTTON_B, BUTTON_X, BUTTON_Y,

    // Shoulder buttons
    SHOULDER_L1, SHOULDER_R1, SHOULDER_L2, SHOULDER_R2,

    // System buttons
    START, SELECT, HOME, BACK, MENU,

    // Call buttons
    CALL, END_CALL,

    // Soft keys
    SOFT_LEFT, SOFT_RIGHT,

    // Volume
    VOLUME_UP, VOLUME_DOWN,

    // Misc
    POWER, CAMERA, UNKNOWN
}

/**
 * ButtonShape - Visual shape of button
 */
enum class ButtonShape {
    CIRCLE, RECT, ROUNDED_RECT
}
