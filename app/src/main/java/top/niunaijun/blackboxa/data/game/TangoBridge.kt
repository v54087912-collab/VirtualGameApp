package top.niunaijun.blackboxa.data.game

import android.util.Log
import java.io.File

class TangoBridge {

    companion object {
        private const val TAG = "TangoBridge"
        private const val TANGO_LIB_NAME = "tango_translator"
        private const val TANGO_FALLBACK_LIB = "tango_core"
        private val TANGO_LIB_PATHS = listOf(
            "libtango_translator.so",
            "libtango_core.so",
            "libtango.so"
        )
    }

    enum class TranslationStatus {
        NOT_NEEDED,
        TANGO_READY,
        FALLBACK_NATIVE,
        UNAVAILABLE
    }

    data class TranslationConfig(
        val status: TranslationStatus,
        val nativeLibsLoaded: List<String> = emptyList(),
        val message: String = ""
    )

    private var currentStatus: TranslationStatus = TranslationStatus.NOT_NEEDED
    private var loadedLibs: MutableList<String> = mutableListOf()

    fun initializeForGame(architectureType: String): TranslationConfig {
        return try {
            if (!architectureType.contains("32", ignoreCase = true) &&
                !architectureType.contains("86", ignoreCase = true)
            ) {
                currentStatus = TranslationStatus.NOT_NEEDED
                return TranslationConfig(
                    status = TranslationStatus.NOT_NEEDED,
                    message = "64-bit game, no translation needed"
                )
            }
            val loaded = tryLoadTangoLibrary()
            if (loaded) {
                currentStatus = TranslationStatus.TANGO_READY
                TranslationConfig(
                    status = TranslationStatus.TANGO_READY,
                    nativeLibsLoaded = loadedLibs.toList(),
                    message = "Tango Core translation layer initialized"
                )
            } else {
                currentStatus = TranslationStatus.FALLBACK_NATIVE
                TranslationConfig(
                    status = TranslationStatus.FALLBACK_NATIVE,
                    nativeLibsLoaded = loadedLibs.toList(),
                    message = "Tango not found, using BlackBox native 32-bit fallback"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation init failed: ${e.message}", e)
            currentStatus = TranslationStatus.UNAVAILABLE
            TranslationConfig(
                status = TranslationStatus.UNAVAILABLE,
                message = "Translation init error: ${e.message}"
            )
        }
    }

    private fun tryLoadTangoLibrary(): Boolean {
        val libNames = listOf(TANGO_LIB_NAME, TANGO_FALLBACK_LIB)
        for (libName in libNames) {
            try {
                System.loadLibrary(libName)
                loadedLibs.add(libName)
                Log.i(TAG, "Loaded translation library: $libName")
                return true
            } catch (e: UnsatisfiedLinkError) {
                Log.d(TAG, "Library $libName not available: ${e.message}")
            }
        }
        return false
    }

    fun isTranslationActive(): Boolean {
        return currentStatus == TranslationStatus.TANGO_READY
    }

    fun shutdown() {
        currentStatus = TranslationStatus.NOT_NEEDED
        loadedLibs.clear()
        Log.d(TAG, "Tango Bridge shut down")
    }
}
