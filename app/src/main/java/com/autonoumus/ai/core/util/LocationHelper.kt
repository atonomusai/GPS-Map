package com.autonoumus.ai.core.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.domain.model.UserLocationData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.location.LocationListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

@Singleton
class LocationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferenceManager: PreferenceManager
) {

    // Reverse-geocoding (Geocoder.getFromLocation) is a slow, network/service-backed blocking call.
    // The camera stamp refreshes every 5s while previewing, but the user rarely moves far enough in
    // 5s to change the resolved address - so cache the last geocode result and skip the call entirely
    // when the new fix is within [GEOCODE_CACHE_DISTANCE_METERS] and [GEOCODE_CACHE_MAX_AGE_MS] of the
    // cached one, instead of paying the geocoder's latency on every single refresh tick.
    @Volatile private var cachedGeocodeLocation: Location? = null
    @Volatile private var cachedGeocodeResult: Triple<String, String, String>? = null
    @Volatile private var cachedGeocodeTimeMs: Long = 0L

    // One shared "where is the user" for the whole app. Home (or any screen) resolves a fix plus
    // its address; before, every other module then started from scratch - its own GPS request
    // and its own network reverse-geocode - so each screen waited seconds for data the app
    // already had. Every fix resolved by the functions below is published here, and those same
    // functions answer from it first while it is recent.
    private class SharedFix(val data: UserLocationData, val fixElapsedMs: Long)
    @Volatile private var sharedFix: SharedFix? = null
    private val _currentLocation = MutableStateFlow<UserLocationData?>(null)

    /** Latest user location (with address) resolved anywhere in the app; null until the first fix. */
    val currentLocation: StateFlow<UserLocationData?> = _currentLocation.asStateFlow()

    /**
     * The most recent fix (and its address) that any screen already resolved in this app session,
     * if it is at most [maxAgeMs] old - no GPS wait, no geocoding.
     */
    fun sharedLocation(maxAgeMs: Long = SHARED_FIX_MAX_AGE_MS): UserLocationData? {
        val fix = sharedFix ?: return null
        if (!hasLocationPermission()) return null
        if (SystemClock.elapsedRealtime() - fix.fixElapsedMs > maxAgeMs) return null
        // Position/address are reused; the date/time shown on stamps must still be "now"
        return fix.data.copy(
            timestampFormatted = getFormattedCurrentTime(),
            dateFormatted = getFormattedCurrentDate(),
            timeFormatted = getFormattedCurrentTimeOnly()
        )
    }

    /**
     * [sharedLocation] for one-shot "where am I now" calls. GPS sends no updates while the user
     * stands still, so the shared fix's age alone says little; it is reused as long as the
     * device's newest cached fix (free to read) is still within [SHARED_FIX_REUSE_DISTANCE_M] of it.
     */
    private fun reusableSharedLocation(): UserLocationData? {
        val shared = sharedLocation() ?: return null
        val newest = getLastKnownLocation() ?: return shared
        val moved = FloatArray(1)
        Location.distanceBetween(shared.rawLatitude ?: return null, shared.rawLongitude ?: return null,
            newest.latitude, newest.longitude, moved)
        return shared.takeIf { moved[0] <= SHARED_FIX_REUSE_DISTANCE_M }
    }

    private fun publish(data: UserLocationData, location: Location): UserLocationData {
        if (data.rawLatitude == null || data.rawLongitude == null) return data
        // Age by when the fix was taken, not when it was published (a last-known fix can be old)
        val fixElapsedMs = if (location.elapsedRealtimeNanos > 0L) location.elapsedRealtimeNanos / 1_000_000L
            else SystemClock.elapsedRealtime() - (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
        val current = sharedFix
        if (current == null || fixElapsedMs >= current.fixElapsedMs) {
            sharedFix = SharedFix(data, fixElapsedMs)
            _currentLocation.value = data
        }
        return data
    }

    private fun resolveAddress(lat: Double, lon: Double, coordStr: String): Triple<String, String, String> {
        val now = System.currentTimeMillis()
        val cachedLocation = cachedGeocodeLocation
        val cachedResult = cachedGeocodeResult
        if (cachedLocation != null && cachedResult != null &&
            (now - cachedGeocodeTimeMs) < GEOCODE_CACHE_MAX_AGE_MS
        ) {
            val results = FloatArray(1)
            Location.distanceBetween(lat, lon, cachedLocation.latitude, cachedLocation.longitude, results)
            if (results[0] <= GEOCODE_CACHE_DISTANCE_METERS) {
                return cachedResult
            }
        }

        var country = ""
        var city = "Current Location"
        var street = coordStr
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val locality = addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: "Current Location"
                country = addr.countryName ?: ""
                city = if (country.isNotBlank()) "$locality, $country" else locality
                street = addr.getAddressLine(0) ?: addr.thoroughfare ?: addr.featureName ?: coordStr
            }
        } catch (e: Exception) {
            // Fallback
        }

        val result = Triple(country, city, street)
        cachedGeocodeLocation = Location("cache").apply { latitude = lat; longitude = lon }
        cachedGeocodeResult = result
        cachedGeocodeTimeMs = now
        return result
    }

    fun hasLocationPermission(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineLocation || coarseLocation
    }

    /**
     * Instant, non-blocking best cached device fix (no fresh GPS request, no reverse geocoding).
     * Used by map screens to center the map immediately on open instead of waiting up to 8s for
     * [getRealLiveLocationData]; the live fix can then refine the position afterwards.
     */
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        var best: Location? = null
        for (provider in locationManager.getProviders(true)) {
            val loc = try {
                locationManager.getLastKnownLocation(provider)
            } catch (e: Exception) {
                null
            } ?: continue
            // Prefer the newest fix; among fixes of similar age, prefer the more accurate one.
            if (best == null || loc.time - best.time > 60_000L ||
                (Math.abs(loc.time - best.time) <= 60_000L && loc.accuracy < best.accuracy)
            ) {
                best = loc
            }
        }
        return best
    }

    /**
     * Streams live real GPS location updates from the device hardware.
     * Only emits when a valid device fix or real location callback is received.
     */
    @SuppressLint("MissingPermission")
    fun observeLocationUpdates(): Flow<UserLocationData> = callbackFlow {
        if (!hasLocationPermission()) {
            close()
            return@callbackFlow
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            close()
            return@callbackFlow
        }

        // Tracks the best fix emitted so far so a later low-accuracy (e.g. NETWORK_PROVIDER)
        // update can't silently regress an already-good GPS fix. Still allowed through after
        // 10s regardless of accuracy so the position keeps tracking as the user actually moves.
        var lastEmitted: Location? = null

        fun maybeEmit(location: Location) {
            val previous = lastEmitted
            val shouldEmit = previous == null ||
                location.accuracy <= previous.accuracy ||
                (location.time - previous.time) > 10_000L
            if (!shouldEmit) return
            lastEmitted = location
            launch(Dispatchers.IO) {
                val data = publish(convertLocationToUserData(location, isLive = true), location)
                trySend(data)
            }
        }

        // Instant first emission: the fix + address another screen (usually Home) already
        // resolved moments ago. Without it, every collector waited for its own fix AND a network
        // reverse-geocode before getting anything. Live updates below refine it.
        sharedLocation()?.let { trySend(it) }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Log.d("FamousPlaceDebug", "📍 [GPS Callback] Real Location Received from device: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}")
                maybeEmit(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        val providers = listOfNotNull(
            LocationManager.GPS_PROVIDER.takeIf { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) },
            LocationManager.NETWORK_PROVIDER.takeIf { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
        )

        // Check if there is an existing recent device fix
        var bestLocation: Location? = null
        for (provider in providers) {
            try {
                val loc = locationManager.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                    bestLocation = loc
                }
            } catch (e: Exception) {}
        }

        if (bestLocation != null) {
            Log.d("FamousPlaceDebug", "📍 [Initial Device Fix] Last Known Location: lat=${bestLocation.latitude}, lon=${bestLocation.longitude}")
            maybeEmit(bestLocation)
        }

        val handler = Handler(Looper.getMainLooper())
        handler.post {
            for (provider in providers) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        4000L,
                        5f,
                        listener,
                        Looper.getMainLooper()
                    )
                    Log.d("FamousPlaceDebug", "📡 Registered requestLocationUpdates for provider: $provider")
                } catch (e: Exception) {
                    Log.e("FamousPlaceDebug", "🚨 Error registering requestLocationUpdates for $provider: ${e.message}")
                }
            }
        }

        awaitClose {
            handler.post {
                try {
                    locationManager.removeUpdates(listener)
                } catch (e: Exception) {}
            }
        }
    }

    /**
     * Streams high-frequency (500ms, 0m distance constraint) raw Location updates from all active
     * providers (GPS, Network, Fused) tailored for real-time Speedometer & Trip tracking.
     * Ensures accurate low-speed walking detection and seamless indoor/outdoor transitions.
     */
    @SuppressLint("MissingPermission")
    fun observeSpeedometerUpdates(): Flow<Location> = callbackFlow {
        if (!hasLocationPermission()) {
            close()
            return@callbackFlow
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            close()
            return@callbackFlow
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        val providers = mutableListOf<String>()
        val hasGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (hasGps) {
            providers.add(LocationManager.GPS_PROVIDER)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                try {
                    if (locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER) &&
                        !providers.contains(LocationManager.FUSED_PROVIDER)
                    ) {
                        providers.add(LocationManager.FUSED_PROVIDER)
                    }
                } catch (e: Exception) {}
            }
        } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            // Only fallback to network if GPS is completely disabled in system settings
            providers.add(LocationManager.NETWORK_PROVIDER)
        }

        if (providers.isEmpty()) {
            close()
            return@callbackFlow
        }

        // Seed initial fix only if fresh (< 3000ms old) to avoid stale needle sweeps on launch
        var bestRecentFix: Location? = null
        for (provider in providers) {
            try {
                val lastFix = locationManager.getLastKnownLocation(provider) ?: continue
                if ((System.currentTimeMillis() - lastFix.time) < 3000L) {
                    if (bestRecentFix == null || lastFix.accuracy < bestRecentFix.accuracy) {
                        bestRecentFix = lastFix
                    }
                }
            } catch (e: Exception) {}
        }
        if (bestRecentFix != null) {
            trySend(bestRecentFix)
        }

        val handler = Handler(Looper.getMainLooper())
        handler.post {
            for (provider in providers) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        500L,
                        0f,
                        listener,
                        Looper.getMainLooper()
                    )
                    Log.d("LocationHelper", "Speedometer registered on provider: $provider")
                } catch (e: Exception) {
                    Log.e("LocationHelper", "Speedometer GPS listener registration failed for $provider: ${e.message}")
                }
            }
        }

        awaitClose {
            handler.post {
                try {
                    locationManager.removeUpdates(listener)
                } catch (e: Exception) {}
            }
        }
    }

    suspend fun convertLocationToUserData(location: Location, isLive: Boolean = true): UserLocationData = withContext(Dispatchers.IO) {
        val lat = location.latitude
        val lon = location.longitude
        val latDirection = if (lat >= 0) "N" else "S"
        val lonDirection = if (lon >= 0) "E" else "W"
        val coordStr = String.format(
            Locale.US,
            "%.4f° %s, %.4f° %s",
            Math.abs(lat),
            latDirection,
            Math.abs(lon),
            lonDirection
        )
        val altStr = String.format(Locale.US, "%.1f m", location.altitude)
        val accStr = String.format(Locale.US, "± %.1f m", location.accuracy)

        val (country, city, street) = resolveAddress(lat, lon, coordStr)

        val dynamicDeclination = calculateDeclination(lat, lon, location.altitude)

        UserLocationData(
            countryName = country,
            cityName = city,
            streetAddress = street,
            coordinatesFormatted = coordStr,
            timestampFormatted = getFormattedCurrentTime(),
            dateFormatted = getFormattedCurrentDate(),
            timeFormatted = getFormattedCurrentTimeOnly(),
            altitude = altStr,
            accuracy = accStr,
            rawLatitude = lat,
            rawLongitude = lon,
            rawAltitudeMeters = location.altitude,
            rawAccuracyMeters = location.accuracy,
            rawSpeedMps = location.speed,
            magneticDeclination = dynamicDeclination,
            isLiveGpsFix = isLive
        )
    }

    /**
     * Retrieves the real live GPS location from device LocationManager.
     * Always shows true device position for Home screen, Compass screen, and Map "My Location".
     */
    @SuppressLint("MissingPermission")
    suspend fun getRealLiveLocationData(): UserLocationData = withContext(Dispatchers.IO) {
        val disabledData = UserLocationData(
            cityName = "Location Disabled",
            streetAddress = "Location Disabled",
            coordinatesFormatted = "NO GEOTAG",
            timestampFormatted = getFormattedCurrentTime(),
            dateFormatted = getFormattedCurrentDate(),
            timeFormatted = getFormattedCurrentTimeOnly(),
            altitude = "--",
            accuracy = "--",
            rawLatitude = null,
            rawLongitude = null,
            rawAltitudeMeters = null,
            rawAccuracyMeters = null,
            rawSpeedMps = 0f,
            magneticDeclination = 0f,
            isLiveGpsFix = false
        )

        if (!hasLocationPermission()) {
            return@withContext disabledData
        }

        try {
            // A fix + address another screen already resolved: instant, no GPS, no geocoding
            reusableSharedLocation()?.let { return@withContext it }

            // First check if there is a recent cached fix (<15s) so callers get an instant response
            val cached = getLastKnownLocation()
            if (cached != null && (System.currentTimeMillis() - cached.time) < 15_000L) {
                return@withContext publish(convertLocationToUserData(cached, isLive = true), cached)
            }

            // Otherwise, request a fresh fix with a responsive timeout (2.5s)
            val freshFix = requestFreshFix(timeoutMs = 2500L, desiredAccuracyMeters = 30f)
            if (freshFix != null) {
                return@withContext publish(convertLocationToUserData(freshFix, isLive = true), freshFix)
            }

            // Fall back to any cached fix
            if (cached != null) {
                return@withContext publish(convertLocationToUserData(cached, isLive = true), cached)
            }
        } catch (e: Exception) {
            // In case of error, return disabled fallback
        }

        disabledData
    }

    /**
     * Actively requests a new location fix instead of relying on a possibly stale cached one.
     * Resolves as soon as a fix at or better than [desiredAccuracyMeters] arrives, or after
     * [timeoutMs] returns the best fix seen so far (may be null if nothing arrived at all).
     */
    @SuppressLint("MissingPermission")
    private suspend fun requestFreshFix(
        timeoutMs: Long = 2500L,
        desiredAccuracyMeters: Float = 30f
    ): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        val providers = listOfNotNull(
            LocationManager.GPS_PROVIDER.takeIf { locationManager.isProviderEnabled(it) },
            LocationManager.NETWORK_PROVIDER.takeIf { locationManager.isProviderEnabled(it) }
        )
        if (providers.isEmpty()) return null

        var bestSoFar: Location? = null

        val earlyResult = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location> { cont ->
                val handler = Handler(Looper.getMainLooper())
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        val previousBest = bestSoFar
                        if (previousBest == null || location.accuracy < previousBest.accuracy) {
                            bestSoFar = location
                        }
                        if (location.accuracy <= desiredAccuracyMeters && cont.isActive) {
                            try {
                                locationManager.removeUpdates(this)
                            } catch (e: Exception) {}
                            cont.resume(location)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                handler.post {
                    providers.forEach { provider ->
                        try {
                            locationManager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                        } catch (e: Exception) {}
                    }
                }

                cont.invokeOnCancellation {
                    handler.post {
                        try {
                            locationManager.removeUpdates(listener)
                        } catch (e: Exception) {}
                    }
                }
            }
        }

        return earlyResult ?: bestSoFar
    }

    /**
     * Retrieves location specifically for camera template stamp overlays.
     * Uses custom location stamp only when user customized the template.
     * Otherwise defaults to real live device GPS.
     */
    suspend fun getCameraStampLocationData(): UserLocationData = withContext(Dispatchers.IO) {
        if (preferenceManager.isCustomLocationOverrideEnabled) {
            val country = preferenceManager.customLocationCountry
            val address = preferenceManager.customLocationAddress
            val latStr = preferenceManager.customLocationLatitude
            val lonStr = preferenceManager.customLocationLongitude
            val coordsFormatted = "$latStr, $lonStr"
            // A custom place has no measured altitude/accuracy: don't invent them (they used to be
            // a fixed 540 m / ±1 m, and unreadable coordinates silently became Miami).
            val latNum = parseLatitude(latStr)
            val lonNum = parseLongitude(lonStr)
            val declination = if (latNum != null && lonNum != null) calculateDeclination(latNum, lonNum, 0.0) else 0f

            return@withContext UserLocationData(
                cityName = country,
                streetAddress = address,
                coordinatesFormatted = coordsFormatted,
                timestampFormatted = getFormattedCurrentTime(),
                dateFormatted = getFormattedCurrentDate(),
                timeFormatted = getFormattedCurrentTimeOnly(),
                altitude = "--",
                accuracy = CUSTOM_LOCATION_ACCURACY,
                rawLatitude = latNum,
                rawLongitude = lonNum,
                rawAltitudeMeters = null,
                rawAccuracyMeters = null,
                magneticDeclination = declination
            )
        }

        reusableSharedLocation()?.let { return@withContext it }

        val lastKnown = getLastKnownLocation()
        if (lastKnown != null) {
            return@withContext publish(convertLocationToUserData(lastKnown, isLive = true), lastKnown)
        }

        getRealLiveLocationData()
    }

    suspend fun getCurrentLocationData(forCameraStamp: Boolean = false): UserLocationData {
        return if (forCameraStamp) getCameraStampLocationData() else getRealLiveLocationData()
    }

    private fun calculateDeclination(lat: Double, lon: Double, altMeters: Double): Float {
        return try {
            val geoField = GeomagneticField(
                lat.toFloat(),
                lon.toFloat(),
                altMeters.toFloat(),
                System.currentTimeMillis()
            )
            geoField.declination
        } catch (e: Exception) {
            1.8f
        }
    }

    private fun parseLatitude(latStr: String): Double? {
        val clean = latStr.replace("°", "").replace("N", "", ignoreCase = true).trim()
        if (latStr.contains("S", ignoreCase = true)) {
            return clean.replace("S", "", ignoreCase = true).trim().toDoubleOrNull()?.let { -it }
        }
        return clean.toDoubleOrNull()
    }

    private fun parseLongitude(lonStr: String): Double? {
        val clean = lonStr.replace("°", "").replace("E", "", ignoreCase = true).trim()
        if (lonStr.contains("W", ignoreCase = true)) {
            return clean.replace("W", "", ignoreCase = true).trim().toDoubleOrNull()?.let { -it }
        }
        return clean.toDoubleOrNull()
    }

    fun getFormattedCurrentTime(): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy  hh:mm a", Locale.US)
        return sdf.format(Date())
    }

    fun getFormattedCurrentDate(): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        return sdf.format(Date())
    }

    fun getFormattedCurrentTimeOnly(): String {
        val sdf = SimpleDateFormat("hh:mm a", Locale.US)
        return sdf.format(Date())
    }

    companion object {
        /** UserLocationData.accuracy marker for a user-entered (custom) stamp location. */
        const val CUSTOM_LOCATION_ACCURACY = "Custom location"

        private const val GEOCODE_CACHE_DISTANCE_METERS = 40f
        private const val GEOCODE_CACHE_MAX_AGE_MS = 60_000L
        /** observeLocationUpdates() starts from a shared fix up to this old (live updates refine it). */
        private const val SHARED_FIX_MAX_AGE_MS = 2 * 60_000L
        /** A shared fix is still "current" while the device's newest cached fix is this close to it. */
        private const val SHARED_FIX_REUSE_DISTANCE_M = 100f
    }
}
