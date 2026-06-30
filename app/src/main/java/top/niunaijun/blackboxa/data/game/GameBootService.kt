package top.niunaijun.blackboxa.data.game

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.system.user.BUserInfo
import top.niunaijun.blackboxa.data.network.DownloadManager
import top.niunaijun.blackboxa.data.network.model.GameInfo
import java.io.File

sealed class BootState {
    object Idle : BootState()
    data class Verifying(val gameId: String, val progress: Int = 0) : BootState()
    data class Extracting(val gameId: String, val progress: Int = 0) : BootState()
    data class Morphing(val gameId: String) : BootState()
    data class RoutingObb(val gameId: String) : BootState()
    data class InitializingTranslation(val gameId: String) : BootState()
    data class Installing(val gameId: String, val progress: Int = 0) : BootState()
    data class Launching(val gameId: String) : BootState()
    object Completed : BootState()
    data class Error(val gameId: String, val message: String) : BootState()
}

class GameBootService(private val context: Context) {

    companion object {
        private const val TAG = "GameBootService"
    }

    private val _bootState = MutableLiveData<BootState>(BootState.Idle)
    val bootState: LiveData<BootState> = _bootState

    private val gameInstaller = GameInstaller(context)
    private val sandboxMorpher = SandboxMorpher()
    private val obbRouter = ObbRouter()
    private val tangoBridge = TangoBridge()
    private val downloadManager = DownloadManager(context)

    data class BootResult(
        val success: Boolean,
        val gameId: String,
        val message: String = ""
    )

    fun bootGame(gameInfo: GameInfo): BootResult {
        return try {
            val downloadedFile = downloadManager.getDownloadedFile(gameInfo.gameId)
            if (downloadedFile == null) {
                val msg = "Game $gameInfo.gameId not downloaded"
                Log.e(TAG, msg)
                _bootState.postValue(BootState.Error(gameInfo.gameId, msg))
                return BootResult(false, gameInfo.gameId, msg)
            }
            step(gameInfo, downloadedFile)
        } catch (e: Exception) {
            val msg = "Boot failed: ${e.message}"
            Log.e(TAG, msg, e)
            _bootState.postValue(BootState.Error(gameInfo.gameId, msg))
            BootResult(false, gameInfo.gameId, msg)
        }
    }

    private fun step(gameInfo: GameInfo, downloadedFile: File): BootResult {
        val userId = resolveUserId()

        _bootState.postValue(BootState.Verifying(gameInfo.gameId))
        val verified = safeCall("IntegrityCheck") {
            gameInstaller.verifyIntegrity(downloadedFile, gameInfo.sha256)
        }
        if (verified != true) {
            downloadManager.deleteDownload(gameInfo.gameId)
            val msg = "SHA-256 integrity check failed for ${gameInfo.title}"
            _bootState.postValue(BootState.Error(gameInfo.gameId, msg))
            return BootResult(false, gameInfo.gameId, msg)
        }

        _bootState.postValue(BootState.Extracting(gameInfo.gameId))
        val extraction = safeCall("Extraction") {
            gameInstaller.extractGame(
                zippedFile = downloadedFile,
                gameId = gameInfo.gameId,
                onProgress = { pct ->
                    _bootState.postValue(BootState.Extracting(gameInfo.gameId, pct))
                }
            )
        } ?: run {
            val msg = "Game extraction failed"
            _bootState.postValue(BootState.Error(gameInfo.gameId, msg))
            return BootResult(false, gameInfo.gameId, msg)
        }

        _bootState.postValue(BootState.Morphing(gameInfo.gameId))
        safeCall("SandboxMorphing") {
            sandboxMorpher.applySpoofing(gameInfo, userId)
        }

        _bootState.postValue(BootState.RoutingObb(gameInfo.gameId))
        safeCall("ObbRouting") {
            obbRouter.routeObb(gameInfo, extraction.extractedDir, userId)
        }

        _bootState.postValue(BootState.InitializingTranslation(gameInfo.gameId))
        val translationConfig = safeCall("TranslationInit") {
            tangoBridge.initializeForGame(gameInfo.architectureType)
        }
        if (translationConfig?.status == TangoBridge.TranslationStatus.TANGO_READY) {
            Log.i(TAG, "Tango Core translation active for ${gameInfo.gameId}")
        }

        _bootState.postValue(BootState.Installing(gameInfo.gameId))
        val apkFile = extraction.apkFile
        if (apkFile == null) {
            val msg = "No APK found in extracted package for ${gameInfo.title}"
            _bootState.postValue(BootState.Error(gameInfo.gameId, msg))
            cleanup(gameInfo, userId)
            return BootResult(false, gameInfo.gameId, msg)
        }
        val installResult = safeCall("InstallInSandbox") {
            BlackBoxCore.get().installPackageAsUser(apkFile, userId)
        }
        if (installResult == null || !installResult.success) {
            val msg = installResult?.msg ?: "Installation returned null"
            _bootState.postValue(BootState.Error(gameInfo.gameId, msg))
            cleanup(gameInfo, userId)
            return BootResult(false, gameInfo.gameId, msg)
        }

        _bootState.postValue(BootState.Launching(gameInfo.gameId))
        val launched = safeCall("LaunchGame") {
            BlackBoxCore.get().launchApk(gameInfo.gameId, userId)
        }
        if (launched != true) {
            val msg = "Failed to launch ${gameInfo.title} inside sandbox"
            _bootState.postValue(BootState.Error(gameInfo.gameId, msg))
            return BootResult(false, gameInfo.gameId, msg)
        }

        _bootState.postValue(BootState.Completed)
        return BootResult(true, gameInfo.gameId, "Game launched successfully")
    }

    private fun resolveUserId(): Int {
        return try {
            val users: List<BUserInfo> = BlackBoxCore.get().users
            if (users.isNotEmpty()) users[0].id else 0
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve user, defaulting to 0: ${e.message}")
            0
        }
    }

    private fun <T> safeCall(stepName: String, block: () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Step '$stepName' crashed: ${e.message}", e)
            null
        }
    }

    private fun cleanup(gameInfo: GameInfo, userId: Int) {
        try {
            gameInstaller.deleteGame(gameInfo.gameId)
            obbRouter.cleanupObb(gameInfo.gameId, userId)
            sandboxMorpher.resetSpoofing()
            tangoBridge.shutdown()
            Log.d(TAG, "Cleanup complete for ${gameInfo.gameId}")
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup error: ${e.message}")
        }
    }

    fun resetState() {
        _bootState.postValue(BootState.Idle)
        sandboxMorpher.resetSpoofing()
        tangoBridge.shutdown()
    }
}
