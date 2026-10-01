package com.autonoumus.ai.data.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.autonoumus.ai.R
import com.autonoumus.ai.core.util.DeviceMotionDetector
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.core.util.SpeedAlertManager
import com.autonoumus.ai.core.util.StepSpeedEstimator
import com.autonoumus.ai.core.util.VibrationHelper
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.domain.model.TripRecord
import com.autonoumus.ai.domain.repository.TripHistoryRepository
import com.autonoumus.ai.presentation.speedometer.SpeedometerActivity
import com.autonoumus.ai.presentation.speedometer.TripSummaryActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs

import android.os.SystemClock

data class TripLiveState(
    val isTracking: Boolean = false,
    val startTimeMs: Long = 0L,
    val durationSec: Long = 0L,
    val currentSpeedKmh: Float = 0f,
    val maxSpeedKmh: Float = 0f,
    val totalDistanceMeters: Double = 0.0,
    val startLat: Double = 0.0,
    val startLon: Double = 0.0,
    val currentLat: Double = 0.0,
    val currentLon: Double = 0.0,
    val startAddress: String = "Start Location",
    val currentAddress: String = "Current Location",
    val waypoints: List<Pair<Double, Double>> = emptyList(),
    val safeSamplesCount: Int = 0,
    val warningSamplesCount: Int = 0,
    val overSpeedSamplesCount: Int = 0,
    val limitExceededCount: Int = 0,
    val topSpeedLocation: String = "",
    // False until a usable GPS fix arrives (cold GPS start can take 10-60s) or if the signal is lost,
    // so the UI can say "Waiting for GPS" instead of an unexplained 0.
    val hasGpsFix: Boolean = false,
    // Speed currently comes from steps (indoors / GPS can't measure walking) instead of GPS
    val isStepBased: Boolean = false
)

@AndroidEntryPoint
class TripTrackingService : Service() {

    @Inject
    lateinit var locationHelper: LocationHelper

    @Inject
    lateinit var preferenceManager: PreferenceManager

    @Inject
    lateinit var tripHistoryRepository: TripHistoryRepository

    @Inject
    lateinit var motionDetector: DeviceMotionDetector

    @Inject
    lateinit var stepSpeedEstimator: StepSpeedEstimator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The 1s ticker and the location handler both read-modify-write the trip state and the speed
    // filter. On Dispatchers.Default they ran in parallel, so the ticker could overwrite a fresh
    // speed with the value it had read a moment earlier. Confining both to one thread fixes that.
    private val trackingDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var locationCollectorJob: Job? = null
    private var tickerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastLocation: Location? = null
    private var startLocation: Location? = null
    private var lastAlertTimeMs: Long = 0L
    private var lastNotificationUpdateTimeMs: Long = 0L

    // Low-speed walking & smoothing state
    private var smoothedSpeedMps: Float = 0f
    private var consecutiveZeroCount: Int = 0
    private var lastSpeedUpdateTimeMs: Long = 0L
    private var lastGpsFixTimeMs: Long = 0L
    // Last fix accepted from the real GNSS chip (GPS_PROVIDER), used to prefer it over FUSED/NETWORK.
    private var lastGnssFixTimeMs: Long = 0L
    // When this trip's first usable fix arrived (elapsedRealtime), to let GNSS settle first
    private var firstFixElapsedMs: Long = 0L
    private var lastDopplerStillElapsedMs: Long = 0L
    // Step-based speed hand-over (wall-clock ms like the other *TimeMs fields)
    private var lastTrustedGpsSpeedMs: Long = 0L
    private var lastVehicleSpeedMs: Long = 0L
    private var lastStepTickMs: Long = 0L

