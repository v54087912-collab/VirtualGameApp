package top.niunaijun.blackboxa.data.network

import android.content.Context
import top.niunaijun.blackboxa.data.network.model.Catalog
import top.niunaijun.blackboxa.data.network.model.GameInfo
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class CatalogRepository(private val context: Context) {

    private val cacheFile: File
        get() = File(context.cacheDir, "catalog_cache.json")

    fun fetchCatalog(): Result<Catalog> {
        return try {
            val json = fetchFromNetwork()
            cacheCatalog(json)
            val catalog = Catalog.fromJsonString(json)
            Result.success(catalog)
        } catch (e: Exception) {
            val cached = readCachedCatalog()
            if (cached != null) {
                Result.success(cached)
            } else {
                Result.failure(e)
            }
        }
    }

    fun getCachedGames(): List<GameInfo> {
        val cached = readCachedCatalog()
        return cached?.gamesList ?: emptyList()
    }

    private fun fetchFromNetwork(): String {
        val url = URL(
            "https://raw.githubusercontent.com/ALEX5402/NewBlackbox/main/catalog.json"
        )
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            if (connection.responseCode !in 200..299) {
                throw RuntimeException("HTTP ${connection.responseCode}: $response")
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun cacheCatalog(json: String) {
        try {
            cacheFile.writeText(json)
        } catch (_: Exception) {
        }
    }

    private fun readCachedCatalog(): Catalog? {
        return try {
            if (cacheFile.exists()) {
                val json = cacheFile.readText()
                Catalog.fromJsonString(json)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun clearCache() {
        try {
            if (cacheFile.exists()) cacheFile.delete()
        } catch (_: Exception) {
        }
    }
}
