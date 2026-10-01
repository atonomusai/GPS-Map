package com.autonoumus.ai.presentation.area

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.autonoumus.ai.R
import com.autonoumus.ai.core.util.AreaCalculator
import com.autonoumus.ai.core.util.AreaUnit
import com.autonoumus.ai.core.util.FastTileSources
import com.autonoumus.ai.databinding.ActivityAreaMeasurementBinding
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.util.ArrayList

class AreaMeasurementActivity : AppCompatActivity(), MapEventsReceiver {

    private lateinit var binding: ActivityAreaMeasurementBinding
    private lateinit var preferenceManager: com.autonoumus.ai.data.local.PreferenceManager
    private val polygonPoints = ArrayList<GeoPoint>()
    private val markerOverlays = ArrayList<Marker>()

    private var activePolygon: Polygon? = null
    private var activePolyline: Polyline? = null
    private var currentUnit: AreaUnit = AreaUnit.SQ_METERS
    private var currentMeasureType: MeasureType = MeasureType.AREA

    private val enterCoordinatesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val returnedPoints = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableArrayListExtra(
                    EnterCoordinatesActivity.EXTRA_POINTS,
                    GeoPoint::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableArrayListExtra<GeoPoint>(
                    EnterCoordinatesActivity.EXTRA_POINTS
                )
            }
            if (returnedPoints != null && returnedPoints.isNotEmpty()) {
                polygonPoints.clear()
                polygonPoints.addAll(returnedPoints)
                refreshMapOverlays()
                zoomToPolygon()
            }
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            moveToCurrentLocation()
        } else {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
            com.autonoumus.ai.core.util.PermissionSettingsDialog.handleDenied(
                context = this,
                shouldShowRationale = shouldShowRationale,
                deniedMessage = "Location permission is required to find your position"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager = com.autonoumus.ai.data.local.PreferenceManager(this)

        // OSMDroid configuration & High-Speed Tile Caching
        FastTileSources.optimizeConfiguration(applicationContext)

        binding = ActivityAreaMeasurementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            binding.topLeftControlsContainer.updatePadding(top = bars.top + (8 * density).toInt())
            binding.topRightControlsContainer.updatePadding(top = bars.top + (8 * density).toInt())
            binding.bottomActionBar.updateLayoutParams<ConstraintLayout.LayoutParams> {
                bottomMargin = bars.bottom + (20 * density).toInt()
            }
            insets
        }

        setupMap()
        setupListeners()
        setupMapTypeSelector()
        updateCalculations()

        // Show Choose Mode sheet on start
        showChooseModeDialog()
    }

    private fun setupMap() {
        binding.mapView.apply {
            FastTileSources.setBaseMap(this, FastTileSources.STREET)
            FastTileSources.run { applyHighPerformanceSettings(true) }
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            // Same limits as the other map screens: without them the map could be pinched out to a
            // tiled whole-world view or in past the deepest tiles the servers have (blank grey).
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            // Start on the user's cached fix so the first tiles requested are the right ones. With
            // no fix, applyHighPerformanceSettings() already opened the last viewed area (it used
            // to be a hard-coded Delhi, far from most users, then a long animated pan).
            bestLastKnownLocation()?.let {
                controller.setZoom(16.0)
                controller.setCenter(GeoPoint(it.latitude, it.longitude))
            }
        }

        // Add map touch receiver overlay
        val mapEventsOverlay = MapEventsOverlay(this)
        binding.mapView.overlays.add(0, mapEventsOverlay)

        // Try to center on user location
        checkLocationPermissionAndMove()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnUnitDropdown.setOnClickListener {
            showUnitSelectionDialog()
        }

        binding.btnSwitchMode.setOnClickListener {
            showChooseModeDialog()
        }

        binding.btnUndo.setOnClickListener {
            if (polygonPoints.isNotEmpty()) {
                polygonPoints.removeAt(polygonPoints.size - 1)
                refreshMapOverlays()
            }
        }

        binding.btnZoomIn.setOnClickListener {
            binding.mapView.controller.zoomIn()
        }

        binding.btnZoomOut.setOnClickListener {
            binding.mapView.controller.zoomOut()
        }

        binding.btnMyLocation.setOnClickListener {
            checkLocationPermissionAndMove(isUserInitiated = true)
        }

        binding.btnResetMeasurement.setOnClickListener {
            if (polygonPoints.isNotEmpty()) {
                polygonPoints.clear()
                refreshMapOverlays()
                Toast.makeText(this, "Measurement reset", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDoneMeasurement.setOnClickListener {
            if (polygonPoints.size < 3) {
                Toast.makeText(this, "Please add at least 3 points to complete measurement", Toast.LENGTH_SHORT).show()
            } else {
                showResultDialog()
            }
        }
    }

    private fun setupMapTypeSelector() {
        fun selectType(type: com.autonoumus.ai.presentation.map.SelectedMapType) {
            fun updateTileUI(border: android.widget.FrameLayout, label: android.widget.TextView, isSelected: Boolean) {
                if (isSelected) {
                    border.setBackgroundResource(R.drawable.bg_map_type_thumb_selected)
                    label.setTextColor(Color.parseColor("#2563EB"))
                } else {
                    border.setBackgroundResource(R.drawable.bg_map_type_thumb_unselected)
                    label.setTextColor(Color.parseColor("#111827"))
                }
            }

            updateTileUI(binding.flSatelliteBorder, binding.tvSatelliteLabel, type == com.autonoumus.ai.presentation.map.SelectedMapType.SATELLITE)
            updateTileUI(binding.flHybridBorder, binding.tvHybridLabel, type == com.autonoumus.ai.presentation.map.SelectedMapType.HYBRID)
            updateTileUI(binding.flTerrainBorder, binding.tvTerrainLabel, type == com.autonoumus.ai.presentation.map.SelectedMapType.TERRAIN)
            updateTileUI(binding.flStreetBorder, binding.tvStreetLabel, type == com.autonoumus.ai.presentation.map.SelectedMapType.STREET)

            when (type) {
                com.autonoumus.ai.presentation.map.SelectedMapType.SATELLITE -> {
                    FastTileSources.setBaseMap(binding.mapView, FastTileSources.SATELLITE)
                }
                com.autonoumus.ai.presentation.map.SelectedMapType.HYBRID -> {
                    FastTileSources.setBaseMap(binding.mapView, FastTileSources.HYBRID)
                }
                com.autonoumus.ai.presentation.map.SelectedMapType.TERRAIN -> {
                    FastTileSources.setBaseMap(binding.mapView, FastTileSources.TERRAIN)
                }
                com.autonoumus.ai.presentation.map.SelectedMapType.STREET -> {
                    FastTileSources.setBaseMap(binding.mapView, FastTileSources.STREET)
                }
            }
            binding.mapView.invalidate()
        }

        binding.cardSatellite.setOnClickListener { selectType(com.autonoumus.ai.presentation.map.SelectedMapType.SATELLITE) }
        binding.cardHybrid.setOnClickListener { selectType(com.autonoumus.ai.presentation.map.SelectedMapType.HYBRID) }
        binding.cardTerrain.setOnClickListener { selectType(com.autonoumus.ai.presentation.map.SelectedMapType.TERRAIN) }
        binding.cardStreet.setOnClickListener { selectType(com.autonoumus.ai.presentation.map.SelectedMapType.STREET) }

        binding.btnMapLayers.setOnClickListener {
            val isCurrentlyVisible = binding.panelMapTypes.visibility == View.VISIBLE
            binding.panelMapTypes.visibility = if (isCurrentlyVisible) View.GONE else View.VISIBLE
        }
    }

    private fun showChooseModeDialog() {
        val dialog = ChooseModeBottomSheetDialog.newInstance()
        dialog.onManualMeasuringSelected = {
            // Stay on map for interactive tapping
        }
        dialog.onEnterCoordinatesSelected = {
            val intent = EnterCoordinatesActivity.createIntent(this, polygonPoints)
            enterCoordinatesLauncher.launch(intent)
        }
        dialog.onMeasureTypeChanged = { type ->
            currentMeasureType = type
            updateCalculations()
        }
        dialog.show(supportFragmentManager, ChooseModeBottomSheetDialog.TAG)
    }

    private fun showUnitSelectionDialog() {
        val dialog = UnitSelectionBottomSheetDialog.newInstance(currentUnit)
        dialog.onUnitSelected = { unit ->
            currentUnit = unit
            binding.tvSelectedUnit.text = unit.symbol
            updateCalculations()
        }
        dialog.show(supportFragmentManager, UnitSelectionBottomSheetDialog.TAG)
    }

    private fun showResultDialog() {
        val areaM2 = AreaCalculator.calculatePolygonArea(polygonPoints)
        val perimeterM = AreaCalculator.calculatePerimeter(polygonPoints)

        val formattedArea = AreaCalculator.formatArea(areaM2, currentUnit)
        val formattedPerimeter = AreaCalculator.formatPerimeter(perimeterM)

        val dialog = AreaResultBottomSheetDialog.newInstance(
            areaText = formattedArea,
            perimeterText = formattedPerimeter,
            locationName = "Measured Field Area",
            pointsCount = polygonPoints.size
        )
        dialog.show(supportFragmentManager, AreaResultBottomSheetDialog.TAG)
    }

    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
        if (p != null) {
            polygonPoints.add(p)
            refreshMapOverlays()
            return true
        }
        return false
    }

    override fun longPressHelper(p: GeoPoint?): Boolean {
        return false
    }

    private fun refreshMapOverlays() {
        // Remove existing polygon/polyline and markers
        if (activePolygon != null) {
            binding.mapView.overlays.remove(activePolygon)
            activePolygon = null
        }
        if (activePolyline != null) {
            binding.mapView.overlays.remove(activePolyline)
            activePolyline = null
        }
        markerOverlays.forEach { binding.mapView.overlays.remove(it) }
        markerOverlays.clear()

        // 1. Draw polygon if >= 3 points
        if (polygonPoints.size >= 3) {
            val polygon = Polygon(binding.mapView).apply {
                points = ArrayList(polygonPoints)
                // Add first point at end to close polygon nicely
                outlinePaint.apply {
                    color = Color.parseColor("#4F46E5")
                    strokeWidth = 6f
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                }
                fillPaint.apply {
                    color = Color.parseColor("#334F46E5") // Semi-transparent indigo
                    style = Paint.Style.FILL
                }
            }
            activePolygon = polygon
            binding.mapView.overlays.add(1, polygon)
        } else if (polygonPoints.size == 2) {
            // 2. Draw polyline if 2 points
            val polyline = Polyline(binding.mapView).apply {
                setPoints(ArrayList(polygonPoints))
                outlinePaint.apply {
                    color = Color.parseColor("#4F46E5")
                    strokeWidth = 6f
                    style = Paint.Style.STROKE
                }
            }
            activePolyline = polyline
            binding.mapView.overlays.add(1, polyline)
        }

        // 3. Add lettered markers for each point (A, B, C...)
        polygonPoints.forEachIndexed { index, geoPoint ->
            val label = AddedCoordinatesAdapter.getPointLabel(index)
            val marker = Marker(binding.mapView).apply {
                position = geoPoint
                title = "Point $label"
                icon = createMarkerBitmap(this@AreaMeasurementActivity, label)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                isDraggable = true
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDrag(marker: Marker?) {}
                    override fun onMarkerDragStart(marker: Marker?) {}
                    override fun onMarkerDragEnd(marker: Marker?) {
                        if (marker != null) {
                            polygonPoints[index] = marker.position
                            refreshMapOverlays()
                        }
                    }
                })
            }
            markerOverlays.add(marker)
            binding.mapView.overlays.add(marker)
        }

        binding.mapView.invalidate()
        updateCalculations()
    }

    private fun updateCalculations() {
        val count = polygonPoints.size
        if (count >= 3) {
            val areaM2 = AreaCalculator.calculatePolygonArea(polygonPoints)
            val perimeterM = AreaCalculator.calculatePerimeter(polygonPoints)
            binding.tvLiveArea.text = AreaCalculator.formatArea(areaM2, currentUnit)
            binding.tvLivePerimeter.text = AreaCalculator.formatPerimeter(perimeterM)
            binding.tvMapHint.text = "$count vertices connected • Polygon closed"
        } else if (count == 2) {
            val perimeterM = AreaCalculator.calculatePerimeter(polygonPoints)
            binding.tvLiveArea.text = "0.0 ${currentUnit.symbol}"
            binding.tvLivePerimeter.text = AreaCalculator.formatPerimeter(perimeterM)
            binding.tvMapHint.text = "2 points connected • Tap 1 more point to close area"
        } else if (count == 1) {
            binding.tvLiveArea.text = "0.0 ${currentUnit.symbol}"
            binding.tvLivePerimeter.text = "0.0 m"
            binding.tvMapHint.text = "1 point added • Tap to add next vertex"
        } else {
            binding.tvLiveArea.text = "0.0 ${currentUnit.symbol}"
            binding.tvLivePerimeter.text = "0.0 m"
            binding.tvMapHint.text = "Tap map to add vertices (3+ for area)"
        }

        binding.btnDoneMeasurement.alpha = 1.0f

        val hasPoints = count > 0
        binding.bottomActionBar.visibility = if (hasPoints) View.VISIBLE else View.GONE
        binding.btnUndo.visibility = if (hasPoints) View.VISIBLE else View.GONE
    }

    private fun zoomToPolygon() {
        if (polygonPoints.isEmpty()) return
        if (polygonPoints.size == 1) {
            com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, polygonPoints[0])
            return
        }

        var minLat = 90.0
        var maxLat = -90.0
        var minLon = 180.0
        var maxLon = -180.0

        polygonPoints.forEach { pt ->
            if (pt.latitude < minLat) minLat = pt.latitude
            if (pt.latitude > maxLat) maxLat = pt.latitude
            if (pt.longitude < minLon) minLon = pt.longitude
            if (pt.longitude > maxLon) maxLon = pt.longitude
        }

        val padding = 0.001
        val boundingBox = BoundingBox(
            maxLat + padding,
            maxLon + padding,
            minLat - padding,
            minLon - padding
        )
        binding.mapView.zoomToBoundingBox(boundingBox, true, 100)
    }

    private fun createMarkerBitmap(context: Context, label: String): BitmapDrawable {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Outer filled circle (Indigo)
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4F46E5")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4, circlePaint)

        // White border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4, borderPaint)

        // Text label
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val yPos = (size / 2f - (textPaint.descent() + textPaint.ascent()) / 2)
        canvas.drawText(label, size / 2f, yPos, textPaint)

        return BitmapDrawable(context.resources, bitmap)
    }

    private fun checkLocationPermissionAndMove(isUserInitiated: Boolean = false) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            moveToCurrentLocation()
        } else {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
            if (isUserInitiated && preferenceManager.hasRequestedLocationPermission && !shouldShowRationale) {
                com.autonoumus.ai.core.util.PermissionSettingsDialog.show(
                    context = this,
                    message = "Location permission is disabled. Please enable it in Settings to find your position."
                )
            } else if (isUserInitiated || !preferenceManager.hasRequestedLocationPermission) {
                preferenceManager.hasRequestedLocationPermission = true
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }

    private fun moveToCurrentLocation() {
        val bestLocation = bestLastKnownLocation() ?: return
        val userGeoPoint = GeoPoint(bestLocation.latitude, bestLocation.longitude)
        val center = binding.mapView.mapCenter
        val results = FloatArray(1)
        Location.distanceBetween(center.latitude, center.longitude, userGeoPoint.latitude, userGeoPoint.longitude, results)
        // Animating a long way (e.g. from the fallback city) downloads tiles for every frame of the
        // pan; jump instead and only animate short, visible moves.
        if (results[0] > 2_000f) {
            binding.mapView.controller.setCenter(userGeoPoint)
        } else {
            com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, userGeoPoint)
        }
    }

    /** Newest cached fix across all enabled providers (GPS, network, fused), or null. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun bestLastKnownLocation(): Location? {
        val hasPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return null
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return locationManager.getProviders(true)
            .mapNotNull { provider -> try { locationManager.getLastKnownLocation(provider) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }
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
        } catch (e: Exception) {
            // Ignore
        }
        super.onDestroy()
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, AreaMeasurementActivity::class.java)
        }
    }
}
