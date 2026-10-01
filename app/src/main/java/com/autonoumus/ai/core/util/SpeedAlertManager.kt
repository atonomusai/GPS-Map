package com.autonoumus.ai.core.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.autonoumus.ai.R

/**
 * Manages overspeed audio and haptic alerts.
 * Plays the warning sound in a loop at device media volume while overspeeding,
 * and stops immediately when speed drops back within the speed limit.
 */
object SpeedAlertManager {
    private const val TAG = "SpeedAlertManager"

    private var mediaPlayer: MediaPlayer? = null
    private var isAudioPlaying = false
    private var lastVibrationTimeMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
    fun updateOverspeedStatus(
        context: Context,
        isOverspeed: Boolean,
        isAudioEnabled: Boolean,
        isVibrationEnabled: Boolean
    ) {
        mainHandler.post {
            handleStatusUpdate(context.applicationContext, isOverspeed, isAudioEnabled, isVibrationEnabled)
        }
    }

    private fun handleStatusUpdate(
        context: Context,
        isOverspeed: Boolean,
        isAudioEnabled: Boolean,
        isVibrationEnabled: Boolean
    ) {
        if (isOverspeed) {
            // 1. Audio loop alert
            if (isAudioEnabled) {
                startAudioLoop(context)
            } else {
                stopAudio()
            }

            // 2. Vibration pulse
            if (isVibrationEnabled) {
                val now = System.currentTimeMillis()
                if (now - lastVibrationTimeMs > 1500L) {
                    lastVibrationTimeMs = now
                    VibrationHelper.vibrateOverspeedAlert(context)
                }
            }
        } else {
            // Speed dropped within limit range -> stop all alerts immediately
            stopAlert()
        }
    }

    private fun startAudioLoop(context: Context) {
        if (mediaPlayer != null && isAudioPlaying) {
            return
        }

        try {
            stopAudio()
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .build()

            val player = MediaPlayer.create(context, R.raw.speed_warning_beep, audioAttrs, 0)
                ?: MediaPlayer.create(context, R.raw.speed_warning_beep)

            if (player != null) {
                player.isLooping = true
                player.setVolume(1.0f, 1.0f)
                player.setOnErrorListener { mp, _, _ ->
                    try {
                        mp.reset()
                        mp.release()
                    } catch (e: Exception) {}
                    mediaPlayer = null
                    isAudioPlaying = false
                    true
                }
                player.start()
                mediaPlayer = player
                isAudioPlaying = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start overspeed warning audio: ${e.message}", e)
            mediaPlayer = null
            isAudioPlaying = false
        }
    }

    private fun stopAudio() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio: ${e.message}")
        } finally {
            mediaPlayer = null
            isAudioPlaying = false
        }
    }

    @Synchronized
    fun stopAlert() {
        mainHandler.post {
            stopAudio()
            lastVibrationTimeMs = 0L
        }
    }
}
