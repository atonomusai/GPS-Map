package com.autonoumus.ai.presentation.routefinder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.core.util.FastTileSources
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.core.util.OsmGeocodingHelper
import com.autonoumus.ai.core.util.RouteResult
import com.autonoumus.ai.core.util.TravelMode
import com.autonoumus.ai.databinding.ActivityRouteFinderBinding
import com.autonoumus.ai.presentation.routefinder.adapter.RouteSuggestionAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import com.autonoumus.ai.data.local.PreferenceManager
import org.osmdroid.views.overlay.Polyline
import javax.inject.Inject

@AndroidEntryPoint
class RouteFinderActivity : BaseActivity<ActivityRouteFinderBinding>() {

    @Inject
    lateinit var locationHelper: LocationHelper

    @Inject
    lateinit var preferenceManager: PreferenceManager

    private val viewModel: RouteFinderViewModel by viewModels()
    private lateinit var suggestionAdapter: RouteSuggestionAdapter

    private var startMarker: Marker? = null
    private var destMarker: Marker? = null
    private var primaryPolyline: Polyline? = null
    private var alternatePolyline: Polyline? = null

    private var currentTileIndex = 0
    private val tileSources = listOf(
        FastTileSources.STREET,
        FastTileSources.SATELLITE,
        FastTileSources.HYBRID,
        FastTileSources.TERRAIN
    )
    private val tileNames = listOf("Street", "Satellite", "Hybrid", "Terrain")

