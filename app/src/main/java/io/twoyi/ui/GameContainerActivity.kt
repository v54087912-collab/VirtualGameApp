package io.twoyi.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.twoyi.R
import io.twoyi.engine.ContainerEngineManager
import io.twoyi.engine.GameLaunchManager
import io.twoyi.input.InputOverlayView
import io.twoyi.input.KeyMapManager
import io.twoyi.input.OverlayLayoutProfile
import io.twoyi.input.TouchForwarder
import io.twoyi.update.UpdateManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * GameContainerActivity - Main game container with input overlay
 *
 * Integrates:
 * - TwoYi container surface
 * - Input overlay (D-Pad, buttons)
 * - Touch forwarding for direct touch games
 * - Update engine
 */
class GameContainerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GAME_ID = "game_id"
        const val EXTRA_GAME_TITLE = "game_title"
        const val EXTRA_CONTROL_TYPE = "control_type"
    }

    private lateinit var containerSurface: SurfaceView
    private lateinit var inputOverlay: InputOverlayView
    private lateinit var editModeIndicator: TextView
    private lateinit var toggleLayoutBtn: ImageButton
    private lateinit var editModeBtn: ImageButton
    private lateinit var hideOverlayBtn: ImageButton

    private lateinit var keyMapManager: KeyMapManager
    private lateinit var touchForwarder: TouchForwarder
    private lateinit var engineManager: ContainerEngineManager
    private lateinit var updateManager: UpdateManager

    private var gameId: String = ""
    private var gameTitle: String = ""
    private var controlType: String = ""
    private var isDirectTouchMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_container)

        // Keep screen on and fullscreen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        // Get intent extras
        gameId = intent.getStringExtra(EXTRA_GAME_ID) ?: ""
        gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE) ?: ""
        controlType = intent.getStringExtra(EXTRA_CONTROL_TYPE) ?: ""

        if (gameId.isEmpty()) {
            Toast.makeText(this, "Invalid game ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        initManagers()
        setupSurface()
        setupOverlay()
        setupControls()
        checkForUpdates()
    }

    private fun initViews() {
        containerSurface = findViewById(R.id.container_surface)
        inputOverlay = findViewById(R.id.input_overlay)
        editModeIndicator = findViewById(R.id.edit_mode_indicator)
        toggleLayoutBtn = findViewById(R.id.btn_toggle_layout)
        editModeBtn = findViewById(R.id.btn_edit_mode)
        hideOverlayBtn = findViewById(R.id.btn_hide_overlay)
    }

    private fun initManagers() {
        keyMapManager = KeyMapManager.getInstance(this)
        touchForwarder = TouchForwarder.getInstance(this)
        engineManager = ContainerEngineManager.getInstance(this)
        updateManager = UpdateManager.getInstance(this)

        // Load game-specific preferences
        keyMapManager.loadGamePreferences(gameId)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Surface Setup
    // ─────────────────────────────────────────────────────────────────────

    private fun setupSurface() {
        containerSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                onSurfaceCreated(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Surface dimensions changed
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSurfaceDestroyed()
            }
        })
    }

    private fun onSurfaceCreated(holder: SurfaceHolder) {
        // Start container with game
        lifecycleScope.launch {
            val bootSuccess = engineManager.bootContainer(
                surfaceHandle = 0,  // Will be set by native code
                gamePackagePath = gameId
            )

            if (bootSuccess) {
                // Initialize touch forwarder
                touchForwarder.initialize(engineManager.getContainerState().hashCode())
                touchForwarder.startForwarding()

                runOnUiThread {
                    Toast.makeText(this@GameContainerActivity, "Game started!", Toast.LENGTH_SHORT).show()
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this@GameContainerActivity, "Failed to start game", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun onSurfaceDestroyed() {
        touchForwarder.stopForwarding()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Overlay Setup
    // ─────────────────────────────────────────────────────────────────────

    private fun setupOverlay() {
        // Check if this is a direct touch game
        isDirectTouchMode = touchForwarder.shouldUseDirectTouch(controlType)

        if (isDirectTouchMode) {
            // Auto-hide overlay for touch games
            inputOverlay.visibility = View.GONE
            hideOverlayBtn.visibility = View.GONE
        } else {
            // Setup overlay with current layout profile
            val currentLayout = keyMapManager.getCurrentLayout()
            inputOverlay.setLayoutProfile(currentLayout)
            inputOverlay.visibility = View.VISIBLE
        }

        // Setup touch forwarding
        inputOverlay.setOnButtonPressedListener { button, keyEvent ->
            if (!isDirectTouchMode) {
                // Forward key event to container
                forwardKeyToContainer(keyEvent)
            }
        }

        inputOverlay.setOnButtonReleasedListener { button, keyEvent ->
            if (!isDirectTouchMode) {
                forwardKeyToContainer(keyEvent)
            }
        }

        inputOverlay.setOnEditModeChangedListener { isEditMode ->
            editModeIndicator.visibility = if (isEditMode) View.VISIBLE else View.GONE
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Controls Setup
    // ─────────────────────────────────────────────────────────────────────

    private fun setupControls() {
        // Toggle layout button
        toggleLayoutBtn.setOnClickListener {
            val newLayout = keyMapManager.toggleLayout()
            inputOverlay.setLayoutProfile(newLayout)

            val layoutName = when (newLayout) {
                is OverlayLayoutProfile.ClassicNokia -> "Nokia"
                is OverlayLayoutProfile.RetroGamePad -> "GamePad"
            }
            Toast.makeText(this, "Layout: $layoutName", Toast.LENGTH_SHORT).show()
        }

        // Edit mode button
        editModeBtn.setOnClickListener {
            val isEditMode = inputOverlay.toggleEditMode()
            editModeBtn.alpha = if (isEditMode) 1.0f else 0.5f
        }

        // Hide overlay button
        hideOverlayBtn.setOnClickListener {
            inputOverlay.fadeOverlay(0.3f)
            hideOverlayBtn.alpha = 0.5f

            // Show again on tap
            containerSurface.setOnClickListener {
                inputOverlay.fadeOverlay(1.0f)
                hideOverlayBtn.alpha = 1.0f
                containerSurface.setOnClickListener(null)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Touch Forwarding
    // ─────────────────────────────────────────────────────────────────────

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isDirectTouchMode) {
            // Direct touch mode - forward all touches to container
            touchForwarder.processDirectTouch(
                ev,
                containerSurface.width,
                containerSurface.height
            )
            return true
        }

        return super.dispatchTouchEvent(ev)
    }

    /**
     * Forward key event to TwoYi container
     */
    private fun forwardKeyToContainer(keyEvent: io.twoyi.input.KeyEvent) {
        // Write key event to container's input pipe
        lifecycleScope.launch {
            try {
                val commandDir = filesDir.resolve("twoyi/container/commands")
                commandDir.mkdirs()

                val keyEventFile = commandDir.resolve("key_${System.nanoTime()}.bin")
                keyEventFile.writeBytes(
                    byteArrayOf(
                        keyEvent.action.toByte(),
                        (keyEvent.keyCode and 0xFF).toByte(),
                        (keyEvent.keyCode shr 8 and 0xFF).toByte()
                    )
                )
            } catch (e: Exception) {
                // Silent fail for input
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Hardware Key Support
    // ─────────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Map hardware keys to virtual buttons
        val mappedKey = mapHardwareKeyToVirtual(keyCode)
        if (mappedKey != null) {
            forwardKeyToContainer(io.twoyi.input.KeyEvent(
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                io.twoyi.input.KeyEvent.ACTION_DOWN,
                keyCode,
                0
            ))
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val mappedKey = mapHardwareKeyToVirtual(keyCode)
        if (mappedKey != null) {
            forwardKeyToContainer(io.twoyi.input.KeyEvent(
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                io.twoyi.input.KeyEvent.ACTION_UP,
                keyCode,
                0
            ))
            return true
        }

        return super.onKeyUp(keyCode, event)
    }

    private fun mapHardwareKeyToVirtual(keyCode: Int): String? {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER -> "dpad"

            KeyEvent.KEYCODE_VOLUME_UP -> "vol_up"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "vol_down"

            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> "gamepad"

            else -> null
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Update Engine
    // ─────────────────────────────────────────────────────────────────────

    private fun checkForUpdates() {
        lifecycleScope.launch {
            updateManager.checkForUpdates()

            updateManager.updateState.collectLatest { state ->
                when (state) {
                    is UpdateManager.UpdateState.UpdateAvailable -> {
                        runOnUiThread {
                            updateManager.handleUpdate(this@GameContainerActivity, state.info)
                        }
                    }
                    else -> { /* Handle other states */ }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        // Re-enter immersive mode
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onPause() {
        super.onPause()
        // Suspend container when pausing
        lifecycleScope.launch {
            engineManager.suspendContainer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        touchForwarder.destroy()
        keyMapManager.savePreferences()
    }

    override fun onBackPressed() {
        // Show exit confirmation
        AlertDialog.Builder(this)
            .setTitle("Exit Game")
            .setMessage("Are you sure you want to exit?")
            .setPositiveButton("Exit") { _, _ ->
                lifecycleScope.launch {
                    engineManager.shutdownContainer()
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
