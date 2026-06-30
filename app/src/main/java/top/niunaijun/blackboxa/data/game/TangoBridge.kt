package top.niunaijun.blackboxa.data.game

import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.NativeCore
import java.io.File
import java.io.FileOutputStream

class TangoBridge {

    companion object {
        private const val TAG = "TangoBridge"
        private const val TANGO_LIB_PRIMARY = "tango_translator"
        private const val TANGO_LIB_SECONDARY = "tango_core"
        private const val TANGO_LIB_FALLBACK = "tango"
        private const val NATIVE_LIB_DIR = "lib"
        private val SUPPORTED_32BIT_ABIS = listOf("armeabi-v7a", "armeabi")
        private val SUPPORTED_64BIT_ABIS = listOf("arm64-v8a")
    }

    enum class TranslationStatus {
        NOT_NEEDED,
        TANGO_READY,
        NATIVE_COMPAT,
        FALLBACK_EMULATED,
        UNAVAILABLE
    }

    data class TranslationConfig(
        val status: TranslationStatus,
        val nativeLibsLoaded: List<String> = emptyList(),
        val hostArch: String = "",
        val targetArch: String = "",
        val message: String = ""
    )

    private var currentStatus: TranslationStatus = TranslationStatus.NOT_NEEDED
    private var loadedLibs: MutableList<String> = mutableListOf()
    private var translationActive = false

    fun initializeForGame(architectureType: String): TranslationConfig {
        return try {
            val hostArch = resolveHostArch()
            val is32BitGame = architectureType.contains("32", ignoreCase = true) ||
                    architectureType.contains("86", ignoreCase = true) ||
                    architectureType.equals("armeabi-v7a", ignoreCase = true) ||
                    architectureType.equals("armeabi", ignoreCase = true)

            if (!is32BitGame) {
                currentStatus = TranslationStatus.NOT_NEEDED
                return TranslationConfig(
                    status = TranslationStatus.NOT_NEEDED,
                    hostArch = hostArch,
                    targetArch = architectureType,
                    message = "64-bit game, native execution supported"
                )
            }

            if (!BlackBoxCore.is64Bit()) {
                currentStatus = TranslationStatus.NATIVE_COMPAT
                translationActive = false
                return TranslationConfig(
                    status = TranslationStatus.NATIVE_COMPAT,
                    hostArch = hostArch,
                    targetArch = architectureType,
                    message = "32-bit host, native 32-bit execution available"
                )
            }

            Log.i(TAG, "32-bit game on 64-bit host — initializing translation layer")
            val loaded = tryLoadTranslationLibs()
            if (loaded) {
                currentStatus = TranslationStatus.TANGO_READY
                translationActive = true
                TranslationConfig(
                    status = TranslationStatus.TANGO_READY,
                    nativeLibsLoaded = loadedLibs.toList(),
                    hostArch = hostArch,
                    targetArch = architectureType,
                    message = "Translation layer active: 32-bit → 64-bit binary translation enabled"
                )
            } else {
                currentStatus = TranslationStatus.FALLBACK_EMULATED
                translationActive = false
                TranslationConfig(
                    status = TranslationStatus.FALLBACK_EMULATED,
                    hostArch = hostArch,
                    targetArch = architectureType,
                    message = "No translation lib found, using Android native 32-bit compat layer"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation init failed: ${e.message}", e)
            currentStatus = TranslationStatus.UNAVAILABLE
            translationActive = false
            TranslationConfig(
                status = TranslationStatus.UNAVAILABLE,
                message = "Translation init error: ${e.message}"
            )
        }
    }

    fun prepareNativeLibs(gameDir: File, architectureType: String): Boolean {
        return try {
            val is32BitGame = architectureType.contains("32", ignoreCase = true)
            if (!is32BitGame || !BlackBoxCore.is64Bit()) {
                return true
            }

            val libDir = File(gameDir, NATIVE_LIB_DIR)
            if (!libDir.exists()) libDir.mkdirs()

            var hasLibs = false
            for (abi in SUPPORTED_32BIT_ABIS) {
                val abiDir = File(libDir, abi)
                if (abiDir.exists() && abiDir.listFiles()?.isNotEmpty() == true) {
                    hasLibs = true
                    Log.d(TAG, "Found 32-bit libs in $abi")
                }
            }

            if (!hasLibs) {
                Log.w(TAG, "No 32-bit native libs found in game package")
            }

            hasLibs
        } catch (e: Exception) {
            Log.e(TAG, "Native lib preparation failed: ${e.message}", e)
            false
        }
    }

    fun injectTranslationEnv(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        if (currentStatus == TranslationStatus.TANGO_READY) {
            env["TANGO_TRANSLATION_MODE"] = "arm64_to_arm32"
            env["TANGO_ABI_REDIRECT"] = "armeabi-v7a"
            env["LD_PRELOAD"] = "libtango_translator.so"
            Log.d(TAG, "Translation environment variables set")
        } else if (currentStatus == TranslationStatus.FALLBACK_EMULATED) {
            env["TANGO_TRANSLATION_MODE"] = "native_compat"
            env["TANGO_ABI_REDIRECT"] = "armeabi-v7a"
            Log.d(TAG, "Fallback compatibility environment set")
        }
        return env
    }

    fun isTranslationActive(): Boolean = translationActive

    fun getCurrentStatus(): TranslationStatus = currentStatus

    fun shutdown() {
        currentStatus = TranslationStatus.NOT_NEEDED
        translationActive = false
        loadedLibs.clear()
        Log.d(TAG, "Tango Bridge shut down")
    }

    private fun resolveHostArch(): String {
        return try {
            val abis = Build.SUPPORTED_ABIS
            if (abis.isNotEmpty()) abis[0] else "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun tryLoadTranslationLibs(): Boolean {
        val libNames = listOf(TANGO_LIB_PRIMARY, TANGO_LIB_SECONDARY, TANGO_LIB_FALLBACK)
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
}
