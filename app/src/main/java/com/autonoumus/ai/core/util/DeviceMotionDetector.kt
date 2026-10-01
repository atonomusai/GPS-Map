package com.autonoumus.ai.core.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sensor-fusion physical motion detector for GPS Speedometer.
 * Compatible across all Android devices from SDK 24 (Android 7.0) to SDK 36 (Android 16+).
 *
 * Uses device linear acceleration and self-calibrating accelerometer filtering to distinguish
 * real physical movement (walking, running, vehicle motion) from stationary states (sitting, lying down, table resting).
 * Eliminates indoor GPS multipath jitter and phantom speed spikes when sitting in one place.
 */
@Singleton
class DeviceMotionDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    // TYPE_LINEAR_ACCELERATION is a *virtual* sensor fused from accelerometer + gyroscope. On many
    // budget phones without a real gyroscope it is still advertised but outputs ~0 constantly,
    // which made us report "stationary" forever and forced the speedometer to 0 while jogging.
    // Only trust it when a hardware gyroscope exists; otherwise use the raw accelerometer.
    private val linearAccel = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    }

    private var isListening = false

    @Volatile
    private var lastMovementTimeMs: Long = 0L

    @Volatile
    private var motionEnergy: Float = 0f

    // Time of the last sensor event. Some OEMs (Xiaomi, Oppo, Huawei...) stop delivering
    // non-wakeup sensor events when the screen is off; without this we would keep using the
    // last (stale) reading and could lock speed at 0 for the rest of the trip.
    @Volatile
    private var lastSampleTimeMs: Long = 0L

    private val gravityVec = FloatArray(3)
    private var hasGravityInit = false

    private val windowSamples = FloatArray(15)
    private var sampleIndex = 0
    private var sampleCount = 0

    fun start() {
        if (isListening || sensorManager == null) return
        val sensor = linearAccel ?: accelerometer ?: return
        isListening = true
        lastMovementTimeMs = 0L
        lastSampleTimeMs = 0L
        motionEnergy = 0f
        sampleIndex = 0
        sampleCount = 0
        hasGravityInit = false
        try {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        } catch (e: Exception) {}
    }

    fun stop() {
        if (!isListening || sensorManager == null) return
        isListening = false
        try {
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        val dynamicAccel: Float
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            dynamicAccel = sqrt(ax * ax + ay * ay + az * az)
        } else {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]

            // Auto-calibrating low-pass gravity filter for devices without hardware linear acceleration
            val alpha = 0.85f
            if (!hasGravityInit) {
                gravityVec[0] = ax
                gravityVec[1] = ay
                gravityVec[2] = az
                hasGravityInit = true
            } else {
                gravityVec[0] = alpha * gravityVec[0] + (1 - alpha) * ax
                gravityVec[1] = alpha * gravityVec[1] + (1 - alpha) * ay
                gravityVec[2] = alpha * gravityVec[2] + (1 - alpha) * az
            }

            val lx = ax - gravityVec[0]
            val ly = ay - gravityVec[1]
            val lz = az - gravityVec[2]
            val linearMag = sqrt(lx * lx + ly * ly + lz * lz)
            val totalMag = sqrt(ax * ax + ay * ay + az * az)
            val scalarDiff = abs(totalMag - 9.80665f)
            dynamicAccel = maxOf(linearMag, scalarDiff)
        }

        windowSamples[sampleIndex] = dynamicAccel
        sampleIndex = (sampleIndex + 1) % windowSamples.size
        if (sampleCount < windowSamples.size) sampleCount++

        var sum = 0f
        for (i in 0 until sampleCount) {
            sum += windowSamples[i]
        }
        val avgEnergy = sum / sampleCount
        motionEnergy = avgEnergy

        val now = System.currentTimeMillis()
        lastSampleTimeMs = now
        if (avgEnergy > 0.16f) {
            lastMovementTimeMs = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Returns true if physical movement (walking, running, vehicle vibration)
     * was detected within the last 2.0 seconds.
     */
    fun isPhysicallyMoving(): Boolean {
        if (!hasFreshSensorData()) return true // Fallback to allowing GPS if sensor is unavailable/silent
        val now = System.currentTimeMillis()
        return (now - lastMovementTimeMs) < 2000L || motionEnergy >= 0.15f
    }

    /**
     * Strict version of [isPhysicallyMoving]: true only when the sensor is running, has a full
     * window of samples, and actually measured movement in the last 2s. [isPhysicallyMoving]
     * answers "true" when it simply has no data yet (first second of a trip, silent sensor) so it
     * never blocks real GPS speed - but that default must not be enough to accept speed readings
     * the GPS itself marks as unreliable, or a phone lying on a table shows 20 km/h at start.
     */
    fun hasConfirmedMovement(): Boolean {
        if (!hasFreshSensorData() || sampleCount < windowSamples.size) return false
        return System.currentTimeMillis() - lastMovementTimeMs < 2000L
    }

    /**
     * Returns true if the device has been sitting still for more than 1.5 seconds.
     */
    fun isConfirmedStationary(): Boolean {
        // No (recent) sensor data means we simply don't know - never block GPS speed on that.
        if (!hasFreshSensorData() || sampleCount < windowSamples.size) return false
        val now = System.currentTimeMillis()
        return (now - lastMovementTimeMs) > 1500L && motionEnergy < 0.10f
    }

    private fun hasFreshSensorData(): Boolean {
        if (!isListening || lastSampleTimeMs == 0L) return false
        return System.currentTimeMillis() - lastSampleTimeMs < 3000L
    }
}
