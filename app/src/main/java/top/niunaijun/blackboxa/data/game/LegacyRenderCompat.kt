package top.niunaijun.blackboxa.data.game

import android.app.Activity
import android.util.Log

/**
 * LegacyRenderCompat — Minimal rendering configuration for legacy games.
 *
 * IMPORTANT: All operations MUST be individually safe and wrapped in try-catch.
 * This code runs inside the sandbox process via AppLifecycleCallback.
 * Any unhandled exception WILL crash the game process.
 *
 * Only safe, non-invasive operations should be performed here.
 * DO NOT modify the game's window, view hierarchy, or rendering pipeline.
 */
object LegacyRenderCompat {

    private const val TAG = "LegacyRenderCompat"

    /**
     * Main entry point — minimal configuration for legacy game rendering.
     * Called from AppLifecycleCallback.afterMainActivityOnCreate().
     *
     * @param activity The game's Activity instance
     * @param apiLevel The game's target API level (from catalog.json)
     */
    fun configureForLegacyGame(activity: Activity, apiLevel: Int) {
        try {
            Log.i(TAG, "Configuring legacy render compat for ${activity.javaClass.simpleName} (API $apiLevel)")
            // NO-OP: Do not modify the game's Activity window or view hierarchy.
            // The game expects full control over its rendering surface.
            // Any modifications from the host process will crash the game.
            Log.i(TAG, "Legacy render compat — no modifications applied (safe mode)")
        } catch (e: Exception) {
            Log.w(TAG, "Legacy render compat failed: ${e.message}")
        }
    }

    /**
     * Find and configure all SurfaceViews in the Activity's view hierarchy.
     * This is a no-op for safety — do not modify foreign view hierarchy.
     */
    fun configureSurfaceViews(activity: Activity, apiLevel: Int) {
        try {
            Log.d(TAG, "configureSurfaceViews: no-op (safe mode)")
        } catch (e: Exception) {
            Log.w(TAG, "configureSurfaceViews failed: ${e.message}")
        }
    }
}
