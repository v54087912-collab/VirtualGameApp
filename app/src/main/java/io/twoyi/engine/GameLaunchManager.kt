package io.twoyi.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * GameLaunchManager - Orchestrates the complete game launch workflow
 *
 * Workflow:
 * 1. Check local availability (downloaded + installed in container)
 * 2. If not available: Download → Extract → Install OBB → Install APK
 * 3. Boot container with game
 */
class GameLaunchManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GameLaunchManager"

        @Volatile
        private var instance: GameLaunchManager? = null

        fun getInstance(context: Context): GameLaunchManager {
            return instance ?: synchronized(this) {
                instance ?: GameLaunchManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: State Management
    // ─────────────────────────────────────────────────────────────────────

    sealed class LaunchState {
        object Idle : LaunchState()
        object CheckingAvailability : LaunchState()
        object Downloading : LaunchState()
        data class DownloadProgress(val progress: Int, val bytesDownloaded: Long) : LaunchState()
        object Extracting : LaunchState()
        object InstallingOBB : LaunchState()
        object InstallingAPK : LaunchState()
        object Booting : LaunchState()
        object Running : LaunchState()
        data class Error(val message: String, val exception: Throwable? = null) : LaunchState()
    }

    private val _launchState = MutableStateFlow<LaunchState>(LaunchState.Idle)
    val launchState: StateFlow<LaunchState> = _launchState.asStateFlow()

    private val launchScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Launch coroutine error", throwable)
            _launchState.value = LaunchState.Error(
                throwable.message ?: "Unknown launch error",
                throwable
            )
        }
    )

    private val engineManager by lazy { ContainerEngineManager.getInstance(context) }
    private val database by lazy { io.twoyi.db.GameCacheDatabase.getInstance(context) }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Storage Paths
    // ─────────────────────────────────────────────────────────────────────

    private val gamesDir: File
        get() = File(context.filesDir, "twoyi/games")

    private val downloadCacheDir: File
        get() = File(context.cacheDir, "game_downloads")

    private val guestDataDir: File
        get() = File(context.filesDir, "twoyi/container/data")

    private fun getGameDir(gameId: String): File = File(gamesDir, gameId)

    private fun getGameApkPath(gameId: String): File = File(getGameDir(gameId), "game.apk")

    private fun getGameObbDir(gameId: String): File = File(getGameDir(gameId), "obb")

    // ─────────────────────────────────────────────────────────────────────
    // Region: 1. Local Availability Handshake
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Check if game is already downloaded AND installed in TwoYi container.
     * Returns true if game can be launched directly without any downloads.
     */
    suspend fun isGameReadyToLaunch(gameId: String): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Checking availability for game: $gameId")

        // Check 1: Is game downloaded?
        if (!database.isGameDownloaded(gameId)) {
            Log.d(TAG, "Game not downloaded: $gameId")
            return@withContext false
        }

        // Check 2: Is APK present in local storage?
        val apkPath = getGameApkPath(gameId)
        if (!apkPath.exists() || apkPath.length() == 0L) {
            Log.d(TAG, "APK not found locally: $gameId")
            return@withContext false
        }

        // Check 3: Is game installed in TwoYi container?
        val installedMarker = File(getGameDir(gameId), ".installed")
        if (!installedMarker.exists()) {
            Log.d(TAG, "Game not installed in container: $gameId")
            return@withContext false
        }

        Log.i(TAG, "Game ready to launch: $gameId")
        true
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 2. Silent Downloader with Progress
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Download game assets (ZIP containing APK + OBB) with progress tracking.
     */
    suspend fun downloadGameAssets(
        gameId: String,
        downloadUrl: String
    ) = withContext(Dispatchers.IO) {
        _launchState.value = LaunchState.Downloading

        try {
            // Prepare directories
            val gameDir = getGameDir(gameId)
            gameDir.mkdirs()
            downloadCacheDir.mkdirs()

            val zipFile = File(downloadCacheDir, "$gameId.zip")

            // Download with progress
            downloadWithProgress(downloadUrl, zipFile)

            // Verify download
            if (!zipFile.exists() || zipFile.length() == 0L) {
                throw IOException("Download failed: empty file")
            }

            Log.i(TAG, "Game assets downloaded: ${zipFile.length()} bytes")

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for game: $gameId", e)
            _launchState.value = LaunchState.Error("Download failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * Download file with progress updates
     */
    private suspend fun downloadWithProgress(
        urlString: String,
        destination: File
    ) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null

        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.requestProperty("Accept", "application/octet-stream")
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP error: $responseCode")
            }

            val contentLength = connection.contentLength.toLong()
            var bytesDownloaded = 0L

            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesDownloaded += read

                        // Update progress every 512KB
                        if (bytesDownloaded % (512 * 1024) < buffer.size) {
                            val progress = if (contentLength > 0) {
                                ((bytesDownloaded * 100) / contentLength).toInt()
                            } else {
                                -1
                            }
                            _launchState.value = LaunchState.DownloadProgress(
                                progress,
                                bytesDownloaded
                            )
                        }
                    }
                }
            }

            Log.d(TAG, "Download complete: $bytesDownloaded bytes")

        } finally {
            connection?.disconnect()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 3. Automated OBB Extraction & Internal Routing
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Extract game assets and route OBB to internal virtual storage.
     */
    suspend fun extractAndRouteAssets(gameId: String) = withContext(Dispatchers.IO) {
        _launchState.value = LaunchState.Extracting

        try {
            val zipFile = File(downloadCacheDir, "$gameId.zip")
            val gameDir = getGameDir(gameId)

            if (!zipFile.exists()) {
                throw IOException("ZIP file not found: ${zipFile.absolutePath}")
            }

            // Extract ZIP contents
            extractGameZip(zipFile, gameDir)

            // Route OBB to TwoYi internal storage
            routeObbToInternalStorage(gameId)

            // Clean up ZIP
            zipFile.delete()

            Log.i(TAG, "Assets extracted and routed for game: $gameId")

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed for game: $gameId", e)
            _launchState.value = LaunchState.Error("Extraction failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * Extract game ZIP containing APK + OBB files
     */
    private fun extractGameZip(zipFile: File, destinationDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zip ->
            var entry = zip.nextEntry

            while (entry != null) {
                val entryName = entry.name

                // Security: prevent path traversal
                if (entryName.contains("..") || entryName.startsWith("/")) {
                    Log.w(TAG, "Skipping suspicious entry: $entryName")
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }

                val outputFile = File(destinationDir, entryName)

                // Create parent directories
                outputFile.parentFile?.mkdirs()

                // Extract file
                if (!entry.isDirectory) {
                    FileOutputStream(outputFile).use { output ->
                        zip.copyTo(output, bufferSize = 8192)
                    }
                    Log.d(TAG, "Extracted: $entryName")
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /**
     * Route OBB files to TwoYi internal virtual storage path.
     * Path: /data/data/io.twoyi/files/twoyi/container/data/media/0/Android/obb/<package>/
     */
    private fun routeObbToInternalStorage(gameId: String) {
        _launchState.value = LaunchState.InstallingOBB

        val gameDir = getGameDir(gameId)
        val obbDir = getGameObbDir(gameId)

        // Find OBB files in extracted content
        val obbFiles = gameDir.listFiles { file ->
            file.extension == "obb" || file.name.contains("main.") || file.name.contains("patch.")
        }

        if (obbFiles == null || obbFiles.isEmpty()) {
            Log.d(TAG, "No OBB files found for game: $gameId")
            return
        }

        // Get game package name from database or filename
        val packageName = extractPackageName(gameId)

        // Target OBB directory in TwoYi virtual storage
        val targetObbDir = File(
            guestDataDir,
            "media/0/Android/obb/$packageName"
        )
        targetObbDir.mkdirs()

        // Move OBB files to target directory
        obbFiles.forEach { obbFile ->
            val targetFile = File(targetObbDir, obbFile.name)
            obbFile.copyTo(targetFile, overwrite = true)
            obbFile.delete()
            Log.d(TAG, "OBB routed: ${obbFile.name} → ${targetFile.absolutePath}")
        }

        Log.i(TAG, "OBB routing complete for game: $gameId")
    }

    /**
     * Extract package name from game ID or APK
     */
    private fun extractPackageName(gameId: String): String {
        // Try to read from manifest if available
        // Fallback to game ID format
        return gameId.replace("-", ".").lowercase()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 4. Programmatic APK Install & Command Launch
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Install APK inside TwoYi container environment using internal package manager.
     */
    suspend fun installApkInContainer(gameId: String) = withContext(Dispatchers.IO) {
        _launchState.value = LaunchState.InstallingAPK

        try {
            val apkPath = getGameApkPath(gameId)

            if (!apkPath.exists()) {
                throw IOException("APK not found: ${apkPath.absolutePath}")
            }

            // Copy APK to container's app directory
            val containerAppDir = File(guestDataDir, "app")
            containerAppDir.mkdirs()

            val targetApk = File(containerAppDir, "$gameId.apk")
            apkPath.copyTo(targetApk, overwrite = true)

            // Execute package install command in container
            val installSuccess = executeContainerCommand(
                "pm install -r -g ${targetApk.absolutePath}"
            )

            if (installSuccess) {
                // Mark as installed
                val installedMarker = File(getGameDir(gameId), ".installed")
                installedMarker.writeText(System.currentTimeMillis().toString())

                Log.i(TAG, "APK installed in container: $gameId")
            } else {
                throw IOException("Package install command failed")
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "APK installation failed for game: $gameId", e)
            _launchState.value = LaunchState.Error("Installation failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * Execute a command inside the TwoYi container environment.
     * Uses container's shell to run package manager commands.
     */
    private suspend fun executeContainerCommand(command: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Write command to container's command queue
                val commandDir = File(context.filesDir, "twoyi/container/commands")
                commandDir.mkdirs()

                val commandFile = File(commandDir, "cmd_${System.currentTimeMillis()}.sh")
                commandFile.writeText("#!/system/bin/sh\n$command\n")

                // Wait for command execution (native code will pick up)
                var attempts = 0
                val maxAttempts = 30 // 30 seconds timeout

                while (commandFile.exists() && attempts < maxAttempts) {
                    delay(1000)
                    attempts++
                }

                // Check result
                val resultFile = File(commandDir, "${commandFile.name}.result")
                if (resultFile.exists()) {
                    val result = resultFile.readText().trim()
                    resultFile.delete()
                    commandFile.delete()
                    return@withContext result == "0" || result.contains("Success")
                }

                commandFile.delete()
                false

            } catch (e: Exception) {
                Log.e(TAG, "Command execution failed", e)
                false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Main Launch Workflow
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Complete game launch workflow - orchestrates all steps.
     *
     * @param gameId Game identifier
     * @param downloadUrl URL to download game assets
     * @param surfaceHandle GL surface handle for rendering
     * @param onProgress Callback for progress updates
     * @param onComplete Callback when game starts
     * @param onError Callback for errors
     */
    fun launchGame(
        gameId: String,
        downloadUrl: String,
        surfaceHandle: Long,
        onProgress: ((state: LaunchState) -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
        onError: ((error: String) -> Unit)? = null
    ) {
        launchScope.launch {
            try {
                // Collect state changes
                val job = launch {
                    launchState.collect { state ->
                        onProgress?.invoke(state)
                    }
                }

                // Step 1: Check availability
                _launchState.value = LaunchState.CheckingAvailability
                val isReady = isGameReadyToLaunch(gameId)

                if (!isReady) {
                    // Step 2: Download assets
                    downloadGameAssets(gameId, downloadUrl)

                    // Step 3: Extract and route OBB
                    extractAndRouteAssets(gameId)

                    // Step 4: Install APK in container
                    installApkInContainer(gameId)

                    // Update database
                    val localPath = getGameDir(gameId).absolutePath
                    database.markGameDownloaded(gameId, localPath)
                }

                // Step 5: Boot container with game
                _launchState.value = LaunchState.Booting
                val bootSuccess = engineManager.bootContainer(surfaceHandle, gameId)

                if (bootSuccess) {
                    _launchState.value = LaunchState.Running
                    Log.i(TAG, "Game launched successfully: $gameId")
                    onComplete?.invoke()
                } else {
                    throw IOException("Container boot failed")
                }

                job.cancel()

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Game launch failed: $gameId", e)
                _launchState.value = LaunchState.Error(e.message ?: "Launch failed")
                onError?.invoke(e.message ?: "Launch failed")
            }
        }
    }

    /**
     * Quick launch for already-installed games (bypass download)
     */
    fun quickLaunch(
        gameId: String,
        surfaceHandle: Long,
        onComplete: (() -> Unit)? = null,
        onError: ((error: String) -> Unit)? = null
    ) {
        launchScope.launch {
            try {
                _launchState.value = LaunchState.Booting

                val bootSuccess = engineManager.bootContainer(surfaceHandle, gameId)

                if (bootSuccess) {
                    _launchState.value = LaunchState.Running
                    Log.i(TAG, "Game quick-launched: $gameId")
                    onComplete?.invoke()
                } else {
                    throw IOException("Container boot failed")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Quick launch failed: $gameId", e)
                onError?.invoke(e.message ?: "Launch failed")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Utility
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Get current launch state
     */
    fun getCurrentState(): LaunchState = _launchState.value

    /**
     * Cancel ongoing launch process
     */
    fun cancelLaunch() {
        launchScope.coroutineContext.cancelChildren()
        _launchState.value = LaunchState.Idle
    }

    /**
     * Delete game assets and installation
     */
    suspend fun deleteGame(gameId: String) = withContext(Dispatchers.IO) {
        val gameDir = getGameDir(gameId)
        if (gameDir.exists()) {
            gameDir.deleteRecursively()
        }

        val downloadZip = File(downloadCacheDir, "$gameId.zip")
        if (downloadZip.exists()) {
            downloadZip.delete()
        }

        Log.i(TAG, "Game deleted: $gameId")
    }

    /**
     * Release resources
     */
    fun destroy() {
        launchScope.cancel()
        instance = null
    }
}
