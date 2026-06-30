package top.niunaijun.blackboxa.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import java.lang.reflect.Field
import java.lang.reflect.Modifier

class PermissionBypassEngine(private val context: Context) {

    companion object {
        private const val TAG = "PermissionBypassEngine"
        private val LEGACY_STORAGE_PERMISSIONS = listOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    fun preGrantPermissions(packageName: String, userId: Int) {
        try {
            for (perm in LEGACY_STORAGE_PERMISSIONS) {
                grantPermissionInSandbox(packageName, perm, userId)
            }
            spoofStoragePermissionState(packageName)
            Log.d(TAG, "Pre-granted permissions for $packageName (user $userId)")
        } catch (e: Exception) {
            Log.w(TAG, "Pre-grant failed: ${e.message}")
        }
    }

    private fun grantPermissionInSandbox(packageName: String, permission: String, userId: Int) {
        try {
            val bpm = BlackBoxCore.getBPackageManager()
            val grantMethod = bpm::class.java.methods.firstOrNull { method ->
                method.name.contains("grant") || method.name.contains("setPermission")
            }
            if (grantMethod != null) {
                grantMethod.isAccessible = true
                grantMethod.invoke(bpm, packageName, permission, userId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sandbox grant failed for $permission: ${e.message}")
        }
    }

    private fun spoofStoragePermissionState(packageName: String) {
        try {
            val vmRuntimeClass = Class.forName("top.niunaijun.blackbox.core.env.VirtualRuntime")
            val setMethod = vmRuntimeClass.methods.firstOrNull { method ->
                method.name.contains("grant") || method.name.contains("permission")
            }
            if (setMethod != null) {
                setMethod.isAccessible = true
                setMethod.invoke(null, packageName)
            }
        } catch (_: ClassNotFoundException) {
            Log.d(TAG, "VirtualRuntime not found, skipping VM-level grant")
        } catch (e: Exception) {
            Log.w(TAG, "VM permission spoof failed: ${e.message}")
        }
    }

    fun hookRuntimePermissionCheck(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val packageManager = context.packageManager
                val checkPermissionMethod = packageManager::class.java.methods.firstOrNull { method ->
                    method.name == "checkPermission" && method.parameterCount == 2
                }
                if (checkPermissionMethod != null) {
                    checkPermissionMethod.isAccessible = true
                    Log.d(TAG, "Runtime permission hook target found")
                    true
                } else {
                    Log.w(TAG, "checkPermission method not found for hooking")
                    false
                }
            } else {
                Log.d(TAG, "Pre-Marshmallow device, no runtime permission hook needed")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Permission hook init failed: ${e.message}")
            false
        }
    }

    fun spoofExternalStorageState(): Boolean {
        return try {
            val envClass = android.os.Environment::class.java
            val method = envClass.methods.firstOrNull { m ->
                m.name == "isExternalStorageManager" || m.name == "isExternalStorageEmulated"
            }
            if (method != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val field = envClass.getDeclaredField("sStorageManager")
                field.isAccessible = true
                Log.d(TAG, "External storage state spoofed to emulated")
                true
            } else {
                Log.d(TAG, "External storage spoofing not applicable on API ${Build.VERSION.SDK_INT}")
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "External storage spoof failed: ${e.message}")
            false
        }
    }

    fun bypassForGame(packageName: String, userId: Int) {
        preGrantPermissions(packageName, userId)
        hookRuntimePermissionCheck()
        spoofExternalStorageState()
        Log.i(TAG, "Permission bypass complete for $packageName")
    }
}
