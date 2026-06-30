package top.niunaijun.blackboxa.data.game

import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment
import top.niunaijun.blackboxa.data.network.model.GameInfo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class ObbRouter {

    companion object {
        private const val TAG = "ObbRouter"
        private const val BUFFER_SIZE = 32 * 1024
        private const val OBB_SEGMENT = "Android/obb"
    }

    data class ObbRoutingResult(
        val obbDir: File?,
        val filesCopied: Int = 0,
        val totalBytes: Long = 0
    )

    fun routeObb(
        gameInfo: GameInfo,
        extractedDir: File,
        userId: Int
    ): ObbRoutingResult {
        return try {
            val obbSourceDir = findObbSourceDir(extractedDir, gameInfo.gameId)
            if (obbSourceDir == null) {
                Log.d(TAG, "No OBB directory found for ${gameInfo.gameId}")
                return ObbRoutingResult(null)
            }
            val virtualObbDir = BEnvironment.getExternalObbDir(gameInfo.gameId, userId)
            if (virtualObbDir == null) {
                Log.w(TAG, "Virtual OBB dir is null for ${gameInfo.gameId}")
                return ObbRoutingResult(null)
            }
            if (!virtualObbDir.exists()) {
                virtualObbDir.mkdirs()
            }
            var filesCopied = 0
            var totalBytes = 0L
            val obbFiles = obbSourceDir.listFiles() ?: emptyArray()
            for (file in obbFiles) {
                if (file.isFile && (file.name.endsWith(".obb") || file.name.endsWith(".zip"))) {
                    val destFile = File(virtualObbDir, file.name)
                    copyFile(file, destFile)
                    filesCopied++
                    totalBytes += file.length()
                    Log.d(TAG, "Copied OBB: ${file.name} (${file.length()} bytes) -> ${destFile.absolutePath}")
                }
            }
            Log.d(TAG, "OBB routing complete: $filesCopied files, $totalBytes bytes")
            ObbRoutingResult(
                obbDir = virtualObbDir,
                filesCopied = filesCopied,
                totalBytes = totalBytes
            )
        } catch (e: Exception) {
            Log.e(TAG, "OBB routing failed for ${gameInfo.gameId}: ${e.message}", e)
            ObbRoutingResult(null)
        }
    }

    private fun findObbSourceDir(extractedDir: File, packageName: String): File? {
        val directObb = File(extractedDir, "$OBB_SEGMENT/$packageName")
        if (directObb.exists() && directObb.isDirectory) return directObb
        val obbRoot = File(extractedDir, OBB_SEGMENT)
        if (obbRoot.exists() && obbRoot.isDirectory) {
            val subDirs = obbRoot.listFiles { file -> file.isDirectory } ?: emptyArray()
            if (subDirs.isNotEmpty()) return subDirs[0]
        }
        val topLevelObb = File(extractedDir, "obb")
        if (topLevelObb.exists() && topLevelObb.isDirectory) {
            val pkgDirs = topLevelObb.listFiles { file -> file.isDirectory } ?: emptyArray()
            if (pkgDirs.isNotEmpty()) return pkgDirs[0]
        }
        val allObbFiles = extractedDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".obb") }
            .toList()
        if (allObbFiles.isNotEmpty()) {
            val parent = allObbFiles.first().parentFile
            return parent
        }
        return null
    }

    private fun copyFile(source: File, dest: File) {
        dest.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
        }
    }

    fun cleanupObb(packageName: String, userId: Int) {
        try {
            val obbDir = BEnvironment.getExternalObbDir(packageName, userId)
            if (obbDir != null && obbDir.exists()) {
                obbDir.deleteRecursively()
                Log.d(TAG, "Cleaned up OBB dir for $packageName")
            }
        } catch (e: Exception) {
            Log.w(TAG, "OBB cleanup failed: ${e.message}")
        }
    }
}
