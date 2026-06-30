package top.niunaijun.blackboxa.data.network.model

import org.json.JSONArray
import org.json.JSONObject

data class AppMetadata(
    val latestAppVersion: String,
    val updateType: String
) {
    companion object {
        fun fromJson(json: JSONObject): AppMetadata {
            return AppMetadata(
                latestAppVersion = json.getString("latest_app_version"),
                updateType = json.getString("update_type")
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("latest_app_version", latestAppVersion)
        put("update_type", updateType)
    }
}

data class GameInfo(
    val gameId: String,
    val title: String,
    val downloadUrl: String,
    val sha256: String = "",
    val apiLevel: Int,
    val architectureType: String,
    val controlType: String,
    val obbUrl: String = "",
    val iconUrl: String = ""
) {
    companion object {
        fun fromJson(json: JSONObject): GameInfo {
            return GameInfo(
                gameId = json.getString("game_id"),
                title = json.getString("title"),
                downloadUrl = json.getString("download_url"),
                sha256 = json.optString("sha256", ""),
                apiLevel = json.getInt("api_level"),
                architectureType = json.getString("architecture_type"),
                controlType = json.getString("control_type"),
                obbUrl = json.optString("obb_url", ""),
                iconUrl = json.optString("icon_url", "")
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("game_id", gameId)
        put("title", title)
        put("download_url", downloadUrl)
        put("sha256", sha256)
        put("api_level", apiLevel)
        put("architecture_type", architectureType)
        put("control_type", controlType)
        put("obb_url", obbUrl)
        put("icon_url", iconUrl)
    }
}

data class Catalog(
    val appMetadata: AppMetadata,
    val gamesList: List<GameInfo>
) {
    companion object {
        fun fromJson(json: JSONObject): Catalog {
            val metadata = AppMetadata.fromJson(json.getJSONObject("app_metadata"))
            val gamesArray = json.getJSONArray("games_list")
            val games = mutableListOf<GameInfo>()
            for (i in 0 until gamesArray.length()) {
                games.add(GameInfo.fromJson(gamesArray.getJSONObject(i)))
            }
            return Catalog(
                appMetadata = metadata,
                gamesList = games
            )
        }

        fun fromJsonString(raw: String): Catalog {
            return fromJson(JSONObject(raw))
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("app_metadata", appMetadata.toJson())
        put("games_list", JSONArray(gamesList.map { it.toJson() }))
    }
}
