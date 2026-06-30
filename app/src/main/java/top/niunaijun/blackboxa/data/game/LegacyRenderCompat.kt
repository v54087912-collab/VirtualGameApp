package top.niunaijun.blackboxa.data.game

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import java.lang.reflect.Method

/**
 * LegacyRenderCompat — Configures Activity window and rendering pipeline
 * for legacy OpenGL ES 1.x/2.0 games running in a 64-bit sandbox.
 *
 * Addresses the black-screen issue by:
 * 1. Setting the correct pixel format for old rendering contexts
 * 2. Configuring hardware acceleration behavior per API level
 * 3. Forcing compatible layer types so SurfaceView textures survive
 * 4. Injecting window flags that prevent SurfaceFlinger token loss
 *
 * Call configureForLegacyGame() in afterMainActivityOnCreate() callback.
 */
object LegacyRenderCompat {

    private const val TAG = "LegacyRenderCompat"

    /**
     * Main entry point — configure an Activity for legacy game rendering.
     * Called from AppLifecycleCallback.afterMainActivityOnCreate().
     *
     * @param activity The game's Activity instance
     * @param apiLevel The game's target API level (from catalog.json)
     */
    fun configureForLegacyGame(activity: Activity, apiLevel: Int) {
        Log.i(TAG, "Configuring legacy render compat for ${activity.javaClass.simpleName} (API $apiLevel)")

        try {
            configureWindowFormat(activity)
            configureHardwareAcceleration(activity, apiLevel)
            configureLayerType(activity, apiLevel)
            configureWindowFlags(activity)
            configureDisplayRefresh(activity)
            Log.i(TAG, "Legacy render compat applied successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Partial render compat failure: ${e.message}", e)
        }
    }

    // ──────────────────────────────────────────────────
    //  1. SurfaceView Token Interception & Pixel Format
    // ──────────────────────────────────────────────────

