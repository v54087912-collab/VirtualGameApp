package io.twoyi.engine

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*

/**
 * VirtualMachineService - Runs in isolated :virtual_machine subprocess
 *
 * This service hosts the TwoYi guest VM in a separate process.
 * If the guest crashes, the host app remains unaffected.
 *
 * Process isolation:
 * - Host app: io.twoyi (main process)
 * - Guest VM: io.twoyi:virtual_machine (isolated process)
 */
class VirtualMachineService : Service() {

    companion object {
        private const val TAG = "VirtualMachineService"

        const val ACTION_START = "io.twoyi.engine.ACTION_START_VM"
        const val ACTION_STOP = "io.twoyi.engine.ACTION_STOP_VM"
        const val ACTION_SUSPEND = "io.twoyi.engine.ACTION_SUSPEND_VM"
        const val ACTION_RESUME = "io.twoyi.engine.ACTION_RESUME_VM"

        const val EXTRA_GAME_PACKAGE = "game_package_path"
        const val EXTRA_SURFACE_HANDLE = "surface_handle"
        const val EXTRA_ROM_DIR = "rom_dir"
        const val EXTRA_WORK_DIR = "work_dir"
    }

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Service coroutine error", throwable)
            handleCrash(throwable.message ?: "Unknown error")
        }
    )

    private var engineManager: ContainerEngineManager? = null
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VirtualMachineService created in process: ${android.os.Process.myPid()}")
        engineManager = ContainerEngineManager.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val gamePackage = intent.getStringExtra(EXTRA_GAME_PACKAGE)
                val surfaceHandle = intent.getLongExtra(EXTRA_SURFACE_HANDLE, -1L)

                if (gamePackage != null && surfaceHandle != -1L) {
                    startVirtualMachine(gamePackage, surfaceHandle)
                } else {
                    Log.e(TAG, "Invalid intent extras")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopVirtualMachine()
            }
            ACTION_SUSPEND -> {
                suspendVirtualMachine()
            }
            ACTION_RESUME -> {
                resumeVirtualMachine()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Start the virtual machine with given game package
     */
    private fun startVirtualMachine(gamePackagePath: String, surfaceHandle: Long) {
        if (isRunning) {
            Log.w(TAG, "VM already running")
            return
        }

        serviceScope.launch {
            try {
                Log.i(TAG, "Starting virtual machine...")
                Log.i(TAG, "Game: $gamePackagePath")
                Log.i(TAG, "Surface: $surfaceHandle")

                val success = engineManager?.bootContainer(surfaceHandle, gamePackagePath) ?: false

                if (success) {
                    isRunning = true
                    Log.i(TAG, "Virtual machine started successfully")

                    // Notify host process
                    notifyHost(VmStatus.RUNNING)
                } else {
                    Log.e(TAG, "Failed to start virtual machine")
                    notifyHost(VmStatus.FAILED)
                    stopSelf()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error starting VM", e)
                handleCrash(e.message ?: "Start failed")
            }
        }
    }

    /**
     * Stop the virtual machine gracefully
     */
    private fun stopVirtualMachine() {
        if (!isRunning) return

        serviceScope.launch {
            try {
                Log.i(TAG, "Stopping virtual machine...")
                engineManager?.shutdownContainer()
                isRunning = false
                notifyHost(VmStatus.STOPPED)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping VM", e)
            } finally {
                stopSelf()
            }
        }
    }

    /**
     * Suspend VM for background optimization
     */
    private fun suspendVirtualMachine() {
        if (!isRunning) return

        serviceScope.launch {
            Log.i(TAG, "Suspending virtual machine...")
            engineManager?.suspendContainer()
            notifyHost(VmStatus.SUSPENDED)
        }
    }

    /**
     * Resume suspended VM
     */
    private fun resumeVirtualMachine() {
        serviceScope.launch {
            Log.i(TAG, "Resuming virtual machine...")
            engineManager?.resumeContainer()
            notifyHost(VmStatus.RUNNING)
        }
    }

    /**
     * Handle VM crash gracefully
     * Host app remains alive, user can restart
     */
    private fun handleCrash(reason: String) {
        Log.e(TAG, "VM CRASHED: $reason")
        isRunning = false
        engineManager?.handleGuestCrash(reason)
        notifyHost(VmStatus.CRASHED)
    }

    /**
     * Notify host process about VM status changes
     */
    private fun notifyHost(status: VmStatus) {
        val intent = Intent("io.twoyi.engine.VM_STATUS_CHANGED").apply {
            putExtra("status", status.name)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "VirtualMachineService destroyed")
        serviceScope.cancel()
        engineManager?.shutdownContainer()
        super.onDestroy()
    }

    enum class VmStatus {
        RUNNING, STOPPED, SUSPENDED, CRASHED, FAILED
    }
}
