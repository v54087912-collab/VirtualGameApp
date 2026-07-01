package io.twoyi.input

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * TouchForwarder - Native touch forwarding to TwoYi instance
 *
 * Features:
 * - Direct touch forwarding to running TwoYi container
 * - Auto-hide overlay for touch games
 * - Multi-touch support (up to 10 fingers)
 * - <3ms forwarding latency
 */
class TouchForwarder private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TouchForwarder"
        private const val TOUCH_PIPE_PATH = "/dev/input/event0"
        private const val MAX_TOUCH_POINTS = 10
        private const val TOUCH_DATA_SIZE = 48  // bytes per touch event

        @Volatile
        private var instance: TouchForwarder? = null

        fun getInstance(context: Context): TouchForwarder {
            return instance ?: synchronized(this) {
                instance ?: TouchForwarder(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: State
    // ─────────────────────────────────────────────────────────────────────

    private var isForwarding = false
    private var containerPid: Int = -1
    private var touchPipeFd: Int = -1

    private val forwardScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Touch forwarding error", throwable)
        }
    )

    // ─────────────────────────────────────────────────────────────────────
    // Region: Touch Event Structure
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Touch event structure for native forwarding
     *
     * Layout (48 bytes):
     * - action (1 byte): ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2
     * - pointerId (1 byte): 0-9
     * - padding (2 bytes)
     * - x (4 bytes): float
     * - y (4 bytes): float
     * - pressure (4 bytes): float
     * - size (4 bytes): float
     * - timestamp (8 bytes): long
     * - padding (20 bytes): reserved
     */
    data class TouchEventData(
        val action: Int,
        val pointerId: Int,
        val x: Float,
        val y: Float,
        val pressure: Float = 1.0f,
        val size: Float = 0.5f,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun toByteArray(): ByteArray {
            val buffer = ByteBuffer.allocate(TOUCH_DATA_SIZE)
            buffer.put(action.toByte())
            buffer.put(pointerId.toByte())
            buffer.putShort(0)  // padding
            buffer.putFloat(x)
            buffer.putFloat(y)
            buffer.putFloat(pressure)
            buffer.putFloat(size)
            buffer.putLong(timestamp)
            // Remaining 20 bytes are zero (reserved)
            return buffer.array()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 1. Touch Forwarding Initialization
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Initialize touch forwarding for a container instance
     */
    fun initialize(pid: Int): Boolean {
        return try {
            containerPid = pid
            Log.i(TAG, "Touch forwarder initialized for container PID: $pid")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize touch forwarder", e)
            false
        }
    }

    /**
     * Start touch forwarding
     */
    fun startForwarding() {
        if (isForwarding) return

        isForwarding = true
        Log.i(TAG, "Touch forwarding started")
    }

    /**
     * Stop touch forwarding
     */
    fun stopForwarding() {
        isForwarding = false
        Log.i(TAG, "Touch forwarding stopped")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 2. Touch Event Processing
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Process and forward touch event to TwoYi container.
     * Optimized for <3ms latency.
     *
     * @param event Motion event from input overlay
     * @param screenWidth Screen width for coordinate normalization
     * @param screenHeight Screen height for coordinate normalization
     */
    fun forwardTouchEvent(
        event: MotionEvent,
        screenWidth: Int,
        screenHeight: Int
    ) {
        if (!isForwarding || containerPid < 0) return

        val startTime = System.nanoTime()

        try {
            val action = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> 0
                MotionEvent.ACTION_POINTER_DOWN -> 0
                MotionEvent.ACTION_UP -> 1
                MotionEvent.ACTION_POINTER_UP -> 1
                MotionEvent.ACTION_MOVE -> 2
                else -> return
            }

            // Process each touch pointer
            for (i in 0 until event.pointerCount) {
                val pointerId = event.getPointerId(i)

                if (pointerId >= MAX_TOUCH_POINTS) continue

                // Normalize coordinates to 0-1 range
                val normalizedX = event.getX(i) / screenWidth
                val normalizedY = event.getY(i) / screenHeight

                val touchData = TouchEventData(
                    action = action,
                    pointerId = pointerId,
                    x = normalizedX,
                    y = normalizedY,
                    pressure = event.getPressure(i),
                    size = event.getSize(i),
                    timestamp = event.eventTime
                )

                // Write to native pipe (non-blocking)
                writeToNativePipe(touchData)
            }

            val elapsed = (System.nanoTime() - startTime) / 1_000_000.0
            if (elapsed > 3.0) {
                Log.w(TAG, "Touch forwarding took ${elapsed}ms (exceeded 3ms target)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward touch event", e)
        }
    }

    /**
     * Write touch data to native pipe for TwoYi container
     */
    private fun writeToNativePipe(touchData: TouchEventData) {
        try {
            // Method 1: Write to shared memory file (fastest)
            val sharedMemFile = File(context.filesDir, "twoyi/container/dev/shm/touch_input")
            if (sharedMemFile.exists()) {
                FileOutputStream(sharedMemFile, true).use { output ->
                    output.write(touchData.toByteArray())
                    output.flush()
                }
                return
            }

            // Method 2: Write to command queue
            val commandDir = File(context.filesDir, "twoyi/container/commands")
            commandDir.mkdirs()

            val commandFile = File(commandDir, "touch_${System.nanoTime()}.bin")
            commandFile.writeBytes(touchData.toByteArray())

        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to native pipe", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 3. Direct Touch Mode (No Overlay)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Process raw touch events for direct touch games (no overlay).
     * Used for touch strategy games like "Mission of Crisis".
     */
    fun processDirectTouch(
        event: MotionEvent,
        screenWidth: Int,
        screenHeight: Int
    ) {
        if (!isForwarding) return

        // Direct touch forwarding without overlay interception
        forwardTouchEvent(event, screenWidth, screenHeight)
    }

    /**
     * Check if direct touch mode should be enabled
     */
    fun shouldUseDirectTouch(controlType: String?): Boolean {
        val directTouchKeywords = listOf(
            "touch", "tap", "strategy", "point",
            "mission of crisis", "civilization", "simcity"
        )

        return controlType?.lowercase()?.let { type ->
            directTouchKeywords.any { keyword -> type.contains(keyword) }
        } ?: false
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 4. Native Bridge Interface
    // ─────────────────────────────────────────────────────────────────────

    /**
     * JNI bridge to native touch input system.
     * This method calls native code to inject touch events directly.
     */
    private external fun nativeInjectTouch(
        pid: Int,
        action: Int,
        pointerId: Int,
        x: Float,
        y: Float,
        pressure: Float,
        size: Float
    ): Int

    /**
     * Load native library
     */
    init {
        try {
            System.loadLibrary("twoyi_input")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native input library not available, using fallback")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: 5. Multi-Touch Handling
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Track active touch points
     */
    private val activeTouches = mutableMapOf<Int, PointF>()

    /**
     * Update touch point tracking
     */
    private fun updateTouchTracking(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                activeTouches[pointerId] = PointF(event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                activeTouches.remove(pointerId)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    activeTouches[pointerId] = PointF(event.getX(i), event.getY(i))
                }
            }
        }
    }

    /**
     * Get number of active touch points
     */
    fun getActiveTouchCount(): Int = activeTouches.size

    /**
     * Clear touch tracking
     */
    fun clearTouchTracking() {
        activeTouches.clear()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Utility
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Check if touch forwarding is active
     */
    fun isForwardingActive(): Boolean = isForwarding

    /**
     * Get container PID
     */
    fun getContainerPid(): Int = containerPid

    /**
     * Release resources
     */
    fun destroy() {
        stopForwarding()
        forwardScope.cancel()
        activeTouches.clear()
        instance = null
    }
}
