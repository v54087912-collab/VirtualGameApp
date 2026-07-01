package io.twoyi.input

import android.content.Context
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * KeyMapManager - Touch to KeyCode mapping manager
 *
 * Features:
 * - Maps touch coordinates to hardware KeyCodes
 * - <5ms latency for input processing
 * - Drag-and-drop position customization
 * - Per-game JSON preferences storage
 */
class KeyMapManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "KeyMapManager"
        private const val PREFS_FILE = "keymap_preferences.json"
        private const val CUSTOM_LAYOUTS_DIR = "custom_layouts"

        @Volatile
        private var instance: KeyMapManager? = null

        fun getInstance(context: Context): KeyMapManager {
            return instance ?: synchronized(this) {
                instance ?: KeyMapManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: KeyCode Mapping Table
    // ─────────────────────────────────────────────────────────────────────

    private val keyCodeMap = mapOf(
        // D-Pad
        VirtualKeyType.DPAD_UP to KeyEvent.KEYCODE_DPAD_UP,
        VirtualKeyType.DPAD_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
        VirtualKeyType.DPAD_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
        VirtualKeyType.DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
        VirtualKeyType.DPAD_CENTER to KeyEvent.KEYCODE_DPAD_CENTER,

        // Numeric keys (Nokia mapping)
        VirtualKeyType.KEY_NUM_0 to KeyEvent.KEYCODE_0,
        VirtualKeyType.KEY_NUM_1 to KeyEvent.KEYCODE_1,
        VirtualKeyType.KEY_NUM_2 to KeyEvent.KEYCODE_2,
        VirtualKeyType.KEY_NUM_3 to KeyEvent.KEYCODE_3,
        VirtualKeyType.KEY_NUM_4 to KeyEvent.KEYCODE_4,
        VirtualKeyType.KEY_NUM_5 to KeyEvent.KEYCODE_5,
        VirtualKeyType.KEY_NUM_6 to KeyEvent.KEYCODE_6,
        VirtualKeyType.KEY_NUM_7 to KeyEvent.KEYCODE_7,
        VirtualKeyType.KEY_NUM_8 to KeyEvent.KEYCODE_8,
        VirtualKeyType.KEY_NUM_9 to KeyEvent.KEYCODE_9,
        VirtualKeyType.KEY_STAR to KeyEvent.KEYCODE_STAR,
        VirtualKeyType.KEY_HASH to KeyEvent.KEYCODE_POUND,

        // GamePad buttons
        VirtualKeyType.BUTTON_A to KeyEvent.KEYCODE_BUTTON_A,
        VirtualKeyType.BUTTON_B to KeyEvent.KEYCODE_BUTTON_B,
        VirtualKeyType.BUTTON_X to KeyEvent.KEYCODE_BUTTON_X,
        VirtualKeyType.BUTTON_Y to KeyEvent.KEYCODE_BUTTON_Y,

        // Shoulder buttons
        VirtualKeyType.SHOULDER_L1 to KeyEvent.KEYCODE_BUTTON_L1,
        VirtualKeyType.SHOULDER_R1 to KeyEvent.KEYCODE_BUTTON_R1,
        VirtualKeyType.SHOULDER_L2 to KeyEvent.KEYCODE_BUTTON_L2,
        VirtualKeyType.SHOULDER_R2 to KeyEvent.KEYCODE_BUTTON_R2,

        // System buttons
        VirtualKeyType.START to KeyEvent.KEYCODE_BUTTON_START,
        VirtualKeyType.SELECT to KeyEvent.KEYCODE_BUTTON_SELECT,
        VirtualKeyType.HOME to KeyEvent.KEYCODE_HOME,
        VirtualKeyType.BACK to KeyEvent.KEYCODE_BACK,
        VirtualKeyType.MENU to KeyEvent.KEYCODE_MENU,

        // Call buttons
        VirtualKeyType.CALL to KeyEvent.KEYCODE_CALL,
        VirtualKeyType.END_CALL to KeyEvent.KEYCODE_ENDCALL,

        // Soft keys
        VirtualKeyType.SOFT_LEFT to KeyEvent.KEYCODE_SOFT_LEFT,
        VirtualKeyType.SOFT_RIGHT to KeyEvent.KEYCODE_SOFT_RIGHT,

        // Volume
        VirtualKeyType.VOLUME_UP to KeyEvent.KEYCODE_VOLUME_UP,
        VirtualKeyType.VOLUME_DOWN to KeyEvent.KEYCODE_VOLUME_DOWN,

        // Misc
        VirtualKeyType.POWER to KeyEvent.KEYCODE_POWER,
        VirtualKeyType.CAMERA to KeyEvent.KEYCODE_CAMERA
    )

    // ─────────────────────────────────────────────────────────────────────
    // Region: State
    // ─────────────────────────────────────────────────────────────────────

    private var currentLayout: OverlayLayoutProfile = OverlayLayoutProfile.ClassicNokia
    private var customButtonPositions = mutableMapOf<String, PointF>()
    private var activeGameId: String? = null

    // ─────────────────────────────────────────────────────────────────────
    // Region: Initialization
    // ─────────────────────────────────────────────────────────────────────

    init {
        ensureDirectoriesExist()
    }

    private fun ensureDirectoriesExist() {
        File(context.filesDir, CUSTOM_LAYOUTS_DIR).mkdirs()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 1. Touch to KeyCode Mapping
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Convert touch coordinates to KeyEvent.
     * Optimized for <5ms latency.
     *
     * @param x Touch X coordinate (pixels)
     * @param y Touch Y coordinate (pixels)
     * @param screenWidth Screen width in pixels
     * @param screenHeight Screen height in pixels
     * @return KeyEvent if touch hit a button, null otherwise
     */
    fun mapTouchToKeyCode(
        x: Float,
        y: Float,
        screenWidth: Int,
        screenHeight: Int
    ): KeyEvent? {
        val startTime = System.nanoTime()

        // Normalize coordinates
        val normalizedX = x / screenWidth
        val normalizedY = y / screenHeight

        // Find button at touch position
        val hitButton = findButtonAtPosition(normalizedX, normalizedY)

        val elapsedTime = (System.nanoTime() - startTime) / 1_000_000.0
        if (elapsedTime > 5.0) {
            android.util.Log.w(TAG, "Key mapping took ${elapsedTime}ms (exceeded 5ms target)")
        }

        return hitButton?.let { button ->
            val keyCode = keyCodeMap[button.keyType] ?: return null
            KeyEvent(System.currentTimeMillis(), System.currentTimeMillis(),
                KeyEvent.ACTION_DOWN, keyCode, 0)
        }
    }

    /**
     * Find virtual button at normalized position
     */
    private fun findButtonAtPosition(normalizedX: Float, normalizedY: Float): VirtualButton? {
        val buttons = currentLayout.getButtons()
        val hitRadius = 0.05f  // 5% of screen width as hit radius

        for (button in buttons) {
            val pos = button.customPosition ?: button.normalizedPosition
            val dx = normalizedX - pos.x
            val dy = normalizedY - pos.y
            val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

            if (distance <= hitRadius + button.normalizedSize) {
                return button
            }
        }

        return null
    }

    /**
     * Get KeyEvent for specific virtual button
     */
    fun getKeyEventForButton(button: VirtualButton, isDown: Boolean): KeyEvent {
        val keyCode = keyCodeMap[button.keyType] ?: KeyEvent.KEYCODE_UNKNOWN
        val action = if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        return KeyEvent(System.currentTimeMillis(), System.currentTimeMillis(),
            action, keyCode, 0)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 2. Drag-and-Drop Customization
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Update button position (called during drag-and-drop)
     */
    fun updateButtonPosition(buttonId: String, newX: Float, newY: Float) {
        customButtonPositions[buttonId] = PointF(newX, newY)
    }

    /**
     * Finalize drag operation and save to preferences
     */
    fun finalizeButtonPosition(buttonId: String) {
        // Position already updated in updateButtonPosition
        savePreferences()
    }

    /**
     * Reset button to default position
     */
    fun resetButtonPosition(buttonId: String) {
        customButtonPositions.remove(buttonId)
        savePreferences()
    }

    /**
     * Reset all buttons to default positions
     */
    fun resetAllPositions() {
        customButtonPositions.clear()
        savePreferences()
    }

    /**
     * Get effective position for button (custom or default)
     */
    fun getButtonPosition(button: VirtualButton): PointF {
        return customButtonPositions[button.id] ?: button.normalizedPosition
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 3. Layout Profile Management
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Set active layout profile
     */
    fun setLayoutProfile(profile: OverlayLayoutProfile) {
        currentLayout = profile
        savePreferences()
    }

    /**
     * Get current layout profile
     */
    fun getCurrentLayout(): OverlayLayoutProfile = currentLayout

    /**
     * Toggle between Nokia and GamePad layouts
     */
    fun toggleLayout(): OverlayLayoutProfile {
        currentLayout = when (currentLayout) {
            is OverlayLayoutProfile.ClassicNokia -> OverlayLayoutProfile.RetroGamePad
            is OverlayLayoutProfile.RetroGamePad -> OverlayLayoutProfile.ClassicNokia
        }
        savePreferences()
        return currentLayout
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 4. Per-Game Preferences
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Load preferences for specific game
     */
    fun loadGamePreferences(gameId: String) {
        activeGameId = gameId
        val prefsFile = getGamePrefsFile(gameId)

        if (prefsFile.exists()) {
            try {
                val json = JSONObject(prefsFile.readText())
                loadFromJson(json)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to load prefs for $gameId", e)
            }
        }
    }

    /**
     * Save preferences for current game
     */
    fun savePreferences() {
        val gameId = activeGameId ?: return
        val prefsFile = getGamePrefsFile(gameId)

        try {
            val json = toJson()
            prefsFile.writeText(json.toString(2))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to save prefs for $gameId", e)
        }
    }

    /**
     * Get game-specific preferences file
     */
    private fun getGamePrefsFile(gameId: String): File {
        return File(context.filesDir, "$CUSTOM_LAYOUTS_DIR/$gameId.json")
    }

    /**
     * Load settings from JSON
     */
    private fun loadFromJson(json: JSONObject) {
        // Load layout profile
        val layoutName = json.optString("layout_profile", "classic_nokia")
        currentLayout = OverlayLayoutProfile.fromName(layoutName)

        // Load custom positions
        customButtonPositions.clear()
        val positionsArray = json.optJSONArray("custom_positions")
        positionsArray?.let { array ->
            for (i in 0 until array.length()) {
                val posJson = array.getJSONObject(i)
                val id = posJson.getString("id")
                val x = posJson.getDouble("x").toFloat()
                val y = posJson.getDouble("y").toFloat()
                customButtonPositions[id] = PointF(x, y)
            }
        }
    }

    /**
     * Convert settings to JSON
     */
    private fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("layout_profile", currentLayout.name)

        val positionsArray = JSONArray()
        customButtonPositions.forEach { (id, pos) ->
            val posJson = JSONObject()
            posJson.put("id", id)
            posJson.put("x", pos.x.toDouble())
            posJson.put("y", pos.y.toDouble())
            positionsArray.put(posJson)
        }
        json.put("custom_positions", positionsArray)

        return json
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 5. Touch Forwarding Mode
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Check if game uses direct touch (no overlay needed)
     */
    fun isDirectTouchGame(controlType: String?): Boolean {
        return controlType?.lowercase()?.contains("touch") == true
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Utility
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Clear all customizations for current game
     */
    fun clearGamePreferences() {
        val gameId = activeGameId ?: return
        val prefsFile = getGamePrefsFile(gameId)
        if (prefsFile.exists()) {
            prefsFile.delete()
        }
        customButtonPositions.clear()
    }

    /**
     * Export layout configuration
     */
    fun exportLayout(): String {
        return toJson().toString(2)
    }

    /**
     * Import layout configuration
     */
    fun importLayout(jsonString: String): Boolean {
        return try {
            val json = JSONObject(jsonString)
            loadFromJson(json)
            savePreferences()
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to import layout", e)
            false
        }
    }

    /**
     * Release resources
     */
    fun destroy() {
        savePreferences()
        instance = null
    }
}

/**
 * PointF - Simple point class for coordinates
 */
data class PointF(val x: Float, val y: Float)
