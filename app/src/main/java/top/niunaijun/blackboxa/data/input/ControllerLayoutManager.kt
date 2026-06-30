package top.niunaijun.blackboxa.data.input

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import top.niunaijun.blackboxa.widget.ButtonConfig
import top.niunaijun.blackboxa.widget.RetroSkin
import top.niunaijun.blackboxa.widget.VirtualButton
import java.io.File

data class ControllerProfile(
    val gameId: String,
    val skin: RetroSkin,
    val keyLayout: KeyLayout,
    val opacity: Int,
    val buttons: List<ButtonConfig>
)

class ControllerLayoutManager(private val context: Context) {

    private val layoutDir: File
        get() {
            val dir = File(context.cacheDir, "controller_layouts")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun saveLayout(gameId: String, buttons: List<ButtonConfig>, skin: RetroSkin, keyLayout: KeyLayout, opacity: Int) {
        try {
            val json = JSONObject().apply {
                put("game_id", gameId)
                put("skin", skin.name)
                put("key_layout", keyLayout.name)
                put("opacity", opacity)
                put("buttons", JSONArray(buttons.map { btn ->
                    JSONObject().apply {
                        put("id", btn.button.id)
                        put("x", btn.x.toDouble())
                        put("y", btn.y.toDouble())
                        put("width", btn.width.toDouble())
                        put("height", btn.height.toDouble())
                        put("visible", btn.isVisible)
                    }
                }))
            }
            val file = File(layoutDir, "${sanitizeGameId(gameId)}.json")
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            android.util.Log.e("ControllerLayout", "Save failed: ${e.message}", e)
        }
    }

    fun loadLayout(gameId: String): ControllerProfile? {
        return try {
            val file = File(layoutDir, "${sanitizeGameId(gameId)}.json")
            if (!file.exists()) return null

            val json = JSONObject(file.readText())
            val skin = try { RetroSkin.valueOf(json.optString("skin", RetroSkin.CLASSIC.name)) }
                catch (_: Exception) { RetroSkin.CLASSIC }
            val keyLayout = try { KeyLayout.valueOf(json.optString("key_layout", KeyLayout.GAMEBOY.name)) }
                catch (_: Exception) { KeyLayout.GAMEBOY }
            val opacity = json.optInt("opacity", 128)

            val buttonsArray = json.getJSONArray("buttons")
            val buttons = mutableListOf<ButtonConfig>()
            for (i in 0 until buttonsArray.length()) {
                val btnJson = buttonsArray.getJSONObject(i)
                val virtualBtn = VirtualButton.entries.find { it.id == btnJson.getString("id") }
                if (virtualBtn != null) {
                    buttons.add(ButtonConfig(
                        button = virtualBtn,
                        x = btnJson.getDouble("x").toFloat(),
                        y = btnJson.getDouble("y").toFloat(),
                        width = btnJson.getDouble("width").toFloat(),
                        height = btnJson.getDouble("height").toFloat(),
                        isVisible = btnJson.optBoolean("visible", true)
                    ))
                }
            }

            ControllerProfile(
                gameId = gameId,
                skin = skin,
                keyLayout = keyLayout,
                opacity = opacity,
                buttons = buttons
            )
        } catch (e: Exception) {
            android.util.Log.e("ControllerLayout", "Load failed: ${e.message}", e)
            null
        }
    }

    fun deleteLayout(gameId: String) {
        val file = File(layoutDir, "${sanitizeGameId(gameId)}.json")
        if (file.exists()) file.delete()
    }

    fun hasCustomLayout(gameId: String): Boolean {
        return File(layoutDir, "${sanitizeGameId(gameId)}.json").exists()
    }

    private fun sanitizeGameId(gameId: String): String {
        return gameId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}
