package io.twoyi.update

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StrictMode
import android.util.Log
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * UpdateManager - App update engine with force/flexible modes
 *
 * Features:
 * - Version checking from catalog.json
 * - Force update (UI blocked) mode
 * - Flexible update (changelog prompt) mode
 * - REQUEST_INSTALL_PACKAGES handler
 * - StrictMode LAX for legacy permissions
 */
class UpdateManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val CATALOG_URL =
            "https://raw.githubusercontent.com/Saini920/Games-Releases-virtual-00282hehe/main/catalog.json"
        private const val DOWNLOAD_DIR = "updates"

        @Volatile
        private var instance: UpdateManager? = null

        fun getInstance(context: Context): UpdateManager {
            return instance ?: synchronized(this) {
                instance ?: UpdateManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: State
    // ─────────────────────────────────────────────────────────────────────

    data class UpdateInfo(
        val latestVersion: String,
        val updateType: String,  // "force" or "flexible"
        val downloadUrl: String? = null,
        val changelog: String? = null,
        val minSdkVersion: Int = 0
    )

    sealed class UpdateState {
        object Idle : UpdateState()
        object Checking : UpdateState()
        data class UpdateAvailable(val info: UpdateInfo) : UpdateState()
        object Downloading : UpdateState()
        data class DownloadProgress(val progress: Int) : UpdateState()
        object Installing : UpdateState()
        object UpToDate : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val updateScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Update check error", throwable)
            _updateState.value = UpdateState.Error(throwable.message ?: "Update check failed")
        }
    )

    // ─────────────────────────────────────────────────────────────────────
    // Region: 1. Version Checking
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Check for app updates from catalog.json
     */
    suspend fun checkForUpdates() = withContext(Dispatchers.IO) {
        _updateState.value = UpdateState.Checking

        try {
            val updateInfo = fetchUpdateInfo()

            if (updateInfo != null && isNewerVersion(updateInfo.latestVersion)) {
                _updateState.value = UpdateState.UpdateAvailable(updateInfo)
                Log.i(TAG, "Update available: ${updateInfo.latestVersion}")
            } else {
                _updateState.value = UpdateState.UpToDate
                Log.i(TAG, "App is up to date")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            _updateState.value = UpdateState.Error(e.message ?: "Check failed")
        }
    }

    /**
     * Fetch update info from remote catalog
     */
    private suspend fun fetchUpdateInfo(): UpdateInfo? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null

        try {
            val url = URL(CATALOG_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP error: $responseCode")
            }

            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)

            val metadata = json.optJSONObject("app_metadata") ?: return@withContext null

            UpdateInfo(
                latestVersion = metadata.optString("latest_app_version", ""),
                updateType = metadata.optString("update_type", "flexible"),
                changelog = metadata.optString("changelog", null),
                minSdkVersion = metadata.optInt("min_sdk_version", 0)
            )

        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Compare versions and check if update is available
     */
    private fun isNewerVersion(remoteVersion: String): Boolean {
        try {
            val currentVersion = getAppVersionName()
            val currentParts = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val remoteParts = remoteVersion.split(".").map { it.toIntOrNull() ?: 0 }

            // Pad with zeros
            val maxSize = maxOf(currentParts.size, remoteParts.size)
            val current = currentParts + List(maxSize - currentParts.size) { 0 }
            val remote = remoteParts + List(maxSize - remoteParts.size) { 0 }

            for (i in 0 until maxSize) {
                if (remote[i] > current[i]) return true
                if (remote[i] < current[i]) return false
            }

            return false
        } catch (e: Exception) {
            Log.e(TAG, "Version comparison failed", e)
            return false
        }
    }

    /**
     * Get current app version name
     */
    private fun getAppVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "0.0.0"
        }
    }

    /**
     * Get current app version code
     */
    private fun getAppVersionCode(): Long {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            0L
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 2. Force Update Handling
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Show force update dialog - blocks UI until update is installed
     */
    fun showForceUpdateDialog(activity: Activity, updateInfo: UpdateInfo) {
        AlertDialog.Builder(activity)
            .setTitle("Update Required")
            .setMessage("A mandatory update is available (v${updateInfo.latestVersion}). " +
                    "Please update to continue using the app.")
            .setCancelable(false)
            .setPositiveButton("Update Now") { _, _ ->
                startUpdateProcess(activity, updateInfo)
            }
            .setOnCancelListener { _ ->
                // Force dialog cannot be cancelled
                showForceUpdateDialog(activity, updateInfo)
            }
            .show()
    }

    /**
     * Show flexible update dialog - shows changelog, user can dismiss
     */
    fun showFlexibleUpdateDialog(
        activity: Activity,
        updateInfo: UpdateInfo,
        onDismiss: (() -> Unit)? = null
    ) {
        val message = buildString {
            appendLine("Version ${updateInfo.latestVersion} is available.")
            appendLine()
            updateInfo.changelog?.let { changelog ->
                appendLine("What's New:")
                appendLine(changelog)
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("Update Available")
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton("Update") { _, _ ->
                startUpdateProcess(activity, updateInfo)
            }
            .setNegativeButton("Later") { _, _ ->
                onDismiss?.invoke()
            }
            .setOnDismissListener {
                onDismiss?.invoke()
            }
            .show()
    }

    /**
     * Handle update based on type (force/flexible)
     */
    fun handleUpdate(activity: Activity, updateInfo: UpdateInfo) {
        when (updateInfo.updateType.lowercase()) {
            "force" -> showForceUpdateDialog(activity, updateInfo)
            "flexible" -> showFlexibleUpdateDialog(activity, updateInfo)
            else -> Log.w(TAG, "Unknown update type: ${updateInfo.updateType}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 3. APK Download & Install
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Start update download and install process
     */
    private fun startUpdateProcess(activity: Activity, updateInfo: UpdateInfo) {
        updateScope.launch {
            try {
                _updateState.value = UpdateState.Downloading

                // Download APK
                val apkFile = downloadApk(updateInfo.downloadUrl)

                if (apkFile != null) {
                    _updateState.value = UpdateState.Installing

                    // Install APK
                    withContext(Dispatchers.Main) {
                        installApk(activity, apkFile)
                    }
                } else {
                    _updateState.value = UpdateState.Error("Download failed")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Update process failed", e)
                _updateState.value = UpdateState.Error(e.message ?: "Update failed")
            }
        }
    }

    /**
     * Download APK file with progress tracking
     */
    private suspend fun downloadApk(downloadUrl: String?): File? = withContext(Dispatchers.IO) {
        if (downloadUrl == null) {
            // Use default download path
            val defaultUrl = "https://github.com/username/repo/releases/latest/download/app-release.apk"
            return@withContext downloadApkFile(defaultUrl)
        }

        return@withContext downloadApkFile(downloadUrl)
    }

    /**
     * Download APK file from URL
     */
    private fun downloadApkFile(urlString: String): File? {
        var connection: HttpURLConnection? = null

        return try {
            val downloadDir = File(context.cacheDir, DOWNLOAD_DIR)
            downloadDir.mkdirs()

            val apkFile = File(downloadDir, "update.apk")

            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("Download failed: HTTP $responseCode")
            }

            val contentLength = connection.contentLength
            var bytesDownloaded = 0L

            connection.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesDownloaded += read

                        // Update progress
                        val progress = if (contentLength > 0) {
                            ((bytesDownloaded * 100) / contentLength).toInt()
                        } else {
                            -1
                        }
                        _updateState.value = UpdateState.DownloadProgress(progress)
                    }
                }
            }

            Log.i(TAG, "APK downloaded: ${apkFile.length()} bytes")
            apkFile

        } catch (e: Exception) {
            Log.e(TAG, "APK download failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 4. REQUEST_INSTALL_PACKAGES Handler
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Install APK with proper intent handling for Android 8.0+
     */
    private fun installApk(activity: Activity, apkFile: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    getUriForFile(apkFile),
                    "application/vnd.android.package-archive"
                )

                // Flag for fresh install
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                // Handle Android 8.0+ INSTALL_UNKNOWN_SOURCES
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                    // Check if we have REQUEST_INSTALL_PACKAGES permission
                    if (context.packageManager.canRequestPackageInstalls()) {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } else {
                        // Direct user to settings
                        val settingsIntent = Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")
                        )
                        activity.startActivityForResult(settingsIntent, 1001)
                        return
                    }
                }

                // Flag for non-system app
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            activity.startActivityForResult(intent, 1000)

        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No activity found to install APK", e)
            _updateState.value = UpdateState.Error("Cannot install APK")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK", e)
            _updateState.value = UpdateState.Error("Installation failed: ${e.message}")
        }
    }

    /**
     * Get URI for file (handles Android 7.0+ FileProvider)
     */
    private fun getUriForFile(file: File): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } else {
            Uri.fromFile(file)
        }
    }

    /**
     * Handle activity result for install permission
     */
    fun handleInstallResult(requestCode: Int, resultCode: Int) {
        when (requestCode) {
            1001 -> {
                // User granted unknown sources permission
                if (resultCode == Activity.RESULT_OK) {
                    Log.i(TAG, "Unknown sources permission granted")
                    // Retry installation
                } else {
                    _updateState.value = UpdateState.Error("Install permission denied")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 5. StrictMode Relaxation (Legacy Permissions Faker)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Set StrictMode to LAX mode for legacy SDK compatibility.
     * This relaxes execution policy for external SDK security flags.
     */
    fun enableStrictModeLax() {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .permitAll()
                    .build()
            )

            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .permitAll()
                    .build()
            )

            Log.i(TAG, "StrictMode set to LAX mode")
        }
    }

    /**
     * Disable StrictMode entirely
     */
    fun disableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .permitAll()
                .build()
        )

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .build()
        )

        Log.i(TAG, "StrictMode disabled")
    }

    /**
     * Check and request REQUEST_INSTALL_PACKAGES permission
     */
    fun checkInstallPermission(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
                activity.startActivityForResult(intent, 1001)
                return false
            }
        }
        return true
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Utility
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Get current update state
     */
    fun getCurrentState(): UpdateState = _updateState.value

    /**
     * Clean up downloaded files
     */
    fun cleanupDownloads() {
        val downloadDir = File(context.cacheDir, DOWNLOAD_DIR)
        if (downloadDir.exists()) {
            downloadDir.deleteRecursively()
        }
    }

    /**
     * Release resources
     */
    fun destroy() {
        updateScope.cancel()
        cleanupDownloads()
        instance = null
    }
}