    companion object {
        private const val TAG = "TripTrackingService"
        private const val SPEED_DEBUG_TAG = "SpeedDebug"
        const val CHANNEL_ID = "gsm_trip_tracking_channel"
        const val NOTIFICATION_ID = 9001

        const val ACTION_START_TRACKING = "com.autonoumus.ai.action.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.autonoumus.ai.action.STOP_TRACKING"

        private const val MAX_WAYPOINTS_BEFORE_THINNING = 2000
        /** A last-known fix older than this is not "where the trip starts". */
        private const val SEED_FIX_MAX_AGE_MS = 20_000L
        /** GNSS position/Doppler settle time after the first fix of a trip. */
        private const val GPS_SETTLE_MS = 5_000L
        /** Doppler speeds below this are treated as standing still. */
        private const val DOPPLER_MIN_MPS = 0.5f
        /** A Doppler speed must be this many times its reported uncertainty to count as movement. */
        private const val DOPPLER_SIGNIFICANCE = 2.5f
        /** A GNSS fix must be at least this accurate for its speed to overrule step-based speed. */
        private const val TRUSTED_GPS_ACCURACY_M = 15f
        /** Steps take over after this long without a trustworthy GPS speed. */
        private const val STEPS_TAKEOVER_MS = 3_000L
        /** Trustworthy GPS above this (~16 km/h) means a vehicle; steps are ignored for a minute after. */
        private const val VEHICLE_SPEED_MPS = 4.5f
        private const val VEHICLE_STEP_LOCKOUT_MS = 60_000L

        private val _isTrackingState = MutableStateFlow(false)
        val isTrackingState: StateFlow<Boolean> = _isTrackingState.asStateFlow()

        private val _liveTripState = MutableStateFlow(TripLiveState())
        val liveTripState: StateFlow<TripLiveState> = _liveTripState.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, TripTrackingService::class.java).apply {
                action = ACTION_START_TRACKING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TripTrackingService::class.java).apply {
                action = ACTION_STOP_TRACKING
            }
            context.startService(intent)
        }

        fun resetLiveState() {
            _isTrackingState.value = false
            _liveTripState.value = TripLiveState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_TRACKING -> {
                stopTracking()
            }
            ACTION_START_TRACKING -> {
                if (!startForegroundServiceSafely()) {
                    // Without foreground status Android stops delivering location once the screen
                    // is off, and on Android 8+ the app would even crash ~10s later for not calling
                    // startForeground(). Stop cleanly so the UI returns to "Start" instead.
                    stopTracking()
                    return START_NOT_STICKY
                }
                startTracking()
            }
            null -> {
                // The system restarted us after killing the process (common on MIUI/HyperOS).
                // The trip lived only in memory, so it is gone; and Android 12+/14+ don't allow a
                // location foreground service to start from the background without background-
                // location permission. Previously this silently began a new "phantom" trip from 0
                // that couldn't get location updates - don't; stop instead.
                stopTracking()
                return START_NOT_STICKY
            }
        }
        // Not sticky: after a process kill the in-memory trip can't be resumed (see above)
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Trip Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active trip distance, speed, and time while tracking in background."
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    /** @return false if Android refused foreground status (e.g. location permission revoked). */
    private fun startForegroundServiceSafely(): Boolean {
        val initialNotification = buildNotification("Initializing tracking...", "Acquiring GPS fix")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
            false
        }
    }

    private fun startTracking() {
        if (_isTrackingState.value) return

        val now = System.currentTimeMillis()
        _isTrackingState.value = true
        smoothedSpeedMps = 0f
        consecutiveZeroCount = 0
        lastSpeedUpdateTimeMs = now
        lastGpsFixTimeMs = 0L
        lastGnssFixTimeMs = 0L
        firstFixElapsedMs = 0L
        lastDopplerStillElapsedMs = 0L
        lastTrustedGpsSpeedMs = 0L
        lastVehicleSpeedMs = 0L
        lastStepTickMs = 0L
        recentFixes.clear()

        // Start physical motion sensor fusion detector
        try {
            motionDetector.start()
        } catch (e: Exception) {}
        try {
            stepSpeedEstimator.start()
        } catch (e: Exception) {}

        // Seed the start only from a fix that is really "here and now". This used to take
        // GPS_PROVIDER's last-known fix unconditionally - on Xiaomi/Redmi (where the GPS chip is
        // rarely used; other apps mostly get network/fused fixes) that can be hours old and tens
        // of km away, so a 15s trip showed its start marker, first route point and start address
        // in another town. With no fresh fix, the first live fix becomes the start instead.
        startLocation = null
        lastLocation = null
        val initialLoc = freshSeedFix()
        if (initialLoc != null) {
            startLocation = initialLoc
            lastLocation = initialLoc
        }

        val initialLat = initialLoc?.latitude ?: 0.0
        val initialLon = initialLoc?.longitude ?: 0.0
        val initialWaypoints = if (initialLat != 0.0 && initialLon != 0.0) listOf(Pair(initialLat, initialLon)) else emptyList()

        _liveTripState.value = TripLiveState(
            isTracking = true,
            startTimeMs = now,
            durationSec = 0L,
            currentSpeedKmh = 0f,
            maxSpeedKmh = 0f,
            totalDistanceMeters = 0.0,
            startLat = initialLat,
            startLon = initialLon,
            currentLat = initialLat,
            currentLon = initialLon,
            startAddress = "Start Location",
            currentAddress = "Current Location",
            waypoints = initialWaypoints
        )

        if (initialLoc != null) resolveStartAddress(initialLoc)

        startTicker()
        startLocationUpdates()
    }

    /** Newest last-known fix from any provider, but only if it is at most 20s old and within 50m accuracy. */
    @SuppressLint("MissingPermission")
    private fun freshSeedFix(): Location? {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return null
        return try {
            locationManager.getProviders(true)
                .mapNotNull { provider -> try { locationManager.getLastKnownLocation(provider) } catch (e: Exception) { null } }
                .filter { fixAgeMs(it) in 0L..SEED_FIX_MAX_AGE_MS && it.hasAccuracy() && it.accuracy <= 50f }
                .minByOrNull { fixAgeMs(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun fixAgeMs(location: Location): Long =
        if (location.elapsedRealtimeNanos > 0L) {
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        } else {
            System.currentTimeMillis() - location.time
        }

    /**
     * Makes [location] the trip's start if it has none yet, and returns the start coordinates.
     * Called for stationary fixes too - a trip that begins (or stays) standing still used to
     * record no start of its own at all.
     */
    private fun recordStartIfNeeded(location: Location, current: TripLiveState): Pair<Double, Double> {
        if (startLocation != null) return current.startLat to current.startLon
        startLocation = location
        resolveStartAddress(location)
        return location.latitude to location.longitude
    }

    private fun resolveStartAddress(location: Location) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val userData = locationHelper.convertLocationToUserData(location)
                val addr = if (userData.cityName.isNotBlank() && userData.cityName != "Current Location") {
                    userData.cityName
                } else {
                    userData.streetAddress
                }
                withContext(trackingDispatcher) { _liveTripState.value = _liveTripState.value.copy(startAddress = addr) }
            } catch (e: Exception) {}
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch(trackingDispatcher) {
            while (isActive && _isTrackingState.value) {
                delay(1000L)
                val current = _liveTripState.value
                val elapsed = if (current.startTimeMs > 0) {
                    ((System.currentTimeMillis() - current.startTimeMs) / 1000L).coerceAtLeast(0L)
                } else 0L

                val now = System.currentTimeMillis()
                // Fast Zero-Speed Lock: If motion sensor confirms device is still / stationary,
                // or if no usable GPS fix has arrived for > 4.0s, decay speed to 0.0 km/h.
                // (Many phones deliver GPS at only 1Hz and occasionally skip a fix; the old 2s
                // window made the needle keep dropping to 0 while jogging.)
                var activeSpeed = current.currentSpeedKmh
                var distance = current.totalDistanceMeters
                var maxSpeed = current.maxSpeedKmh
                val isStationary = motionDetector.isConfirmedStationary()
                val isGpsStale = (now - lastSpeedUpdateTimeMs) > 4000L

                // Indoors (or whenever GPS hasn't produced a trustworthy speed for a few seconds)
                // walking/running speed comes from steps; GPS takes over again as soon as it can.
                val stepSpeed = if (isStationary) null else stepSpeedIfUsable(now)
                val stepsInCharge = stepSpeed != null && now - lastTrustedGpsSpeedMs > STEPS_TAKEOVER_MS

                if (stepSpeed != null && stepsInCharge) {
                    smoothedSpeedMps = if (smoothedSpeedMps <= 0.15f) stepSpeed
                        else 0.5f * stepSpeed + 0.5f * smoothedSpeedMps
                    activeSpeed = smoothedSpeedMps * 3.6f
                    lastSpeedUpdateTimeMs = now
                    // Indoor GPS positions jump tens of meters: distance walked = speed x time
                    val dtSec = if (lastStepTickMs > 0L) ((now - lastStepTickMs) / 1000.0).coerceIn(0.0, 2.0) else 1.0
                    distance += smoothedSpeedMps * dtSec
                    maxSpeed = maxOf(maxSpeed, activeSpeed)
                    lastStepTickMs = now
                    if (com.autonoumus.ai.BuildConfig.DEBUG) {
                        Log.d(SPEED_DEBUG_TAG, "steps in charge: step=${String.format(Locale.US, "%.2f", stepSpeed)} smoothed=$smoothedSpeedMps")
                    }
                } else {
                    val stepsJustStopped = lastStepTickMs > 0L
                    lastStepTickMs = 0L
                    // Stepping stopped with no trustworthy GPS speed to hand over to: that's a stop
                    val gpsSpeedCurrent = now - lastTrustedGpsSpeedMs <= STEPS_TAKEOVER_MS
                    if (isStationary || isGpsStale || (stepsJustStopped && !gpsSpeedCurrent)) {
                        smoothedSpeedMps = 0f
                        activeSpeed = 0f
                        SpeedAlertManager.stopAlert()
                    }
                }

                _liveTripState.value = current.copy(
                    durationSec = elapsed,
                    currentSpeedKmh = activeSpeed,
                    totalDistanceMeters = distance,
                    maxSpeedKmh = maxSpeed,
                    hasGpsFix = lastGpsFixTimeMs > 0L && (now - lastGpsFixTimeMs) < 5000L,
                    isStepBased = stepsInCharge
                )

                if (now - lastNotificationUpdateTimeMs >= 1000L) {
                    lastNotificationUpdateTimeMs = now
                    updateNotification()
                }
            }
        }
    }

    private fun startLocationUpdates() {
        locationCollectorJob?.cancel()
        locationCollectorJob = serviceScope.launch(trackingDispatcher) {
            locationHelper.observeSpeedometerUpdates().collect { location ->
                handleLocationUpdate(location)
            }
        }
    }

    private fun handleLocationUpdate(location: Location) {
        if (!_isTrackingState.value) return

        val nowMs = System.currentTimeMillis()

        // 0. Source arbitration: on Android 12+ we listen to GPS_PROVIDER *and* FUSED_PROVIDER.
        // On many phones (reproduced on Xiaomi/Redmi) the fused provider emits ~2 fixes for every
        // GPS fix, frequently Wi-Fi/cell based and without Doppler speed. Each of those used to be
        // counted as "speed = 0", so two in a row wiped out the real GPS speed and the meter sat at
        // 0 while jogging. While the GNSS chip itself is delivering good fixes, use only those;
        // fused/network fixes are only a fallback when GPS goes quiet (tunnels, indoors).
        val isGnssFix = location.provider == android.location.LocationManager.GPS_PROVIDER
        if (!isGnssFix && lastGnssFixTimeMs > 0L && (nowMs - lastGnssFixTimeMs) < 3000L) {
            return
        }

        // 1. Physical Motion Sensor Fusion Gate:
        // If the accelerometer confirms the phone is stationary (e.g. sitting on a chair/table),
        // strictly reject any GPS multipath reflection or coordinate drift spike!
        if (motionDetector.isConfirmedStationary()) {
            logSpeedDebug(location, location.accuracy, "forced 0: motion sensor reports stationary")
            // Still a valid fix: without this, standing still at the start of a trip showed
            // "Waiting for GPS..." forever even with a 4m fix, and the GNSS-preference below
            // wasn't primed, so the first fused fix after starting to walk was used too.
            smoothedSpeedMps = 0f
            val usable = location.hasAccuracy() && location.accuracy <= 50f && fixAgeMs(location) < 10_000L
            if (usable) {
                lastGpsFixTimeMs = nowMs
                if (isGnssFix) lastGnssFixTimeMs = nowMs
                // Standing still is still "here": record the start and the current position
                // (the trip's end point) from real fixes, not only while moving.
                val current = _liveTripState.value
                val (sLat, sLon) = recordStartIfNeeded(location, current)
                _liveTripState.value = current.copy(
                    currentSpeedKmh = 0f,
                    startLat = sLat,
                    startLon = sLon,
                    currentLat = location.latitude,
                    currentLon = location.longitude
                )
            } else {
                _liveTripState.value = _liveTripState.value.copy(currentSpeedKmh = 0f)
            }
            SpeedAlertManager.stopAlert()
            lastLocation = location
            return
        }

        // 2. Check fix age (monotonic elapsedRealtime preferred to avoid system clock drift).
        // Some GNSS HALs (notably several MediaTek devices) report elapsedRealtimeNanos = 0 or a
        // bogus value - a strict check there rejected EVERY fix and the speed never showed, so
        // only reject fixes that are clearly old and skip the check when the timestamp is unusable.
        if (location.elapsedRealtimeNanos > 0L) {
            val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
            if (ageMs in 10_000L..120_000L) return
        }

        // 3. Accuracy Gating: ignore only very noisy fixes. Doppler speed stays accurate even when
        // the position is off by 30-40m (phone in pocket/armband, city streets), and a 25m cut-off
        // threw away most fixes on many phones while jogging. Distance/route use stricter gates below.
        val accuracy = if (location.hasAccuracy()) location.accuracy else 999f
        if (accuracy > 50f) {
            logSpeedDebug(location, accuracy, "rejected: accuracy > 50m")
            return
        }
        lastGpsFixTimeMs = nowMs
        if (isGnssFix) lastGnssFixTimeMs = nowMs
        if (firstFixElapsedMs == 0L) firstFixElapsedMs = SystemClock.elapsedRealtime()
        recordRecentFix(location)
        // GNSS output (position AND Doppler) is unreliable for the first seconds after it starts
        val gpsSettled = SystemClock.elapsedRealtime() - firstFixElapsedMs >= GPS_SETTLE_MS

        val current = _liveTripState.value
        val isKmh = preferenceManager.isSpeedUnitKmh

        // 4. Extract Doppler / Chipset Hardware Speed (Ground Truth from Satellite Doppler Shift)
        var rawSpeedMps = 0f
        var speedValid = false          // usable, non-zero Doppler speed
        var dopplerSaysStill = false    // chip explicitly reports ~0 m/s with acceptable accuracy
        var displacementValid = false   // speed derived from position change over 3-10s
        var lowConfidenceSpeed = false  // Doppler accepted despite speedAcc > speed (walking pace)

        if (location.hasSpeed()) {
            val speedAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
                location.speedAccuracyMetersPerSecond
            } else -1f

            val candidateSpeed = location.speed
            // Check speed accuracy if reported by GNSS driver. Many chipsets (Xiaomi/Redmi, and
            // Pixel with the phone in hand or pocket) report a conservative 2-4 m/s uncertainty at
            // WALKING pace (~1.3 m/s) - larger than the speed itself. Such readings used to be
            // dropped completely, the 3-8s displacement fallback can't beat the GPS error at walking
            // pace either, so after 4s the ticker zeroed the meter: walking never showed on screen.
            // Doppler is still unbiased there, so trust it whenever the motion sensor confirms the
            // user is moving; the uncertainty only decides how much a single reading moves the
            // filter (see lowConfidenceSpeed below) and whether a near-zero value means "still".
            val confident = speedAcc < 0f || speedAcc <= 1.8f || speedAcc < candidateSpeed
            // Only real, measured movement may vouch for an unreliable reading - not the
            // "no sensor data yet" default of isPhysicallyMoving() in a trip's first second.
            val moving = motionDetector.hasConfirmedMovement() && gpsSettled
            if (!confident && candidateSpeed >= DOPPLER_MIN_MPS && moving) lowConfidenceSpeed = true
            if (confident || lowConfidenceSpeed) {
                // Dead band: below 0.5 m/s (1.8 km/h, slower than any walk), or not clearly above
                // its own uncertainty, a Doppler reading is noise - e.g. a still phone indoors got
                // 0.6 +/- 0.25 m/s from multipath and showed ~2-3 km/h. Walking (1-1.5 m/s at
                // +/- 0.3) passes easily. lowConfidenceSpeed readings are vouched for by the motion
                // sensor instead (their uncertainty is larger than the speed by definition).
                val significant = candidateSpeed >= DOPPLER_MIN_MPS &&
                    (speedAcc < 0f || lowConfidenceSpeed || candidateSpeed >= DOPPLER_SIGNIFICANCE * speedAcc)
                if (significant) {
                    val prev = lastLocation
                    val timeDeltaSec = if (prev != null) elapsedSeconds(prev, location).coerceIn(0.2, 5.0) else 1.0

                    // Physical G-force Acceleration Clamp:
                    // Max realistic human sprint acceleration is ~3.5 m/s²; passenger vehicle is ~3.5 - 4.5 m/s².
                    // If a sudden fix claims a delta > 4.5 m/s² (e.g. 0 -> 30 km/h in 0.5s), clamp to realistic slope.
                    val deltaSpeed = abs(candidateSpeed - smoothedSpeedMps)
                    val accel = (deltaSpeed / timeDeltaSec).toFloat()

                    if (accel > 4.5f && accuracy > 8f) {
                        val maxAllowedDelta = (4.5f * timeDeltaSec).toFloat()
                        rawSpeedMps = candidateSpeed.coerceIn(
                            (smoothedSpeedMps - maxAllowedDelta).coerceAtLeast(0f),
                            smoothedSpeedMps + maxAllowedDelta
                        )
                    } else {
                        rawSpeedMps = candidateSpeed
                    }
                    speedValid = true
                } else {
                    dopplerSaysStill = true
                    lastDopplerStillElapsedMs = SystemClock.elapsedRealtime()
                }
            }
        }

        // Displacement Speed Fallback for chipsets that don't report Doppler speed (hasSpeed() false
        // or unusable). Measured over a 3-10s baseline instead of fix-to-fix: at walking pace
        // (~1.3 m/s) one second is ~1.3m of travel, far inside any drift guard.
        // Never when the chip's own Doppler confidently says "not moving" - that used to be
        // overridden by this estimate, and while GPS settles after starting, the position wanders
        // tens of meters: ~20m in 3.5s read as 20 km/h with the phone lying on a table. Also not
        // before GNSS has had a few seconds to settle, and only with real measured movement.
        // Nor within 3s of the chip last reporting "still": some fixes in between carry no Doppler
        // (Pixel reports speedAccuracy=20 = unknown), and GPS drift alone must not fill that gap.
        val recentlyStill = SystemClock.elapsedRealtime() - lastDopplerStillElapsedMs < 3_000L
        if (!speedValid && !dopplerSaysStill && !recentlyStill && gpsSettled && accuracy <= 25f && motionDetector.hasConfirmedMovement()) {
            val baseline = recentFixes.firstOrNull { elapsedSeconds(it, location) in 5.0..10.0 }
            if (baseline != null) {
                val timeDeltaSec = elapsedSeconds(baseline, location)
                val dist = baseline.distanceTo(location)
                val baselineAcc = if (baseline.hasAccuracy()) baseline.accuracy else accuracy
                // Drift guard. GNSS position error is strongly correlated over a few seconds, so
                // consecutive fixes drift together, far less than their reported (68%) accuracy
                // radius apart. The old 1.2x-accuracy guard needed 12m+ in <=8s at 10m accuracy -
                // faster than walking. The motion sensor already requires real movement above.
                // A still phone's GPS drifts ~3m over a few seconds; demand clearly more than that
                // (walking covers 6.5m+ in the 5s minimum baseline).
                if (dist >= maxOf(5f, 0.8f * maxOf(accuracy, baselineAcc)) && dist <= 450f) {
                    val calcSpeed = (dist / timeDeltaSec).toFloat()
                    if (calcSpeed in 0.35f..45.0f) {
                        rawSpeedMps = calcSpeed
                        displacementValid = true
                    }
                }
            }
        }

        // Noise floor: anything below 0.30 m/s (~1.08 km/h) is stationary
        if (rawSpeedMps < 0.30f) {
            rawSpeedMps = 0f
        }

        // A fix with no usable Doppler speed and no displacement baseline carries NO information
        // about how fast we are going - it must not be read as "0 km/h" (that is what zeroed the
        // meter on Redmi phones). Leave the current speed as is; the 1s ticker still drops it to 0
        // if no real speed reading arrives for 4s.
        val hasSpeedInfo = speedValid || displacementValid || dopplerSaysStill
        logSpeedDebug(
            location, accuracy,
            "doppler=$speedValid lowConf=$lowConfidenceSpeed still=$dopplerSaysStill displacement=$displacementValid raw=${String.format(Locale.US, "%.2f", rawSpeedMps)}"
        )

        // GPS stays in charge of speed while it is trustworthy: a real GNSS fix within 15m with a
        // confident Doppler reading. Such readings also teach the step model this user's stride
        // and reveal vehicle travel (then steps are ignored - car vibration is not walking).
        val gpsTrustworthy = isGnssFix && accuracy <= TRUSTED_GPS_ACCURACY_M &&
            ((speedValid && !lowConfidenceSpeed) || dopplerSaysStill)
        if (gpsTrustworthy) {
            lastTrustedGpsSpeedMs = nowMs
            if (speedValid) {
                if (rawSpeedMps > VEHICLE_SPEED_MPS) lastVehicleSpeedMs = nowMs
                stepSpeedEstimator.calibrate(rawSpeedMps)
            }
        }
        // Otherwise, while the user is stepping, the 1s ticker drives speed from steps; this
        // (indoor-quality) fix must not overwrite it or add its jumpy displacement as distance.
        val stepsInCharge = !gpsTrustworthy && nowMs - lastTrustedGpsSpeedMs > STEPS_TAKEOVER_MS &&
            stepSpeedIfUsable(nowMs) != null
        val useFixSpeed = hasSpeedInfo && !stepsInCharge

        // 5. Adaptive Low-Pass / Kalman-like Smoothing Filter
        if (!useFixSpeed) {
            // keep smoothedSpeedMps unchanged
        } else if (rawSpeedMps <= 0f) {
            consecutiveZeroCount++
            if (consecutiveZeroCount >= 2 || !motionDetector.isPhysicallyMoving()) {
                smoothedSpeedMps = 0f
            } else {
                smoothedSpeedMps *= 0.3f
            }
        } else {
            consecutiveZeroCount = 0
            if (smoothedSpeedMps <= 0.15f) {
                smoothedSpeedMps = rawSpeedMps
            } else {
                // Low-confidence (walking-pace) Doppler readings are noisier: let each one move the
                // filter less so the display stays steady instead of jumping 3 <-> 7 km/h.
                val alpha = when {
                    lowConfidenceSpeed || displacementValid -> 0.45f
                    rawSpeedMps < smoothedSpeedMps -> 0.80f
                    else -> 0.70f
                }
                smoothedSpeedMps = (alpha * rawSpeedMps) + ((1f - alpha) * smoothedSpeedMps)
            }
        }

        val speedKmh = smoothedSpeedMps * 3.6f
        val displaySpeed = if (isKmh) speedKmh else smoothedSpeedMps * 2.23694f
        val newMaxSpeedKmh = maxOf(current.maxSpeedKmh, speedKmh)
        if (useFixSpeed) lastSpeedUpdateTimeMs = nowMs

        // 6. Distance Accumulation (only when moving and speed is real)
        var newDistance = current.totalDistanceMeters
        lastLocation?.let { prev ->
            val dist = prev.distanceTo(location)
            val timeDeltaSec = elapsedSeconds(prev, location)
            // Measured movement only: the "no sensor data" default let GPS jitter add distance
            if (!stepsInCharge && smoothedSpeedMps >= 0.28f && dist in 0.8..200.0 && timeDeltaSec in 0.3..5.0 && accuracy <= 20f && motionDetector.hasConfirmedMovement()) {
                newDistance += dist
            }
        }

        // 7. Start position recording
        val (startLat, startLon) = recordStartIfNeeded(location, current)

        // 8. Update waypoints (filter: at least 4.0m displacement from last waypoint)
        val updatedWaypoints = current.waypoints.toMutableList()
        val lastPoint = updatedWaypoints.lastOrNull()
        val shouldAddPoint = if (lastPoint == null) {
            true
        } else {
            val dist = FloatArray(1)
            Location.distanceBetween(lastPoint.first, lastPoint.second, location.latitude, location.longitude, dist)
            dist[0] >= 4.0f
        }
        if (shouldAddPoint && smoothedSpeedMps >= 0.28f && accuracy <= 25f) {
            updatedWaypoints.add(Pair(location.latitude, location.longitude))
        }

        // 9. Update speed distribution sample counts & limit exceeded count
        val limitRaw = preferenceManager.speedLimit
        val limitKmh = if (preferenceManager.isSpeedUnitKmh) limitRaw.toFloat() else limitRaw * 1.60934f
        val safeThreshold = if (limitKmh > 0) limitKmh * 0.8f else 48f
        val warningThreshold = if (limitKmh > 0) limitKmh else 60f

        var newSafe = current.safeSamplesCount
        var newWarning = current.warningSamplesCount
        var newOver = current.overSpeedSamplesCount
        var newExceeded = current.limitExceededCount

        when {
            limitRaw <= 0 -> newSafe++
            speedKmh <= safeThreshold -> newSafe++
            speedKmh <= warningThreshold -> newWarning++
            else -> {
                newOver++
                if (current.currentSpeedKmh <= warningThreshold) {
                    newExceeded++
                }
            }
        }

        lastLocation = location

        _liveTripState.value = current.copy(
            currentSpeedKmh = speedKmh,
            maxSpeedKmh = newMaxSpeedKmh,
            totalDistanceMeters = newDistance,
            startLat = startLat,
            startLon = startLon,
            currentLat = location.latitude,
            currentLon = location.longitude,
            waypoints = thinWaypointsIfNeeded(updatedWaypoints),
            safeSamplesCount = newSafe,
            warningSamplesCount = newWarning,
            overSpeedSamplesCount = newOver,
            limitExceededCount = newExceeded
        )

        // Resolve top speed location if this is a new max speed
        if (speedKmh > current.maxSpeedKmh && speedKmh > 3.0f) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val userData = locationHelper.convertLocationToUserData(location)
                    val addr = if (userData.cityName.isNotBlank() && userData.cityName != "Current Location") {
                        userData.cityName
                    } else {
                        userData.streetAddress
                    }
                    withContext(trackingDispatcher) { _liveTripState.value = _liveTripState.value.copy(topSpeedLocation = addr) }
                } catch (e: Exception) {}
            }
        }

        // 10. Overspeed limit check & vibration in background / foreground
        checkOverspeedAlert(displaySpeed)
    }

    /** Step-based speed (m/s) if the user is stepping and hasn't just been travelling by vehicle. */
    private fun stepSpeedIfUsable(nowMs: Long): Float? {
        if (lastVehicleSpeedMs > 0L && nowMs - lastVehicleSpeedMs < VEHICLE_STEP_LOCKOUT_MS) return null
        return stepSpeedEstimator.speedMps()
    }

    /** Recent accepted fixes (last ~10s) used as a baseline for displacement-based speed. */
    private val recentFixes = ArrayDeque<Location>()

    private fun recordRecentFix(location: Location) {
        recentFixes.addLast(location)
        while (recentFixes.size > 1 && elapsedSeconds(recentFixes.first(), location) > 10.0) {
            recentFixes.removeFirst()
        }
        if (recentFixes.size > 40) recentFixes.removeFirst()
    }

    /**
     * One line per location fix, to diagnose device-specific speed problems in the field:
     *   adb logcat -s SpeedDebug
     */
    private fun logSpeedDebug(location: Location, accuracy: Float, verdict: String) {
        if (!Log.isLoggable(SPEED_DEBUG_TAG, Log.DEBUG) && !com.autonoumus.ai.BuildConfig.DEBUG) return
        val speedAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
            location.speedAccuracyMetersPerSecond
        } else -1f
        Log.d(
            SPEED_DEBUG_TAG,
            "provider=${location.provider} acc=${accuracy} hasSpeed=${location.hasSpeed()} " +
                "speed=${location.speed} speedAcc=$speedAcc moving=${motionDetector.isPhysicallyMoving()} " +
                "stationary=${motionDetector.isConfirmedStationary()} -> $verdict smoothed=$smoothedSpeedMps"
        )
    }

    /**
     * Seconds between two fixes. Prefers the monotonic elapsedRealtimeNanos clock, but falls back
     * to the UTC fix time when a device's GNSS HAL leaves elapsedRealtimeNanos at 0.
     */
    private fun elapsedSeconds(from: Location, to: Location): Double {
        return if (from.elapsedRealtimeNanos > 0L && to.elapsedRealtimeNanos > 0L) {
            (to.elapsedRealtimeNanos - from.elapsedRealtimeNanos) / 1_000_000_000.0
        } else {
            (to.time - from.time) / 1000.0
        }
    }

    /**
     * A long trip (many hours) would otherwise grow this list without bound - both a memory
     * concern and, downstream, a TransactionTooLargeException risk when it's eventually
     * serialized into an Intent extra. Once past the threshold, halve it by dropping every
     * other point rather than truncating, so the whole trip's shape is preserved at lower detail.
     */
    private fun thinWaypointsIfNeeded(waypoints: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (waypoints.size <= MAX_WAYPOINTS_BEFORE_THINNING) return waypoints
        return waypoints.filterIndexed { index, _ -> index % 2 == 0 }
    }

    private fun checkOverspeedAlert(speed: Float) {
        val baseLimit = preferenceManager.speedLimit
        val alertToneEnabled = preferenceManager.isSpeedLimitAlertEnabled
        val vibrationEnabled = preferenceManager.isVibrationAlertEnabled
        val toleranceBuffer = if (preferenceManager.isSpeedToleranceBufferEnabled) 5 else 0
        val effectiveLimit = baseLimit + toleranceBuffer

        // Only trigger overspeed alert if device is physically moving and speed exceeds limit
        val isOverspeed = _isTrackingState.value && baseLimit > 0 && speed > effectiveLimit &&
                speed > 2.0f && motionDetector.isPhysicallyMoving()

        SpeedAlertManager.updateOverspeedStatus(
            context = applicationContext,
            isOverspeed = isOverspeed,
            isAudioEnabled = alertToneEnabled,
            isVibrationEnabled = vibrationEnabled
        )
    }

    private fun updateNotification() {
        val current = _liveTripState.value
        val isKmh = preferenceManager.isSpeedUnitKmh
        val unit = if (isKmh) "km/h" else "mph"
        val distUnit = if (isKmh) "km" else "mi"
        val dist = if (isKmh) current.totalDistanceMeters / 1000.0 else (current.totalDistanceMeters / 1000.0) * 0.621371
        val displaySpeed = if (isKmh) current.currentSpeedKmh else current.currentSpeedKmh * 0.621371f

        val timeStr = formatDuration(current.durationSec)
        val title = if (current.isStepBased) {
            String.format(Locale.US, "Tracking Active: %.0f %s (steps)", displaySpeed, unit)
        } else if (current.hasGpsFix) {
            String.format(Locale.US, "Tracking Active: %.0f %s", displaySpeed, unit)
        } else {
            "Waiting for GPS signal..."
        }
        val content = String.format(Locale.US, "Distance: %.1f %s | Time: %s", dist, distUnit, timeStr)

        val notification = buildNotification(title, content)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openIntent = Intent(this, SpeedometerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val stopPendingIntent = com.autonoumus.ai.presentation.speedometer.TripStopRoutingActivity.createStopPendingIntent(this)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSubText("GPS Tracking")
            .setSmallIcon(R.drawable.ic_navigation_arrow)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_close_gray, "Stop & View Result", stopPendingIntent)
            .addAction(R.drawable.ic_navigation_arrow, "Open App", openPendingIntent)
            .build()
    }

    private fun formatDuration(durationSec: Long): String {
        val mins = durationSec / 60
        val secs = durationSec % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    private fun stopTracking() {
        _isTrackingState.value = false
        tickerJob?.cancel()
        locationCollectorJob?.cancel()
        SpeedAlertManager.stopAlert()

        try {
            motionDetector.stop()
        } catch (e: Exception) {}
        try {
            stepSpeedEstimator.stop()
        } catch (e: Exception) {}

        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {}

        resetLiveState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun encodeWaypointsToJson(waypoints: List<Pair<Double, Double>>): String {
        return try {
            val array = JSONArray()
            for (p in waypoints) {
                val obj = JSONObject().apply {
                    put("lat", p.first)
                    put("lon", p.second)
                }
                array.put(obj)
            }
            array.toString()
        } catch (e: Exception) {
            ""
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "GSMCAMER:TripTrackingWakeLock"
                )?.apply {
                    // Maximum safety timeout of 6 hours to protect user battery
                    acquire(6 * 60 * 60 * 1000L)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not acquire wakelock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wakelock: ${e.message}")
        }
    }

    /**
     * Android 15+: called when a foreground service type reaches its system time limit. The
     * "location" type has no limit today (only dataSync / mediaProcessing do), but if a future
     * Android adds one, the service must stop within seconds or the app is crashed by the system.
     * Save the trip recorded so far (so the user doesn't lose it), then stop cleanly.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout (type=$fgsType) - saving trip and stopping")
        if (_isTrackingState.value) saveTripSnapshot(_liveTripState.value)
        stopTracking()
    }

    /** Minimal trip record from the live state (no network lookups - must finish quickly). */
    private fun saveTripSnapshot(state: TripLiveState) {
        val durationSec = state.durationSec.coerceAtLeast(1L)
        val totalSamples = state.safeSamplesCount + state.warningSamplesCount + state.overSpeedSamplesCount
        val safe = if (totalSamples > 0) Math.round(state.safeSamplesCount * 100.0 / totalSamples).toInt() else 100
        val warning = if (totalSamples > 0) Math.round(state.warningSamplesCount * 100.0 / totalSamples).toInt().coerceAtMost(100 - safe) else 0
        val over = (100 - safe - warning).coerceAtLeast(0)
        val trip = TripRecord(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            durationSec = durationSec,
            distanceMeters = state.totalDistanceMeters,
            avgSpeedKmh = (state.totalDistanceMeters / durationSec * 3.6).toFloat(),
            maxSpeedKmh = state.maxSpeedKmh,
            startAddress = state.startAddress,
            endAddress = state.currentAddress,
            startLat = state.startLat,
            startLon = state.startLon,
            endLat = state.currentLat,
            endLon = state.currentLon,
            waypointsJson = encodeWaypointsToJson(state.waypoints),
            limitExceededCount = state.limitExceededCount,
            topSpeedLocation = state.topSpeedLocation,
            safePercent = safe,
            warningPercent = warning,
            overSpeedPercent = over,
            safetyScore = (100 - (over * 0.8).toInt() - (warning * 0.3).toInt() -
                (state.limitExceededCount * 5).coerceAtMost(30)).coerceIn(10, 100)
        )
        // Own scope: serviceScope is cancelled in onDestroy, which follows stopSelf() immediately.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                tripHistoryRepository.saveTrip(trip)
            } catch (e: Exception) {
                Log.e(TAG, "Could not save trip on timeout: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        _isTrackingState.value = false
        SpeedAlertManager.stopAlert()
        try {
            motionDetector.stop()
        } catch (e: Exception) {}
        try {
            stepSpeedEstimator.stop()
        } catch (e: Exception) {}
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }
}
