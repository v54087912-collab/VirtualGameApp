package top.niunaijun.blackboxa.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

enum class RetroSkin(val displayName: String) {
    CLASSIC("Classic Dark"),
    NOKIA("Nokia 3310"),
    GAMEBOY("Game Boy DMG"),
    PSX("PlayStation Retro")
}

enum class VirtualButton(
    val id: String,
    val label: String
) {
    UP("dpad_up", "\u25B2"),
    DOWN("dpad_down", "\u25BC"),
    LEFT("dpad_left", "\u25C0"),
    RIGHT("dpad_right", "\u25B6"),
    ACTION_A("action_a", "A"),
    ACTION_B("action_b", "B"),
    ACTION_X("action_x", "X"),
    ACTION_Y("action_y", "Y"),
    SELECT("select", "SEL"),
    START("start", "STA")
}

data class ButtonConfig(
    val button: VirtualButton,
    var x: Float,
    var y: Float,
    var width: Float,
    var height: Float,
    val isVisible: Boolean = true
)

class VirtualKeypadView(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    private var currentSkin: RetroSkin = RetroSkin.CLASSIC
    private var buttons: MutableList<ButtonConfig> = mutableListOf()
    private var buttonRects: MutableMap<VirtualButton, RectF> = mutableMapOf()
    private var pressedButtons: MutableSet<VirtualButton> = mutableSetOf()
    private var isRunning = false
    private var keypadListener: KeypadListener? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        isDither = true
        isSubpixelText = true
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }

    interface KeypadListener {
        fun onButtonDown(button: VirtualButton)
        fun onButtonUp(button: VirtualButton)
    }

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.TRANSPARENT)
        setZOrderOnTop(true)
        defaultLayout()
    }

    private fun defaultLayout() {
        buttons.clear()
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val btnSize = w * 0.07f
        val pad = btnSize * 0.3f

        buttons.add(ButtonConfig(VirtualButton.UP, w * 0.18f, h * 0.72f, btnSize, btnSize))
        buttons.add(ButtonConfig(VirtualButton.DOWN, w * 0.18f, h * 0.72f + btnSize + pad, btnSize, btnSize))
        buttons.add(ButtonConfig(VirtualButton.LEFT, w * 0.18f - btnSize - pad, h * 0.72f + (btnSize + pad) / 2, btnSize, btnSize))
        buttons.add(ButtonConfig(VirtualButton.RIGHT, w * 0.18f + btnSize + pad, h * 0.72f + (btnSize + pad) / 2, btnSize, btnSize))

        buttons.add(ButtonConfig(VirtualButton.ACTION_B, w * 0.82f, h * 0.72f, btnSize, btnSize))
        buttons.add(ButtonConfig(VirtualButton.ACTION_A, w * 0.82f + btnSize + pad, h * 0.72f + (btnSize + pad) / 2, btnSize, btnSize))
        buttons.add(ButtonConfig(VirtualButton.ACTION_X, w * 0.82f - btnSize - pad, h * 0.72f + (btnSize + pad) / 2, btnSize, btnSize))
        buttons.add(ButtonConfig(VirtualButton.ACTION_Y, w * 0.82f, h * 0.72f + btnSize + pad, btnSize, btnSize))

        buttons.add(ButtonConfig(VirtualButton.SELECT, w * 0.38f, h * 0.90f, btnSize * 1.2f, btnSize * 0.7f))
        buttons.add(ButtonConfig(VirtualButton.START, w * 0.58f, h * 0.90f, btnSize * 1.2f, btnSize * 0.7f))

        rebuildRects()
    }

    fun setButtons(updated: List<ButtonConfig>) {
        buttons.clear()
        buttons.addAll(updated)
        rebuildRects()
    }

    fun getButtons(): List<ButtonConfig> = buttons.toList()

    private fun rebuildRects() {
        buttonRects.clear()
        for (cfg in buttons) {
            if (cfg.isVisible) {
                buttonRects[cfg.button] = RectF(cfg.x, cfg.y, cfg.x + cfg.width, cfg.y + cfg.height)
            }
        }
    }

    fun setSkin(skin: RetroSkin) {
        currentSkin = skin
        invalidate()
    }

    fun setKeypadListener(listener: KeypadListener?) {
        keypadListener = listener
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isRunning = true
        Thread(this).start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isRunning = false
    }

    override fun run() {
        while (isRunning) {
            val canvas = holder.lockCanvas()
            if (canvas != null) {
                try {
                    canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                    drawButtons(canvas)
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
            }
            try {
                Thread.sleep(16)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun drawButtons(canvas: Canvas) {
        val isNokia = currentSkin == RetroSkin.NOKIA
        val isGameBoy = currentSkin == RetroSkin.GAMEBOY
        val isPsx = currentSkin == RetroSkin.PSX

        for (cfg in buttons) {
            if (!cfg.isVisible) continue
            val rect = buttonRects[cfg.button] ?: continue
            val isPressed = cfg.button in pressedButtons

            val baseAlpha = if (isPressed) 200 else 120
            val bgColor = when {
                isPressed -> Color.argb(220, 255, 255, 255)
                isNokia -> Color.argb(baseAlpha, 57, 72, 48)
                isGameBoy -> Color.argb(baseAlpha, 48, 56, 32)
                isPsx -> Color.argb(baseAlpha, 0, 30, 60)
                else -> Color.argb(baseAlpha, 60, 60, 60)
            }
            val strokeColor = when {
                isPressed -> Color.argb(255, 180, 180, 180)
                isNokia -> Color.argb(180, 140, 180, 100)
                isGameBoy -> Color.argb(180, 120, 160, 60)
                isPsx -> Color.argb(180, 80, 130, 200)
                else -> Color.argb(150, 120, 120, 120)
            }

            paint.color = bgColor
            paint.style = Paint.Style.FILL
            val cornerRadius = when {
                isGameBoy -> 0f
                isNokia -> 4f
                else -> 8f
            }
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

            paint.color = strokeColor
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

            textPaint.color = when {
                isPressed -> Color.BLACK
                isNokia -> Color.argb(200, 140, 180, 100)
                isGameBoy -> Color.argb(200, 120, 160, 60)
                else -> Color.argb(200, 220, 220, 220)
            }
            textPaint.textSize = rect.height() * 0.45f
            val cx = rect.centerX()
            val cy = rect.centerY() - (textPaint.textSize / 3)
            canvas.drawText(cfg.button.label, cx, cy, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val px = event.getX(idx)
                val py = event.getY(idx)
                val hit = findButton(px, py)
                if (hit != null) {
                    pressedButtons.add(hit)
                    keypadListener?.onButtonDown(hit)
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val px = event.getX(idx)
                val py = event.getY(idx)
                val hit = findButton(px, py)
                if (hit != null) {
                    pressedButtons.remove(hit)
                    keypadListener?.onButtonUp(hit)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val px = event.getX(i)
                    val py = event.getY(i)
                    val hit = findButton(px, py)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                for (btn in pressedButtons.toSet()) {
                    keypadListener?.onButtonUp(btn)
                }
                pressedButtons.clear()
            }
        }
        return true
    }

    private fun findButton(x: Float, y: Float): VirtualButton? {
        for ((btn, rect) in buttonRects) {
            if (rect.contains(x, y)) return btn
        }
        return null
    }
}