    private var isUpdatingStartText = false
    private var isUpdatingDestText = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            observeGpsForStartLocation()
        } else {
            val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            com.autonoumus.ai.core.util.PermissionSettingsDialog.handleDenied(
                context = this,
                shouldShowRationale = shouldShowRationale,
                deniedMessage = "Location permission is required to use your current location"
            )
        }
    }

    companion object {
        const val EXTRA_START_LAT = "extra_start_lat"
        const val EXTRA_START_LON = "extra_start_lon"
        const val EXTRA_START_NAME = "extra_start_name"
        const val EXTRA_DEST_LAT = "extra_dest_lat"
        const val EXTRA_DEST_LON = "extra_dest_lon"
        const val EXTRA_DEST_NAME = "extra_dest_name"

        fun newIntent(
            context: Context,
            startLat: Double = 0.0,
            startLon: Double = 0.0,
            startName: String = "",
            destLat: Double = 0.0,
            destLon: Double = 0.0,
            destName: String = ""
        ): Intent {
            return Intent(context, RouteFinderActivity::class.java).apply {
                putExtra(EXTRA_START_LAT, startLat)
                putExtra(EXTRA_START_LON, startLon)
                putExtra(EXTRA_START_NAME, startName)
                putExtra(EXTRA_DEST_LAT, destLat)
                putExtra(EXTRA_DEST_LON, destLon)
                putExtra(EXTRA_DEST_NAME, destName)
            }
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityRouteFinderBinding {
        return ActivityRouteFinderBinding.inflate(inflater)
    }

    override fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.routeFinderRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            binding.topBarContainer.updatePadding(top = bars.top)
            binding.layoutBottomRouteDock.updatePadding(bottom = bars.bottom + (20 * density).toInt())
            binding.layoutMapControls.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    override fun initViews() {
        setupMap()
        setupTopInputs()
        setupSuggestionsRecyclerView()
        setupTravelModeSelectors()
        setupMapControls()
        setupBottomActions()
        resolveInitialIntentOrGps()
    }

    private fun setupMap() {
        try {
            FastTileSources.optimizeConfiguration(this)

            binding.mapView.apply {
                FastTileSources.setBaseMap(this, tileSources[currentTileIndex])
                FastTileSources.run { applyHighPerformanceSettings(true) }
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                minZoomLevel = 4.0
                maxZoomLevel = 20.0
            }
        } catch (e: Exception) {
            // Ignore map config errors
        }
    }

    private fun setupTopInputs() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnSwapLocations.setOnClickListener {
            hideKeyboard()
            binding.etStartLocation.clearFocus()
            binding.etDestLocation.clearFocus()

            binding.btnSwapLocations.animate()
                .rotationBy(180f)
                .setDuration(250)
                .start()

            viewModel.swapLocations(
                currentStartText = binding.etStartLocation.text?.toString(),
                currentDestText = binding.etDestLocation.text?.toString()
            )
        }

        // Start location text listener
        binding.etStartLocation.doAfterTextChanged { editable ->
            if (isUpdatingStartText) return@doAfterTextChanged
            val query = editable?.toString().orEmpty()
            if (binding.etStartLocation.hasFocus()) {
                viewModel.onStartQueryChanged(query)
            }
        }

        binding.etStartLocation.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val current = binding.etStartLocation.text.toString()
                viewModel.onStartQueryChanged(current)
            }
        }

        // Destination location text listener
        binding.etDestLocation.doAfterTextChanged { editable ->
            if (isUpdatingDestText) return@doAfterTextChanged
            val query = editable?.toString().orEmpty()
            if (binding.etDestLocation.hasFocus()) {
                viewModel.onDestQueryChanged(query)
            }
        }

        binding.etDestLocation.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val current = binding.etDestLocation.text.toString()
                viewModel.onDestQueryChanged(current)
            }
        }

        val triggerSearchAction = { isStart: Boolean ->
            val query = if (isStart) {
                binding.etStartLocation.text.toString().trim()
            } else {
                binding.etDestLocation.text.toString().trim()
            }
            hideKeyboard()
            binding.etStartLocation.clearFocus()
            binding.etDestLocation.clearFocus()

            val target = if (isStart) SearchTarget.START else SearchTarget.DESTINATION
            viewModel.closeSuggestions()

            if (query.isNotEmpty()) {
                val suggestions = viewModel.uiState.value.searchSuggestions
                val matching = suggestions.firstOrNull {
                    it.placeName.contains(query, ignoreCase = true) || it.fullAddress.contains(query, ignoreCase = true)
                } ?: suggestions.firstOrNull()

                if (matching != null) {
                    viewModel.selectSuggestion(matching, target)
                } else {
                    lifecycleScope.launch {
                        val state = viewModel.uiState.value
                        val biasLat = if (state.userGpsLat != 0.0) state.userGpsLat else null
                        val biasLon = if (state.userGpsLon != 0.0) state.userGpsLon else null
                        val results = OsmGeocodingHelper.searchLocations(
                            query = query,
                            context = this@RouteFinderActivity,
                            userLat = biasLat,
                            userLon = biasLon
                        )
                        if (results.isNotEmpty()) {
                            viewModel.selectSuggestion(results.first(), target)
                        } else {
                            Toast.makeText(this@RouteFinderActivity, "Location not found for \"$query\"", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        binding.etStartLocation.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || isEnter) {
                triggerSearchAction(true)
                true
            } else false
        }

        binding.etDestLocation.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || isEnter) {
                triggerSearchAction(false)
                true
            } else false
        }
    }

    private fun setupSuggestionsRecyclerView() {
        suggestionAdapter = RouteSuggestionAdapter { suggestion ->
            val target = viewModel.uiState.value.activeSearchTarget
            viewModel.selectSuggestion(suggestion, target)
            hideKeyboard()
            binding.etStartLocation.clearFocus()
            binding.etDestLocation.clearFocus()
        }

        binding.rvRouteSuggestions.apply {
            layoutManager = LinearLayoutManager(this@RouteFinderActivity)
            adapter = suggestionAdapter
        }
    }

    private fun setupTravelModeSelectors() {
        binding.btnModeCar.setOnClickListener {
            viewModel.setTravelMode(TravelMode.CAR)
        }
        binding.btnModeBike.setOnClickListener {
            viewModel.setTravelMode(TravelMode.BIKE)
        }
        binding.btnModeWalking.setOnClickListener {
            viewModel.setTravelMode(TravelMode.WALKING)
        }
    }

    private fun setupMapControls() {
        binding.btnLayerToggle.setOnClickListener {
            currentTileIndex = (currentTileIndex + 1) % tileSources.size
            FastTileSources.setBaseMap(binding.mapView, tileSources[currentTileIndex])
            Toast.makeText(this, "Layer: ${tileNames[currentTileIndex]}", Toast.LENGTH_SHORT).show()
        }

        binding.btnZoomIn.setOnClickListener {
            binding.mapView.controller.zoomIn()
        }

        binding.btnZoomOut.setOnClickListener {
            binding.mapView.controller.zoomOut()
        }

        binding.btnMyLocation.setOnClickListener {
            if (!locationHelper.hasLocationPermission()) {
                val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                if (preferenceManager.hasRequestedLocationPermission && !shouldShowRationale) {
                    com.autonoumus.ai.core.util.PermissionSettingsDialog.show(
                        context = this,
                        message = "Location permission is disabled. Please enable it in Settings to find your current location."
                    )
                } else {
                    preferenceManager.hasRequestedLocationPermission = true
                    locationPermissionLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                }
                return@setOnClickListener
            }
            val state = viewModel.uiState.value
            val lat = if (state.userGpsLat != 0.0) state.userGpsLat else state.startLat
            val lon = if (state.userGpsLon != 0.0) state.userGpsLon else state.startLon
            if (lat != 0.0 && lon != 0.0) {
                com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, GeoPoint(lat, lon), 16.5, 500L)
            }
        }
    }

    private fun setupBottomActions() {
        binding.btnStartNavigation.setOnClickListener {
            viewModel.startNavigation(this)
        }

        binding.cardOptionPrimary.setOnClickListener {
            viewModel.selectRouteOption(0)
        }

        binding.cardOptionAlternate.setOnClickListener {
            viewModel.selectRouteOption(1)
        }
    }

    private fun resolveInitialIntentOrGps() {
        val startLat = intent.getDoubleExtra(EXTRA_START_LAT, 0.0)
        val startLon = intent.getDoubleExtra(EXTRA_START_LON, 0.0)
        val startName = intent.getStringExtra(EXTRA_START_NAME).orEmpty()

        val destLat = intent.getDoubleExtra(EXTRA_DEST_LAT, 0.0)
        val destLon = intent.getDoubleExtra(EXTRA_DEST_LON, 0.0)
        val destName = intent.getStringExtra(EXTRA_DEST_NAME).orEmpty()

        if (startLat != 0.0 && startLon != 0.0) {
            viewModel.setUserGpsLocation(startLat, startLon, startName)
            binding.mapView.controller.setCenter(GeoPoint(startLat, startLon))
            binding.mapView.controller.setZoom(15.5)
        } else if (locationHelper.hasLocationPermission()) {
            observeGpsForStartLocation()
        } else if (!preferenceManager.hasRequestedLocationPermission) {
            preferenceManager.hasRequestedLocationPermission = true
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }

        observeDestinationFromIntentIfPresent(destLat, destLon, destName)
    }

    private fun observeGpsForStartLocation() {
        val startLat = intent.getDoubleExtra(EXTRA_START_LAT, 0.0)
        val destLat = intent.getDoubleExtra(EXTRA_DEST_LAT, 0.0)
        // Center instantly on the cached fix so tiles start loading right away; the live stream
        // below (which also reverse-geocodes, so its first emission is slow) refines it.
        if (destLat == 0.0 && startLat == 0.0) {
            locationHelper.getLastKnownLocation()?.let {
                binding.mapView.controller.setZoom(15.5)
                binding.mapView.controller.setCenter(GeoPoint(it.latitude, it.longitude))
            }
        }
        var centeredOnLiveFix = false
        lifecycleScope.launch {
            locationHelper.observeLocationUpdates().collect { data ->
                val lat = data.rawLatitude
                val lon = data.rawLongitude
                if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
                    viewModel.setUserGpsLocation(lat, lon, data.cityName)
                    // First live fix only, and not once the user has panned/zoomed - this used to
                    // run on every GPS update and kept snapping the user's zoom back to 15.5.
                    val userMoved = FastTileSources.run { binding.mapView.hasUserTouchedMap() }
                    if (destLat == 0.0 && startLat == 0.0 && !centeredOnLiveFix && !userMoved) {
                        binding.mapView.controller.setCenter(GeoPoint(lat, lon))
                        binding.mapView.controller.setZoom(15.5)
                    }
                    centeredOnLiveFix = true
                }
            }
        }
    }

    private fun observeDestinationFromIntentIfPresent(destLat: Double, destLon: Double, destName: String) {
        if (destLat != 0.0 && destLon != 0.0) {
            viewModel.selectSuggestion(
                com.autonoumus.ai.core.util.OsmSearchResult(
                    placeName = destName.ifBlank { "Destination" },
                    fullAddress = destName,
                    country = "",
                    latitude = destLat,
                    longitude = destLon,
                    latitudeFormatted = "",
                    longitudeFormatted = ""
                )
            )
        }
    }

    override fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderUiState(state)
                }
            }
        }
    }

    private fun renderUiState(state: RouteFinderUiState) {
        // 1. Text Inputs Synchronization
        if (binding.etStartLocation.text.toString() != state.startName) {
            isUpdatingStartText = true
            binding.etStartLocation.setText(state.startName)
            if (binding.etStartLocation.hasFocus()) {
                binding.etStartLocation.setSelection(state.startName.length)
            }
            isUpdatingStartText = false
        }

        if (binding.etDestLocation.text.toString() != state.destName) {
            isUpdatingDestText = true
            binding.etDestLocation.setText(state.destName)
            if (binding.etDestLocation.hasFocus()) {
                binding.etDestLocation.setSelection(state.destName.length)
            }
            isUpdatingDestText = false
        }

        // 2. Travel Mode Pill Styling
        updateTravelModePills(state.selectedTravelMode)

        // 3. Suggestions Overlay
        if (state.searchSuggestions.isNotEmpty()) {
            suggestionAdapter.submitList(state.searchSuggestions)
            binding.cardSuggestions.visibility = View.VISIBLE
        } else {
            binding.cardSuggestions.visibility = View.GONE
        }

        // 4. Loading Indicator
        binding.progressRouteLoading.visibility = if (state.isLoadingRoute) View.VISIBLE else View.GONE

        // 5. Active Route & Map Overlays (Markers & Polylines)
        updateMapOverlays(state)
    }

    private fun updateTravelModePills(mode: TravelMode) {
        val selectedBg = R.drawable.bg_travel_mode_selected
        val unselectedBg = R.drawable.bg_travel_mode_unselected
        val selectedColor = Color.WHITE
        val unselectedColor = Color.parseColor("#334155")

        // Car
        val isCar = mode == TravelMode.CAR
        binding.btnModeCar.setBackgroundResource(if (isCar) selectedBg else unselectedBg)
        binding.tvLabelCar.setTextColor(if (isCar) selectedColor else unselectedColor)
        binding.ivIconCar.setColorFilter(if (isCar) selectedColor else unselectedColor)

        // Bike
        val isBike = mode == TravelMode.BIKE
        binding.btnModeBike.setBackgroundResource(if (isBike) selectedBg else unselectedBg)
        binding.tvLabelBike.setTextColor(if (isBike) selectedColor else unselectedColor)
        binding.ivIconBike.setColorFilter(if (isBike) selectedColor else unselectedColor)

        // Walking
        val isWalking = mode == TravelMode.WALKING
        binding.btnModeWalking.setBackgroundResource(if (isWalking) selectedBg else unselectedBg)
        binding.tvLabelWalking.setTextColor(if (isWalking) selectedColor else unselectedColor)
        binding.ivIconWalking.setColorFilter(if (isWalking) selectedColor else unselectedColor)
    }

    private fun updateMapOverlays(state: RouteFinderUiState) {
        clearRouteOnMap()

        val routes = state.routes.ifEmpty {
            listOfNotNull(state.primaryRoute, state.alternateRoute)
        }
        val activeIndex = state.selectedRouteIndex.coerceIn(0, (routes.size - 1).coerceAtLeast(0))
        val activeRoute = routes.getOrNull(activeIndex) ?: state.activeRoute

        if (routes.isNotEmpty() && activeRoute != null && activeRoute.points.isNotEmpty()) {
            // 1. Draw Alternative Routes first (underneath, in Gray with click listener)
            routes.forEachIndexed { idx, r ->
                if (idx != activeIndex && r.points.isNotEmpty()) {
                    val altPolyline = Polyline(binding.mapView).apply {
                        setPoints(r.points)
                        outlinePaint.apply {
                            color = Color.parseColor("#94A3B8") // Slate Gray
                            strokeWidth = 12f
                            style = Paint.Style.STROKE
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            isAntiAlias = true
                        }
                        setOnClickListener { _, _, _ ->
                            viewModel.selectRouteOption(idx)
                            true
                        }
                    }
                    binding.mapView.overlays.add(altPolyline)
                }
            }

            // 2. Draw Active/Selected Route on top (Vibrant Blue #2563EB)
            val polyline = Polyline(binding.mapView).apply {
                setPoints(activeRoute.points)
                outlinePaint.apply {
                    color = Color.parseColor("#2563EB") // Vibrant Blue
                    strokeWidth = 16f
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true
                }
                setOnClickListener { _, _, _ ->
                    viewModel.selectRouteOption(activeIndex)
                    true
                }
            }
            binding.mapView.overlays.add(polyline)
            primaryPolyline = polyline

            // Update Floating On-Map Route Badge
            val isKm = preferenceManager.isDistanceUnitKm
            binding.tvOnMapStats.text = "${activeRoute.getFormattedDuration()} • ${activeRoute.getFormattedDistance(isKm)}"
            binding.tvOnMapTraffic.text = activeRoute.trafficStatus
            binding.layoutOnMapInfoBadge.visibility = View.VISIBLE

            renderBottomRouteDock(activeRoute, state, routes, activeIndex)
        } else {
            binding.layoutOnMapInfoBadge.visibility = View.GONE
            binding.layoutBottomRouteDock.visibility = View.GONE
        }

        // 3. Add Start Marker (Green Pin)
        if (state.startLat != 0.0 && state.startLon != 0.0) {
            val startGeo = GeoPoint(state.startLat, state.startLon)
            startMarker = Marker(binding.mapView).apply {
                position = startGeo
                title = if (state.startName.isNotBlank()) state.startName else "Start Point"
                icon = ContextCompat.getDrawable(this@RouteFinderActivity, R.drawable.ic_map_pin_green)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            binding.mapView.overlays.add(startMarker)
        }

        // 4. Add Destination Marker (Red Pin)
        if (state.destLat != 0.0 && state.destLon != 0.0) {
            val destGeo = GeoPoint(state.destLat, state.destLon)
            destMarker = Marker(binding.mapView).apply {
                position = destGeo
                title = if (state.destName.isNotBlank()) state.destName else "Destination"
                icon = ContextCompat.getDrawable(this@RouteFinderActivity, R.drawable.ic_map_pin_red)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            binding.mapView.overlays.add(destMarker)
        }

        binding.mapView.invalidate()

        // 5. Fit Map Camera to Route or Selected Point
        if (activeRoute != null && activeRoute.points.isNotEmpty()) {
            try {
                val bbox = BoundingBox.fromGeoPoints(activeRoute.points)
                binding.mapView.zoomToBoundingBox(bbox, true, 160)
            } catch (e: Exception) {
                if (state.destLat != 0.0) {
                    com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, GeoPoint(state.destLat, state.destLon), 14.5, 500L)
                }
            }
        } else if (state.destLat != 0.0 && state.destLon != 0.0) {
            com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, GeoPoint(state.destLat, state.destLon), 15.5, 500L)
        } else if (state.startLat != 0.0 && state.startLon != 0.0) {
            com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, GeoPoint(state.startLat, state.startLon), 15.5, 500L)
        }
    }

    private fun renderBottomRouteDock(
        route: RouteResult,
        state: RouteFinderUiState,
        routes: List<RouteResult> = emptyList(),
        selectedIndex: Int = 0
    ) {
        binding.layoutBottomRouteDock.visibility = View.VISIBLE

        val destTitle = if (state.destName.isNotBlank()) state.destName else "Destination"
        binding.tvRouteTitle.text = destTitle
        binding.tvRouteEtaBadge.text = route.getEstimatedArrivalTime()

        val isKm = preferenceManager.isDistanceUnitKm
        // (Removed "Avoids tolls": the OSRM request never excludes tolls, so it was a false claim.)
        binding.tvRouteSubtitle.text = "${route.getFormattedDistance(isKm)} • Live GPS Tracking Enabled"

        binding.tvStartNavText.text = "Start Navigation (${route.getFormattedDuration()})"

        // Route comparison options
        if (routes.size >= 2) {
            binding.layoutRouteOptions.visibility = View.VISIBLE
            val opt1 = routes[0]
            val opt2 = routes[1]

            // Primary Option (Index 0)
            val isOpt1Selected = selectedIndex == 0
            binding.cardOptionPrimary.setBackgroundResource(
                if (isOpt1Selected) R.drawable.bg_route_option_card_selected else R.drawable.bg_route_option_card_unselected
            )
            binding.tvOption1Name.text = opt1.routeName.ifBlank { "Route 1" }
            binding.tvOption1Duration.text = opt1.getFormattedDuration()
            binding.tvOption1Duration.setTextColor(if (isOpt1Selected) Color.parseColor("#2563EB") else Color.parseColor("#475569"))
            binding.tvOption1Distance.text = "${opt1.getFormattedDistance(isKm)} • Fastest"

            // Alternate Option (Index 1)
            val isOpt2Selected = selectedIndex == 1
            binding.cardOptionAlternate.setBackgroundResource(
                if (isOpt2Selected) R.drawable.bg_route_option_card_selected else R.drawable.bg_route_option_card_unselected
            )
            binding.tvOption2Name.text = opt2.routeName.ifBlank { "Route 2" }
            binding.tvOption2Duration.text = opt2.getFormattedDuration()
            binding.tvOption2Duration.setTextColor(if (isOpt2Selected) Color.parseColor("#2563EB") else Color.parseColor("#475569"))
            val diffSec = opt2.durationSec - opt1.durationSec
            val diffStr = if (diffSec > 0) " • +${(diffSec + 59) / 60} min" else ""
            binding.tvOption2Distance.text = "${opt2.getFormattedDistance(isKm)}$diffStr"
        } else {
            binding.layoutRouteOptions.visibility = View.GONE
        }
    }

    private fun clearRouteOnMap() {
        if (primaryPolyline != null) {
            binding.mapView.overlays.remove(primaryPolyline)
            primaryPolyline = null
        }
        if (alternatePolyline != null) {
            binding.mapView.overlays.remove(alternatePolyline)
            alternatePolyline = null
        }
        if (startMarker != null) {
            binding.mapView.overlays.remove(startMarker)
            startMarker = null
        }
        if (destMarker != null) {
            binding.mapView.overlays.remove(destMarker)
            destMarker = null
        }
        binding.mapView.overlays.removeAll { it is Marker || it is Polyline }
        binding.mapView.invalidate()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etStartLocation.windowToken, 0)
        imm?.hideSoftInputFromWindow(binding.etDestLocation.windowToken, 0)
        imm?.hideSoftInputFromWindow(binding.routeFinderRoot.windowToken, 0)
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        val state = viewModel.uiState.value
        val route = state.activeRoute
        if (route != null) {
            val routes = state.routes.ifEmpty { listOfNotNull(state.primaryRoute, state.alternateRoute) }
            renderBottomRouteDock(route, state, routes, state.selectedRouteIndex)
            val isKm = preferenceManager.isDistanceUnitKm
            binding.tvOnMapStats.text = "${route.getFormattedDuration()} • ${route.getFormattedDistance(isKm)}"
        }
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
}
