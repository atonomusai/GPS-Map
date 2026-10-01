package com.autonoumus.ai.core.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Walking/running speed from steps, for when GPS can't measure it - indoors (reflected signals,
 * no Doppler, positions jumping 10-40m) and on chips with poor walking-pace Doppler.
 *
 * Steps are detected from accelerometer peaks (no extra permission: the platform step-detector
 * sensor would need ACTIVITY_RECOGNITION on Android 10+). Speed = cadence x stride, where stride
 * grows with cadence and is scaled by a per-user factor calibrated against good outdoor GPS.
 */
@Singleton
class StepSpeedEstimator @Inject constructor(
    @ApplicationContext private val context: Context
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var isListening = false

    // Signal conditioning (sensor thread only)
    private var smoothed = SensorManager.GRAVITY_EARTH
    private var baseline = SensorManager.GRAVITY_EARTH
    private var inPeak = false
    private var peakValue = 0f
    private var peakTimeMs = 0L

    /** Recent step times (elapsedRealtime ms); guarded by itself. */
    private val stepTimes = ArrayDeque<Long>()

    @Volatile
    private var strideFactor: Float = prefs.getFloat(KEY_STRIDE_FACTOR, 1f)
    private var calibrationSamples = 0

    fun start() {
        if (isListening || sensorManager == null || accelerometer == null) return
        isListening = true
        smoothed = SensorManager.GRAVITY_EARTH
        baseline = SensorManager.GRAVITY_EARTH
        inPeak = false
        synchronized(stepTimes) { stepTimes.clear() }
        // ~50 Hz: steps are 1.5-3.5 Hz, the UI rate (~16 Hz) is too coarse to time them well
        try {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        } catch (e: Exception) {
            isListening = false
        }
    }

    fun stop() {
        if (!isListening) return
        isListening = false
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Exception) {}
        prefs.edit().putFloat(KEY_STRIDE_FACTOR, strideFactor).apply()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z) // orientation-independent

        smoothed += 0.3f * (magnitude - smoothed)   // removes sensor jitter, keeps step peaks
        baseline += 0.02f * (smoothed - baseline)   // ~1s moving level (gravity + posture)

        val now = SystemClock.elapsedRealtime()
        val excess = smoothed - baseline
        if (!inPeak) {
            if (excess > PEAK_THRESHOLD) {
                inPeak = true
                peakValue = excess
                peakTimeMs = now
            }
        } else {
            if (excess > peakValue) {
                peakValue = excess
                peakTimeMs = now
            }
            // Hysteresis: the peak ends only once the signal has clearly dropped again
            if (excess < PEAK_RELEASE) {
                inPeak = false
                registerStep(peakTimeMs)
            }
        }
    }

    private fun registerStep(timeMs: Long) {
        synchronized(stepTimes) {
            val last = stepTimes.lastOrNull()
            if (last != null && timeMs - last < MIN_STEP_INTERVAL_MS) return // double-peak of one step
            stepTimes.addLast(timeMs)
            while (stepTimes.size > 12) stepTimes.removeFirst()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Cadence in steps/s when the user is walking/running with a regular rhythm right now, else null. */
    private fun currentCadenceHz(): Float? {
        val now = SystemClock.elapsedRealtime()
        val recent = synchronized(stepTimes) { stepTimes.filter { now - it <= WINDOW_MS } }
        if (recent.size < MIN_STEPS || now - recent.last() > MAX_SINCE_LAST_STEP_MS) return null
        val intervals = recent.zipWithNext { a, b -> (b - a).toFloat() }
        val mean = intervals.average().toFloat()
        if (mean < MIN_STEP_INTERVAL_MS || mean > MAX_STEP_INTERVAL_MS) return null
        // Walking is rhythmic; bumps, taps and handling are not
        val variance = intervals.sumOf { ((it - mean) * (it - mean)).toDouble() } / intervals.size
        if (sqrt(variance).toFloat() / mean > MAX_INTERVAL_CV) return null
        return 1000f / mean
    }

    /** Uncalibrated stride model: ~0.71m at a 1.8 Hz walk, longer as cadence rises. */
    private fun modelSpeed(cadenceHz: Float): Float =
        cadenceHz * (0.28f + 0.24f * cadenceHz).coerceIn(0.45f, 1.4f)

    /** Walking/running speed from steps in m/s, or null when no regular stepping is detected. */
    fun speedMps(): Float? {
        val cadence = currentCadenceHz() ?: return null
        return (modelSpeed(cadence) * strideFactor).coerceAtMost(MAX_STEP_SPEED_MPS)
    }

    /**
     * Teach the stride length from a trustworthy outdoor GPS speed measured while walking,
     * so indoor step-based speed matches this user's actual stride.
     */
    fun calibrate(gpsSpeedMps: Float) {
        if (gpsSpeedMps !in 0.7f..3.5f) return
        val cadence = currentCadenceHz() ?: return
        val ratio = (gpsSpeedMps / modelSpeed(cadence)).coerceIn(0.6f, 1.6f)
        strideFactor = (strideFactor * 0.9f + ratio * 0.1f).coerceIn(0.6f, 1.6f)
        if (++calibrationSamples % 20 == 0) {
            prefs.edit().putFloat(KEY_STRIDE_FACTOR, strideFactor).apply()
        }
    }

    private companion object {
        const val PREFS = "step_speed"
        const val KEY_STRIDE_FACTOR = "stride_factor"

        /** m/s^2 above the moving baseline that starts / ends a step peak. */
        const val PEAK_THRESHOLD = 1.2f
        const val PEAK_RELEASE = 0.3f

        const val MIN_STEP_INTERVAL_MS = 280L   // ~3.6 steps/s (fast run)
        const val MAX_STEP_INTERVAL_MS = 1000L  // 1 step/s (very slow walk)
        const val WINDOW_MS = 3_500L
        const val MIN_STEPS = 4
        const val MAX_SINCE_LAST_STEP_MS = 1_300L
        const val MAX_INTERVAL_CV = 0.3f
        const val MAX_STEP_SPEED_MPS = 4.0f     // ~14 km/h
    }
}
