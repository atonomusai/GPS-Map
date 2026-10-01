package com.autonoumus.ai.core.util

import android.location.Location
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TravelMode(val title: String, val osrmProfile: String, val iconResName: String, val avgSpeedMps: Double) {
    CAR("Car", "driving", "ic_car", 11.1),
    BIKE("Bike", "bicycle", "ic_bike", 4.2),
    WALKING("Walking", "foot", "ic_walking", 1.4)
}

data class RouteResult(
    val distanceMeters: Double,
    val durationSec: Long,
    val points: List<GeoPoint>,
    val waypointsJson: String,
    val summary: String = "",
    val routeName: String = "Fastest Route",
    val travelMode: TravelMode = TravelMode.CAR,
    val trafficStatus: String = "Low Traffic"
) {
    fun getFormattedDistance(isKm: Boolean = true): String {
        return if (isKm) {
            if (distanceMeters < 1000) {
                String.format(Locale.US, "%.0f m", distanceMeters)
            } else {
                String.format(Locale.US, "%.1f km", distanceMeters / 1000.0)
            }
        } else {
            val miles = (distanceMeters / 1000.0) * 0.621371
            if (miles < 0.1) {
                String.format(Locale.US, "%.0f ft", distanceMeters * 3.28084)
            } else {
                String.format(Locale.US, "%.1f mi", miles)
            }
        }
    }

    fun getFormattedDuration(): String {
        return when {
            durationSec < 60 -> "< 1 min"
            durationSec < 3600 -> {
                val mins = durationSec / 60
                "$mins mins"
            }
            else -> {
                val hrs = durationSec / 3600
                val mins = (durationSec % 3600) / 60
                if (mins > 0) "${hrs}h ${mins}m" else "${hrs}h"
            }
        }
    }

    fun getEstimatedArrivalTime(): String {
        val etaMillis = System.currentTimeMillis() + (durationSec * 1000)
        val sdf = SimpleDateFormat("hh:mm a", Locale.US)
        return sdf.format(Date(etaMillis))
    }
}

object OsmRoutingHelper {

    private const val TAG = "OsmRoutingHelper"
    private val USER_AGENT get() = AppLinks.userAgent
    private const val OSRM_BASE_URL = "https://router.project-osrm.org/route/v1"
    /**
     * FOSSGIS OSRM (routing.openstreetmap.de): free OSM routing with real car / bike / foot
     * profiles. The project-osrm.org demo only hosts the car profile - its "foot" and "bike"
     * URLs silently returned car routes (46 km walk in 46 min, checked 2026-09).
     */
    private const val FOSSGIS_BASE_URL = "https://routing.openstreetmap.de"

    suspend fun getDrivingRoute(
        startLat: Double,
        startLon: Double,
        destLat: Double,
        destLon: Double
    ): RouteResult = getRoute(startLat, startLon, destLat, destLon, TravelMode.CAR)

    suspend fun getRoute(
        startLat: Double,
        startLon: Double,
        destLat: Double,
        destLon: Double,
        mode: TravelMode = TravelMode.CAR
    ): RouteResult = getRoutes(startLat, startLon, destLat, destLon, mode).first()

