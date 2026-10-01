package com.autonoumus.ai.core.util

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Universal vibration utility supporting Android SDK 24 (Nougat) through SDK 36 (Android 16).
 * Safely handles VibratorManager, VibrationEffect, AudioAttributes/VibrationAttributes,
 * and graceful fallback when vibrator hardware is missing or disabled.
 */
object VibrationHelper {

    private const val TAG = "VibrationHelper"

    /**
     * Triggers a noticeable alert vibration for overspeed warnings.
     * Pattern: [pause 0ms, vibrate 200ms, pause 100ms, vibrate 200ms]
     */
    fun vibrateOverspeedAlert(context: Context) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+: vibrate(VibrationEffect, VibrationAttributes) only exists from Android 13;
                // calling it on Android 12/12L threw NoSuchMethodError (not caught below) and crashed.
                val effect = VibrationEffect.createWaveform(
                    longArrayOf(0, 200, 100, 200),
                    intArrayOf(0, 255, 0, 255),
                    -1
                )
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_ALARM)
                    .build()
                vibrator.vibrate(effect, attrs)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // API 26 to 30: VibrationEffect with AudioAttributes
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1)
                val audioAttrs = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                vibrator.vibrate(effect, audioAttrs)
            } else {
                // API 24 to 25: Legacy Vibrator with pattern
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 200, 100, 200), -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute vibration: ${e.message}")
        }
    }

    /**
     * Triggers a single haptic pulse (e.g. for button tap or threshold cue).
     */
    fun vibrateShort(context: Context, durationMs: Long = 100L) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Short vibration failed: ${e.message}")
        }
    }

    fun vibrateButtonTap(context: Context) {
        vibrateShort(context, 35L)
    }

    private fun getVibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            null
        }
    }
}
