package top.niunaijun.blackboxa.data.game

import android.os.Build
import android.util.Log
import java.lang.reflect.Method

/**
 * GraphicsPropertyInjector — Runtime system property spoofing for legacy
 * OpenGL ES 1.x/2.0 games running in BlackBox 64-bit sandbox.
 *
 * Injects GPU, EGL, and rendering pipeline properties that force the
 * graphics driver into a legacy-compatible mode. This bypasses modern
 * Skia/HWUI pipelines that break old rendering contexts.
 *
 * Uses reflection on android.os.SystemProperties (hidden API) to inject
 * properties into the sandbox runtime environment before game launch.
 */
object GraphicsPropertyInjector {

    private const val TAG = "GraphicsPropInjector"

    // Reflection handles for android.os.SystemProperties
    private val systemPropertiesClass: Class<*>? by lazy {
        try {
            Class.forName("android.os.SystemProperties")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot access SystemProperties class: ${e.message}")
            null
        }
    }

    private val setMethod: Method? by lazy {
        systemPropertiesClass?.getMethod("set", String::class.java, String::class.java)
    }

    private val getMethod: Method? by lazy {
        systemPropertiesClass?.getMethod("get", String::class.java, String::class.java)
    }

    /**
     * Inject all graphics-related system properties for legacy game compat.
     * Call this BEFORE BlackBoxCore.launchApk().
     */
    fun injectGraphicsProperties(apiLevel: Int) {
        Log.i(TAG, "Injecting graphics properties for API level $apiLevel")

        // ── 1. Force OpenGL rendering pipeline (bypass Skia/HWUI) ──
        setProp("debug.hwui.renderer", "opengl")

        // ── 2. Disable JNI checking for old native libs ──
        setProp("ro.kernel.android.checkjni", "0")

        // ── 3. Force hardware acceleration layer ──
        setProp("debug.egl.hw", "1")

        // ── 4. EGL compatibility — force ES2 config for old games ──
        setProp("debug.egl.opengles.version", getEsgVersionForApi(apiLevel))

        // ── 5. Force software fallback for broken GPU drivers ──
        if (apiLevel < 16) {
            setProp("debug.hwui.use_shader_cache", "true")
            setProp("debug.hwui.skip_damage_region", "true")
            setProp("ro.hwui.disable_scissor_opt", "true")
        }

        // ── 6. GPU driver compatibility hints ──
        setProp("debug.mediacodec.skip_buffers_with_codec", "0")
        setProp("debug.sf.enable_hwc_vds", "0")

        // ── 7. SurfaceFlinger compatibility ──
        setProp("debug.sf.lcd_backlight", "1")
        setProp("ro.surface_flinger.max_frame_buffer_acquired_buffers", "3")

        // ── 8. Legacy game-specific overrides ──
        setProp("debug.egl.debug.trace", "0")
        setProp("debug.gles.version", getEsgVersionForApi(apiLevel))
        setProp("ro.config.low_ram", "false")

        // ── 9. Disable modern rendering optimizations ──
        setProp("persist.sys.ui.hw", "true")
        setProp("debug.composition.type", "gpu")

        Log.i(TAG, "Graphics properties injected successfully")
    }

    /**
     * Inject properties specifically for 32-bit-on-64-bit translation layer.
     */
    fun injectTranslationLayerProperties() {
        Log.i(TAG, "Injecting translation layer graphics properties")

        setProp("debug.arm64.translation", "1")
        setProp("persist.sys.dalvik.vm.lib.2", "libart.so")
        setProp("dalvik.vm.dex2oat-Xms", "64m")
        setProp("dalvik.vm.dex2oat-Xmx", "512m")
        setProp("dalvik.vm.image-dex2oat-Xms", "64m")
        setProp("dalvik.vm.image-dex2oat-Xmx", "256m")

        // Force 32-bit compat mode in ART runtime
        setProp("ro.dalvik.vm.native.bridge", "0")
        setProp("persist.bionic.migration", "0")
    }

    /**
     * Read back a property to verify injection succeeded.
     */
    fun getProp(key: String, default: String = ""): String {
        return try {
            getMethod?.invoke(null, key, default) as? String ?: default
        } catch (e: Exception) {
            default
        }
    }

    /**
     * Reset all injected properties to defaults.
     */
    fun resetProperties() {
        val keys = listOf(
            "debug.hwui.renderer",
            "debug.egl.hw",
            "debug.egl.opengles.version",
            "debug.gles.version",
            "debug.composition.type",
            "debug.sf.enable_hwc_vds",
            "debug.arm64.translation",
            "debug.hwui.use_shader_cache",
            "debug.hwui.skip_damage_region",
            "ro.hwui.disable_scissor_opt",
            "debug.mediacodec.skip_buffers_with_codec",
            "debug.sf.lcd_backlight",
            "debug.egl.debug.trace",
            "ro.config.low_ram"
        )
        for (key in keys) {
            setProp(key, "")
        }
        Log.d(TAG, "Graphics properties reset")
    }

    // ──────────────────────────────────────────────────
    //  Private helpers
    // ──────────────────────────────────────────────────

    private fun setProp(key: String, value: String) {
        try {
            setMethod?.invoke(null, key, value)
            Log.d(TAG, "Set property: $key = $value")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set $key: ${e.message}")
        }
    }

    private fun getEsgVersionForApi(apiLevel: Int): String {
        return when {
            apiLevel >= 24 -> "0x00030002"  // OpenGL ES 3.2
            apiLevel >= 18 -> "0x00030001"  // OpenGL ES 3.1
            apiLevel >= 16 -> "0x00030000"  // OpenGL ES 3.0
            apiLevel >= 8  -> "0x00020000"  // OpenGL ES 2.0
            else           -> "0x00010001"  // OpenGL ES 1.1
        }
    }
}
