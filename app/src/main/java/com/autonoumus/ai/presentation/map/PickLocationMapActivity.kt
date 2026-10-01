package com.autonoumus.ai.presentation.map

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.core.util.OsmGeocodingHelper
import com.autonoumus.ai.core.util.OsmReverseGeoResult
import com.autonoumus.ai.core.util.OsmSearchResult
import com.autonoumus.ai.core.util.WorldLocationProvider
import com.autonoumus.ai.databinding.ActivityPickLocationMapBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.autonoumus.ai.presentation.gallery.MediaViewerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.core.content.ContextCompat
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

enum class SelectedMapType {
    SATELLITE, HYBRID, TERRAIN, STREET
}

@AndroidEntryPoint
class PickLocationMapActivity : BaseActivity<ActivityPickLocationMapBinding>() {

    @Inject
    lateinit var locationHelper: LocationHelper

    private lateinit var searchAdapter: MapSearchResultAdapter
    private var reverseGeoJob: Job? = null
    private var searchJob: Job? = null
    private var isSearchingProgrammatically = false

    private var userHasInteractedWithMap = false
    private var currentReverseGeo: OsmReverseGeoResult? = null
    private var currentMapType: SelectedMapType = SelectedMapType.STREET
    private var isSatelliteMode: Boolean = false

    private var userLocationMarker: Marker? = null
    // Point the stamp currently describes (map center when last resolved) + guard for stale answers
    private var lastGeocodedPoint: GeoPoint? = null
    private var geocodeRequestId = 0
    // True while the request for the spot currently under the pin hasn't answered yet
    private var awaitingGeocode = false
    private var userCurrentGeoPoint: GeoPoint? = null

    companion object {
        private const val GEOCODE_TIMEOUT_MS = 6000L

        const val EXTRA_COUNTRY = "extra_country"
        const val EXTRA_ADDRESS = "extra_address"
        const val EXTRA_LAT_FORMATTED = "extra_lat_formatted"
        const val EXTRA_LON_FORMATTED = "extra_lon_formatted"
        const val EXTRA_RAW_LAT = "extra_raw_lat"
        const val EXTRA_RAW_LON = "extra_raw_lon"

        const val EXTRA_INITIAL_LAT = "extra_initial_lat"
        const val EXTRA_INITIAL_LON = "extra_initial_lon"
        const val EXTRA_IS_SATELLITE_MODE = "extra_is_satellite_mode"
        const val EXTRA_HIDE_CAPTURE_BUTTON = "extra_hide_capture_button"
        const val EXTRA_HIDE_SELECT_ARROW = "extra_hide_select_arrow"

        fun createSatelliteIntent(context: Context): Intent {
            return Intent(context, PickLocationMapActivity::class.java).apply {
                putExtra(EXTRA_IS_SATELLITE_MODE, true)
                putExtra(EXTRA_HIDE_SELECT_ARROW, true)
            }
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityPickLocationMapBinding {
        return ActivityPickLocationMapBinding.inflate(inflater)
    }

    override fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mapRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            binding.topBarContainer.updatePadding(top = bars.top)
            binding.bottomStampContainer.updateLayoutParams<RelativeLayout.LayoutParams> {
                bottomMargin = bars.bottom + (16 * density).toInt()
            }
            insets
        }
    }

    override fun initViews() {
        isSatelliteMode = intent.getBooleanExtra(EXTRA_IS_SATELLITE_MODE, false)

        initOsmConfiguration()
        setupMapView()
        setupSearch()
        setupMapTypeSelector()
        setupActionButtons()
        setupStampCardClick()
        setupCaptureStampMap()
        // No "Loading Satellite Map" dialog any more: it covered the map on every open - even when
        // all tiles came straight from cache - for up to 4s. Like Google Maps, the map is usable
        // immediately and tiles fill in (cached ones instantly, lower-zoom previews meanwhile).
        loadInitialLocation()
    }

    private fun initOsmConfiguration() {
        com.autonoumus.ai.core.util.FastTileSources.optimizeConfiguration(applicationContext)
    }

