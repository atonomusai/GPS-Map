package com.autonoumus.ai.core.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.osmdroid.api.IGeoPoint
import org.osmdroid.util.GeoPoint
import java.util.TimeZone
import kotlin.math.abs

/**
 * Where a map should open before the screen knows anything better.
 *
 * A fresh osmdroid MapView sits at lat/lon (0,0) - the Gulf of Guinea - until a screen centers
 * it. Screens only did that once a location was known, so every map first showed open ocean:
 * flat blue with Street tiles, near-black with Satellite tiles, for up to several seconds (or for
 * good when there was no cached fix). Like Google Maps, open instead at the user's position or
 * the last area they looked at - whose tiles are almost always already in the disk cache.
 */
object MapCameraStore {

    data class Camera(val center: GeoPoint, val zoom: Double, val isRealPlace: Boolean)

    private const val PREFS = "map_camera"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_ZOOM = "zoom"
    private const val USER_LOCATION_ZOOM = 16.0

    fun initialCamera(context: Context): Camera {
        lastKnownLocation(context)?.let {
            return Camera(GeoPoint(it.latitude, it.longitude), USER_LOCATION_ZOOM, isRealPlace = true)
        }
        savedCamera(context)?.let { return it }
        // Nothing known at all (fresh install, location off): show the user's region at country
        // level rather than ocean. The UTC offset maps roughly to longitude (15 degrees per hour).
        val offsetHours = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000.0
        return Camera(GeoPoint(25.0, (offsetHours * 15.0).coerceIn(-170.0, 170.0)), 4.0, isRealPlace = false)
    }

    fun save(context: Context, center: IGeoPoint, zoom: Double) {
        if (isUnset(center.latitude, center.longitude)) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAT, center.latitude.toRawBits())
            .putLong(KEY_LON, center.longitude.toRawBits())
            .putFloat(KEY_ZOOM, zoom.toFloat())
            .apply()
    }

    fun savedCamera(context: Context): Camera? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAT)) return null
        val lat = Double.fromBits(prefs.getLong(KEY_LAT, 0L))
        val lon = Double.fromBits(prefs.getLong(KEY_LON, 0L))
        if (isUnset(lat, lon)) return null
        return Camera(GeoPoint(lat, lon), prefs.getFloat(KEY_ZOOM, USER_LOCATION_ZOOM.toFloat()).toDouble(), isRealPlace = true)
    }

    /** Newest cached fix across all enabled providers (GPS, network, fused); never waits for GPS. */
    @SuppressLint("MissingPermission")
    fun lastKnownLocation(context: Context): Location? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            locationManager.getProviders(true)
                .mapNotNull { provider -> try { locationManager.getLastKnownLocation(provider) } catch (e: Exception) { null } }
                .filter { !isUnset(it.latitude, it.longitude) }
                .maxByOrNull { it.time }
        } catch (e: Exception) {
            null
        }
    }

    fun isUnset(lat: Double, lon: Double): Boolean = abs(lat) < 1e-6 && abs(lon) < 1e-6
}
