package dev.personal.doomblocker.platform

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

object FocusFeedback {
    fun perform(context: Context) {
        runCatching {
            val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
            if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }
}