    private fun updateUserLocationMarker(geoPoint: GeoPoint) {
        userCurrentGeoPoint = geoPoint
        if (userLocationMarker == null) {
            userLocationMarker = Marker(binding.mapView).apply {
                position = geoPoint
                title = "My Location"
                icon = ContextCompat.getDrawable(this@PickLocationMapActivity, R.drawable.ic_map_beacon_center)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ -> true }
            }
            binding.mapView.overlays.add(userLocationMarker)
        } else {
            userLocationMarker?.position = geoPoint
        }
        binding.mapView.invalidate()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupMapView() {
        binding.mapView.apply {
            com.autonoumus.ai.core.util.FastTileSources.setBaseMap(this, com.autonoumus.ai.core.util.FastTileSources.STREET)
            com.autonoumus.ai.core.util.FastTileSources.run { applyHighPerformanceSettings(true) }
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            // Zoom is set together with a real center in loadInitialLocation(); zooming to 16 here
            // on an unknown position showed a street-level view of the wrong place first.

            setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    userHasInteractedWithMap = true
                    liftCenterPin(true)
                }
                false
            }

            // Tapping a spot brings it under the fixed center pin (instead of dropping a second
            // marker elsewhere); the address is then resolved once the map settles.
            val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                    if (p != null) {
                        com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, p)
                        return true
                    }
                    return false
                }

                override fun longPressHelper(p: GeoPoint?): Boolean {
                    if (p != null) {
                        com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, p)
                        return true
                    }
                    return false
                }
            })
            overlays.add(0, mapEventsOverlay)

            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    onMapMoved()
                    return true
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    onMapMoved()
                    return true
                }
            })
        }
    }

    /**
     * The red pin is a fixed view over the exact center of the map (see centerPinContainer), not
     * a map marker. It used to be a marker on a geo point: it slid away with the map while
     * dragging and only jumped back to the center 0.45s after the map stopped, and a tap dropped
     * it on the tapped spot while "Select location" returned the map center - so the pin, the
     * stamp and the saved location could all disagree. Now whatever is under the pin tip is
     * exactly what gets geocoded, stamped and returned.
     */
    private fun onMapMoved() {
        if (binding.viewStampShimmer.visibility != View.VISIBLE) {
            // The stamp still described the previous spot while the map moved - clear it so it
            // never shows an address that is no longer under the pin.
            showLocatingStamp()
        }

        // Debounce: resolve the address once the map has settled under the pin
        reverseGeoJob?.cancel()
        reverseGeoJob = lifecycleScope.launch {
            delay(450)
            liftCenterPin(false)
            val center = binding.mapView.mapCenter
            val last = lastGeocodedPoint
            if (last != null) {
                val moved = FloatArray(1)
                android.location.Location.distanceBetween(last.latitude, last.longitude, center.latitude, center.longitude, moved)
                if (moved[0] < 3f) {
                    // Same spot: either its answer is still coming, or it's already resolved (e.g.
                    // a search result the map just flew to) - then just restore that stamp.
                    if (!awaitingGeocode) {
                        currentReverseGeo?.let { showStamp(it) } ?: run { binding.viewStampShimmer.visibility = View.GONE }
                    }
                    return@launch
                }
            }
            performReverseGeocoding(center.latitude, center.longitude)
        }
    }

    /** Lift the pin while the user drags (so the spot under it stays visible), drop it on release. */
    private fun liftCenterPin(lift: Boolean) {
        val target = if (lift) -12f * resources.displayMetrics.density else 0f
        if (binding.ivCenterPin.translationY == target) return
        binding.ivCenterPin.animate().translationY(target).setDuration(150L).start()
    }

    private fun performReverseGeocoding(lat: Double, lon: Double) {
        lastGeocodedPoint = GeoPoint(lat, lon)
        val requestId = ++geocodeRequestId
        showLocatingStamp()
        awaitingGeocode = true

        lifecycleScope.launch {
            // Android's Geocoder is a blocking call without a timeout that can hang on a flaky
            // connection - which left the stamp shimmering on "Locating address..." forever.
            // Give up after a few seconds and stamp the coordinates (always known) instead.
            // (async on lifecycleScope, not a child: a blocked lookup can't be interrupted, so it is
            // abandoned instead of being waited for.)
            val lookup = lifecycleScope.async { OsmGeocodingHelper.reverseGeocode(lat, lon, this@PickLocationMapActivity) }
            val result = try {
                withTimeoutOrNull(GEOCODE_TIMEOUT_MS) { lookup.await() }
            } catch (e: Exception) {
                null
            } ?: OsmReverseGeoResult(
                primaryTitle = "Selected Location",
                fullAddress = "${OsmGeocodingHelper.formatLatitude(lat)}, ${OsmGeocodingHelper.formatLongitude(lon)}",
                country = "",
                latitude = lat,
                longitude = lon,
                latitudeFormatted = OsmGeocodingHelper.formatLatitude(lat),
                longitudeFormatted = OsmGeocodingHelper.formatLongitude(lon)
            )
            // A slower answer for a spot the user already dragged away from must not overwrite
            // the address of what is under the pin now.
            if (requestId != geocodeRequestId) return@launch
            awaitingGeocode = false
            currentReverseGeo = result
            showStamp(result)
        }
    }

    /** Stamp card while the address under the pin is being resolved. */
    private fun showLocatingStamp() {
        binding.tvStampTitle.text = "Locating address..."
        binding.tvStampAddress.text = ""
        binding.tvStampCoordsAlt.text = ""
        binding.tvStampDateTime.text = ""
        binding.viewStampShimmer.visibility = View.VISIBLE
    }

    private fun showStamp(result: OsmReverseGeoResult) {
        binding.tvStampTitle.text = result.primaryTitle
        binding.tvStampAddress.text = result.fullAddress
        // (No altitude: it was a fixed "Alt: 540m" for every picked place, e.g. Murree at ~2,000 m.)
        binding.tvStampCoordsAlt.text = "${result.latitudeFormatted}, ${result.longitudeFormatted}"
        binding.tvStampDateTime.text = getFormattedCurrentTime()
        binding.viewStampShimmer.visibility = View.GONE
    }

    private fun loadInitialLocation() {
        binding.viewStampShimmer.visibility = View.VISIBLE

        val initialLat = intent.getDoubleExtra(EXTRA_INITIAL_LAT, Double.NaN)
        val initialLon = intent.getDoubleExtra(EXTRA_INITIAL_LON, Double.NaN)

        val hasInitialPoint = !initialLat.isNaN() && !initialLon.isNaN() && (initialLat != 0.0 || initialLon != 0.0)

        // Center the map IMMEDIATELY so tiles start downloading for the right area right away.
        // Previously the map sat at (0,0) until getRealLiveLocationData() returned, which waits
        // up to 8s for a fresh GPS fix plus a network reverse-geocode - that was the slow load.
        val lastKnown = locationHelper.getLastKnownLocation()
        if (hasInitialPoint) {
            val startPoint = GeoPoint(initialLat, initialLon)
            binding.mapView.controller.setZoom(16.0)
            binding.mapView.controller.setCenter(startPoint)
            performReverseGeocoding(initialLat, initialLon)
        } else if (lastKnown != null) {
            binding.mapView.controller.setZoom(16.0)
            binding.mapView.controller.setCenter(GeoPoint(lastKnown.latitude, lastKnown.longitude))
            performReverseGeocoding(lastKnown.latitude, lastKnown.longitude)
        }
        lastKnown?.let { updateUserLocationMarker(GeoPoint(it.latitude, it.longitude)) }

        // Refine with a fresh live fix in the background without blocking the map.
        lifecycleScope.launch {
            val userLocation = locationHelper.getRealLiveLocationData()
            val liveLat = userLocation.rawLatitude
            val liveLon = userLocation.rawLongitude
            val uLat = liveLat ?: lastKnown?.latitude
            val uLon = liveLon ?: lastKnown?.longitude
            if (uLat == null || uLon == null) {
                // No location available at all - leave the map as is rather than faking a place.
                if (!hasInitialPoint) {
                    binding.tvStampTitle.text = "Location unavailable"
                    binding.tvStampAddress.text = "Search for a place or tap on the map"
                    binding.tvStampCoordsAlt.text = ""
                    binding.viewStampShimmer.visibility = View.GONE
                }
                return@launch
            }
            val userPoint = GeoPoint(uLat, uLon)
            updateUserLocationMarker(userPoint)

            if (hasInitialPoint || userHasInteractedWithMap) return@launch

            if (lastKnown == null) {
                binding.mapView.controller.setZoom(16.0)
                binding.mapView.controller.setCenter(userPoint)
                performReverseGeocoding(uLat, uLon)
            } else if (liveLat != null && liveLon != null) {
                val moved = FloatArray(1)
                android.location.Location.distanceBetween(lastKnown.latitude, lastKnown.longitude, liveLat, liveLon, moved)
                if (moved[0] > 50f) {
                    com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, userPoint)
                    performReverseGeocoding(uLat, uLon)
                }
            }
        }
    }

    private fun setupSearch() {
        searchAdapter = MapSearchResultAdapter { selectedItem ->
            onSearchResultSelected(selectedItem)
        }

        binding.rvSearchResults.apply {
            layoutManager = LinearLayoutManager(this@PickLocationMapActivity)
            adapter = searchAdapter
        }

        binding.btnClearSearch.setOnClickListener {
            binding.etMapSearch.setText("")
            binding.cardSearchResults.visibility = View.GONE
            binding.btnClearSearch.visibility = View.GONE
            hideKeyboard()
        }

        val triggerSearchAndAutoSelect = {
            val query = binding.etMapSearch.text.toString().trim()
            hideKeyboard()
            binding.etMapSearch.clearFocus()
            binding.cardSearchResults.visibility = View.GONE
            if (query.isNotEmpty()) {
                val currentResults = searchAdapter.currentList
                val matchingResult = currentResults.firstOrNull {
                    it.placeName.contains(query, ignoreCase = true) || it.fullAddress.contains(query, ignoreCase = true)
                }
                if (matchingResult != null) {
                    onSearchResultSelected(matchingResult)
                } else {
                    searchJob?.cancel()
                    binding.pbSearchLoading.visibility = View.VISIBLE
                    searchJob = lifecycleScope.launch {
                        val results = OsmGeocodingHelper.searchLocations(query, this@PickLocationMapActivity)
                        binding.pbSearchLoading.visibility = View.GONE
                        if (results.isNotEmpty()) {
                            searchAdapter.submitList(results)
                            onSearchResultSelected(results.first())
                        } else {
                            binding.cardSearchResults.visibility = View.GONE
                            Toast.makeText(this@PickLocationMapActivity, "Location not found for \"$query\"", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        binding.ivSearchIcon.setOnClickListener {
            triggerSearchAndAutoSelect()
        }

        binding.etMapSearch.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || isEnter) {
                triggerSearchAndAutoSelect()
                true
            } else {
                false
            }
        }

        binding.etMapSearch.doAfterTextChanged { text ->
            if (isSearchingProgrammatically) return@doAfterTextChanged

            val query = text?.toString()?.trim().orEmpty()
            if (query.isEmpty()) {
                binding.btnClearSearch.visibility = View.GONE
                binding.cardSearchResults.visibility = View.GONE
                binding.pbSearchLoading.visibility = View.GONE
                searchJob?.cancel()
            } else {
                binding.btnClearSearch.visibility = View.VISIBLE
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(350)
                    performSearch(query)
                }
            }
        }
    }

    private fun performSearch(query: String) {
        binding.pbSearchLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val results = OsmGeocodingHelper.searchLocations(query, this@PickLocationMapActivity)
            binding.pbSearchLoading.visibility = View.GONE
            if (results.isNotEmpty()) {
                searchAdapter.submitList(results)
                binding.cardSearchResults.visibility = View.VISIBLE
            } else {
                binding.cardSearchResults.visibility = View.GONE
            }
        }
    }

    private fun onSearchResultSelected(item: OsmSearchResult) {
        searchJob?.cancel()
        reverseGeoJob?.cancel()
        userHasInteractedWithMap = true

        isSearchingProgrammatically = true
        binding.etMapSearch.setText(item.placeName)
        isSearchingProgrammatically = false

        binding.cardSearchResults.visibility = View.GONE
        binding.etMapSearch.clearFocus()
        hideKeyboard()

        // Fly the searched place under the center pin. Its name/address come from the search
        // result: mark this point as already resolved (and void any geocode still in flight) so
        // the settle-geocode in onMapMoved() doesn't replace them.
        val targetPoint = GeoPoint(item.latitude, item.longitude)
        lastGeocodedPoint = targetPoint
        geocodeRequestId++
        awaitingGeocode = false
        com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, targetPoint, 17.0, 500L)

        val result = OsmReverseGeoResult(
            primaryTitle = item.placeName,
            fullAddress = item.fullAddress,
            country = item.country,
            latitude = item.latitude,
            longitude = item.longitude,
            latitudeFormatted = item.latitudeFormatted,
            longitudeFormatted = item.longitudeFormatted
        )
        currentReverseGeo = result
        showStamp(result)
    }

    private fun setupMapTypeSelector() {
        fun selectType(type: SelectedMapType) {
            currentMapType = type

            fun updateTileUI(border: FrameLayout, label: TextView, isSelected: Boolean) {
                if (isSelected) {
                    border.setBackgroundResource(R.drawable.bg_map_type_thumb_selected)
                    label.setTextColor(Color.parseColor("#3B82F6"))
                } else {
                    border.setBackgroundResource(R.drawable.bg_map_type_thumb_unselected)
                    label.setTextColor(Color.parseColor("#475569"))
                }
            }

            updateTileUI(binding.flSatelliteBorder, binding.tvSatelliteLabel, type == SelectedMapType.SATELLITE)
            updateTileUI(binding.flHybridBorder, binding.tvHybridLabel, type == SelectedMapType.HYBRID)
            updateTileUI(binding.flTerrainBorder, binding.tvTerrainLabel, type == SelectedMapType.TERRAIN)
            updateTileUI(binding.flStreetBorder, binding.tvStreetLabel, type == SelectedMapType.STREET)

            when (type) {
                SelectedMapType.SATELLITE -> {
                    // Pure imagery. (It used to show the Hybrid layer here too, so the Satellite and
                    // Hybrid buttons looked identical on this screen.)
                    com.autonoumus.ai.core.util.FastTileSources.setBaseMap(binding.mapView, com.autonoumus.ai.core.util.FastTileSources.SATELLITE)
                    binding.ivStampThumbnail.setImageResource(R.drawable.img_map_satellite)
                }
                SelectedMapType.HYBRID -> {
                    com.autonoumus.ai.core.util.FastTileSources.setBaseMap(binding.mapView, com.autonoumus.ai.core.util.FastTileSources.HYBRID)
                    binding.ivStampThumbnail.setImageResource(R.drawable.img_map_hybrid)
                }
                SelectedMapType.TERRAIN -> {
                    com.autonoumus.ai.core.util.FastTileSources.setBaseMap(binding.mapView, com.autonoumus.ai.core.util.FastTileSources.TERRAIN)
                    binding.ivStampThumbnail.setImageResource(R.drawable.img_map_terrain)
                }
                SelectedMapType.STREET -> {
                    com.autonoumus.ai.core.util.FastTileSources.setBaseMap(binding.mapView, com.autonoumus.ai.core.util.FastTileSources.STREET)
                    binding.ivStampThumbnail.setImageResource(R.drawable.img_map_normal)
                }
            }
        }

        binding.cardSatellite.setOnClickListener { selectType(SelectedMapType.SATELLITE) }
        binding.cardHybrid.setOnClickListener { selectType(SelectedMapType.HYBRID) }
        binding.cardTerrain.setOnClickListener { selectType(SelectedMapType.TERRAIN) }
        binding.cardStreet.setOnClickListener { selectType(SelectedMapType.STREET) }

        // Layers toggle button
        binding.btnLayers.setOnClickListener {
            val isVisible = binding.panelMapTypes.visibility == View.VISIBLE
            binding.panelMapTypes.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        if (isSatelliteMode) {
            selectType(SelectedMapType.SATELLITE)
            binding.panelMapTypes.visibility = View.VISIBLE
        } else {
            selectType(SelectedMapType.STREET)
            binding.panelMapTypes.visibility = View.GONE
        }
    }

    private fun setupActionButtons() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnShare.setOnClickListener {
            captureStampMap { uri ->
                val location = currentReverseGeo
                val shareText = "📍 ${location?.primaryTitle ?: "Satellite Map"}\n${location?.fullAddress ?: ""}\nCoordinates: ${location?.latitudeFormatted ?: ""}, ${location?.longitudeFormatted ?: ""}"
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, shareText)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Stamped Map"))
            }
        }

        binding.btnMyLocation.setOnClickListener {
            fun centerOn(lat: Double, lon: Double) {
                val targetPoint = GeoPoint(lat, lon)
                updateUserLocationMarker(targetPoint)
                // One combined move+zoom animation; animateTo() followed by setZoom() cancelled the
                // pan animation halfway and jumped.
                com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, targetPoint, 16.5, 400L)
                performReverseGeocoding(lat, lon)
            }

            // Jump to the cached fix instantly, then refine once the live fix arrives.
            val cached = locationHelper.getLastKnownLocation()
            if (cached != null) {
                centerOn(cached.latitude, cached.longitude)
                Toast.makeText(this@PickLocationMapActivity, "Centered on My Location", Toast.LENGTH_SHORT).show()
            }
            lifecycleScope.launch {
                val userLocation = locationHelper.getRealLiveLocationData()
                val lat = userLocation.rawLatitude
                val lon = userLocation.rawLongitude
                if (lat == null || lon == null) {
                    if (cached == null) {
                        Toast.makeText(this@PickLocationMapActivity, "Unable to get your location", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val moved = FloatArray(1)
                if (cached != null) {
                    android.location.Location.distanceBetween(cached.latitude, cached.longitude, lat, lon, moved)
                }
                if (cached == null || moved[0] > 50f) {
                    centerOn(lat, lon)
                    if (cached == null) {
                        Toast.makeText(this@PickLocationMapActivity, "Centered on My Location", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    updateUserLocationMarker(GeoPoint(lat, lon))
                }
            }
        }

        binding.btnZoomIn.setOnClickListener {
            binding.mapView.controller.zoomIn()
        }

        binding.btnZoomOut.setOnClickListener {
            binding.mapView.controller.zoomOut()
        }
    }

    private fun setupCaptureStampMap() {
        val onCaptureClick = {
            captureStampMap { uri ->
                val location = currentReverseGeo
                val intent = MediaViewerActivity.newIntent(
                    context = this,
                    uri = uri,
                    isVideo = false,
                    locationTitle = location?.primaryTitle ?: "Satellite Map Capture",
                    locationCoordinates = location?.let { "${it.latitudeFormatted}, ${it.longitudeFormatted}" } ?: "",
                    captureTime = getFormattedCurrentTime(),
                    rawLat = location?.latitude,
                    rawLon = location?.longitude
                )
                startActivity(intent)
            }
        }

        binding.btnCaptureStampMap.setOnClickListener { onCaptureClick() }
        binding.cardCaptureStampMap.setOnClickListener { onCaptureClick() }

        if (intent.getBooleanExtra(EXTRA_HIDE_CAPTURE_BUTTON, false)) {
            binding.cardCaptureStampMap.visibility = View.GONE
        }
    }

    private fun captureStampMap(onSaved: (Uri) -> Unit) {
        val width = binding.mapRoot.width
        val height = binding.mapRoot.height
        if (width <= 0 || height <= 0) return

        // Temporarily hide floating controls during snapshot
        binding.topBarContainer.visibility = View.INVISIBLE
        binding.rightControlsContainer.visibility = View.INVISIBLE

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        binding.mapRoot.draw(canvas)

        // Restore controls visibility
        binding.topBarContainer.visibility = View.VISIBLE
        binding.rightControlsContainer.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val filename = "SATELLITE_STAMP_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/GSM_Camera")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PickLocationMapActivity, "Satellite Stamp Map saved to Gallery!", Toast.LENGTH_SHORT).show()
                    onSaved(uri)
                }
            }
        }
    }

    private fun setupStampCardClick() {
        val shouldHideArrow = isSatelliteMode || intent.getBooleanExtra(EXTRA_HIDE_SELECT_ARROW, false)
        if (shouldHideArrow) {
            binding.btnSelectLocationNext.visibility = View.GONE
            binding.bottomStampCard.isClickable = false
        } else {
            binding.btnSelectLocationNext.visibility = View.VISIBLE
            val onSelectLocation = {
                confirmAndReturnLocation()
            }
            binding.bottomStampCard.setOnClickListener { onSelectLocation() }
            binding.btnSelectLocationNext.setOnClickListener { onSelectLocation() }
        }
    }

    private fun confirmAndReturnLocation() {
        val center = binding.mapView.mapCenter
        val lat = center.latitude
        val lon = center.longitude
        val latFmt = OsmGeocodingHelper.formatLatitude(lat)
        val lonFmt = OsmGeocodingHelper.formatLongitude(lon)

        val location = currentReverseGeo
        val placeTitle = location?.primaryTitle?.takeIf { it.isNotBlank() && it != "Locating address..." }
            ?: binding.tvStampTitle.text?.toString()?.takeIf { it.isNotBlank() && it != "Locating address..." }
            ?: "Selected Location"

        val fullAddress = location?.fullAddress?.takeIf { it.isNotBlank() }
            ?: binding.tvStampAddress.text?.toString()?.takeIf { it.isNotBlank() }
            ?: "$placeTitle, $latFmt, $lonFmt"

        val country = location?.country?.trim().orEmpty()
        val countryResolved = if (country.isNotBlank()) {
            if (WorldLocationProvider.isCountryValid(country)) WorldLocationProvider.normalizeCountry(country) else country
        } else {
            val normalized = WorldLocationProvider.normalizeCountry(placeTitle)
            if (WorldLocationProvider.isCountryValid(normalized)) normalized else placeTitle
        }

        val resultIntent = Intent().apply {
            putExtra(EXTRA_COUNTRY, countryResolved)
            putExtra(EXTRA_ADDRESS, fullAddress)
            putExtra(EXTRA_LAT_FORMATTED, location?.latitudeFormatted ?: latFmt)
            putExtra(EXTRA_LON_FORMATTED, location?.longitudeFormatted ?: lonFmt)
            putExtra(EXTRA_RAW_LAT, location?.latitude ?: lat)
            putExtra(EXTRA_RAW_LON, location?.longitude ?: lon)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        Toast.makeText(this, "Location Selected: $placeTitle", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun getFormattedCurrentTime(): String {
        val sdf = SimpleDateFormat("EEEE, MMM dd, yyyy • hh:mm a z", Locale.US)
        return sdf.format(Date())
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etMapSearch.windowToken, 0)
        currentFocus?.let { view ->
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    override fun onResume() {
        super.onResume()
        try {
            binding.mapView.onResume()
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onPause() {
        try {
            binding.mapView.onPause()
        } catch (e: Exception) {
            // Ignore
        }
        super.onPause()
    }

    override fun onDestroy() {
        try {
            binding.mapView.onDetach()
        } catch (e: Exception) {
            // Ignore
        }
        super.onDestroy()
    }
}
