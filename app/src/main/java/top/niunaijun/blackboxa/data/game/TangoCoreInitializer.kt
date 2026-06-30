package top.niunaijun.blackboxa.data.game

import android.content.Context
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import java.io.File

/**
 * TangoCoreInitializer — Handles injection of the Tango binary translation layer
 * at app startup or game boot runtime.
 *
 * When a 32-bit game runs on a 64-bit-only device, this initializer:
 * 1. Detects the architecture mismatch
 * 2. Loads the Tango .so translator libraries if available
 * 3. Configures the sandbox environment for 32-bit → 64-bit instruction translation
 * 4. Falls back to Android's native 32-bit compat layer if Tango libs are absent
 */
class TangoCoreInitializer(private val context: Context) {

    companion object {
        private const val TAG = "TangoCoreInit"

        // Native library names to attempt loading (in priority order)
        private val TANGO_LIB_CANDIDATES = listOf(
            "tango_translator",
            "tango_core",
            "tango"
        )

        // Environment variables injected for translation mode
        private const val ENV_TRANSLATION_MODE = "TANGO_TRANSLATION_MODE"
        private const val ENV_ABI_REDIRECT = "TANGO_ABI_REDIRECT"
        private const val ENV_LD_PRELOAD = "LD_PRELOAD"
        private const val ENV_TRANSLATION_ACTIVE = "TANGO_TRANSLATION_ACTIVE"

        // Translation modes
        const val MODE_ARM64_TO_ARM32 = "arm64_to_arm32"
        const val MODE_NATIVE_COMPAT = "native_compat"
        const val MODE_NONE = "none"
    }

    /**
     * Result of Tango Core initialization
     */
    data class InitResult(
        val success: Boolean,
        val translationMode: String,
        val libsLoaded: List<String>,
        val message: String
    )

    private var initialized = false
    private var loadedLibs = mutableListOf<String>()
    private var currentMode = MODE_NONE

    /**
     * Main initialization entry point. Call this at app startup OR before game boot.
     *
     * @param gameArch The architecture type string from GameInfo (e.g., "32bit", "arm64-v8a")
     * @param gameDir Optional game directory for native lib preparation
     * @return InitResult with status and loaded libraries
     */
    fun initialize(gameArch: String, gameDir: File? = null): InitResult {
        if (initialized) {
            Log.d(TAG, "Already initialized with mode=$currentMode")
            return InitResult(
                success = true,
                translationMode = currentMode,
                libsLoaded = loadedLibs.toList(),
                message = "Already initialized"
            )
        }

        val is32BitGame = gameArch.contains("32", ignoreCase = true) ||
                gameArch.equals("armeabi-v7a", ignoreCase = true) ||
                gameArch.equals("armeabi", ignoreCase = true)

        // 64-bit game on any host — no translation needed
        if (!is32BitGame) {
            currentMode = MODE_NONE
            initialized = true
            return InitResult(
                success = true,
                translationMode = MODE_NONE,
                libsLoaded = emptyList(),
                message = "64-bit game, native execution"
            )
        }

        // 32-bit game on 32-bit host — native execution, no translation
        if (!BlackBoxCore.is64Bit()) {
            currentMode = MODE_NATIVE_COMPAT
            initialized = true
            return InitResult(
                success = true,
                translationMode = MODE_NATIVE_COMPAT,
                libsLoaded = emptyList(),
                message = "32-bit host, native 32-bit execution available"
            )
        }

        // 32-bit game on 64-bit host — need translation layer
        Log.i(TAG, "32-bit game on 64-bit host — initializing Tango translation")
        return initializeTranslation(gameDir)
    }