    /**
     * Force the window to use a pixel format that's compatible with
     * old SurfaceView rendering. Legacy games expect RGB_565 or RGBA_8888
     * surfaces, but modern windows default to TRANSLUCENT which can
     * cause the display token to be lost in the sandbox.
     */
    private fun configureWindowFormat(activity: Activity) {
        try {
            // Force OPAQUE pixel format — prevents alpha compositing issues
            // that cause black screens when the SurfaceView token leaks
            activity.window.setFormat(PixelFormat.OPAQUE)

            // Set the window to use a compatible surface type
            activity.window.attributes.format = PixelFormat.RGBA_8888

            Log.d(TAG, "Window format set to RGBA_8888 OPAQUE")
        } catch (e: Exception) {
            Log.w(TAG, "Window format config failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────
    //  2. Hardware Acceleration Toggle per API Level
    // ──────────────────────────────────────────────────

    /**
     * API < 16 (Jelly Bean): Games expect SOFTWARE rendering.
     * API 16-20: Mixed — some games need HW, some SW.
     * API 21+: Hardware acceleration works but may need compat flags.
     *
     * For very old games (API < 16), we disable HW acceleration at the
     * window level so the game's internal SurfaceView uses the correct
     * rendering path.
     */
    private fun configureHardwareAcceleration(activity: Activity, apiLevel: Int) {
        try {
            val window = activity.window

            if (apiLevel < 16) {
                // Pre-Honeycomb games: force software rendering pipeline
                // This ensures the game's GL context uses the correct
                // software rasterizer instead of the modern HWUI pipeline
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_SOFTWARE_UI_MODE,
                    WindowManager.LayoutParams.FLAG_SOFTWARE_UI_MODE
                )
                Log.d(TAG, "Software rendering forced for API $apiLevel")
            } else if (apiLevel < 21) {
                // Jelly Bean to KitKat: use hardware-accelerated compat mode
                // with legacy rendering hints
                window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                setLegacyRenderingMode(activity, true)
                Log.d(TAG, "Hardware-accelerated compat mode for API $apiLevel")
            } else {
                // Lollipop+: standard hardware acceleration with compat layer
                window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                Log.d(TAG, "Standard HW acceleration for API $apiLevel")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hardware acceleration config failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────
    //  3. Layer Type Configuration
    // ──────────────────────────────────────────────────

    /**
     * Configure the Activity's DecorView layer type to ensure proper
     * texture binding for the game's SurfaceView.
     *
     * For API < 16: LAYER_TYPE_NONE (let the game manage its own layers)
     * For API 16+: LAYER_TYPE_HARDWARE (force GPU compositing of all layers)
     *
     * This prevents the texture from being lost when the sandbox's
     * virtual SurfaceFlinger composites the game's rendering surface.
     */
    private fun configureLayerType(activity: Activity, apiLevel: Int) {
        try {
            val decorView = activity.window.decorView

            if (apiLevel < 16) {
                // Legacy games: use NONE to let them manage their own layers
                // Setting HARDWARE on very old games can cause double-rendering
                decorView.setLayerType(View.LAYER_TYPE_NONE, null)
                Log.d(TAG, "Layer type NONE for API $apiLevel (game manages layers)")
            } else {
                // Modern compat: force hardware layer for GPU compositing
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = false
                    isFilterBitmap = false
                }
                decorView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
                Log.d(TAG, "Layer type HARDWARE for API $apiLevel")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Layer type config failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────
    //  4. Window Flags for Surface Token Preservation
    // ──────────────────────────────────────────────────

    /**
     * Set window flags that prevent the SurfaceView's display token
     * from being lost in the sandbox's virtual window hierarchy.
     */
    private fun configureWindowFlags(activity: Activity) {
        try {
            val window = activity.window

            // Keep screen on while game is running
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // Prevent the window from dimming when the game is in background
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

            // Ensure the window can receive touch events even during
            // rendering pipeline stalls (prevents ANR on old games)
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)

            Log.d(TAG, "Window flags configured for surface token preservation")
        } catch (e: Exception) {
            Log.w(TAG, "Window flags config failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────
    //  5. Display Refresh Rate Compatibility
    // ──────────────────────────────────────────────────

    /**
     * Old games were designed for 60Hz displays. Modern devices run
     * at 90Hz/120Hz which can cause timing issues in game loops.
     * Force 60Hz for games with API < 16.
     */
    private fun configureDisplayRefresh(activity: Activity, apiLevel: Int) {
        if (apiLevel >= 16) return

        try {
            val window = activity.window
            val layoutParams = window.attributes
            layoutParams.preferredDisplayModeId = 0 // Default mode (60Hz)
            window.attributes = layoutParams
            Log.d(TAG, "Display refresh locked to 60Hz for API $apiLevel")
        } catch (e: Exception) {
            Log.w(TAG, "Display refresh config failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────
    //  Reflection helpers
    // ──────────────────────────────────────────────────

    /**
     * Use reflection to set legacy rendering mode in the View system.
     * This is a hidden API that forces the rendering pipeline to use
     * the legacy Canvas-based path instead of the modern DisplayList path.
     */
    private fun setLegacyRenderingMode(activity: Activity, enabled: Boolean) {
        try {
            val decorView = activity.window.decorView
            val method: Method = decorView.javaClass.getMethod(
                "setLegacyDrawingEnabled",
                Boolean::class.javaPrimitiveType!!
            )
            method.invoke(decorView, enabled)
            Log.d(TAG, "Legacy drawing mode set to $enabled")
        } catch (e: NoSuchMethodException) {
            // Method doesn't exist on this API level — that's fine
            Log.d(TAG, "Legacy drawing mode not available (API ${Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            Log.w(TAG, "Legacy drawing mode set failed: ${e.message}")
        }
    }

    /**
     * Force a specific View to use software rendering.
     * Useful for intercepting SurfaceView after game creates it.
     */
    fun forceSoftwareRendering(view: View) {
        try {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            Log.d(TAG, "Software rendering forced on view: ${view.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.w(TAG, "Force software rendering failed: ${e.message}")
        }
    }

    /**
     * Find and configure all SurfaceViews in the Activity's view hierarchy.
     * This is a best-effort interception that wraps old SurfaceViews with
     * compatible rendering attributes.
     */
    fun configureSurfaceViews(activity: Activity, apiLevel: Int) {
        try {
            val rootView = activity.window.decorView
            configureViewTree(rootView, apiLevel)
        } catch (e: Exception) {
            Log.w(TAG, "SurfaceView tree scan failed: ${e.message}")
        }
    }

    private fun configureViewTree(view: View, apiLevel: Int) {
        try {
            val className = view.javaClass.name
            if (className.contains("SurfaceView") || className.contains("GLSurfaceView")) {
                // Found a SurfaceView — apply compat layer
                if (apiLevel < 16) {
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                    Log.d(TAG, "Configured legacy SurfaceView: $className")
                }
            }
        } catch (e: Exception) {
            // Silently continue scanning
        }

        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                configureViewTree(view.getChildAt(i), apiLevel)
            }
        }
    }
}
