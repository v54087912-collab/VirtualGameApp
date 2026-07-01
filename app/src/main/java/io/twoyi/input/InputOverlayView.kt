package io.twoyi.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * InputOverlayView - Transparent virtual keypad overlay
 *
 * Features:
 * - Dynamic overlay canvas grid rendering
 * - D-Pad and action buttons
 * - Layout profile toggle (Nokia vs GamePad)
 * - Drag-and-drop button customization
 * - High performance rendering (<2ms per frame)
 */
class InputOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "InputOverlayView"
        private const val BUTTON_PRESS_ALPHA = 180
        private const val BUTTON_NORMAL_ALPHA = 120
        private const val DRAG_THRESHOLD = 20f
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: State
    // ─────────────────────────────────────────────────────────────────────

    private var buttons = listOf<VirtualButton>()
    private var pressedButton: VirtualButton? = null
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var isEditMode = false

    private val keyMapManager by lazy { KeyMapManager.getInstance(context) }

    // Callbacks
    private var onButtonPressedListener: ((VirtualButton, KeyEvent) -> Unit)? = null
    private var onButtonReleasedListener: ((VirtualButton, KeyEvent) -> Unit)? = null
    private var onEditModeChangedListener: ((Boolean) -> Unit)? = null

    // ─────────────────────────────────────────────────────────────────────
    // Region: Paint Objects
    // ─────────────────────────────────────────────────────────────────────

    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = BUTTON_NORMAL_ALPHA
        style = Paint.Style.FILL
    }

    private val pressedButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = BUTTON_PRESS_ALPHA
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 200
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val editModePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        alpha = 100
        style = Paint.Style.FILL
    }

    private val dragIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        alpha = 150
        style = Paint.Style.FILL
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Initialization
    // ─────────────────────────────────────────────────────────────────────

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * Set layout profile and load buttons
     */
    fun setLayoutProfile(profile: OverlayLayoutProfile) {
        buttons = profile.getButtons().map { button ->
            val customPos = keyMapManager.getButtonPosition(button)
            if (customPos != button.normalizedPosition) {
                button.copy(customPosition = customPos)
            } else {
                button
            }
        }
        invalidate()
    }

    /**
     * Set control type for game (auto-hide for touch games)
     */
    fun setControlType(controlType: String?) {
        if (keyMapManager.isDirectTouchGame(controlType)) {
            visibility = GONE
        } else {
            visibility = VISIBLE
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Drawing
    // ─────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        if (width == 0f || height == 0f) return

        // Draw edit mode background
        if (isEditMode) {
            canvas.drawRect(0f, 0f, width, height, editModePaint)
        }

        // Draw all buttons
        buttons.forEach { button ->
            drawButton(canvas, button, width, height)
        }
    }

    private fun drawButton(canvas: Canvas, button: VirtualButton, width: Float, height: Float) {
        val pos = button.customPosition ?: button.normalizedPosition
        val centerX = pos.x * width
        val centerY = pos.y * height
        val size = button.normalizedSize * min(width, height)

        val paint = if (button.isPressed) pressedButtonPaint else buttonPaint

        // Draw button shape
        when (button.shape) {
            ButtonShape.CIRCLE -> {
                canvas.drawCircle(centerX, centerY, size, paint)
                canvas.drawCircle(centerX, centerY, size, borderPaint)
            }
            ButtonShape.RECT -> {
                val rect = RectF(
                    centerX - size, centerY - size,
                    centerX + size, centerY + size
                )
                canvas.drawRect(rect, paint)
                canvas.drawRect(rect, borderPaint)
            }
            ButtonShape.ROUNDED_RECT -> {
                val rect = RectF(
                    centerX - size, centerY - size * 0.6f,
                    centerX + size, centerY + size * 0.6f
                )
                canvas.drawRoundRect(rect, size * 0.3f, size * 0.3f, paint)
                canvas.drawRoundRect(rect, size * 0.3f, size * 0.3f, borderPaint)
            }
        }

        // Draw button label
        textPaint.textSize = size * 0.8f
        val textY = centerY - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(button.label, centerX, textY, textPaint)

        // Draw drag indicator in edit mode
        if (isEditMode && button.customPosition != null) {
            canvas.drawCircle(centerX + size, centerY - size, 8f, dragIndicatorPaint)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Touch Handling
    // ─────────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val width = width.toFloat()
        val height = height.toFloat()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val hitButton = findButtonAtTouch(x, y, width, height)

                if (isEditMode && hitButton != null) {
                    // Start drag
                    isDragging = true
                    dragStartX = x
                    dragStartY = y
                    pressedButton = hitButton
                    return true
                }

                if (hitButton != null) {
                    hitButton.isPressed = true
                    pressedButton = hitButton
                    invalidate()

                    // Generate and dispatch key event
                    val keyEvent = keyMapManager.getKeyEventForButton(hitButton, isDown = true)
                    onButtonPressedListener?.invoke(hitButton, keyEvent)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging && pressedButton != null) {
                    // Update button position during drag
                    val normalizedX = x / width
                    val normalizedY = y / height
                    keyMapManager.updateButtonPosition(pressedButton!!.id, normalizedX, normalizedY)

                    // Update button position in list
                    val index = buttons.indexOfFirst { it.id == pressedButton!!.id }
                    if (index >= 0) {
                        buttons = buttons.toMutableList().apply {
                            this[index] = this[index].copy(customPosition = PointF(normalizedX, normalizedY))
                        }
                    }

                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    // Finalize drag
                    pressedButton?.let {
                        keyMapManager.finalizeButtonPosition(it.id)
                    }
                    isDragging = false
                    pressedButton = null
                    invalidate()
                    return true
                }

                if (pressedButton != null) {
                    pressedButton!!.isPressed = false
                    invalidate()

                    // Generate and dispatch key release event
                    val keyEvent = keyMapManager.getKeyEventForButton(pressedButton!!, isDown = false)
                    onButtonReleasedListener?.invoke(pressedButton!!, keyEvent)
                    pressedButton = null
                    return true
                }
            }
        }

        return super.onTouchEvent(event)
    }

    /**
     * Find button at touch position
     */
    private fun findButtonAtTouch(x: Float, y: Float, width: Float, height: Float): VirtualButton? {
        val normalizedX = x / width
        val normalizedY = y / height

        // Check buttons in reverse order (top-most first)
        for (button in buttons.reversed()) {
            val pos = button.customPosition ?: button.normalizedPosition
            val dx = normalizedX - pos.x
            val dy = normalizedY - pos.y
            val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            val hitRadius = button.normalizedSize + 0.03f

            if (distance <= hitRadius) {
                return button
            }
        }

        return null
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Edit Mode
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Toggle edit mode for drag-and-drop customization
     */
    fun toggleEditMode(): Boolean {
        isEditMode = !isEditMode
        onEditModeChangedListener?.invoke(isEditMode)
        invalidate()
        return isEditMode
    }

    /**
     * Set edit mode
     */
    fun setEditMode(enabled: Boolean) {
        isEditMode = enabled
        onEditModeChangedListener?.invoke(isEditMode)
        invalidate()
    }

    /**
     * Check if in edit mode
     */
    fun isInEditMode(): Boolean = isEditMode

    // ─────────────────────────────────────────────────────────────────────
    // Region: Callbacks
    // ─────────────────────────────────────────────────────────────────────

    fun setOnButtonPressedListener(listener: (VirtualButton, io.twoyi.input.KeyEvent) -> Unit) {
        onButtonPressedListener = listener
    }

    fun setOnButtonReleasedListener(listener: (VirtualButton, io.twoyi.input.KeyEvent) -> Unit) {
        onButtonReleasedListener = listener
    }

    fun setOnEditModeChangedListener(listener: (Boolean) -> Unit) {
        onEditModeChangedListener = listener
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Visibility Control
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Show overlay
     */
    fun showOverlay() {
        visibility = VISIBLE
        alpha = 1f
    }

    /**
     * Hide overlay
     */
    fun hideOverlay() {
        visibility = GONE
    }

    /**
     * Fade overlay
     */
    fun fadeOverlay(alpha: Float) {
        this.alpha = alpha.coerceIn(0f, 1f)
    }

    /**
     * Get current buttons
     */
    fun getButtons(): List<VirtualButton> = buttons
}

/**
 * KeyEvent - Custom key event for input overlay
 */
data class KeyEvent(
    val timestamp: Long,
    val downTime: Long,
    val action: Int,
    val keyCode: Int,
    val metaState: Int
) {
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
    }
}
