package com.autonoumus.ai.presentation.speedometer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.core.util.FastTileSources
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.databinding.ActivityTripMapBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class TripMapActivity : BaseActivity<ActivityTripMapBinding>() {

    @Inject
    lateinit var preferenceManager: PreferenceManager

    private var startLat: Double = 0.0
    private var startLon: Double = 0.0
    private var endLat: Double = 0.0
    private var endLon: Double = 0.0
    private var startAddress: String = "Start Location"
    private var endAddress: String = "Destination"
    private var durationSec: Long = 0L
    private var distanceMeters: Double = 0.0
    private var avgSpeedKmh: Float = 0f
    private var maxSpeedKmh: Float = 0f
    private var waypointsJson: String = ""

    private var startGeoPoint: GeoPoint? = null
    private var endGeoPoint: GeoPoint? = null
    private var routeBoundingBox: BoundingBox? = null
    private var currentTileSourceIndex = 0

    private val tileSources = listOf(
        FastTileSources.STREET,
        FastTileSources.SATELLITE,
        FastTileSources.HYBRID,
        FastTileSources.TERRAIN
    )

    private val tileSourceNames = listOf("Street", "Satellite", "Hybrid", "Terrain")

    override fun inflateBinding(inflater: LayoutInflater): ActivityTripMapBinding {
        return ActivityTripMapBinding.inflate(inflater)
    }

    override fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.tripMapRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            binding.layoutTopBar.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                topMargin = bars.top + (8 * density).toInt()
            }
            binding.bottomTripCard.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                bottomMargin = bars.bottom + (16 * density).toInt()
            }
            insets
        }
    }

    override fun initViews() {
        extractIntentData()
        setupTopBar()
        setupMap()
        setupControls()
        renderBottomCard()
    }

    private fun extractIntentData() {
        startLat = intent.getDoubleExtra(EXTRA_START_LAT, 0.0)
        startLon = intent.getDoubleExtra(EXTRA_START_LON, 0.0)
        endLat = intent.getDoubleExtra(EXTRA_END_LAT, 0.0)
        endLon = intent.getDoubleExtra(EXTRA_END_LON, 0.0)
        startAddress = intent.getStringExtra(EXTRA_START_ADDRESS) ?: "Start Location"
        endAddress = intent.getStringExtra(EXTRA_END_ADDRESS) ?: "Destination"
        durationSec = intent.getLongExtra(EXTRA_DURATION_SEC, 0L)
        distanceMeters = intent.getDoubleExtra(EXTRA_DISTANCE_METERS, 0.0)
        avgSpeedKmh = intent.getFloatExtra(EXTRA_AVG_SPEED_KMH, 0f)
        maxSpeedKmh = intent.getFloatExtra(EXTRA_MAX_SPEED_KMH, 0f)
        waypointsJson = intent.getStringExtra(EXTRA_WAYPOINTS_JSON).orEmpty()
    }

    private fun setupTopBar() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnFitRoute.setOnClickListener {
            fitRouteInView()
        }
    }

    private fun setupMap() {
        try {
            FastTileSources.optimizeConfiguration(this)

            binding.mapView.apply {
                FastTileSources.setBaseMap(this, tileSources[currentTileSourceIndex])
                FastTileSources.run { applyHighPerformanceSettings(true) }
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                minZoomLevel = 3.0
                maxZoomLevel = 20.0
                // Show the trip area immediately instead of the default world view (zoom 3 at 0,0)
                // while the waypoints are parsed, so no world-level tiles are downloaded for nothing.
                initialTripPoint()?.let {
                    controller.setZoom(15.0)
                    controller.setCenter(it)
                }
            }

            // JSON parsing moved off the main thread before rendering the route.
            lifecycleScope.launch {
                val parsedWaypoints = withContext(Dispatchers.IO) { parseWaypointsJson(waypointsJson) }
                renderRouteAndMarkers(parsedWaypoints)
            }
        } catch (e: Exception) {
            // Safe fallback
        }
    }

    private fun parseWaypointsJson(json: String): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        if (json.isNotBlank()) {
            try {
                val array = JSONArray(json)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val lat = obj.optDouble("lat", 0.0)
                    val lon = obj.optDouble("lon", 0.0)
                    if (lat != 0.0 && lon != 0.0) {
                        points.add(GeoPoint(lat, lon))
                    }
                }
            } catch (e: Exception) {}
        }
        return points
    }

    private fun renderRouteAndMarkers(parsedWaypoints: List<GeoPoint>) {
        val hasStart = startLat != 0.0 && startLon != 0.0
        val hasEnd = endLat != 0.0 && endLon != 0.0

        val actualStartLat = if (hasStart) startLat else if (hasEnd) endLat else parsedWaypoints.firstOrNull()?.latitude ?: 0.0
        val actualStartLon = if (hasStart) startLon else if (hasEnd) endLon else parsedWaypoints.firstOrNull()?.longitude ?: 0.0
        val actualEndLat = if (hasEnd) endLat else if (hasStart) startLat else parsedWaypoints.lastOrNull()?.latitude ?: actualStartLat
        val actualEndLon = if (hasEnd) endLon else if (hasStart) startLon else parsedWaypoints.lastOrNull()?.longitude ?: actualStartLon

        val isSameLocation = Math.abs(actualStartLat - actualEndLat) < 0.00005 &&
                Math.abs(actualStartLon - actualEndLon) < 0.00005

        val routePoints = parsedWaypoints.toMutableList()

        // Fallback to start and end if waypoints empty
        if (routePoints.isEmpty()) {
            routePoints.add(GeoPoint(actualStartLat, actualStartLon))
            if (!isSameLocation) {
                routePoints.add(GeoPoint(actualEndLat, actualEndLon))
            }
        }

        val map = binding.mapView
        map.overlays.removeAll { !FastTileSources.isMapLayerOverlay(it) }

        // 2. Draw Polyline Route
        if (routePoints.size >= 2) {
            val polyline = Polyline(map).apply {
                setPoints(routePoints)
                outlinePaint.apply {
                    color = Color.parseColor("#4F46E5")
                    strokeWidth = 10f
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true
                }
            }
            map.overlays.add(polyline)
        }

        if (isSameLocation) {
            val pPoint = GeoPoint(actualStartLat, actualStartLon)
            startGeoPoint = pPoint
            endGeoPoint = pPoint

            val startMarker = Marker(map).apply {
                position = pPoint
                title = "START POINT"
                snippet = startAddress
                icon = ContextCompat.getDrawable(this@TripMapActivity, R.drawable.ic_map_pin_green)
                setAnchor(0.15f, 1.0f)
            }
            map.overlays.add(startMarker)

            val endMarker = Marker(map).apply {
                position = pPoint
                title = "DESTINATION"
                snippet = endAddress
                icon = ContextCompat.getDrawable(this@TripMapActivity, R.drawable.ic_map_pin_red)
                setAnchor(0.85f, 1.0f)
            }
            map.overlays.add(endMarker)

            routeBoundingBox = BoundingBox(
                actualStartLat + 0.003,
                actualStartLon + 0.003,
                actualStartLat - 0.003,
                actualStartLon - 0.003
            )
            map.post {
                map.controller.setZoom(16.5)
                map.controller.setCenter(pPoint)
            }
        } else {
            startGeoPoint = GeoPoint(actualStartLat, actualStartLon)
            val startMarker = Marker(map).apply {
                position = startGeoPoint
                title = "START POINT"
                snippet = startAddress
                icon = ContextCompat.getDrawable(this@TripMapActivity, R.drawable.ic_map_pin_green)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            map.overlays.add(startMarker)

            endGeoPoint = GeoPoint(actualEndLat, actualEndLon)
            val endMarker = Marker(map).apply {
                position = endGeoPoint
                title = "DESTINATION"
                snippet = endAddress
                icon = ContextCompat.getDrawable(this@TripMapActivity, R.drawable.ic_map_pin_red)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            map.overlays.add(endMarker)

            var minLat = minOf(actualStartLat, actualEndLat)
            var maxLat = maxOf(actualStartLat, actualEndLat)
            var minLon = minOf(actualStartLon, actualEndLon)
            var maxLon = maxOf(actualStartLon, actualEndLon)

            for (p in routePoints) {
                if (p.latitude < minLat) minLat = p.latitude
                if (p.latitude > maxLat) maxLat = p.latitude
                if (p.longitude < minLon) minLon = p.longitude
                if (p.longitude > maxLon) maxLon = p.longitude
            }

            if (maxLat - minLat < 0.002) {
                maxLat += 0.002
                minLat -= 0.002
            }
            if (maxLon - minLon < 0.002) {
                maxLon += 0.002
                minLon -= 0.002
            }

            routeBoundingBox = BoundingBox(
                maxLat + 0.003,
                maxLon + 0.003,
                minLat - 0.003,
                minLon - 0.003
            )

            map.post {
                // Not animated on first display: an animated zoom downloads tiles for every
                // intermediate zoom level before the final ones.
                fitRouteInView(animated = false)
            }
        }

        map.invalidate()
    }

    private fun fitRouteInView(animated: Boolean = true) {
        routeBoundingBox?.let { box ->
            binding.mapView.zoomToBoundingBox(box, animated, 120)
        }
    }

    private fun initialTripPoint(): GeoPoint? {
        val hasStart = startLat != 0.0 && startLon != 0.0
        val hasEnd = endLat != 0.0 && endLon != 0.0
        return when {
            hasStart && hasEnd -> GeoPoint((startLat + endLat) / 2, (startLon + endLon) / 2)
            hasStart -> GeoPoint(startLat, startLon)
            hasEnd -> GeoPoint(endLat, endLon)
            else -> null
        }
    }

    private fun setupControls() {
        binding.btnZoomIn.setOnClickListener {
            binding.mapView.controller.zoomIn()
        }

        binding.btnZoomOut.setOnClickListener {
            binding.mapView.controller.zoomOut()
        }

        binding.btnFocusStart.setOnClickListener {
            startGeoPoint?.let { pt ->
                com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, pt, 17.0, 800L)
                Toast.makeText(this, "Start: $startAddress", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnFocusEnd.setOnClickListener {
            endGeoPoint?.let { pt ->
                com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, pt, 17.0, 800L)
                Toast.makeText(this, "Destination: $endAddress", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLayerToggle.setOnClickListener {
            currentTileSourceIndex = (currentTileSourceIndex + 1) % tileSources.size
            FastTileSources.setBaseMap(binding.mapView, tileSources[currentTileSourceIndex])
            Toast.makeText(this, "Map Style: ${tileSourceNames[currentTileSourceIndex]}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderBottomCard() {
        val isKmh = preferenceManager.isSpeedUnitKmh
        val unit = if (isKmh) "km/h" else "mph"
        val distUnit = if (isKmh) "km" else "mi"

        binding.tvStartAddress.text = startAddress
        binding.tvEndAddress.text = endAddress

        // Distance
        val distConverted = if (isKmh) distanceMeters / 1000.0 else (distanceMeters / 1000.0) * 0.621371
        binding.tvStatDistance.text = String.format(Locale.US, "%.1f %s", distConverted, distUnit)

        // Duration
        val timeFormatted = when {
            durationSec < 60 -> "$durationSec s"
            durationSec < 3600 -> "${durationSec / 60}m ${durationSec % 60}s"
            else -> "${durationSec / 3600}h ${(durationSec % 3600) / 60}m"
        }
        binding.tvStatTime.text = timeFormatted

        // Avg Speed & Max Speed
        val avgConverted = if (isKmh) avgSpeedKmh else avgSpeedKmh * 0.621371f
        val maxConverted = if (isKmh) maxSpeedKmh else maxSpeedKmh * 0.621371f
        binding.tvStatAvgSpeed.text = String.format(Locale.US, "%.0f %s", avgConverted, unit)
        binding.tvStatMaxSpeed.text = String.format(Locale.US, "%.0f %s", maxConverted, unit)
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroy() {
        try {
            binding.mapView.onDetach()
        } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val EXTRA_START_LAT = "extra_start_lat"
        const val EXTRA_START_LON = "extra_start_lon"
        const val EXTRA_END_LAT = "extra_end_lat"
        const val EXTRA_END_LON = "extra_end_lon"
        const val EXTRA_START_ADDRESS = "extra_start_address"
        const val EXTRA_END_ADDRESS = "extra_end_address"
        const val EXTRA_DURATION_SEC = "extra_duration_sec"
        const val EXTRA_DISTANCE_METERS = "extra_distance_meters"
        const val EXTRA_AVG_SPEED_KMH = "extra_avg_speed_kmh"
        const val EXTRA_MAX_SPEED_KMH = "extra_max_speed_kmh"
        const val EXTRA_WAYPOINTS_JSON = "extra_waypoints_json"

        fun newIntent(
            context: Context,
            startLat: Double,
            startLon: Double,
            endLat: Double,
            endLon: Double,
            startAddress: String,
            endAddress: String,
            durationSec: Long,
            distanceMeters: Double,
            avgSpeedKmh: Float,
            maxSpeedKmh: Float,
            waypointsJson: String = ""
        ): Intent {
            return Intent(context, TripMapActivity::class.java).apply {
                putExtra(EXTRA_START_LAT, startLat)
                putExtra(EXTRA_START_LON, startLon)
                putExtra(EXTRA_END_LAT, endLat)
                putExtra(EXTRA_END_LON, endLon)
                putExtra(EXTRA_START_ADDRESS, startAddress)
                putExtra(EXTRA_END_ADDRESS, endAddress)
                putExtra(EXTRA_DURATION_SEC, durationSec)
                putExtra(EXTRA_DISTANCE_METERS, distanceMeters)
                putExtra(EXTRA_AVG_SPEED_KMH, avgSpeedKmh)
                putExtra(EXTRA_MAX_SPEED_KMH, maxSpeedKmh)
                putExtra(EXTRA_WAYPOINTS_JSON, waypointsJson)
            }
        }
    }
}
