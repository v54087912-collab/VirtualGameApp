package top.niunaijun.blackboxa.data.game

import android.os.Build
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.NativeCore
import top.niunaijun.blackbox.core.env.BEnvironment
import top.niunaijun.blackboxa.data.network.model.GameInfo
import java.lang.reflect.Field
import java.lang.reflect.Modifier

class SandboxMorpher {

    companion object {
        private const val TAG = "SandboxMorpher"
    }

    fun applySpoofing(gameInfo: GameInfo, userId: Int) {
        try {
            spoofTargetSdk(gameInfo.apiLevel)
            spoofArchitectureDual(gameInfo.architectureType)
            spoofDeviceProperties()
            spoofNativeLibPath(gameInfo.architectureType)
            Log.d(TAG, "Sandbox morphing applied for ${gameInfo.gameId}")
        } catch (e: Exception) {
            Log.w(TAG, "Partial spoofing failure: ${e.message}", e)
        }
    }

    private fun spoofTargetSdk(targetApiLevel: Int) {
        try {
            NativeCore.init(targetApiLevel)
            Log.d(TAG, "NativeCore initialized with API level: $targetApiLevel")
        } catch (e: Exception) {
            Log.w(TAG, "NativeCore init failed, falling back to reflection: ${e.message}")
            reflectSpoofBuildField("SDK_INT", targetApiLevel)
        }
    }

    private fun spoofArchitectureDual(architectureType: String) {
        try {
            val is32BitGame = architectureType.contains("32", ignoreCase = true)

            if (is32BitGame && BlackBoxCore.is64Bit()) {
                reflectSpoofBuildField("SUPPORTED_32_BIT_ABIS", arrayOf("armeabi-v7a", "armeabi"))
                reflectSpoofBuildField("SUPPORTED_64_BIT_ABIS", arrayOf("arm64-v8a"))
                reflectSpoofBuildField("SUPPORTED_ABIS", arrayOf("arm64-v8a", "armeabi-v7a", "armeabi"))
                reflectSpoofBuildField("CPU_ABI", "armeabi-v7a")
                reflectSpoofBuildField("CPU_ABI2", "arm64-v8a")
                Log.d(TAG, "Dual ABI mode: 32-bit game on 64-bit host — ABIs set to arm64-v8a + armeabi-v7a")
            } else if (is32BitGame) {
                reflectSpoofBuildField("SUPPORTED_32_BIT_ABIS", arrayOf("armeabi-v7a", "armeabi"))
                reflectSpoofBuildField("SUPPORTED_ABIS", arrayOf("armeabi-v7a", "armeabi"))
                reflectSpoofBuildField("CPU_ABI", "armeabi-v7a")
                reflectSpoofBuildField("CPU_ABI2", "armeabi")
                Log.d(TAG, "Architecture spoofed to 32-bit (armeabi-v7a)")
            } else {
                reflectSpoofBuildField("SUPPORTED_64_BIT_ABIS", arrayOf("arm64-v8a"))
                reflectSpoofBuildField("SUPPORTED_ABIS", arrayOf("arm64-v8a"))
                reflectSpoofBuildField("CPU_ABI", "arm64-v8a")
                Log.d(TAG, "Architecture set to 64-bit (arm64-v8a)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Architecture spoof failed: ${e.message}")
        }
    }

    private fun spoofNativeLibPath(architectureType: String) {
        try {
            val is32BitGame = architectureType.contains("32", ignoreCase = true)
            if (is32BitGame && BlackBoxCore.is64Bit()) {
                reflectSpoofBuildField("ABI", "armeabi-v7a")
                Log.d(TAG, "Native lib ABI path set to armeabi-v7a for 32-bit compat")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native lib path spoof failed: ${e.message}")
        }
    }

    private fun spoofDeviceProperties() {
        try {
            reflectSpoofBuildField("BRAND", "Google")
            reflectSpoofBuildField("MANUFACTURER", "Google")
            reflectSpoofBuildField("MODEL", "Pixel 4 XL")
            reflectSpoofBuildField("DEVICE", "flame")
            reflectSpoofBuildField("PRODUCT", "flame")
            reflectSpoofBuildField("FINGERPRINT", "google/flame/flame:10/QP1A.191005.007/5903527:user/release-keys")
            Log.d(TAG, "Device properties spoofed to Pixel 4 XL")
        } catch (e: Exception) {
            Log.w(TAG, "Device property spoof failed: ${e.message}")
        }
    }

    private fun reflectSpoofBuildField(fieldName: String, value: Any) {
        try {
            val field: Field = Build::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            val modifiersField: Field = Field::class.java.getDeclaredField("accessFlags")
            modifiersField.isAccessible = true
            modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())
            field.set(null, value)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to spoof Build.$fieldName: ${e.message}")
        }
    }

    fun resetSpoofing() {
        try {
            NativeCore.init(Build.VERSION.SDK_INT)
            Log.d(TAG, "Spoofing reset to host API level: ${Build.VERSION.SDK_INT}")
        } catch (e: Exception) {
            Log.w(TAG, "Spoofing reset failed: ${e.message}")
        }
    }
}