    suspend fun getRoutes(
        startLat: Double,
        startLon: Double,
        destLat: Double,
        destLon: Double,
        mode: TravelMode = TravelMode.CAR
    ): List<RouteResult> = withContext(Dispatchers.IO) {
        val fossgisProfile = when (mode) {
            TravelMode.CAR -> "routed-car"
            TravelMode.BIKE -> "routed-bike"
            TravelMode.WALKING -> "routed-foot"
        }
        val query = "$startLon,$startLat;$destLon,$destLat?overview=full&geometries=geojson&alternatives=true&steps=true"

        try {
            val urlString = "$FOSSGIS_BASE_URL/$fossgisProfile/route/v1/driving/$query"
            val routeResults = executeOsrmQuery(urlString, mode, startLat, startLon, destLat, destLon)
            if (routeResults.isNotEmpty()) {
                return@withContext routeResults
            }
        } catch (e: Exception) {
            Log.w(TAG, "FOSSGIS $fossgisProfile failed: ${e.message}. Trying project-osrm fallback...")
        }

        // Car: second server with the same profile
        if (mode == TravelMode.CAR) {
            try {
                val results = executeOsrmQuery("$OSRM_BASE_URL/driving/$query", mode, startLat, startLon, destLat, destLon)
                if (results.isNotEmpty()) return@withContext results
            } catch (e: Exception) {
                Log.w(TAG, "OSRM driving failed: ${e.message}")
            }
        }

        // Bike / walk fallback: car road geometry with the mode's own travel time
        if (mode != TravelMode.CAR) {
            try {
                val drivingUrl = "$OSRM_BASE_URL/driving/$startLon,$startLat;$destLon,$destLat?overview=full&geometries=geojson&alternatives=true&steps=true"
                val drivingResults = executeOsrmQuery(drivingUrl, mode, startLat, startLon, destLat, destLon)
                if (drivingResults.isNotEmpty()) {
                    return@withContext drivingResults.map { r ->
                        val modeDuration = (r.distanceMeters / mode.avgSpeedMps).toLong().coerceAtLeast(60L)
                        r.copy(
                            durationSec = modeDuration,
                            travelMode = mode,
                            summary = "${mode.title} Route"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OSRM driving fallback failed: ${e.message}")
            }
        }

        // Final fallback: interpolated straight route
        listOf(generateFallbackRoute(startLat, startLon, destLat, destLon, mode))
    }

    private fun executeOsrmQuery(
        urlString: String,
        mode: TravelMode,
        startLat: Double,
        startLon: Double,
        destLat: Double,
        destLon: Double
    ): List<RouteResult> {
        val url = URL(urlString)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 6000
            readTimeout = 6000
        }

        val results = mutableListOf<RouteResult>()
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(responseText)
                val code = json.optString("code", "")

                if (code.equals("Ok", ignoreCase = true)) {
                    val routesArray = json.optJSONArray("routes")
                    if (routesArray != null && routesArray.length() > 0) {
                        for (rIdx in 0 until routesArray.length()) {
                            val routeObj = routesArray.getJSONObject(rIdx)
                            val distance = routeObj.optDouble("distance", 0.0)
                            var duration = routeObj.optDouble("duration", 0.0).toLong()

                            if (mode != TravelMode.CAR && duration < (distance / mode.avgSpeedMps) * 0.5) {
                                duration = (distance / mode.avgSpeedMps).toLong().coerceAtLeast(60L)
                            }

                            val geometryObj = routeObj.optJSONObject("geometry")
                            val coordsArray = geometryObj?.optJSONArray("coordinates")

                            val geoPoints = mutableListOf<GeoPoint>()
                            val waypointsJsonArray = JSONArray()

                            if (coordsArray != null) {
                                for (i in 0 until coordsArray.length()) {
                                    val coord = coordsArray.getJSONArray(i)
                                    val lon = coord.getDouble(0)
                                    val lat = coord.getDouble(1)
                                    geoPoints.add(GeoPoint(lat, lon))

                                    val wpObj = JSONObject().apply {
                                        put("lat", lat)
                                        put("lon", lon)
                                    }
                                    waypointsJsonArray.put(wpObj)
                                }
                            }

                            if (geoPoints.isNotEmpty()) {
                                val legs = routeObj.optJSONArray("legs")
                                val legSummary = if (legs != null && legs.length() > 0) {
                                    val leg0 = legs.getJSONObject(0)
                                    leg0.optString("summary", "").ifBlank {
                                        routeObj.optString("summary", "")
                                    }
                                } else {
                                    routeObj.optString("summary", "")
                                }

                                val routeName = when {
                                    rIdx == 0 -> if (legSummary.isNotBlank()) "Via $legSummary" else "Fastest Route"
                                    else -> if (legSummary.isNotBlank()) "Via $legSummary" else "Alternative ${rIdx}"
                                }

                                // No live traffic data is used, so don't claim any traffic state.
                                val trafficStatus = when (rIdx) {
                                    0 -> "Fastest"
                                    1 -> "Alternative Route"
                                    else -> "Alternative ${rIdx}"
                                }

                                results.add(
                                    RouteResult(
                                        distanceMeters = distance,
                                        durationSec = duration,
                                        points = geoPoints,
                                        waypointsJson = waypointsJsonArray.toString(),
                                        summary = if (legSummary.isNotBlank()) "Via $legSummary" else "${mode.title} Route",
                                        routeName = routeName,
                                        travelMode = mode,
                                        trafficStatus = trafficStatus
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        return results
    }

    private fun generateFallbackRoute(
        startLat: Double,
        startLon: Double,
        destLat: Double,
        destLon: Double,
        mode: TravelMode
    ): RouteResult {
        val distArr = FloatArray(1)
        Location.distanceBetween(startLat, startLon, destLat, destLon, distArr)
        val distance = distArr[0].toDouble()
        val durationSec = (distance / mode.avgSpeedMps).toLong().coerceAtLeast(60L)

        val points = mutableListOf<GeoPoint>()
        val waypointsArray = JSONArray()

        val steps = 12
        for (i in 0..steps) {
            val fraction = i.toDouble() / steps
            val lat = startLat + fraction * (destLat - startLat)
            val lon = startLon + fraction * (destLon - startLon)
            points.add(GeoPoint(lat, lon))

            val wp = JSONObject().apply {
                put("lat", lat)
                put("lon", lon)
            }
            waypointsArray.put(wp)
        }

        return RouteResult(
            distanceMeters = distance,
            durationSec = durationSec,
            points = points,
            waypointsJson = waypointsArray.toString(),
            // Offline / all routing servers failed: be explicit that this is a straight line.
            summary = "Straight-line estimate (routing unavailable)",
            routeName = "Straight-line estimate",
            travelMode = mode,
            trafficStatus = "Offline estimate"
        )
    }
}
