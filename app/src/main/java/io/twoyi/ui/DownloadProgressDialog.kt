package io.twoyi.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Window
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.twoyi.R
import io.twoyi.engine.GameLaunchManager

/**
 * DownloadProgressDialog - Custom loading dialog with real-time progress
 *
 * Shows progress for:
 * - Downloading game assets
 * - Extracting files
 * - Installing OBB
 * - Installing APK
 * - Booting container
 */
class DownloadProgressDialog(context: Context) : Dialog(context) {

    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var progressText: TextView
    private lateinit var progressPercent: TextView
    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var dialogTitle: TextView
    private lateinit var gameName: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var startTime = 0L
    private var lastBytes = 0L

    private var onCancelListener: (() -> Unit)? = null

    fun setOnCancelListener(listener: () -> Unit) {
        onCancelListener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_download_progress, null)
        setContentView(view)

        // Initialize views
        progressBar = view.findViewById(R.id.progress_bar)
        progressText = view.findViewById(R.id.progress_text)
        progressPercent = view.findViewById(R.id.progress_percent)
        statusText = view.findViewById(R.id.status_text)
        speedText = view.findViewById(R.id.speed_text)
        dialogTitle = view.findViewById(R.id.dialog_title)
        gameName = view.findViewById(R.id.game_name)

        // Configure dialog
        setCancelable(false)
        setCanceledOnTouchOutside(false)

        // Handle back press
        setOnCancelListener {
            onCancelListener?.invoke()
        }

        window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    /**
     * Set game name to display
     */
    fun setGameName(name: String) {
        gameName.text = name
    }

    /**
     * Update progress from LaunchState
     */
    fun updateState(state: GameLaunchManager.LaunchState) {
        when (state) {
            is GameLaunchManager.LaunchState.CheckingAvailability -> {
                updateProgress(0, "Checking availability...", false)
            }
            is GameLaunchManager.LaunchState.Downloading -> {
                dialogTitle.text = "Downloading Game"
                updateProgress(0, "Starting download...", true)
                startTime = System.currentTimeMillis()
                lastBytes = 0
            }
            is GameLaunchManager.LaunchState.DownloadProgress -> {
                val progress = state.progress.coerceIn(0, 100)
                val speed = calculateSpeed(state.bytesDownloaded)
                updateProgress(progress, "Downloading... $speed", true)
            }
            is GameLaunchManager.LaunchState.Extracting -> {
                dialogTitle.text = "Extracting Files"
                updateProgress(-1, "Extracting game files...", false)
            }
            is GameLaunchManager.LaunchState.InstallingOBB -> {
                dialogTitle.text = "Installing Game Data"
                updateProgress(-1, "Setting up game data...", false)
            }
            is GameLaunchManager.LaunchState.InstallingAPK -> {
                dialogTitle.text = "Installing Game"
                updateProgress(-1, "Installing game package...", false)
            }
            is GameLaunchManager.LaunchState.Booting -> {
                dialogTitle.text = "Launching Game"
                updateProgress(-1, "Starting TwoYi container...", false)
            }
            is GameLaunchManager.LaunchState.Running -> {
                updateProgress(100, "Game running!", false)
                handler.postDelayed({ dismiss() }, 500)
            }
            is GameLaunchManager.LaunchState.Error -> {
                dialogTitle.text = "Error"
                updateProgress(0, state.message, false)
                handler.postDelayed({ dismiss() }, 2000)
            }
            else -> {
                // Idle state - do nothing
            }
        }
    }

    /**
     * Update progress bar and text
     */
    private fun updateProgress(progress: Int, status: String, showSpeed: Boolean) {
        handler.post {
            statusText.text = status

            if (progress >= 0) {
                progressBar.isIndeterminate = false
                progressBar.progress = progress
                progressPercent.text = "$progress%"
                progressText.text = if (progress == 100) "Complete" else "Preparing..."
            } else {
                progressBar.isIndeterminate = true
                progressPercent.text = ""
                progressText.text = "Please wait..."
            }

            speedText.visibility = if (showSpeed) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    /**
     * Calculate download speed
     */
    private fun calculateSpeed(bytesDownloaded: Long): String {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        if (elapsed < 1) return ""

        val bytesPerSecond = bytesDownloaded / elapsed
        lastBytes = bytesDownloaded

        return when {
            bytesPerSecond > 1024 * 1024 -> {
                String.format("%.1f MB/s", bytesPerSecond / (1024 * 1024))
            }
            bytesPerSecond > 1024 -> {
                String.format("%.1f KB/s", bytesPerSecond / 1024)
            }
            else -> {
                String.format("%.0f B/s", bytesPerSecond)
            }
        }
    }

    /**
     * Format bytes to human readable string
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes > 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
            bytes > 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            bytes > 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    override fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        super.dismiss()
    }
}
