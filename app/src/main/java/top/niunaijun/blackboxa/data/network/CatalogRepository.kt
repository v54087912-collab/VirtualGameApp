package top.niunaijun.blackboxa.data.network

import android.content.Context
import android.util.Log
import top.niunaijun.blackboxa.data.network.model.Catalog
import top.niunaijun.blackboxa.data.network.model.GameInfo
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class CatalogRepository(private val context: Context) {

    companion object {
        private const val TAG = "CatalogRepository"
        private const val CATALOG_ASSET = "catalog.json"
        private const val CATALOG_URL =
            "https://raw.githubusercontent.com/v54087912-collab/VirtualGameApp/main/catalog.json"
    }

    private val cacheFile: File
        get() = File(context.cacheDir, "catalog_cache.json")

    /**
     * Fetch catalog with 3-tier fallback:
     *   1. Disk cache (if exists from previous network refresh)
     *   2. Network fetch (updates cache on success)
     *   3. Bundled APK asset (always available, works offline)
     *
     * This guarantees games always show — even on first launch with no network.
     */
    fun fetchCatalog(): Result<Catalog> {
        // Tier 1: Try disk cache first (instant, no I/O wait)
        val cached = readCachedCatalog()
        if (cached != null) {
            Log.d(TAG, "Loaded catalog from disk cache (${cached.gamesList.size} games)")
            // Try network refresh in background (non-blocking for caller)
            refreshFromNetworkInBackground()
            return Result.success(cached)
        }

        // Tier 2: Try network (first launch or cache cleared)
        try {
            val json = fetchFromNetwork()
            cacheCatalog(json)
            val catalog = Catalog.fromJsonString(json)
            Log.d(TAG, "Loaded catalog from network (${catalog.gamesList.size} games)")
            return Result.success(catalog)
        } catch (e: Exception) {
            Log.w(TAG, "Network fetch failed: ${e.message}")
        }

        // Tier 3: Read bundled asset from APK (guaranteed to exist)
        val bundled = readBundledCatalog()
        if (bundled != null) {
            Log.d(TAG, "Loaded catalog from APK asset (${bundled.gamesList.size} games)")
            return Result.success(bundled)
        }

        Log.e(TAG, "All catalog sources failed")
        return Result.failure(RuntimeException("No catalog source available"))
    }

    fun getCachedGames(): List<GameInfo> {
        val cached = readCachedCatalog()
        if (cached != null) return cached.gamesList
        val bundled = readBundledCatalog()
        return bundled?.gamesList ?: emptyList()
    }

    private fun refreshFromNetworkInBackground() {
        try {
            val json = fetchFromNetwork()
            cacheCatalog(json)
            Log.d(TAG, "Background network refresh succeeded")
        } catch (e: Exception) {
            Log.d(TAG, "Background network refresh failed (offline?): ${e.message}")
        }
    }

    private fun fetchFromNetwork(): String {
        val url = URL(CATALOG_URL)
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = connection.responseCode
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code")
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun readBundledCatalog(): Catalog? {
        return try {
            val json = context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
            Catalog.fromJsonString(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read bundled catalog: ${e.message}")
            null
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