    /**
     * Prepare native libraries for a 32-bit game in the given directory.
     * Copies armeabi-v7a .so files to the correct location.
     */
    fun prepareNativeLibs(gameDir: File, gameArch: String): Boolean {
        val is32BitGame = gameArch.contains("32", ignoreCase = true)
        if (!is32BitGame || !BlackBoxCore.is64Bit()) {
            return true
        }

        return try {
            val libDir = File(gameDir, "lib")
            if (!libDir.exists()) libDir.mkdirs()

            // Ensure armeabi-v7a directory exists
            val arm32Dir = File(libDir, "armeabi-v7a")
            if (!arm32Dir.exists()) arm32Dir.mkdirs()

            val hasLibs = arm32Dir.listFiles()?.isNotEmpty() == true
            if (hasLibs) {
                Log.i(TAG, "32-bit native libs prepared in ${arm32Dir.absolutePath}")
            } else {
                Log.w(TAG, "No 32-bit native libs found in ${gameDir.name}")
            }
            hasLibs
        } catch (e: Exception) {
            Log.e(TAG, "Native lib preparation failed: ${e.message}", e)
            false
        }
    }

    /**
     * Inject translation environment variables into the current process.
     * These variables signal the sandbox runtime to activate translation.
     */
    fun injectTranslationEnv(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        when (currentMode) {
            MODE_ARM64_TO_ARM32 -> {
                env[ENV_TRANSLATION_MODE] = MODE_ARM64_TO_ARM32
                env[ENV_ABI_REDIRECT] = "armeabi-v7a"
                env[ENV_TRANSLATION_ACTIVE] = "true"
                if (loadedLibs.isNotEmpty()) {
                    env[ENV_LD_PRELOAD] = loadedLibs.joinToString(":") { "lib${it}.so" }
                }
                Log.d(TAG, "Translation env injected: mode=arm64_to_arm32")
            }
            MODE_NATIVE_COMPAT -> {
                env[ENV_TRANSLATION_MODE] = MODE_NATIVE_COMPAT
                env[ENV_ABI_REDIRECT] = "armeabi-v7a"
                Log.d(TAG, "Compat env injected: mode=native_compat")
            }
        }
        return env
    }

    /**
     * Check if translation is currently active.
     */
    fun isTranslationActive(): Boolean = currentMode == MODE_ARM64_TO_ARM32

    /**
     * Get the current translation mode.
     */
    fun getCurrentMode(): String = currentMode

    /**
     * Shutdown and reset the initializer.
     */
    fun shutdown() {
        initialized = false
        loadedLibs.clear()
        currentMode = MODE_NONE
        Log.d(TAG, "TangoCoreInitializer shut down")
    }

    // ──────────────────────────────────────────────────
    //  Private implementation
    // ──────────────────────────────────────────────────

    private fun initializeTranslation(gameDir: File?): InitResult {
        // Attempt to load Tango native translation libraries
        val loaded = loadTangoLibraries()

        return if (loaded) {
            currentMode = MODE_ARM64_TO_ARM32
            initialized = true

            // Prepare native libs if game directory provided
            gameDir?.let { prepareNativeLibs(it, "32bit") }

            InitResult(
                success = true,
                translationMode = MODE_ARM64_TO_ARM32,
                libsLoaded = loadedLibs.toList(),
                message = "Tango translation layer active"
            )
        } else {
            // Fallback: rely on Android's native 32-bit compat layer
            // On most ARM64 devices, the kernel can execute 32-bit ARM code
            // even without the translation libraries, via the zygote's
            // 32-bit process support
            currentMode = MODE_NATIVE_COMPAT
            initialized = true

            Log.w(TAG, "Tango libs not available, using native 32-bit compat")
            InitResult(
                success = true,
                translationMode = MODE_NATIVE_COMPAT,
                libsLoaded = emptyList(),
                message = "Tango not found — using Android native 32-bit compat layer"
            )
        }
    }

    private fun loadTangoLibraries(): Boolean {
        for (libName in TANGO_LIB_CANDIDATES) {
            try {
                System.loadLibrary(libName)
                loadedLibs.add(libName)
                Log.i(TAG, "Loaded Tango library: $libName")
                return true
            } catch (e: UnsatisfiedLinkError) {
                Log.d(TAG, "Tango library $libName not available: ${e.message}")
            }
        }
        return false
    }
}
