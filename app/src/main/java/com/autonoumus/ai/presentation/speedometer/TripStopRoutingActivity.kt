package com.autonoumus.ai.presentation.speedometer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.data.service.TripTrackingService
import com.autonoumus.ai.domain.model.TripRecord
import com.autonoumus.ai.domain.repository.TripHistoryRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Trampoline Activity triggered directly from the Notification "Stop Trip" action.
 * Since this is an Activity PendingIntent, Android OS guarantees bringing the app
 * to the foreground on all Android versions (SDK 24 to SDK 36), avoiding background launch blocks.
 */
@AndroidEntryPoint
class TripStopRoutingActivity : AppCompatActivity() {

    @Inject
    lateinit var tripHistoryRepository: TripHistoryRepository

    @Inject
    lateinit var locationHelper: LocationHelper

    @Inject
    lateinit var preferenceManager: PreferenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val liveState = TripTrackingService.liveTripState.value

        lifecycleScope.launch {
            val endLat = liveState.currentLat
            val endLon = liveState.currentLon
            var endAddressText = "Destination"

            // Reverse geocode destination if available. Geocoder is a blocking network call that
            // takes several seconds on a weak connection (10s+ offline), and this invisible
            // trampoline waited for it before even opening the summary - so the summary map
            // looked like it "took ages to load". Wait briefly, then fall back to coordinates.
            if (endLat != 0.0 && endLon != 0.0) {
                val loc = android.location.Location("GPS").apply {
                    latitude = endLat
                    longitude = endLon
                }
                // lifecycleScope (supervisor) instead of a child: a timed-out lookup is simply
                // abandoned and can't fail or hold up this coroutine.
                val geocode = lifecycleScope.async(Dispatchers.IO) { locationHelper.convertLocationToUserData(loc) }
                val data = try {
                    withTimeoutOrNull(GEOCODE_TIMEOUT_MS) { geocode.await() }
                } catch (e: Exception) {
                    null
                }
                endAddressText = when {
                    data == null -> String.format(Locale.US, "%.5f, %.5f", endLat, endLon)
                    data.cityName.isNotBlank() && data.cityName != "Current Location" -> data.cityName
                    else -> data.streetAddress
                }
            }

            val recordedDurationSec = liveState.durationSec.coerceAtLeast(1L)
            val recordedDistance = liveState.totalDistanceMeters
            val avgSpeedMps = if (recordedDurationSec > 0) (recordedDistance / recordedDurationSec).toFloat() else 0f
            val avgSpeedKmh = avgSpeedMps * 3.6f

            // Full-precision waypoints for permanent trip-history storage (disk, no size limit).
            var routePoints = liveState.waypoints

            var recordedStartLat = if (liveState.startLat != 0.0) liveState.startLat else (if (endLat != 0.0) endLat else locationHelper.getLastKnownLocation()?.latitude ?: 0.0)
            var recordedStartLon = if (liveState.startLon != 0.0) liveState.startLon else (if (endLon != 0.0) endLon else locationHelper.getLastKnownLocation()?.longitude ?: 0.0)
            var startAddressText = liveState.startAddress

            // Safety net: a start farther from the end than anything could travel in this trip's
            // time (with GPS error allowance) can't be real - e.g. a stale cached fix from another
            // town. Fall back to the first recorded route point, else the end point.
            if (recordedStartLat != 0.0 && endLat != 0.0) {
                val gap = FloatArray(1)
                android.location.Location.distanceBetween(recordedStartLat, recordedStartLon, endLat, endLon, gap)
                val maxPossibleMeters = MAX_PLAUSIBLE_SPEED_MPS * recordedDurationSec + GPS_ERROR_ALLOWANCE_M
                if (gap[0] > maxPossibleMeters) {
                    val fallback = liveState.waypoints.firstOrNull { (lat, lon) ->
                        val d = FloatArray(1)
                        android.location.Location.distanceBetween(lat, lon, endLat, endLon, d)
                        d[0] <= maxPossibleMeters
                    } ?: (endLat to endLon)
                    recordedStartLat = fallback.first
                    recordedStartLon = fallback.second
                    startAddressText = endAddressText
                    // Same test for the route line: drop points that can't belong to this trip
                    routePoints = routePoints.filter { (lat, lon) ->
                        val d = FloatArray(1)
                        android.location.Location.distanceBetween(lat, lon, endLat, endLon, d)
                        d[0] <= maxPossibleMeters
                    }
                }
            }

            val waypointsJson = encodeWaypointsToJson(routePoints)
            // Downsampled waypoints for the hand-off Intent to TripSummaryActivity - Android's
            // ~1MB Binder transaction limit makes an unbounded JSON blob a real crash risk for
            // long trips, so bound it regardless of how long the trip was.
            val waypointsJsonForIntent = downsampleWaypointsJson(waypointsJson)

            val recordedEndLat = if (endLat != 0.0) endLat else recordedStartLat
            val recordedEndLon = if (endLon != 0.0) endLon else recordedStartLon

            val totalSamples = liveState.safeSamplesCount + liveState.warningSamplesCount + liveState.overSpeedSamplesCount
            val safePercent: Int
            val warningPercent: Int
            val overSpeedPercent: Int
            val safetyScore: Int

            if (totalSamples > 0) {
                safePercent = Math.round((liveState.safeSamplesCount.toDouble() / totalSamples) * 100).toInt().coerceIn(0, 100)
                warningPercent = Math.round((liveState.warningSamplesCount.toDouble() / totalSamples) * 100).toInt().coerceIn(0, 100 - safePercent)
                overSpeedPercent = (100 - safePercent - warningPercent).coerceIn(0, 100)

                val overspeedDeduction = overSpeedPercent * 0.8
                val warningDeduction = warningPercent * 0.3
                val violationDeduction = (liveState.limitExceededCount * 5.0).coerceAtMost(30.0)
                safetyScore = (100.0 - overspeedDeduction - warningDeduction - violationDeduction).toInt().coerceIn(10, 100)
            } else {
                val isKmh = preferenceManager.isSpeedUnitKmh
                val limitRaw = preferenceManager.speedLimit
                val limitKmh = if (isKmh) limitRaw.toFloat() else limitRaw * 1.60934f
                val safeThresh = if (limitKmh > 0) limitKmh * 0.8f else 48f
                val warnThresh = if (limitKmh > 0) limitKmh else 60f
                val maxSpd = liveState.maxSpeedKmh
                val exceededCount = liveState.limitExceededCount

                when {
                    exceededCount == 0 && maxSpd <= safeThresh -> {
                        safePercent = 100
                        warningPercent = 0
                        overSpeedPercent = 0
                        safetyScore = 100
                    }
                    exceededCount == 0 && maxSpd <= warnThresh -> {
                        safePercent = 85
                        warningPercent = 15
                        overSpeedPercent = 0
                        safetyScore = 95
                    }
                    else -> {
                        val excessRatio = if (warnThresh > 0) ((maxSpd - warnThresh) / warnThresh).coerceIn(0.05f, 0.6f) else 0.2f
                        val computedOver = Math.round(excessRatio * 50).toInt().coerceIn(5, 40)
                        val computedWarn = Math.round(computedOver * 0.8).toInt().coerceIn(5, 30)
                        val computedSafe = (100 - computedOver - computedWarn).coerceIn(30, 90)

                        safePercent = computedSafe
                        warningPercent = computedWarn
                        overSpeedPercent = (100 - safePercent - warningPercent).coerceIn(0, 100)
                        val penalty = (exceededCount * 5).coerceAtMost(30)
                        safetyScore = (100 - (overSpeedPercent * 0.8).toInt() - (warningPercent * 0.3).toInt() - penalty).coerceIn(10, 100)
                    }
                }
            }

            val topSpeedLoc = if (liveState.topSpeedLocation.isNotBlank()) liveState.topSpeedLocation else startAddressText
            val limitExceeded = liveState.limitExceededCount

            val trip = TripRecord(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                durationSec = recordedDurationSec,
                distanceMeters = recordedDistance,
                avgSpeedKmh = avgSpeedKmh,
                maxSpeedKmh = liveState.maxSpeedKmh,
                startAddress = startAddressText,
                endAddress = endAddressText,
                startLat = recordedStartLat,
                startLon = recordedStartLon,
                endLat = recordedEndLat,
                endLon = recordedEndLon,
                waypointsJson = waypointsJson,
                limitExceededCount = limitExceeded,
                topSpeedLocation = topSpeedLoc,
                safePercent = safePercent,
                warningPercent = warningPercent,
                overSpeedPercent = overSpeedPercent,
                safetyScore = safetyScore
            )

            // Save to database (always save trip to history, even when distance is 0 km)
            withContext(Dispatchers.IO) {
                tripHistoryRepository.saveTrip(trip)
            }

            // Stop Foreground Service
            TripTrackingService.stop(this@TripStopRoutingActivity)
            TripTrackingService.resetLiveState()

            // Open Result Screen (TripSummaryActivity)
            val summaryIntent = TripSummaryActivity.newIntent(
                context = this@TripStopRoutingActivity,
                durationSec = recordedDurationSec,
                distanceMeters = recordedDistance,
                avgSpeedKmh = avgSpeedKmh,
                maxSpeedKmh = liveState.maxSpeedKmh,
                startAddress = startAddressText,
                endAddress = endAddressText,
                startLat = recordedStartLat,
                startLon = recordedStartLon,
                endLat = recordedEndLat,
                endLon = recordedEndLon,
                waypointsJson = waypointsJsonForIntent,
                limitExceededCount = limitExceeded,
                topSpeedLocation = topSpeedLoc,
                safePercent = safePercent,
                warningPercent = warningPercent,
                overSpeedPercent = overSpeedPercent,
                safetyScore = safetyScore
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            startActivity(summaryIntent)
            finish()
        }
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

    companion object {
        private const val GEOCODE_TIMEOUT_MS = 1500L
        /** Faster than any car on a normal road; a start beyond reach at this speed is bogus. */
        private const val MAX_PLAUSIBLE_SPEED_MPS = 70.0
        private const val GPS_ERROR_ALLOWANCE_M = 500.0

        fun createStopPendingIntent(context: Context): android.app.PendingIntent {
            val intent = Intent(context, TripStopRoutingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
            return android.app.PendingIntent.getActivity(context, 1002, intent, flags)
        }
    }
}
