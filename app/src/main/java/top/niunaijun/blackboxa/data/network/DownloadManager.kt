package top.niunaijun.blackboxa.data.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class DownloadProgress(
    val gameId: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percentage: Int,
    val isExtracting: Boolean = false,
    val extractProgress: Int = 0
)

data class DownloadMetadata(
    val url: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val etag: String? = null
)

sealed class DownloadResult {
    data class Success(val gameId: String, val file: File) : DownloadResult()
    data class Error(val gameId: String, val message: String, val exception: Throwable? = null) :
        DownloadResult()

    data class Cancelled(val gameId: String) : DownloadResult()
}

class DownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "DownloadManager"
        private const val BUFFER_SIZE = 32 * 1024
        private const val CHUNK_SIZE = 256 * 1024
        private const val MAX_RETRIES = 3
    }

    private val downloadDir: File
        get() {
            val dir = File(context.cacheDir, "downloads")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private val metaDir: File
        get() {
            val dir = File(context.cacheDir, "download_meta")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    suspend fun download(
        gameId: String,
        url: String,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        var retries = 0
        var lastException: Exception? = null

        while (retries < MAX_RETRIES) {
            try {
                val result = tryDownload(gameId, url, onProgress)
                return@withContext result
            } catch (e: Exception) {
                lastException = e
                retries++
                if (retries >= MAX_RETRIES) {
                    return@withContext DownloadResult.Error(
                        gameId = gameId,
                        message = "Download failed after $MAX_RETRIES retries: ${e.message}",
                        exception = e
                    )
                }
            }
        }

        return@withContext DownloadResult.Error(
            gameId = gameId,
            message = "Download failed: ${lastException?.message}",
            exception = lastException
        )
    }

    private fun tryDownload(
        gameId: String,
        url: String,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadResult {
        val outputFile = File(downloadDir, "${gameId}.zip")
        val metaFile = File(metaDir, "${gameId}.meta")
        var downloadedBytes = 0L
        var totalBytes = -1L

        val savedMeta = loadMeta(metaFile)
        if (savedMeta != null && outputFile.exists() && outputFile.length() > 0) {
            downloadedBytes = outputFile.length()
        } else {
            downloadedBytes = 0L
        }

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            if (downloadedBytes > 0) {
                setRequestProperty("Range", "bytes=$downloadedBytes-")
            }
        }

        return try {
            connection.connect()
            val responseCode = connection.responseCode

            if (responseCode == 416) {
                // 416 Range Not Satisfiable — stale meta or server doesn't support range
                // Clear everything and retry fresh
                Log.w(TAG, "HTTP 416 for $gameId — clearing stale meta, retrying fresh")
                metaFile.delete()
                outputFile.delete()
                downloadedBytes = 0L

                // Retry without Range header
                connection.disconnect()
                return tryDownloadFresh(gameId, url, onProgress)
            }

            if (responseCode == HttpURLConnection.HTTP_PARTIAL || responseCode == HttpURLConnection.HTTP_OK) {
                totalBytes = getContentLength(connection, responseCode, downloadedBytes)
                val etag = connection.getHeaderField("ETag")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    downloadedBytes = 0L
                }

                val inputStream: InputStream = connection.inputStream
                val outputStream = FileOutputStream(outputFile, downloadedBytes > 0)

                inputStream.use { input ->
                    outputStream.use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        var lastReportedPercent = -1
                        var chunkBytes = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            chunkBytes += bytesRead

                            if (chunkBytes >= CHUNK_SIZE) {
                                val percent = if (totalBytes > 0) {
                                    ((downloadedBytes * 100) / totalBytes).toInt()
                                } else 0

                                if (percent != lastReportedPercent) {
                                    lastReportedPercent = percent
                                    onProgress(
                                        DownloadProgress(
                                            gameId = gameId,
                                            bytesDownloaded = downloadedBytes,
                                            totalBytes = totalBytes,
                                            percentage = percent.coerceIn(0, 100)
                                        )
                                    )
                                }
                                chunkBytes = 0L
                            }
                        }
                    }
                }

                saveMeta(metaFile, DownloadMetadata(url, totalBytes, downloadedBytes, etag))

                onProgress(
                    DownloadProgress(
                        gameId = gameId,
                        bytesDownloaded = downloadedBytes,
                        totalBytes = totalBytes,
                        percentage = 100
                    )
                )

                DownloadResult.Success(gameId = gameId, file = outputFile)
            } else {
                DownloadResult.Error(
                    gameId = gameId,
                    message = "Server returned $responseCode"
                )
            }
        } catch (e: Exception) {
            if (outputFile.exists() && downloadedBytes > 0) {
                saveMeta(
                    metaFile,
                    DownloadMetadata(
                        url = url,
                        totalBytes = totalBytes,
                        downloadedBytes = downloadedBytes
                    )
                )
            }
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun tryDownloadFresh(
        gameId: String,
        url: String,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadResult {
        val outputFile = File(downloadDir, "${gameId}.zip")
        val metaFile = File(metaDir, "${gameId}.meta")

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
        }

        return try {
            connection.connect()
            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val totalBytes = connection.contentLengthLong
                val etag = connection.getHeaderField("ETag")
                var downloadedBytes = 0L

                val inputStream: InputStream = connection.inputStream
                val outputStream = FileOutputStream(outputFile, false)

                inputStream.use { input ->
                    outputStream.use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        var lastReportedPercent = -1
                        var chunkBytes = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            chunkBytes += bytesRead

                            if (chunkBytes >= CHUNK_SIZE) {
                                val percent = if (totalBytes > 0) {
                                    ((downloadedBytes * 100) / totalBytes).toInt()
                                } else 0

                                if (percent != lastReportedPercent) {
                                    lastReportedPercent = percent
                                    onProgress(
                                        DownloadProgress(
                                            gameId = gameId,
                                            bytesDownloaded = downloadedBytes,
                                            totalBytes = totalBytes,
                                            percentage = percent.coerceIn(0, 100)
                                        )
                                    )
                                }
                                chunkBytes = 0L
                            }
                        }
                    }
                }

                saveMeta(metaFile, DownloadMetadata(url, totalBytes, downloadedBytes, etag))

                onProgress(
                    DownloadProgress(
                        gameId = gameId,
                        bytesDownloaded = downloadedBytes,
                        totalBytes = totalBytes,
                        percentage = 100
                    )
                )

                DownloadResult.Success(gameId = gameId, file = outputFile)
            } else {
                DownloadResult.Error(
                    gameId = gameId,
                    message = "Server returned $responseCode"
                )
            }
        } catch (e: Exception) {
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun getContentLength(
        connection: HttpURLConnection,
        responseCode: Int,
        existingBytes: Long
    ): Long {
        return when (responseCode) {
            HttpURLConnection.HTTP_PARTIAL -> {
                val contentRange = connection.getHeaderField("Content-Range")
                if (contentRange != null) {
                    val total = contentRange.substringAfter("/").trim().toLongOrNull()
                    if (total != null && total > 0) total else -1L
                } else {
                    connection.contentLengthLong + existingBytes
                }
            }
            else -> connection.contentLengthLong
        }
    }

    private fun saveMeta(file: File, meta: DownloadMetadata) {
        try {
            val json = org.json.JSONObject().apply {
                put("url", meta.url)
                put("totalBytes", meta.totalBytes)
                put("downloadedBytes", meta.downloadedBytes)
                meta.etag?.let { put("etag", it) }
            }
            file.writeText(json.toString())
        } catch (_: Exception) {
        }
    }

    private fun loadMeta(file: File): DownloadMetadata? {
        return try {
            if (file.exists()) {
                val json = org.json.JSONObject(file.readText())
                DownloadMetadata(
                    url = json.getString("url"),
                    totalBytes = json.optLong("totalBytes", -1L),
                    downloadedBytes = json.optLong("downloadedBytes", 0L),
                    etag = json.optString("etag", null)
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun isDownloaded(gameId: String): Boolean {
        return File(downloadDir, "${gameId}.zip").exists()
    }

    fun getDownloadedFile(gameId: String): File? {
        val file = File(downloadDir, "${gameId}.zip")
        return if (file.exists()) file else null
    }

    fun deleteDownload(gameId: String) {
        try {
            File(downloadDir, "${gameId}.zip").delete()
            File(metaDir, "${gameId}.meta").delete()
        } catch (_: Exception) {
        }
    }

    fun getTotalDownloadSize(): Long {
        return downloadDir.listFiles()
            ?.filter { it.extension == "zip" }
            ?.sumOf { it.length() } ?: 0L
    }
}
