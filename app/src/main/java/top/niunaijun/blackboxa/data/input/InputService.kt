package top.niunaijun.blackboxa.data.input

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import top.niunaijun.blackboxa.widget.VirtualButton

class InputService(private val context: Context) {

    private var hapticEnabled = true
    private var audioEnabled = false
    private var upscalingEnabled = true

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private var soundPool: SoundPool? = null

    fun triggerHaptic() {
        if (!hapticEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(
                    20,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(20)
            }
        } catch (_: Exception) {
        }
    }

    fun triggerKeypressHaptic(button: VirtualButton) {
        if (!hapticEnabled) return
        try {
            val durationMs = when (button) {
                VirtualButton.UP, VirtualButton.DOWN,
                VirtualButton.LEFT, VirtualButton.RIGHT -> 15
                VirtualButton.ACTION_A, VirtualButton.ACTION_B,
                VirtualButton.ACTION_X, VirtualButton.ACTION_Y -> 25
                VirtualButton.SELECT, VirtualButton.START -> 30
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(
                    durationMs.toLong(),
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs.toLong())
            }
        } catch (_: Exception) {
        }
    }

    fun playClickSound() {
        if (!audioEnabled) return
        try {
            soundPool?.play(1, 1.0f, 1.0f, 0, 0, 1.0f)
        } catch (_: Exception) {
        }
    }

    fun setHapticEnabled(enabled: Boolean) {
        hapticEnabled = enabled
    }

    fun setAudioEnabled(enabled: Boolean) {
        audioEnabled = enabled
        if (enabled) {
            initSoundPool()
        } else {
            soundPool?.release()
            soundPool = null
        }
    }

    fun setUpscalingEnabled(enabled: Boolean) {
        upscalingEnabled = enabled
    }

    fun isUpscalingEnabled(): Boolean = upscalingEnabled

    private fun initSoundPool() {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            soundPool = SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(attrs)
                .build()
        } catch (_: Exception) {
        }
    }

    fun release() {
        soundPool?.release()
        soundPool = null
    }
}
