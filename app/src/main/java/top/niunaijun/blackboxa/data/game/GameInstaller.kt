package top.niunaijun.blackboxa.data.game

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class ExtractionResult(
    val gameId: String,
    val apkFile: File?,
    val obbFiles: List<File>,
    val extractedDir: File,
    val totalEntries: Int = 0
)

class GameInstaller(private val context: Context) {

    companion object {
        private const val TAG = "GameInstaller"
        private const val BUFFER_SIZE = 32 * 1024
        private const val EXPECTED_APK_EXTENSION = ".apk"
    }

    private val gamesDir: File
        get() {
            val dir = File(context.cacheDir, "games")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun verifyIntegrity(zippedFile: File, expectedSha256: String): Boolean {
        return try {
            if (expectedSha256.isBlank()) {
                Log.w(TAG, "No SHA-256 provided for integrity check, skipping")
                return true
            }
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(FileInputStream(zippedFile)).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            val matches = actualHash.equals(expectedSha256, ignoreCase = true)
            if (!matches) {
                Log.e(TAG, "SHA-256 mismatch! Expected: $expectedSha256, Actual: $actualHash")
            } else {
                Log.d(TAG, "SHA-256 integrity check passed: $actualHash")
            }
            matches
        } catch (e: Exception) {
            Log.e(TAG, "Integrity check failed: ${e.message}", e)
            false
        }
    }

    fun extractGame(zippedFile: File, gameId: String, onProgress: (Int) -> Unit): ExtractionResult {
        val targetDir = File(gamesDir, gameId)
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        val obbFiles = mutableListOf<File>()
        var apkFile: File? = null
        var totalEntries = 0

        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zippedFile))).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outputFile = File(targetDir, entry.name)
                        outputFile.parentFile?.mkdirs()

                        FileOutputStream(outputFile).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            while (zis.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                            }
                        }

                        when {
                            entry.name.endsWith(EXPECTED_APK_EXTENSION, ignoreCase = true) ->
                                apkFile = outputFile
                            entry.name.contains("Android/obb/", ignoreCase = true) ->
                                obbFiles.add(outputFile)
                        }
                        totalEntries++
                        if (totalEntries % 5 == 0) {
                            onProgress((totalEntries % 100))
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            onProgress(100)
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed for $gameId: ${e.message}", e)
            targetDir.deleteRecursively()
            throw e
        }
        return ExtractionResult(
            gameId = gameId,
            apkFile = apkFile,
            obbFiles = obbFiles,
            extractedDir = targetDir,
            totalEntries = totalEntries
        )
    }

    fun getGameDir(gameId: String): File? {
        val dir = File(gamesDir, gameId)
        return if (dir.exists()) dir else null
    }

    fun getApkFile(gameId: String): File? {
        val dir = File(gamesDir, gameId)
        if (!dir.exists()) return null
        return dir.listFiles()?.firstOrNull { it.name.endsWith(EXPECTED_APK_EXTENSION, ignoreCase = true) }
    }

    fun deleteGame(gameId: String) {
        val dir = File(gamesDir, gameId)
        if (dir.exists()) dir.deleteRecursively()
    }

    fun getInstalledSize(gameId: String): Long {
        val dir = File(gamesDir, gameId)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
