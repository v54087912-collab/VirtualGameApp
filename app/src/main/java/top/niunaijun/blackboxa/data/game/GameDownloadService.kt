package top.niunaijun.blackboxa.data.game

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.data.local.DatabaseHelper
import top.niunaijun.blackboxa.data.network.DownloadManager
import top.niunaijun.blackboxa.data.network.DownloadProgress
import top.niunaijun.blackboxa.data.network.DownloadResult
import top.niunaijun.blackboxa.data.network.model.GameInfo
import top.niunaijun.blackboxa.view.main.MainActivity

/**
 * GameDownloadService — Foreground service that handles the complete
 * download → extract → OBB inject → install → launch pipeline
 * in the background with real-time progress notifications.
 *
 * Flow:
 * 1. Download ZIP from GitHub Release URL (chunk-by-chunk, resume support)
 * 2. Extract ZIP to internal scoped directory
 * 3. Route OBB files to BlackBox virtual directory
 * 4. Install APK via BlackBoxCore
 * 5. Auto-launch the game
 * 6. Update DatabaseHelper with install status
 */
class GameDownloadService : Service() {

    companion object {
        private const val TAG = "GameDownloadService"
        private const val CHANNEL_ID = "game_download_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "action_start_download"
        private const val ACTION_CANCEL = "action_cancel_download"
        const val EXTRA_GAME_ID = "extra_game_id"
        const val EXTRA_GAME_TITLE = "extra_game_title"
        const val EXTRA_DOWNLOAD_URL = "extra_download_url"
        const val EXTRA_API_LEVEL = "extra_api_level"
        private const val FOREGROUND_SERVICE_ID = 2001

        fun start(
            context: Context,
            gameInfo: GameInfo
        ) {
            val intent = Intent(context, GameDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_GAME_ID, gameInfo.gameId)
                putExtra(EXTRA_GAME_TITLE, gameInfo.title)
                putExtra(EXTRA_DOWNLOAD_URL, gameInfo.downloadUrl)
                putExtra(EXTRA_API_LEVEL, gameInfo.apiLevel)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, GameDownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    // Callbacks for UI updates
    private var progressCallback: ((DownloadProgress) -> Unit)? = null
    private var statusCallback: ((String) -> Unit)? = null
    private var completionCallback: ((Boolean, String) -> Unit)? = null

    private lateinit var downloadManager: DownloadManager
    private lateinit var dbHelper: DatabaseHelper
    private val gameBootService by lazy { GameBootService(this) }

    inner class LocalBinder : Binder() {
        fun getService(): GameDownloadService = this@GameDownloadService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        downloadManager = DownloadManager(this)
        dbHelper = DatabaseHelper.getInstance(this)
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val gameId = intent.getStringExtra(EXTRA_GAME_ID) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_GAME_TITLE) ?: gameId
                val url = intent.getStringExtra(EXTRA_DOWNLOAD_URL) ?: return START_NOT_STICKY
                val apiLevel = intent.getIntExtra(EXTRA_API_LEVEL, 10)

                startForeground(FOREGROUND_SERVICE_ID, buildNotification(title, 0))
                startDownloadAndInstall(gameId, title, url, apiLevel)
            }
            ACTION_CANCEL -> {
                currentJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    fun setProgressCallback(callback: (DownloadProgress) -> Unit) {
        progressCallback = callback
    }

    fun setStatusCallback(callback: (String) -> Unit) {
        statusCallback = callback
    }

    fun setCompletionCallback(callback: (Boolean, String) -> Unit) {
        completionCallback = callback
    }

    private fun startDownloadAndInstall(
        gameId: String,
        title: String,
        url: String,
        apiLevel: Int
    ) {
        currentJob = serviceScope.launch {
            try {
                // Step 1: Update status to DOWNLOADING
                dbHelper.updateInstallStatus(gameId, DatabaseHelper.STATUS_DOWNLOADING)
                statusCallback?.invoke("Downloading $title…")
                updateNotification(title, 0, "Downloading…")

                // Step 2: Download ZIP
                val downloadResult = downloadManager.download(gameId, url) { progress ->
                    progressCallback?.invoke(progress)
                    updateNotification(title, progress.percentage, "Downloading… ${progress.percentage}%")
                }

                when (downloadResult) {
                    is DownloadResult.Success -> {
                        Log.i(TAG, "Download complete: ${downloadResult.file.name}")

                        // Step 3: Update status to DOWNLOADED
                        dbHelper.updateInstallStatus(gameId, DatabaseHelper.STATUS_DOWNLOADED)

                        // Step 4: Extract + Install
                        extractAndInstall(gameId, title, apiLevel)
                    }
                    is DownloadResult.Error -> {
                        Log.e(TAG, "Download failed: ${downloadResult.message}")
                        dbHelper.markFailed(gameId)
                        statusCallback?.invoke("Download failed: ${downloadResult.message}")
                        completionCallback?.invoke(false, downloadResult.message)
                        updateNotification(title, 0, "Download failed")
                        delay(2000)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    is DownloadResult.Cancelled -> {
                        dbHelper.updateInstallStatus(gameId, DatabaseHelper.STATUS_NOT_INSTALLED)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled")
                dbHelper.updateInstallStatus(gameId, DatabaseHelper.STATUS_NOT_INSTALLED)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline failed: ${e.message}", e)
                dbHelper.markFailed(gameId)
                completionCallback?.invoke(false, e.message ?: "Unknown error")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun extractAndInstall(gameId: String, title: String, apiLevel: Int) {
        dbHelper.updateInstallStatus(gameId, DatabaseHelper.STATUS_EXTRACTING)

        val downloadedFile = downloadManager.getDownloadedFile(gameId)
        if (downloadedFile == null) {
            dbHelper.markFailed(gameId)
            statusCallback?.invoke("Downloaded file not found")
            completionCallback?.invoke(false, "Downloaded file not found")
            delay(2000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val extractionResult: ExtractionResult

        if (downloadedFile.extension.equals("apk", ignoreCase = true)) {
            // Direct APK download — no extraction needed
            Log.i(TAG, "Direct APK file detected: ${downloadedFile.name}, skipping extraction")
            statusCallback?.invoke("Preparing game…")
            updateNotification(title, 100, "Preparing…")

            extractionResult = ExtractionResult(
                gameId = gameId,
                apkFile = downloadedFile,
                obbFiles = emptyList(),
                extractedDir = downloadedFile.parentFile ?: downloadedFile,
                totalEntries = 1,
                detectedArch = "32bit",
                needsTranslation = false
            )
        } else {
            // ZIP file — extract to find APK inside
            statusCallback?.invoke("Extracting game files…")
            updateNotification(title, 100, "Extracting…")

            extractionResult = gameBootService.safeCall("Extraction") {
                val gameInstaller = top.niunaijun.blackboxa.data.game.GameInstaller(this@GameDownloadService)
                gameInstaller.extractGame(
                    zippedFile = downloadedFile,
                    gameId = gameId,
                    onProgress = { pct ->
                        statusCallback?.invoke("Extracting… $pct%")
                        updateNotification(title, 100, "Extracting… $pct%")
                    }
                )
            } ?: run {
                dbHelper.markFailed(gameId)
                statusCallback?.invoke("Extraction failed")
                completionCallback?.invoke(false, "Extraction failed")
                delay(2000)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            if (extractionResult.apkFile == null) {
                dbHelper.markFailed(gameId)
                statusCallback?.invoke("Extraction failed")
                completionCallback?.invoke(false, "Extraction failed — no APK found")
                delay(2000)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
        }

        // Step 4b: Route OBB
        statusCallback?.invoke("Injecting OBB files…")
        updateNotification(title, 100, "Injecting OBB…")
        val obbRouter = ObbRouter()
        val gameInfo = GameInfo(
            gameId = gameId,
            title = title,
            downloadUrl = "",
            apiLevel = apiLevel,
            architectureType = "32bit",
            controlType = "touch"
        )
        obbRouter.routeObb(gameInfo, extractionResult.extractedDir, 0)

        // Step 4c: Install APK
        statusCallback?.invoke("Installing in sandbox…")
        updateNotification(title, 100, "Installing…")
        dbHelper.updateInstallStatus(gameId, DatabaseHelper.STATUS_INSTALLING)

        val installResult = gameBootService.safeCall("InstallInSandbox") {
            BlackBoxCore.get().installPackageAsUser(extractionResult.apkFile, 0)
        }

        if (installResult == null || !installResult.success) {
            val errorMsg = installResult?.msg ?: "Installation returned null"
            dbHelper.markFailed(gameId)
            statusCallback?.invoke("Install failed: $errorMsg")
            completionCallback?.invoke(false, errorMsg)
            delay(2000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Step 5: Mark installed
        dbHelper.markInstalled(gameId, gameId)
        statusCallback?.invoke("Installation complete!")
        completionCallback?.invoke(true, "Game installed successfully")
        updateNotification(title, 100, "Installed!")

        // Step 6: Auto-launch game
        delay(500)
        statusCallback?.invoke("Launching $title…")
        val launched = gameBootService.safeCall("LaunchGame") {
            BlackBoxCore.get().launchApk(gameId, 0)
        }

        if (launched != true) {
            Log.w(TAG, "Auto-launch failed for $gameId, but install was successful")
        }

        delay(1000)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ──────────────────────────────────────────────────
    //  Notification helpers
    // ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Game Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download and installation progress"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, progress: Int): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_empty)
            .setContentTitle("Downloading $title")
            .setContentText("Preparing download…")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(title: String, progress: Int, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notification = buildNotification(title, progress).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Rebuild with updated content
            }
        }
        val updated = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_empty)
            .setContentTitle("Downloading $title")
            .setContentText(text)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        nm.notify(NOTIFICATION_ID, updated)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }
}
