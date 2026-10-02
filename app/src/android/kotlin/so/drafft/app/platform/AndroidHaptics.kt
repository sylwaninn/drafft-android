package so.drafft.app.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import so.drafft.core.data.platform.Haptics

/**
 * The app's haptic feedback on Android's predefined effects: light impact → tick, medium
 * impact → click, success → double click, warning → heavy click, selection → tick. The vibrator is
 * looked up once and kept, so the effect fires without delay.
 */
class AndroidHaptics(context: Context) : Haptics.Engine {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    private fun play(effect: Int) {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        runCatching { v.vibrate(VibrationEffect.createPredefined(effect)) }
    }

    override fun tap() = play(VibrationEffect.EFFECT_TICK)
    override fun thump() = play(VibrationEffect.EFFECT_CLICK)
    override fun success() = play(VibrationEffect.EFFECT_DOUBLE_CLICK)
    override fun warning() = play(VibrationEffect.EFFECT_HEAVY_CLICK)
    override fun select() = play(VibrationEffect.EFFECT_TICK)
}
