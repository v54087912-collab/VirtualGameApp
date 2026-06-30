package top.niunaijun.blackboxa.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class BackgroundInstaller(private val context: Context) {

    companion object {
        private const val TAG = "BackgroundInstaller"
        private const val FILE_PROVIDER_AUTHORITY = "top.niunaijun.blackbox.fileprovider"
    }

    fun installApk(apkFile: File): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            installWithPackageInstaller(apkFile)
        } else {
            installWithIntent(apkFile)
        }
    }

    fun installWithIntent(apkFile: File): Boolean {
        return try {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, apkFile)
            } else {
                Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
            }
            context.startActivity(intent)
            Log.d(TAG, "System installer launched for: ${apkFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch system installer: ${e.message}", e)
            false
        }
    }

    private fun installWithPackageInstaller(apkFile: File): Boolean {
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, apkFile)
            context.contentResolver.openInputStream(uri)?.use { input ->
                session.openWrite(apkFile.name, 0, apkFile.length())?.use { output ->
                    input.copyTo(output, 32 * 1024)
                    session.fsync(output)
                }
            }

            session.commit(PendingInstallReceiver.getIntentSender(context, sessionId))
            session.close()
            Log.d(TAG, "PackageInstaller session committed: $sessionId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller failed, falling back to intent: ${e.message}")
            installWithIntent(apkFile)
        }
    }

    fun canRequestInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun openInstallSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

class PendingInstallReceiver : android.content.BroadcastReceiver() {

    companion object {
        private const val TAG = "PendingInstallReceiver"

        fun getIntentSender(context: Context, sessionId: Int): android.content.IntentSender {
            val intent = Intent(context, PendingInstallReceiver::class.java).apply {
                action = "top.niunaijun.blackbox.INSTALL_RESULT"
                putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
            return pendingIntent.intentSender
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_SUCCESS -> "Installation successful"
            PackageInstaller.STATUS_FAILURE -> "Installation failed"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "User action required"
            else -> "Unknown status"
        }
        Log.i(TAG, "Install result: $status")
    }
}
