package com.autonoumus.ai.presentation.speedometer

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.core.util.FastTileSources
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.databinding.ActivityTripSummaryBinding
import com.autonoumus.ai.domain.repository.TripHistoryRepository
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
class TripSummaryActivity : BaseActivity<ActivityTripSummaryBinding>() {

    @Inject
    lateinit var tripHistoryRepository: TripHistoryRepository

    @Inject
    lateinit var preferenceManager: PreferenceManager

    private var durationSec: Long = 0L
    private var distanceMeters: Double = 0.0
    private var avgSpeedKmh: Float = 0f
    private var maxSpeedKmh: Float = 0f
    private var startAddress: String = "Start Location"
    private var endAddress: String = "Destination"
    private var startLat: Double = 0.0
    private var startLon: Double = 0.0
    private var endLat: Double = 0.0
    private var endLon: Double = 0.0
    private var waypointsJson: String = ""
    private var isFromHistory: Boolean = false

    private var limitExceededCount: Int = 0
    private var topSpeedLocation: String = ""
    private var safePercent: Int = 0
    private var warningPercent: Int = 0
    private var overSpeedPercent: Int = 0
    private var safetyScore: Int = 0

    override fun inflateBinding(inflater: LayoutInflater): ActivityTripSummaryBinding {
        return ActivityTripSummaryBinding.inflate(inflater)
    }

