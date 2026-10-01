package com.autonoumus.ai.presentation.nearby

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
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
import com.autonoumus.ai.core.util.RouteResult
import com.autonoumus.ai.databinding.ActivityNearbyPlacesBinding
import com.autonoumus.ai.domain.model.NearbyCategory
import com.autonoumus.ai.domain.model.NearbyPlace
import com.autonoumus.ai.domain.model.NearbySort
import com.autonoumus.ai.presentation.nearby.adapter.CategoryFilterAdapter
import com.autonoumus.ai.presentation.nearby.adapter.NearbyPlacesAdapter
import com.autonoumus.ai.presentation.nearby.adapter.SearchSuggestionAdapter
import com.google.android.material.bottomsheet.BottomSheetBehavior
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import javax.inject.Inject

@AndroidEntryPoint
class NearbyPlacesActivity : BaseActivity<ActivityNearbyPlacesBinding>() {

    @Inject
    lateinit var locationHelper: LocationHelper

    @Inject
    lateinit var preferenceManager: com.autonoumus.ai.data.local.PreferenceManager

    private val locationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            observeGpsForUserLocation()
        } else {
            val shouldShowRationale = shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_FINE_LOCATION)
            com.autonoumus.ai.core.util.PermissionSettingsDialog.handleDenied(
                context = this,
                shouldShowRationale = shouldShowRationale,
                deniedMessage = "Location permission is required to find nearby places"
            )
        }
    }

    private val viewModel: NearbyPlacesViewModel by viewModels()

    private lateinit var categoryAdapter: CategoryFilterAdapter
    private lateinit var placesAdapter: NearbyPlacesAdapter
    private lateinit var searchSuggestionAdapter: SearchSuggestionAdapter
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<android.widget.LinearLayout>

    private var userMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var currentRoutePolyline: Polyline? = null
    private var searchRadiusCircle: Polygon? = null
    private val placeMarkers = mutableListOf<Marker>()

    // Marker bookkeeping: only rebuild what actually changed, and draw each pin style once
    private var renderedMarkerPlaces: List<NearbyPlace>? = null
    private val pinCache = mutableMapOf<Pair<NearbyCategory, Boolean>, Drawable>()
    private var lastCircle: Triple<Double, Double, Double>? = null

    private var currentTileIndex = 0
    private val tileSources = listOf(
        FastTileSources.STREET,
        FastTileSources.SATELLITE,
        FastTileSources.HYBRID,
        FastTileSources.TERRAIN
    )
    private val tileNames = listOf("Street", "Satellite", "Hybrid", "Terrain")

    companion object {
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LON = "extra_lon"
        const val EXTRA_CITY = "extra_city"

        fun newIntent(
            context: Context,
            latitude: Double = 0.0,
            longitude: Double = 0.0,
            cityName: String = ""
        ): Intent {
            return Intent(context, NearbyPlacesActivity::class.java).apply {
                putExtra(EXTRA_LAT, latitude)
                putExtra(EXTRA_LON, longitude)
                putExtra(EXTRA_CITY, cityName)
            }
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityNearbyPlacesBinding {
        return ActivityNearbyPlacesBinding.inflate(inflater)
    }

    override fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.nearbyRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBarContainer.updatePadding(top = bars.top)
            binding.bottomSheetNearby.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    override fun initViews() {
        setupMap()
        setupBottomSheet()
        setupTopSearchAndCategories()
        setupRadiusSelector()
        setupResultFilters()
        setupMapControls()
        resolveInitialLocation()
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
            // Ignore setup errors
        }
    }

    private fun setupBottomSheet() {
        val screenHeight = resources.displayMetrics.heightPixels
        val peek = (screenHeight * 0.30f).toInt().coerceAtLeast((resources.displayMetrics.density * 220).toInt())
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheetNearby).apply {
            peekHeight = peek
            isHideable = false
            state = BottomSheetBehavior.STATE_COLLAPSED
        }

        placesAdapter = NearbyPlacesAdapter(
            onPlaceClick = { place ->
                focusOnPlace(place)
            },
            onDirectionsClick = { place ->
                viewModel.requestRoute(place)
            }
        )

        binding.rvNearbyPlaces.apply {
            layoutManager = LinearLayoutManager(this@NearbyPlacesActivity)
            adapter = placesAdapter
        }

        binding.btnClosePreview.setOnClickListener {
            viewModel.clearRoute()
            viewModel.selectPlace(null)
        }
    }

    private fun setupRadiusSelector() {
        binding.btnRadiusSelector.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            NearbyRadius.OPTIONS.forEachIndexed { index, meters ->
                popup.menu.add(0, meters, index, "Within ${meters / 1000} km")
            }

            popup.setOnMenuItemClickListener { item ->
                applyRadius(item.itemId)
                binding.tvRadiusValue.text = item.title
                true
            }
            popup.show()
        }
    }

    private fun applyRadius(radius: Int) {
        viewModel.setSearchRadius(radius)

        val state = viewModel.uiState.value
        val centerLat = if (state.searchCenterLat != 0.0) state.searchCenterLat else state.userLatitude
        val centerLon = if (state.searchCenterLon != 0.0) state.searchCenterLon else state.userLongitude
        if (centerLat != 0.0 && centerLon != 0.0) {
            val targetZoom = when {
                radius <= 1000 -> 15.5
                radius <= 2000 -> 14.8
                radius <= 5000 -> 13.5
                radius <= 10000 -> 12.2
                radius <= 20000 -> 11.2
                else -> 10.4
            }
            val geo = GeoPoint(centerLat, centerLon)
            com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, geo, targetZoom, 600L)
        }
    }

    private fun setupResultFilters() {
        binding.btnSort.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            NearbySort.values().forEachIndexed { index, sort ->
                popup.menu.add(0, index, index, sort.title)
            }
            popup.setOnMenuItemClickListener { item ->
                viewModel.setSortOption(NearbySort.values()[item.itemId])
                true
            }
            popup.show()
        }
        binding.chipOpenNow.setOnClickListener { viewModel.toggleOpenNow() }
        binding.chipHasPhone.setOnClickListener { viewModel.toggleHasPhone() }
    }

    private fun setupTopSearchAndCategories() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Category Filter Chips
        categoryAdapter = CategoryFilterAdapter(
            onCategorySelected = { category ->
                viewModel.selectCategory(category)
            }
        )

        binding.rvCategoryChips.apply {
            layoutManager = LinearLayoutManager(this@NearbyPlacesActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = categoryAdapter
        }

        // Search Suggestions Adapter
        searchSuggestionAdapter = SearchSuggestionAdapter { suggestion ->
            viewModel.selectSearchResult(suggestion)
            hideKeyboard()
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            val geo = GeoPoint(suggestion.latitude, suggestion.longitude)
            com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, geo, 16.5, 500L)
        }

        binding.rvSearchSuggestions.apply {
            layoutManager = LinearLayoutManager(this@NearbyPlacesActivity)
            adapter = searchSuggestionAdapter
        }

        // Search Input
        binding.layoutSearchBar.setOnClickListener {
            binding.etSearchPlaces.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etSearchPlaces, InputMethodManager.SHOW_IMPLICIT)
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }

        binding.etSearchPlaces.setOnClickListener {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }

        binding.etSearchPlaces.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            }
        }

        binding.etSearchPlaces.doAfterTextChanged { editable ->
            val query = editable?.toString().orEmpty()
            binding.btnClearSearch.visibility = if (query.isNotBlank()) View.VISIBLE else View.GONE
            if (query.isNotBlank()) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            }
            viewModel.onSearchQueryChanged(query)
        }

        binding.btnClearSearch.setOnClickListener {
            binding.etSearchPlaces.text = null
            viewModel.clearSearch()
            hideKeyboard()
        }

        binding.etSearchPlaces.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || isEnter) {
                hideKeyboard()
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                val currentSuggestions = searchSuggestionAdapter.currentList
                if (currentSuggestions.isNotEmpty()) {
                    val first = currentSuggestions.first()
                    viewModel.selectSearchResult(first)
                    val geo = GeoPoint(first.latitude, first.longitude)
                    com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, geo, 16.5, 500L)
                }
                true
            } else {
                false
            }
        }
    }

    private fun setupMapControls() {
        binding.btnMyLocation.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.userLatitude != 0.0 && state.userLongitude != 0.0) {
                val userGeo = GeoPoint(state.userLatitude, state.userLongitude)
                com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, userGeo, 16.5, 600L)
            } else {
                if (!locationHelper.hasLocationPermission()) {
                    val shouldShowRationale = shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    if (preferenceManager.hasRequestedLocationPermission && !shouldShowRationale) {
                        com.autonoumus.ai.core.util.PermissionSettingsDialog.show(
                            context = this,
                            message = "Location permission is disabled. Please enable it in Settings to find nearby places."
                        )
                    } else {
                        preferenceManager.hasRequestedLocationPermission = true
                        locationPermissionLauncher.launch(
                            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                    }
                } else {
                    resolveInitialLocation()
                }
            }
        }

        binding.btnZoomIn.setOnClickListener {
            binding.mapView.controller.zoomIn()
        }

        binding.btnZoomOut.setOnClickListener {
            binding.mapView.controller.zoomOut()
        }

        binding.btnLayerToggle.setOnClickListener {
            currentTileIndex = (currentTileIndex + 1) % tileSources.size
            FastTileSources.setBaseMap(binding.mapView, tileSources[currentTileIndex])
            Toast.makeText(this, "Layer: ${tileNames[currentTileIndex]}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolveInitialLocation() {
        val extraLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val extraLon = intent.getDoubleExtra(EXTRA_LON, 0.0)
        val extraCity = intent.getStringExtra(EXTRA_CITY)

        if (extraLat != 0.0 && extraLon != 0.0) {
            viewModel.setUserLocation(extraLat, extraLon, extraCity)
            centerMapOnLocation(extraLat, extraLon)
        } else if (locationHelper.hasLocationPermission()) {
            observeGpsForUserLocation()
        } else if (!preferenceManager.hasRequestedLocationPermission) {
            preferenceManager.hasRequestedLocationPermission = true
            locationPermissionLauncher.launch(
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun observeGpsForUserLocation() {
        // Center instantly on the cached fix so tiles start loading right away; the live stream
        // below (which also reverse-geocodes, so its first emission is slow) refines it.
        // Start from the location Home (or another screen) already resolved - position AND city,
        // no waiting. Otherwise fall back to the device's cached fix. Either way places load right
        // away (usually straight from the local cache) instead of after a live fix + geocode.
        val shared = locationHelper.sharedLocation()
        val sharedLat = shared?.rawLatitude
        val sharedLon = shared?.rawLongitude
        if (sharedLat != null && sharedLon != null) {
            viewModel.setUserLocation(sharedLat, sharedLon, shared.cityName)
            centerMapOnLocation(sharedLat, sharedLon)
        } else {
            locationHelper.getLastKnownLocation()?.let {
                viewModel.setUserLocation(it.latitude, it.longitude, null, isProvisional = true)
                centerMapOnLocation(it.latitude, it.longitude)
            }
        }
        var centeredOnLiveFix = false
        lifecycleScope.launch {
            locationHelper.observeLocationUpdates().collect { data ->
                val lat = data.rawLatitude
                val lon = data.rawLongitude
                if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
                    viewModel.setUserLocation(lat, lon, data.cityName)
                    // Move the camera for the first live fix only, and not once the user has
                    // panned/zoomed - this ran on every GPS update and kept snapping the user's
                    // zoom back to 15.5. The marker and radius circle still follow every update.
                    val userMoved = FastTileSources.run { binding.mapView.hasUserTouchedMap() }
                    centerMapOnLocation(lat, lon, moveCamera = !centeredOnLiveFix && !userMoved)
                    centeredOnLiveFix = true
                }
            }
        }
    }

    private fun centerMapOnLocation(lat: Double, lon: Double, moveCamera: Boolean = true) {
        val geoPoint = GeoPoint(lat, lon)
        if (moveCamera) {
            binding.mapView.controller.setZoom(15.5)
            binding.mapView.controller.setCenter(geoPoint)
        }
        updateUserLocationMarker(geoPoint)
        updateSearchRadiusCircle(geoPoint, viewModel.uiState.value.searchRadiusMeters.toDouble())
    }

    private fun updateUserLocationMarker(geoPoint: GeoPoint) {
        if (userMarker == null) {
            userMarker = Marker(binding.mapView).apply {
                position = geoPoint
                title = "Your Location"
                icon = ContextCompat.getDrawable(this@NearbyPlacesActivity, R.drawable.ic_map_center_pin)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            binding.mapView.overlays.add(userMarker)
        } else {
            userMarker?.position = geoPoint
        }
        binding.mapView.invalidate()
    }

    private fun updateSearchRadiusCircle(geoPoint: GeoPoint, radiusMeters: Double) {
        val key = Triple(geoPoint.latitude, geoPoint.longitude, radiusMeters)
        if (key == lastCircle && searchRadiusCircle != null) return
        lastCircle = key
        if (searchRadiusCircle != null) {
            binding.mapView.overlays.remove(searchRadiusCircle)
        }
        searchRadiusCircle = Polygon(binding.mapView).apply {
            points = Polygon.pointsAsCircle(geoPoint, radiusMeters)
            fillPaint.color = Color.parseColor("#184F46E5") // soft translucent indigo
            outlinePaint.apply {
                color = Color.parseColor("#444F46E5")
                strokeWidth = 2.5f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
        }
        binding.mapView.overlays.add(0, searchRadiusCircle)
        binding.mapView.invalidate()
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

    private fun renderUiState(state: NearbyPlacesUiState) {
        // 1. Progress & Search Loading
        binding.progressLoading.visibility = if (state.isLoading || state.isRouteLoading) View.VISIBLE else View.GONE
        binding.progressSearch.visibility = if (state.isSearchingAddress) View.VISIBLE else View.GONE

        // 2. Radius text
        binding.tvRadiusValue.text = state.getFormattedRadius()

        // 3. Search suggestions dropdown
        if (state.searchResults.isNotEmpty()) {
            searchSuggestionAdapter.submitList(state.searchResults)
            binding.rvSearchSuggestions.visibility = View.VISIBLE
        } else {
            binding.rvSearchSuggestions.visibility = View.GONE
        }

        // 4. Sort / filter controls
        renderResultFilters(state)

        // 5. Update user marker & radius circle (the circle follows the search centre)
        if (state.userLatitude != 0.0 && state.userLongitude != 0.0) {
            updateUserLocationMarker(GeoPoint(state.userLatitude, state.userLongitude))
        }
        val centerLat = if (state.searchCenterLat != 0.0) state.searchCenterLat else state.userLatitude
        val centerLon = if (state.searchCenterLon != 0.0) state.searchCenterLon else state.userLongitude
        if (centerLat != 0.0 && centerLon != 0.0) {
            updateSearchRadiusCircle(GeoPoint(centerLat, centerLon), state.searchRadiusMeters.toDouble())
        }

        // 6. In-App Routing or Selected Place or Places List
        if (state.activeRoute != null && state.selectedPlace != null) {
            renderActiveRoute(state.activeRoute, state.selectedPlace, state.userCity)
        } else if (state.selectedPlace != null) {
            renderSelectedPlace(state.selectedPlace)
        } else {
            renderPlacesList(state)
        }
    }

    private fun renderResultFilters(state: NearbyPlacesUiState) {
        binding.btnSort.text = "Sort: ${state.sortOption.title}"
        styleToggleChip(binding.chipOpenNow, state.openNowOnly)
        styleToggleChip(binding.chipHasPhone, state.hasPhoneOnly)
    }

    private fun styleToggleChip(chip: android.widget.TextView, active: Boolean) {
        chip.setBackgroundResource(if (active) R.drawable.bg_nearby_chip_selected else R.drawable.bg_nearby_chip_unselected)
        chip.setTextColor(Color.parseColor(if (active) "#FFFFFF" else "#334155"))
    }

    private fun renderPlacesList(state: NearbyPlacesUiState) {
        // Remove active route polyline and destination marker
        clearRouteOverlay()

        binding.layoutRoutePreview.visibility = View.GONE
        binding.layoutSelectedPlacePreview.visibility = View.GONE
        binding.btnClosePreview.visibility = View.GONE
        binding.scrollResultFilters.visibility = View.VISIBLE

        binding.tvSheetTitle.text = "Closest Results"
        binding.tvPlacesCount.text = if (state.places.size == state.totalFound) {
            "${state.places.size} found"
        } else {
            "${state.places.size} of ${state.totalFound}"
        }
        binding.tvPlacesCount.visibility = View.VISIBLE

        placesAdapter.submitList(state.places)

        if (state.places.isEmpty() && !state.isLoading) {
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.rvNearbyPlaces.visibility = View.GONE
            renderEmptyState(state)
        } else {
            binding.layoutEmptyState.visibility = View.GONE
            binding.rvNearbyPlaces.visibility = View.VISIBLE
        }

        renderPlaceMarkers(state.places)
    }

    /** Explains why the list is empty and offers the one action that is most likely to fix it. */
    private fun renderEmptyState(state: NearbyPlacesUiState) {
        val action = binding.btnEmptyAction
        when {
            state.errorMessage != null -> {
                binding.tvEmptyMessage.text = state.errorMessage
                action.text = "Try again"
                action.setOnClickListener { viewModel.refreshPlaces() }
                action.visibility = View.VISIBLE
            }
            state.totalFound > 0 && state.hasActiveFilters() -> {
                // Most places in the map data carry no opening hours / phone number at all, so
                // say that instead of a vague "no match" - it isn't the filter that's broken.
                val count = "${state.totalFound} place${if (state.totalFound == 1) "" else "s"}"
                binding.tvEmptyMessage.text = when {
                    state.openNowOnly && state.withHoursCount == 0 ->
                        "None of the $count found here list opening hours yet, so \"Open now\" can't be checked."
                    state.hasPhoneOnly && state.withPhoneCount == 0 ->
                        "None of the $count found here list a phone number yet."
                    else -> "No places match your filters"
                }
                action.text = "Clear filters"
                action.setOnClickListener { viewModel.clearFilters() }
                action.visibility = View.VISIBLE
            }
            state.canExpandRadius() -> {
                binding.tvEmptyMessage.text = "No places found ${state.getFormattedRadius().replaceFirstChar { it.lowercase() }}"
                action.text = "Search wider area"
                action.setOnClickListener {
                    NearbyRadius.next(state.searchRadiusMeters)?.let { applyRadius(it) }
                }
                action.visibility = View.VISIBLE
            }
            else -> {
                binding.tvEmptyMessage.text = "No places found nearby"
                action.visibility = View.GONE
            }
        }
    }

    private fun renderSelectedPlace(place: NearbyPlace) {
        clearRouteOverlay()

        binding.layoutRoutePreview.visibility = View.GONE
        binding.rvNearbyPlaces.visibility = View.GONE
        binding.layoutEmptyState.visibility = View.GONE
        binding.scrollResultFilters.visibility = View.GONE
        binding.layoutSelectedPlacePreview.visibility = View.VISIBLE
        binding.btnClosePreview.visibility = View.VISIBLE
        binding.tvPlacesCount.visibility = View.GONE

        binding.tvSheetTitle.text = place.name

        // Bind single place card
        binding.cardSelectedPlace.apply {
            tvCategoryEmoji.text = place.getCategoryEmoji()
            tvPlaceName.text = place.name
            tvPlaceSubtitle.text = "${place.categoryName} • ${place.getFormattedDistance()} away"
            tvPlaceStatus.text = place.address

            btnDirections.setOnClickListener {
                viewModel.requestRoute(place)
            }

            btnShare.setOnClickListener {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, place.name)
                    putExtra(Intent.EXTRA_TEXT, "${place.name}\n${place.address}\nhttps://www.google.com/maps/search/?api=1&query=${place.latitude},${place.longitude}")
                }
                startActivity(Intent.createChooser(shareIntent, "Share Place"))
            }
        }

        // Add destination marker and zoom
        addDestinationMarker(place)
    }

    private fun renderActiveRoute(route: RouteResult, destinationPlace: NearbyPlace, userCity: String) {
        binding.layoutSelectedPlacePreview.visibility = View.GONE
        binding.rvNearbyPlaces.visibility = View.GONE
        binding.layoutEmptyState.visibility = View.GONE
        binding.scrollResultFilters.visibility = View.GONE
        binding.layoutRoutePreview.visibility = View.VISIBLE
        binding.btnClosePreview.visibility = View.VISIBLE
        binding.tvPlacesCount.visibility = View.GONE

        binding.tvSheetTitle.text = "Route Preview"
        binding.tvRouteOrigin.text = userCity.ifBlank { "My Location" }
        binding.tvRouteDestination.text = destinationPlace.name
        binding.tvRouteDuration.text = route.getFormattedDuration()
        binding.tvRouteDistance.text = "(${route.getFormattedDistance()})"

        binding.btnStartNavigation.setOnClickListener {
            viewModel.startNavigationAndSaveTrip(this, destinationPlace)
        }

        // Draw Route Polyline on Map
        drawRoutePolyline(route, destinationPlace)
    }

    private fun drawRoutePolyline(route: RouteResult, destinationPlace: NearbyPlace) {
        clearRouteOverlay()

        // Hide normal place markers during routing
        placeMarkers.forEach { binding.mapView.overlays.remove(it) }
        placeMarkers.clear()
        renderedMarkerPlaces = null

        if (route.points.isNotEmpty()) {
            val polyline = Polyline(binding.mapView).apply {
                setPoints(route.points)
                outlinePaint.apply {
                    color = Color.parseColor("#4F46E5")
                    strokeWidth = 14f
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true
                }
            }
            binding.mapView.overlays.add(polyline)
            currentRoutePolyline = polyline

            addDestinationMarker(destinationPlace)

            // Zoom to fit route bounding box
            try {
                val bbox = BoundingBox.fromGeoPoints(route.points)
                binding.mapView.zoomToBoundingBox(bbox, true, 140)
            } catch (e: Exception) {
                val geo = GeoPoint(destinationPlace.latitude, destinationPlace.longitude)
                com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, geo, 14.5, 500L)
            }
        }
        binding.mapView.invalidate()
    }

    private fun addDestinationMarker(place: NearbyPlace) {
        if (destinationMarker != null) {
            binding.mapView.overlays.remove(destinationMarker)
        }
        val destGeo = GeoPoint(place.latitude, place.longitude)
        destinationMarker = Marker(binding.mapView).apply {
            position = destGeo
            title = place.name
            snippet = place.address
            icon = pinFor(place.categoryType, highlighted = true)
            setAnchor(Marker.ANCHOR_CENTER, pinAnchorY(highlighted = true))
        }
        binding.mapView.overlays.add(destinationMarker)
        binding.mapView.invalidate()
    }

    private fun clearRouteOverlay() {
        if (currentRoutePolyline != null) {
            binding.mapView.overlays.remove(currentRoutePolyline)
            currentRoutePolyline = null
        }
        if (destinationMarker != null) {
            binding.mapView.overlays.remove(destinationMarker)
            destinationMarker = null
        }
        binding.mapView.invalidate()
    }

    private fun renderPlaceMarkers(places: List<NearbyPlace>) {
        // The state is re-rendered for many unrelated reasons (typing, loading...); skip when nothing changed
        if (places == renderedMarkerPlaces) return
        renderedMarkerPlaces = places

        // Remove previous place markers
        placeMarkers.forEach { binding.mapView.overlays.remove(it) }
        placeMarkers.clear()

        for (place in places) {
            val marker = Marker(binding.mapView).apply {
                position = GeoPoint(place.latitude, place.longitude)
                title = place.name
                snippet = "${place.categoryName} • ${place.getFormattedDistance()}"
                icon = pinFor(place.categoryType, highlighted = false)
                setAnchor(Marker.ANCHOR_CENTER, pinAnchorY(highlighted = false))
                setOnMarkerClickListener { m, _ ->
                    focusOnPlace(place)
                    m.showInfoWindow()
                    true
                }
            }
            binding.mapView.overlays.add(marker)
            placeMarkers.add(marker)
        }

        binding.mapView.invalidate()
    }

    /** One drawable per category & size, shared by every marker that uses it. */
    private fun pinFor(category: NearbyCategory, highlighted: Boolean): Drawable =
        pinCache.getOrPut(category to highlighted) { createPinDrawable(category, highlighted) }

    /** Vertical anchor that puts the pin's tip (not the bitmap edge) on the location; matches [createPinDrawable]. */
    private fun pinAnchorY(highlighted: Boolean): Float {
        val scale = if (highlighted) 1.25f else 1f
        val padDp = 4f
        val heightDp = (2 * 17f + 11f) * scale + 2 * padDp
        return 1f - padDp / heightDp
    }

    /** Teardrop map pin in the category colour with its emoji, so the tip marks the exact spot. */
    private fun createPinDrawable(category: NearbyCategory, highlighted: Boolean): Drawable {
        val density = resources.displayMetrics.density
        val scale = if (highlighted) 1.25f else 1f
        val headRadius = 17f * density * scale
        val tailLength = 11f * density * scale
        val pad = 4f * density // room for the drop shadow

        val width = (2 * headRadius + 2 * pad).toInt()
        val height = (2 * headRadius + tailLength + 2 * pad).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cx = width / 2f
        val cy = pad + headRadius
        val tipY = cy + headRadius + tailLength

        val pin = Path().apply { addCircle(cx, cy, headRadius, Path.Direction.CW) }
        val tail = Path().apply {
            moveTo(cx - headRadius * 0.55f, cy + headRadius * 0.82f)
            lineTo(cx + headRadius * 0.55f, cy + headRadius * 0.82f)
            lineTo(cx, tipY)
            close()
        }
        pin.op(tail, Path.Op.UNION)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(category.pinColor)
            style = Paint.Style.FILL
            setShadowLayer(3f * density, 0f, 1.5f * density, Color.parseColor("#40000000"))
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(pin, fillPaint)
        canvas.drawPath(pin, strokePaint)

        // Inner white disc so the emoji is readable on every pin colour
        val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(cx, cy, headRadius * 0.68f, discPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 15f * density * scale
            textAlign = Paint.Align.CENTER
        }
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(category.emoji, cx, textY, textPaint)

        return BitmapDrawable(resources, bitmap)
    }

    private fun focusOnPlace(place: NearbyPlace) {
        viewModel.selectPlace(place)
        val geo = GeoPoint(place.latitude, place.longitude)
        com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, geo, 17.0, 600L)

        // Find marker and show its info window
        placeMarkers.find { it.position.latitude == place.latitude && it.position.longitude == place.longitude }?.showInfoWindow()

        // Expand bottom sheet to show details card
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        currentFocus?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
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
}
