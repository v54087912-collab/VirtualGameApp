package top.niunaijun.blackboxa.data.game

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback
import top.niunaijun.blackboxa.data.network.model.GameInfo

/**
 * GameRenderBridge — Registers AppLifecycleCallback with BlackBoxCore
 * to intercept game Activity lifecycle and apply rendering fixes.
 *
 * IMPORTANT: All callbacks run inside the sandbox process.
 * Any unhandled exception WILL crash the game process.
 * Every callback body MUST be wrapped in try-catch.
 */
object GameRenderBridge {

    private const val TAG = "GameRenderBridge"

    private var registered = false
    private var currentGameInfo: GameInfo? = null

    /**
     * Register the lifecycle callback with BlackBoxCore.
     * Call this once during app initialization (e.g., in Application.onCreate).
     */
    fun register() {
        if (registered) {
            Log.d(TAG, "Already registered")
            return
        }

        try {
            BlackBoxCore.get().addAppLifecycleCallback(renderCallback)
            registered = true
            Log.i(TAG, "GameRenderBridge registered with BlackBoxCore")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register GameRenderBridge: ${e.message}", e)
        }
    }

    /**
     * Unregister the lifecycle callback.
     * Call this during app shutdown or when no longer needed.
     */
    fun unregister() {
        if (!registered) return

        try {
            BlackBoxCore.get().removeAppLifecycleCallback(renderCallback)
            registered = false
            Log.i(TAG, "GameRenderBridge unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister GameRenderBridge: ${e.message}")
        }
    }

    /**
     * Pre-inject graphics properties BEFORE game launch.
     * Call this from GameDownloadService before BlackBoxCore.launchApk().
     *
     * @param gameInfo The game being launched (provides apiLevel and arch type)
     */
    fun preInjectForGame(gameInfo: GameInfo) {
        currentGameInfo = gameInfo
        Log.i(TAG, "Pre-injecting graphics for: ${gameInfo.gameId} (API ${gameInfo.apiLevel})")

        // 1. Inject system properties for GPU/EGL compatibility
        try {
            GraphicsPropertyInjector.injectGraphicsProperties(gameInfo.apiLevel)
        } catch (e: Exception) {
            Log.w(TAG, "GraphicsPropertyInjector.injectGraphicsProperties failed: ${e.message}")
        }

        // 2. If 32-bit game on 64-bit host, inject translation layer props
        val is32Bit = gameInfo.architectureType.contains("32", ignoreCase = true)
        if (is32Bit && BlackBoxCore.is64Bit()) {
            try {
                GraphicsPropertyInjector.injectTranslationLayerProperties()
            } catch (e: Exception) {
                Log.w(TAG, "GraphicsPropertyInjector.injectTranslationLayerProperties failed: ${e.message}")
            }
        }

        // 3. Initialize NativeCore with the game's target API level
        try {
            top.niunaijun.blackbox.core.NativeCore.init(gameInfo.apiLevel)
            Log.d(TAG, "NativeCore initialized with API ${gameInfo.apiLevel}")
        } catch (e: Exception) {
            Log.w(TAG, "NativeCore init failed: ${e.message}")
        }
    }

    /**
     * Cleanup after game exits. Reset properties to defaults.
     */
    fun postCleanup() {
        currentGameInfo = null
        try {
            GraphicsPropertyInjector.resetProperties()
        } catch (e: Exception) {
            Log.w(TAG, "GraphicsPropertyInjector.resetProperties failed: ${e.message}")
        }
        Log.d(TAG, "Post-cleanup completed")
    }

    // ──────────────────────────────────────────────────
    //  AppLifecycleCallback — hooks into BlackBox sandbox
    // ──────────────────────────────────────────────────

    private val renderCallback = object : AppLifecycleCallback() {

        /**
         * Called BEFORE the game's APK is launched.
         * Second chance to inject properties if preInjectForGame() wasn't called.
         */
        override fun beforeMainLaunchApk(packageName: String, userid: Int) {
            try {
                val game = currentGameInfo
                if (game != null && game.gameId == packageName) {
                    Log.d(TAG, "beforeMainLaunchApk: $packageName — properties already injected")
                    // Properties were pre-injected, but re-inject to be safe
                    GraphicsPropertyInjector.injectGraphicsProperties(game.apiLevel)
                }
            } catch (e: Exception) {
                Log.w(TAG, "beforeMainLaunchApk callback failed: ${e.message}")
            }
        }

        /**
         * Called AFTER the game's main Activity.onCreate().
         * This is where we configure the window for legacy rendering.
         * ALL operations MUST be wrapped in try-catch to prevent game crash.
         */
        override fun afterMainActivityOnCreate(activity: Activity) {
            try {
                val game = currentGameInfo
                val apiLevel = game?.apiLevel ?: 10

                Log.i(TAG, "afterMainActivityOnCreate: ${activity.javaClass.simpleName}")

                // Configure the Activity window for legacy rendering (safe mode)
                LegacyRenderCompat.configureForLegacyGame(activity, apiLevel)
            } catch (e: Exception) {
                Log.w(TAG, "afterMainActivityOnCreate callback failed: ${e.message}")
            }
        }

        /**
         * Called when the game's Activity resumes.
         * Apply post-render fixes (e.g., re-apply layer types after view tree is built).
         * ALL operations MUST be wrapped in try-catch to prevent game crash.
         */
        override fun onActivityResumed(activity: Activity) {
            try {
                val game = currentGameInfo
                val apiLevel = game?.apiLevel ?: 10

                Log.d(TAG, "onActivityResumed: ${activity.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.w(TAG, "onActivityResumed callback failed: ${e.message}")
            }
        }

        /**
         * Called when the game's Activity is destroyed.
         * Cleanup rendering state.
         */
        override fun onActivityDestroyed(activity: Activity) {
            try {
                Log.d(TAG, "onActivityDestroyed: ${activity.javaClass.simpleName}")

                // Only full cleanup if this is the last activity
                if (activity.isFinishing) {
                    postCleanup()
                }
            } catch (e: Exception) {
                Log.w(TAG, "onActivityDestroyed callback failed: ${e.message}")
            }
        }
    }
}
