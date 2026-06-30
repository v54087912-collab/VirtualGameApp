package top.niunaijun.blackboxa.data.update

import top.niunaijun.blackboxa.data.network.model.AppMetadata
import top.niunaijun.blackboxa.BuildConfig
import java.lang.Exception

data class VersionInfo(
    val currentVersion: String,
    val remoteVersion: String,
    val isNewerAvailable: Boolean,
    val updateType: String
)

class UpdateChecker {

    fun checkVersion(metadata: AppMetadata): VersionInfo {
        val current = BuildConfig.VERSION_NAME
        val remote = metadata.latestAppVersion
        return VersionInfo(
            currentVersion = current,
            remoteVersion = remote,
            isNewerAvailable = compareVersions(current, remote) < 0,
            updateType = metadata.updateType
        )
    }

    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    fun isForceUpdate(metadata: AppMetadata): Boolean {
        return metadata.updateType == "force"
    }
}
