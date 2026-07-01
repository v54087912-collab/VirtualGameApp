package io.twoyi.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * ContainerEngineManager - Core manager for TwoYi Container Runtime Environment
 *
 * Handles:
 * 1. Base ROM verification and integrity checks
 * 2. Silent ROM streaming from GitHub Releases
 * 3. Container lifecycle management (boot, mount, surface binding)
 * 4. Multi-process isolation for guest VM
 */
class ContainerEngineManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ContainerEngineManager"

        // GitHub Releases URL for base ROM
        private const val ROM_BASE_URL =
            "https://github.com/Saini920/Games-Releases-virtual-00282hehe/releases/download/v1.0.0"

        // ROM file names
        private const val ROM_BUNDLE_NAME = "twoyi-base-rom.zip"
        private const val SYSTEM_IMG = "system.img"
        private const val RAMDISK_IMG = "ramdisk.img"
        private const val KERNEL_IMG = "kernel.img"
        private const val INITRD_IMG = "initrd.img"

        // Checksum file for verification
        private const val CHECKSUM_FILE = "checksums.sha256"

        // Process name for guest VM
        const val PROCESS_VIRTUAL_MACHINE = ":virtual_machine"

        @Volatile
        private var instance: ContainerEngineManager? = null

        fun getInstance(context: Context): ContainerEngineManager {
            return instance ?: synchronized(this) {
                instance ?: ContainerEngineManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: State Management
    // ─────────────────────────────────────────────────────────────────────

    sealed class EngineState {
        object Idle : EngineState()
        object Verifying : EngineState()
        object Downloading : EngineState()
        data class DownloadProgress(val progress: Int, val bytesDownloaded: Long) : EngineState()
        object Extracting : EngineState()
        object Ready : EngineState()
        data class Error(val message: String, val exception: Throwable? = null) : EngineState()
    }

    sealed class ContainerState {
        object Shutdown : ContainerState()
        object Booting : ContainerState()
        object Running : ContainerState()
        object Suspended : ContainerState()
        data class Crashed(val reason: String) : ContainerState()
    }

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Idle)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _containerState = MutableStateFlow<ContainerState>(ContainerState.Shutdown)
    val containerState: StateFlow<ContainerState> = _containerState.asStateFlow()

    private val engineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Engine coroutine error", throwable)
            _engineState.value = EngineState.Error(
                throwable.message ?: "Unknown engine error",
                throwable
            )
        }
    )

    // ─────────────────────────────────────────────────────────────────────
    // Region: Storage Paths
    // ─────────────────────────────────────────────────────────────────────

    private val baseRomDir: File
        get() = File(context.filesDir, "twoyi/rom")

    private val systemImgPath: File
        get() = File(baseRomDir, SYSTEM_IMG)

    private val ramdiskImgPath: File
        get() = File(baseRomDir, RAMDISK_IMG)

    private val kernelImgPath: File
        get() = File(baseRomDir, KERNEL_IMG)

    private val initrdImgPath: File
        get() = File(baseRomDir, INITRD_IMG)

    private val containerWorkDir: File
        get() = File(context.filesDir, "twoyi/container")

    private val guestDataDir: File
        get() = File(containerWorkDir, "data")

    private val guestSdcardDir: File
        get() = File(containerWorkDir, "sdcard")

    private val downloadCacheDir: File
        get() = File(context.cacheDir, "rom_downloads")

    // ─────────────────────────────────────────────────────────────────────
    // Region: 1. Base ROM Verification
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Verify if all required base ROM images are present and intact.
     * Called on app launch to determine if container can boot.
     */
    suspend fun verifyBaseRom(): Boolean = withContext(Dispatchers.IO) {
        _engineState.value = EngineState.Verifying

        try {
            // Check if base ROM directory exists
            if (!baseRomDir.exists()) {
                Log.w(TAG, "Base ROM directory does not exist")
                return@withContext false
            }

            // Verify all required images exist
            val requiredImages = listOf(
                systemImgPath,
                ramdiskImgPath,
                kernelImgPath,
                initrdImgPath
            )

            for (img in requiredImages) {
                if (!img.exists() || img.length() == 0L) {
                    Log.w(TAG, "Missing or empty ROM image: ${img.name}")
                    return@withContext false
                }
            }

            // Verify checksums if checksum file exists
            val checksumFile = File(baseRomDir, CHECKSUM_FILE)
            if (checksumFile.exists()) {
                val checksumValid = verifyChecksums(checksumFile, requiredImages)
                if (!checksumValid) {
                    Log.e(TAG, "ROM checksum verification failed")
                    return@withContext false
                }
            }

            // Verify system.img minimum size (sanity check)
            if (systemImgPath.length() < 100 * 1024 * 1024) { // < 100MB
                Log.e(TAG, "system.img appears to be corrupted (too small)")
                return@withContext false
            }

            Log.i(TAG, "Base ROM verification passed")
            _engineState.value = EngineState.Ready
            true

        } catch (e: Exception) {
            Log.e(TAG, "ROM verification failed", e)
            _engineState.value = EngineState.Error("ROM verification failed: ${e.message}", e)
            false
        }
    }

    /**
     * Verify SHA-256 checksums for ROM images
     */
    private fun verifyChecksums(checksumFile: File, images: List<File>): Boolean {
        return try {
            val expectedChecksums = checksumFile.readLines()
                .filter { it.isNotBlank() }
                .associate { line ->
                    val parts = line.split("  ", limit = 2)
                    if (parts.size == 2) {
                        parts[1].trim() to parts[0].trim()
                    } else {
                        null
                    }
                }
                .filterNotNullKeys()

            for (img in images) {
                val expected = expectedChecksums[img.name] ?: continue
                val actual = calculateSha256(img)
                if (!actual.equals(expected, ignoreCase = true)) {
                    Log.e(TAG, "Checksum mismatch for ${img.name}")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Checksum verification error", e)
            false
        }
    }

    /**
     * Calculate SHA-256 hash of a file
     */
    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)

        file.inputStream().use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 2. Silent ROM Streamer
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Download and extract base ROM from GitHub Releases.
     * Runs silently in background with progress tracking.
     */
    suspend fun downloadAndExtractRom() = withContext(Dispatchers.IO) {
        _engineState.value = EngineState.Downloading

        try {
            // Ensure directories exist
            baseRomDir.mkdirs()
            downloadCacheDir.mkdirs()

            val zipFile = File(downloadCacheDir, ROM_BUNDLE_NAME)

            // Download with progress
            downloadWithProgress(ROM_BASE_URL, zipFile)

            // Extract silently
            _engineState.value = EngineState.Extracting
            extractRomBundle(zipFile)

            // Clean up download cache
            zipFile.delete()

            // Verify after extraction
            val verified = verifyBaseRom()
            if (!verified) {
                _engineState.value = EngineState.Error("Post-extraction verification failed")
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ROM download/extraction failed", e)
            _engineState.value = EngineState.Error("Download failed: ${e.message}", e)
        }
    }

    /**
     * Download file with progress tracking using HttpURLConnection
     */
    private suspend fun downloadWithProgress(
        urlString: String,
        destination: File
    ) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null

        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.requestProperty("Accept", "application/octet-stream")
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP error: $responseCode")
            }

            val contentLength = connection.contentLength.toLong()
            var bytesDownloaded = 0L

            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesDownloaded += read

                        // Update progress every 1MB
                        if (bytesDownloaded % (1024 * 1024) < buffer.size) {
                            val progress = if (contentLength > 0) {
                                ((bytesDownloaded * 100) / contentLength).toInt()
                            } else {
                                -1
                            }
                            _engineState.value = EngineState.DownloadProgress(
                                progress,
                                bytesDownloaded
                            )
                            Log.d(TAG, "Download progress: $progress% ($bytesDownloaded bytes)")
                        }
                    }
                }
            }

            Log.i(TAG, "Download complete: $bytesDownloaded bytes")

        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Extract ROM bundle (ZIP format) to base ROM directory
     */
    private fun extractRomBundle(zipFile: File) {
        val requiredFiles = setOf(SYSTEM_IMG, RAMDISK_IMG, KERNEL_IMG, INITRD_IMG)
        val extractedFiles = mutableSetOf<String>()

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zip ->
            var entry = zip.nextEntry

            while (entry != null) {
                val entryName = entry.name

                // Security: prevent path traversal
                if (entryName.contains("..") || entryName.startsWith("/")) {
                    Log.w(TAG, "Skipping suspicious entry: $entryName")
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }

                // Only extract required ROM files
                val fileName = File(entryName).name
                if (fileName in requiredFiles) {
                    val outputFile = File(baseRomDir, fileName)
                    Log.d(TAG, "Extracting: $fileName")

                    FileOutputStream(outputFile).use { output ->
                        zip.copyTo(output, bufferSize = 8192)
                    }

                    extractedFiles.add(fileName)

                    // Mark as complete if we found it
                    if (extractedFiles.size == requiredFiles.size) {
                        Log.i(TAG, "All required ROM files extracted")
                        break
                    }
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (extractedFiles.size < requiredFiles.size) {
            val missing = requiredFiles - extractedFiles
            throw IOException("Missing ROM files after extraction: $missing")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 3. Container Lifecycle Hook
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Boot the TwoYi container environment.
     * Allocates instances, mounts storage, binds surface context.
     */
    suspend fun bootContainer(
        surfaceHandle: Long? = null,
        gamePackagePath: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (_containerState.value != ContainerState.Shutdown) {
            Log.w(TAG, "Container already running or in invalid state")
            return@withContext false
        }

        _containerState.value = ContainerState.Booting

        try {
            // Step 1: Verify ROM is ready
            val romReady = verifyBaseRom()
            if (!romReady) {
                Log.e(TAG, "Cannot boot: ROM not ready")
                _containerState.value = ContainerState.Shutdown
                return@withContext false
            }

            // Step 2: Allocate container workspace
            allocateContainerWorkspace()

            // Step 3: Mount storage paths
            mountStoragePaths(gamePackagePath)

            // Step 4: Bind surface context (if provided)
            if (surfaceHandle != null) {
                bindSurfaceContext(surfaceHandle)
            }

            // Step 5: Initialize container runtime
            initializeContainerRuntime()

            _containerState.value = ContainerState.Running
            Log.i(TAG, "Container booted successfully")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Container boot failed", e)
            _containerState.value = ContainerState.Crashed(e.message ?: "Boot failed")
            false
        }
    }

    /**
     * Allocate container workspace directories
     */
    private fun allocateContainerWorkspace() {
        val dirs = listOf(
            containerWorkDir,
            guestDataDir,
            guestSdcardDir,
            File(containerWorkDir, "tmp"),
            File(containerWorkDir, "proc"),
            File(containerWorkDir, "sys"),
            File(containerWorkDir, "dev"),
            File(containerWorkDir, "mnt")
        )

        dirs.forEach { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }

        Log.d(TAG, "Container workspace allocated")
    }

    /**
     * Mount storage paths for guest environment
     */
    private fun mountStoragePaths(gamePackagePath: String?) {
        // Create overlay directories for game data
        val gameDataDir = File(guestDataDir, "app")
        gameDataDir.mkdirs()

        // If game package provided, prepare it for mounting
        if (gamePackagePath != null) {
            val gameFile = File(gamePackagePath)
            if (gameFile.exists()) {
                // Create symlink or bind mount point
                val mountPoint = File(gameDataDir, gameFile.name)
                Log.d(TAG, "Game package mount point: ${mountPoint.absolutePath}")
                // Actual mount will be done by native code
            }
        }

        // Prepare shared memory directory for framebuffer
        val sharedMemDir = File(containerWorkDir, "dev/shm")
        sharedMemDir.mkdirs()

        Log.d(TAG, "Storage paths mounted")
    }

    /**
     * Bind surface context for graphics output
     */
    private fun bindSurfaceContext(surfaceHandle: Long) {
        // Pass surface handle to native container runtime
        // This will be used for OpenGL ES context sharing
        Log.d(TAG, "Surface context bound: handle=$surfaceHandle")

        // Store surface handle for later use by native code
        val surfaceFile = File(containerWorkDir, "surface_handle")
        surfaceFile.writeText(surfaceHandle.toString())
    }

    /**
     * Initialize container runtime with required parameters
     */
    private fun initializeContainerRuntime() {
        // Write container configuration
        val config = buildString {
            appendLine("[container]")
            appendLine("base_dir=${baseRomDir.absolutePath}")
            appendLine("work_dir=${containerWorkDir.absolutePath}")
            appendLine("data_dir=${guestDataDir.absolutePath}")
            appendLine("sdcard_dir=${guestSdcardDir.absolutePath}")
            appendLine("system_img=${systemImgPath.absolutePath}")
            appendLine("ramdisk_img=${ramdiskImgPath.absolutePath}")
            appendLine("kernel_img=${kernelImgPath.absolutePath}")
            appendLine("initrd_img=${initrdImgPath.absolutePath}")
            appendLine()
            appendLine("[display]")
            appendLine("framebuffer=/dev/graphics/fb0")
            appendLine("egl_context=shared")
            appendLine()
            appendLine("[input]")
            appendLine("touch_device=/dev/input/event0")
            appendLine("key_device=/dev/input/event1")
        }

        val configFile = File(containerWorkDir, "container.conf")
        configFile.writeText(config)

        Log.d(TAG, "Container runtime initialized")
    }

    /**
     * Shutdown the container gracefully
     */
    suspend fun shutdownContainer() = withContext(Dispatchers.IO) {
        if (_containerState.value == ContainerState.Shutdown) {
            return@withContext
        }

        Log.i(TAG, "Shutting down container...")
        _containerState.value = ContainerState.Shutdown

        // Cleanup temporary files
        val tmpDir = File(containerWorkDir, "tmp")
        if (tmpDir.exists()) {
            tmpDir.listFiles()?.forEach { it.delete() }
        }

        Log.i(TAG, "Container shutdown complete")
    }

    /**
     * Suspend container state (for background optimization)
     */
    suspend fun suspendContainer() = withContext(Dispatchers.IO) {
        if (_containerState.value == ContainerState.Running) {
            _containerState.value = ContainerState.Suspended
            Log.i(TAG, "Container suspended")
        }
    }

    /**
     * Resume suspended container
     */
    suspend fun resumeContainer() = withContext(Dispatchers.IO) {
        if (_containerState.value == ContainerState.Suspended) {
            _containerState.value = ContainerState.Running
            Log.i(TAG, "Container resumed")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 4. Multi-Process Handling
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Launch guest VM in isolated :virtual_machine subprocess.
     * This ensures host app remains alive even if guest crashes.
     */
    fun launchVirtualMachineProcess(
        gamePackagePath: String,
        surfaceHandle: Long
    ): Intent {
        val intent = Intent(context, VirtualMachineService::class.java).apply {
            action = VirtualMachineService.ACTION_START
            putExtra(VirtualMachineService.EXTRA_GAME_PACKAGE, gamePackagePath)
            putExtra(VirtualMachineService.EXTRA_SURFACE_HANDLE, surfaceHandle)
            putExtra(VirtualMachineService.EXTRA_ROM_DIR, baseRomDir.absolutePath)
            putExtra(VirtualMachineService.EXTRA_WORK_DIR, containerWorkDir.absolutePath)
        }

        Log.i(TAG, "Launching virtual machine process")
        return intent
    }

    /**
     * Handle guest VM crash gracefully.
     * Host layout remains active, user can restart.
     */
    fun handleGuestCrash(reason: String) {
        Log.e(TAG, "Guest VM crashed: $reason")
        _containerState.value = ContainerState.Crashed(reason)

        // Notify host about crash (host stays alive)
        // UI can show restart option
    }

    /**
     * Restart guest VM after crash
     */
    suspend fun restartGuest(
        surfaceHandle: Long? = null,
        gamePackagePath: String? = null
    ): Boolean {
        Log.i(TAG, "Restarting guest VM...")
        shutdownContainer()
        delay(1000) // Brief delay before restart
        return bootContainer(surfaceHandle, gamePackagePath)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Utility
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Get current engine state
     */
    fun getCurrentState(): EngineState = _engineState.value

    /**
     * Get current container state
     */
    fun getContainerState(): ContainerState = _containerState.value

    /**
     * Check if container is ready to boot
     */
    suspend fun isReady(): Boolean {
        return verifyBaseRom()
    }

    /**
     * Get disk usage info for ROM files
     */
    fun getRomDiskUsage(): Long {
        return if (baseRomDir.exists()) {
            baseRomDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }
    }

    /**
     * Clean up all container data
     */
    suspend fun cleanAll() = withContext(Dispatchers.IO) {
        shutdownContainer()

        if (baseRomDir.exists()) {
            baseRomDir.deleteRecursively()
        }
        if (containerWorkDir.exists()) {
            containerWorkDir.deleteRecursively()
        }
        if (downloadCacheDir.exists()) {
            downloadCacheDir.deleteRecursively()
        }

        _engineState.value = EngineState.Idle
        Log.i(TAG, "All container data cleaned")
    }

    /**
     * Release resources
     */
    fun destroy() {
        engineScope.cancel()
        instance = null
    }
}

/**
 * Extension function for Map key filtering
 */
private fun <K, V> Map<K, V>.filterNotNullKeys(): Map<K, V> {
    return filterKeys { it != null }
}