    override fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.tripSummaryRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            binding.layoutTopBarContainer.updatePadding(top = bars.top)
            binding.scrollView.updatePadding(bottom = bars.bottom + (24 * density).toInt())
            insets
        }
    }

    override fun initViews() {
        extractIntentData()
        setupTopBar()
        setupMapView()
        setupMapClickNavigation()
        renderSpeedDistribution()
        renderMetrics()
        setupActionButtons()
    }

    private fun extractIntentData() {
        durationSec = intent.getLongExtra(EXTRA_DURATION_SEC, 0L)
        distanceMeters = intent.getDoubleExtra(EXTRA_DISTANCE_METERS, 0.0)
        avgSpeedKmh = intent.getFloatExtra(EXTRA_AVG_SPEED_KMH, 0f)
        maxSpeedKmh = intent.getFloatExtra(EXTRA_MAX_SPEED_KMH, 0f)
        startAddress = intent.getStringExtra(EXTRA_START_ADDRESS) ?: "Start Location"
        endAddress = intent.getStringExtra(EXTRA_END_ADDRESS) ?: "Destination"
        startLat = intent.getDoubleExtra(EXTRA_START_LAT, 0.0)
        startLon = intent.getDoubleExtra(EXTRA_START_LON, 0.0)
        endLat = intent.getDoubleExtra(EXTRA_END_LAT, 0.0)
        endLon = intent.getDoubleExtra(EXTRA_END_LON, 0.0)
        waypointsJson = intent.getStringExtra(EXTRA_WAYPOINTS_JSON).orEmpty()
        isFromHistory = intent.getBooleanExtra(EXTRA_IS_FROM_HISTORY, false)

        limitExceededCount = intent.getIntExtra(EXTRA_LIMIT_EXCEEDED_COUNT, 0)
        topSpeedLocation = intent.getStringExtra(EXTRA_TOP_SPEED_LOCATION).orEmpty()
        safePercent = intent.getIntExtra(EXTRA_SAFE_PERCENT, 0)
        warningPercent = intent.getIntExtra(EXTRA_WARNING_PERCENT, 0)
        overSpeedPercent = intent.getIntExtra(EXTRA_OVER_SPEED_PERCENT, 0)
        safetyScore = intent.getIntExtra(EXTRA_SAFETY_SCORE, 0)
    }

    private fun setupTopBar() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun setupMapClickNavigation() {
        val clickListener = View.OnClickListener {
            openTripMap()
        }
        binding.mapTouchOverlay.setOnClickListener(clickListener)
        binding.btnViewFullMap.setOnClickListener(clickListener)
        binding.mapPreview.setOnClickListener(clickListener)
    }

    private fun openTripMap() {
        val intent = TripMapActivity.newIntent(
            context = this,
            startLat = startLat,
            startLon = startLon,
            endLat = endLat,
            endLon = endLon,
            startAddress = startAddress,
            endAddress = endAddress,
            durationSec = durationSec,
            distanceMeters = distanceMeters,
            avgSpeedKmh = avgSpeedKmh,
            maxSpeedKmh = maxSpeedKmh,
            waypointsJson = waypointsJson
        )
        startActivity(intent)
    }

    private fun setupMapView() {
        // Configure the map synchronously so its very first frame already requests the right
        // tiles. Before, the tile source/position were only set after the background waypoint
        // parse, so the first draw could fetch the XML default (OpenStreetMap, world view) tiles.
        binding.mapPreview.apply {
            FastTileSources.setBaseMap(this, FastTileSources.STREET)
            FastTileSources.run { applyHighPerformanceSettings(false) }
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            val hasStart = startLat != 0.0 && startLon != 0.0
            val hasEnd = endLat != 0.0 && endLon != 0.0
            val initialPoint = when {
                hasStart && hasEnd -> GeoPoint((startLat + endLat) / 2, (startLon + endLon) / 2)
                hasStart -> GeoPoint(startLat, startLon)
                hasEnd -> GeoPoint(endLat, endLon)
                else -> null
            }
            initialPoint?.let {
                controller.setZoom(15.0)
                controller.setCenter(it)
            }
        }
        lifecycleScope.launch {
            val parsedWaypoints = withContext(Dispatchers.IO) { parseWaypointsJson(waypointsJson) }
            renderMapView(parsedWaypoints)
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

    private fun renderMapView(parsedWaypoints: List<GeoPoint>) {
        try {
            binding.mapPreview.apply {
                val hasStart = startLat != 0.0 && startLon != 0.0
                val hasEnd = endLat != 0.0 && endLon != 0.0

                val actualStartLat = if (hasStart) startLat else if (hasEnd) endLat else parsedWaypoints.firstOrNull()?.latitude ?: 0.0
                val actualStartLon = if (hasStart) startLon else if (hasEnd) endLon else parsedWaypoints.firstOrNull()?.longitude ?: 0.0
                val actualEndLat = if (hasEnd) endLat else if (hasStart) startLat else parsedWaypoints.lastOrNull()?.latitude ?: actualStartLat
                val actualEndLon = if (hasEnd) endLon else if (hasStart) startLon else parsedWaypoints.lastOrNull()?.longitude ?: actualStartLon

                val routePoints = parsedWaypoints.toMutableList()

                if (routePoints.isEmpty()) {
                    routePoints.add(GeoPoint(actualStartLat, actualStartLon))
                    if (actualStartLat != actualEndLat || actualStartLon != actualEndLon) {
                        routePoints.add(GeoPoint(actualEndLat, actualEndLon))
                    }
                }

                overlays.removeAll { !FastTileSources.isMapLayerOverlay(it) }

                // Polyline
                if (routePoints.size >= 2) {
                    val polyline = Polyline(this).apply {
                        setPoints(routePoints)
                        outlinePaint.apply {
                            color = android.graphics.Color.parseColor("#4F46E5")
                            strokeWidth = 8f
                            style = android.graphics.Paint.Style.STROKE
                            strokeCap = android.graphics.Paint.Cap.ROUND
                            strokeJoin = android.graphics.Paint.Join.ROUND
                            isAntiAlias = true
                        }
                    }
                    overlays.add(polyline)
                }

                val isSameLocation = Math.abs(actualStartLat - actualEndLat) < 0.00005 &&
                        Math.abs(actualStartLon - actualEndLon) < 0.00005

                if (isSameLocation) {
                    val pPoint = GeoPoint(actualStartLat, actualStartLon)
                    val viewCenterPoint = GeoPoint(actualStartLat - 0.0010, actualStartLon)

                    val startMarker = Marker(this).apply {
                        position = pPoint
                        title = "Start Point: $startAddress"
                        icon = ContextCompat.getDrawable(this@TripSummaryActivity, R.drawable.ic_map_pin_green)
                        setAnchor(0.15f, 1.0f)
                    }
                    overlays.add(startMarker)

                    val endMarker = Marker(this).apply {
                        position = pPoint
                        title = "Destination: $endAddress"
                        icon = ContextCompat.getDrawable(this@TripSummaryActivity, R.drawable.ic_map_pin_red)
                        setAnchor(0.85f, 1.0f)
                    }
                    overlays.add(endMarker)

                    controller.setZoom(16.5)
                    controller.setCenter(viewCenterPoint)
                } else {
                    val pStart = GeoPoint(actualStartLat, actualStartLon)
                    val pEnd = GeoPoint(actualEndLat, actualEndLon)

                    val startMarker = Marker(this).apply {
                        position = pStart
                        title = "Start: $startAddress"
                        icon = ContextCompat.getDrawable(this@TripSummaryActivity, R.drawable.ic_map_pin_green)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    overlays.add(startMarker)

                    val endMarker = Marker(this).apply {
                        position = pEnd
                        title = "Destination: $endAddress"
                        icon = ContextCompat.getDrawable(this@TripSummaryActivity, R.drawable.ic_map_pin_red)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    overlays.add(endMarker)

                    val minLat = minOf(actualStartLat, actualEndLat)
                    val maxLat = maxOf(actualStartLat, actualEndLat)
                    val minLon = minOf(actualStartLon, actualEndLon)
                    val maxLon = maxOf(actualStartLon, actualEndLon)
                    val box = BoundingBox(maxLat + 0.005, maxLon + 0.005, minLat - 0.005, minLon - 0.005)
                    // Not animated: an animated zoom downloads tiles for every intermediate zoom level.
                    post { zoomToBoundingBox(box, false, 80) }
                }
            }
        } catch (e: Exception) {
            // Ignore map load issues
        }
    }

    private fun renderSpeedDistribution() {
        val safe: Int
        val warning: Int
        val overSpeed: Int
        val score: Int

        if (safePercent > 0 || warningPercent > 0 || overSpeedPercent > 0) {
            safe = safePercent
            warning = warningPercent
            overSpeed = overSpeedPercent
            score = if (safetyScore > 0) safetyScore else {
                val overspeedDeduction = overSpeed * 0.8
                val warningDeduction = warning * 0.3
                val violationDeduction = (limitExceededCount * 5.0).coerceAtMost(30.0)
                (100.0 - overspeedDeduction - warningDeduction - violationDeduction).toInt().coerceIn(10, 100)
            }
        } else {
            val isKmh = preferenceManager.isSpeedUnitKmh
            val limitRaw = preferenceManager.speedLimit
            val limitKmh = if (isKmh) limitRaw.toFloat() else limitRaw * 1.60934f
            val safeThresh = if (limitKmh > 0) limitKmh * 0.8f else 48f
            val warnThresh = if (limitKmh > 0) limitKmh else 60f

            when {
                limitExceededCount == 0 && maxSpeedKmh <= safeThresh -> {
                    safe = 100
                    warning = 0
                    overSpeed = 0
                    score = 100
                }
                limitExceededCount == 0 && maxSpeedKmh <= warnThresh -> {
                    safe = 85
                    warning = 15
                    overSpeed = 0
                    score = 95
                }
                else -> {
                    val excessRatio = if (warnThresh > 0) ((maxSpeedKmh - warnThresh) / warnThresh).coerceIn(0.05f, 0.6f) else 0.2f
                    val computedOver = Math.round(excessRatio * 50).toInt().coerceIn(5, 40)
                    val computedWarn = Math.round(computedOver * 0.8).toInt().coerceIn(5, 30)
                    val computedSafe = (100 - computedOver - computedWarn).coerceIn(30, 90)

                    safe = computedSafe
                    warning = computedWarn
                    overSpeed = (100 - safe - warning).coerceIn(0, 100)
                    val penalty = (limitExceededCount * 5).coerceAtMost(30)
                    score = (100 - (overSpeed * 0.8).toInt() - (warning * 0.3).toInt() - penalty).coerceIn(10, 100)
                }
            }
        }

        binding.donutSpeedDistribution.setDistribution(
            safe = safe.toFloat(),
            warning = warning.toFloat(),
            overSpeed = overSpeed.toFloat(),
            score = score,
            animated = true
        )

        binding.tvSafePercent.text = "Safe: $safe%"
        binding.tvWarningPercent.text = "Warning: $warning%"
        binding.tvOverSpeedPercent.text = "Over Speed: $overSpeed%"
    }

    private fun renderMetrics() {
        val isKm = preferenceManager.isDistanceUnitKm
        val isKmh = preferenceManager.isSpeedUnitKmh

        // 1. Total Distance
        val distanceConverted = if (isKm) {
            distanceMeters / 1000.0
        } else {
            (distanceMeters / 1000.0) * 0.621371
        }
        val distUnit = if (isKm) "km" else "mi"
        binding.tvTotalDistanceValue.text = String.format(Locale.US, "%.1f %s", distanceConverted, distUnit)

        // 2. Duration (HH:mm:ss format matching screenshot)
        val hrs = durationSec / 3600
        val mins = (durationSec % 3600) / 60
        val secs = durationSec % 60
        binding.tvDurationValue.text = String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)

        // 3. Average Speed
        val avgSpeed = if (isKmh) avgSpeedKmh else avgSpeedKmh * 0.621371f
        val speedUnit = if (isKmh) "km/h" else "mph"
        binding.tvAvgSpeedValue.text = String.format(Locale.US, "%.1f %s", avgSpeed, speedUnit)

        // 4. Max Speed
        val maxSpeed = if (isKmh) maxSpeedKmh else maxSpeedKmh * 0.621371f
        binding.tvMaxSpeedValue.text = String.format(Locale.US, "%.1f %s", maxSpeed, speedUnit)

        // 5. Limit Exceeded
        binding.tvLimitExceededValue.text = if (limitExceededCount == 1) "1 time" else "$limitExceededCount times"

        // 6. Top Speed Location
        val topLoc = when {
            topSpeedLocation.isNotBlank() -> topSpeedLocation
            startAddress.isNotBlank() && startAddress != "Start Location" -> startAddress
            endAddress.isNotBlank() && endAddress != "Destination" -> endAddress
            else -> "Main Highway"
        }
        binding.tvTopSpeedLocValue.text = topLoc
    }

    private fun setupActionButtons() {
        // Share Button
        binding.btnShareTrip.setOnClickListener {
            shareTripDetails()
        }

        // Trip History Button
        binding.btnTripHistoryAction.setOnClickListener {
            if (!isFromHistory) {
                startActivity(Intent(this, TripHistoryActivity::class.java))
            }
            finish()
        }
    }

    private fun shareTripDetails() {
        val shareText = buildString {
            append("🚗 GPS Trip Summary\n\n")
            append("• Total Distance: ${binding.tvTotalDistanceValue.text}\n")
            append("• Duration: ${binding.tvDurationValue.text}\n")
            append("• Avg Speed: ${binding.tvAvgSpeedValue.text}\n")
            append("• Max Speed: ${binding.tvMaxSpeedValue.text}\n")
            append("• Speed Limit Exceeded: ${binding.tvLimitExceededValue.text}\n")
            append("• Top Speed Location: ${binding.tvTopSpeedLocValue.text}\n")
            append("• Route: $startAddress ➔ $endAddress\n\n")
            append("Tracked with GPS Camera & Speedometer")
        }
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Share Trip Summary"))
    }

    override fun onResume() {
        super.onResume()
        binding.mapPreview.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapPreview.onPause()
    }

    override fun onDestroy() {
        try {
            binding.mapPreview.onDetach()
        } catch (e: Exception) {
            // Ignore
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DURATION_SEC = "extra_duration_sec"
        const val EXTRA_DISTANCE_METERS = "extra_distance_meters"
        const val EXTRA_AVG_SPEED_KMH = "extra_avg_speed_kmh"
        const val EXTRA_MAX_SPEED_KMH = "extra_max_speed_kmh"
        const val EXTRA_START_ADDRESS = "extra_start_address"
        const val EXTRA_END_ADDRESS = "extra_end_address"
        const val EXTRA_START_LAT = "extra_start_lat"
        const val EXTRA_START_LON = "extra_start_lon"
        const val EXTRA_END_LAT = "extra_end_lat"
        const val EXTRA_END_LON = "extra_end_lon"
        const val EXTRA_WAYPOINTS_JSON = "extra_waypoints_json"
        const val EXTRA_IS_FROM_HISTORY = "extra_is_from_history"
        const val EXTRA_LIMIT_EXCEEDED_COUNT = "extra_limit_exceeded_count"
        const val EXTRA_TOP_SPEED_LOCATION = "extra_top_speed_location"
        const val EXTRA_SAFE_PERCENT = "extra_safe_percent"
        const val EXTRA_WARNING_PERCENT = "extra_warning_percent"
        const val EXTRA_OVER_SPEED_PERCENT = "extra_over_speed_percent"
        const val EXTRA_SAFETY_SCORE = "extra_safety_score"

        fun newIntent(
            context: Context,
            durationSec: Long,
            distanceMeters: Double,
            avgSpeedKmh: Float,
            maxSpeedKmh: Float,
            startAddress: String,
            endAddress: String,
            startLat: Double = 0.0,
            startLon: Double = 0.0,
            endLat: Double = 0.0,
            endLon: Double = 0.0,
            waypointsJson: String = "",
            isFromHistory: Boolean = false,
            limitExceededCount: Int = 0,
            topSpeedLocation: String = "",
            safePercent: Int = 0,
            warningPercent: Int = 0,
            overSpeedPercent: Int = 0,
            safetyScore: Int = 0
        ): Intent {
            return Intent(context, TripSummaryActivity::class.java).apply {
                putExtra(EXTRA_DURATION_SEC, durationSec)
                putExtra(EXTRA_DISTANCE_METERS, distanceMeters)
                putExtra(EXTRA_AVG_SPEED_KMH, avgSpeedKmh)
                putExtra(EXTRA_MAX_SPEED_KMH, maxSpeedKmh)
                putExtra(EXTRA_START_ADDRESS, startAddress)
                putExtra(EXTRA_END_ADDRESS, endAddress)
                putExtra(EXTRA_START_LAT, startLat)
                putExtra(EXTRA_START_LON, startLon)
                putExtra(EXTRA_END_LAT, endLat)
                putExtra(EXTRA_END_LON, endLon)
                putExtra(EXTRA_WAYPOINTS_JSON, waypointsJson)
                putExtra(EXTRA_IS_FROM_HISTORY, isFromHistory)
                putExtra(EXTRA_LIMIT_EXCEEDED_COUNT, limitExceededCount)
                putExtra(EXTRA_TOP_SPEED_LOCATION, topSpeedLocation)
                putExtra(EXTRA_SAFE_PERCENT, safePercent)
                putExtra(EXTRA_WARNING_PERCENT, warningPercent)
                putExtra(EXTRA_OVER_SPEED_PERCENT, overSpeedPercent)
                putExtra(EXTRA_SAFETY_SCORE, safetyScore)
            }
        }
    }
}
