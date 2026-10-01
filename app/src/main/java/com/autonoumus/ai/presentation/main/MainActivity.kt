package com.autonoumus.ai.presentation.main

import android.Manifest
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.core.util.CityLandmarkProvider
import com.autonoumus.ai.databinding.ActivityMainBinding
import com.autonoumus.ai.domain.model.CaptureMode
import com.autonoumus.ai.domain.model.QuickToolItem
import com.autonoumus.ai.domain.model.RecentCaptureItem
import com.autonoumus.ai.domain.model.StreetViewCameraItem
import com.autonoumus.ai.domain.model.UserLocationData
import android.net.Uri
import com.autonoumus.ai.presentation.altimeter.AltimeterElevationActivity
import com.autonoumus.ai.presentation.camera.CameraActivity
import com.autonoumus.ai.presentation.camera.ImportImageStampActivity
import com.autonoumus.ai.presentation.common.adapter.CaptureGridAdapter
import com.autonoumus.ai.presentation.compass.CompassAltimeterActivity
import com.autonoumus.ai.presentation.gallery.GalleryActivity
import com.autonoumus.ai.presentation.gallery.MediaViewerActivity
import com.autonoumus.ai.presentation.home.HomeUiState
import com.autonoumus.ai.presentation.home.HomeViewModel
import com.autonoumus.ai.presentation.home.SelectSourceBottomSheetDialog
import com.autonoumus.ai.presentation.home.adapter.QuickToolsAdapter
import com.autonoumus.ai.presentation.map.PickLocationMapActivity
import com.autonoumus.ai.presentation.settings.SettingsActivity
import com.autonoumus.ai.presentation.streetcamera.StreetCameraActivity
import com.autonoumus.ai.presentation.streetcamera.StreetCameraDetailActivity
import com.autonoumus.ai.presentation.templates.TemplatesActivity
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.presentation.home.RateUsBottomSheetDialog
import com.autonoumus.ai.presentation.common.dialog.PermissionRequestBottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : BaseActivity<ActivityMainBinding>() {

    @Inject
    lateinit var preferenceManager: PreferenceManager

    private val viewModel: HomeViewModel by viewModels()

    private lateinit var quickToolsAdapter: QuickToolsAdapter
    private lateinit var recentCapturesAdapter: CaptureGridAdapter
    private lateinit var streetViewCameraAdapter: StreetViewCameraAdapter

    private var lastRenderedCaptures: List<RecentCaptureItem>? = null
    private var lastRenderedQuickTools: List<QuickToolItem>? = null
    private var lastRenderedStreetCameras: List<StreetViewCameraItem>? = null

    private var lastLoadedLandmarkKey: String? = null

    private val pickMediaFromGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            val isVideo = isUriVideo(selectedUri)
            val intent = ImportImageStampActivity.newIntent(this, selectedUri, isVideo = isVideo)
            startActivity(intent)
        }
    }

    private fun isUriVideo(uri: Uri): Boolean {
        val mimeType = contentResolver.getType(uri) ?: ""
        if (mimeType.startsWith("video/")) return true
        val uriString = uri.toString()
        if (uriString.contains("/video/", ignoreCase = true) || uriString.contains("video%3A", ignoreCase = true)) return true
        return uriString.endsWith(".mp4", ignoreCase = true) ||
                uriString.endsWith(".mkv", ignoreCase = true) ||
                uriString.endsWith(".mov", ignoreCase = true) ||
                uriString.endsWith(".3gp", ignoreCase = true) ||
                uriString.endsWith(".webm", ignoreCase = true) ||
                uriString.endsWith(".avi", ignoreCase = true)
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted || coarseGranted) {
            viewModel.onLocationPermissionGranted()
            Toast.makeText(this, "GPS Location Activated", Toast.LENGTH_SHORT).show()
        } else {
            val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            com.autonoumus.ai.core.util.PermissionSettingsDialog.handleDenied(
                context = this,
                shouldShowRationale = shouldShowRationale,
                deniedMessage = "Location permission is required for GPS Stamp features"
            )
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityMainBinding {
        return ActivityMainBinding.inflate(inflater)
    }

    override fun initViews() {
        setupInitialLandmarkBanner()
        setupRecyclerViews()
        setupListeners()
        setupBackPressHandler()
    }

    private fun setupInitialLandmarkBanner() {
        val cachedName = preferenceManager.lastLandmarkName
        val cachedUrl = preferenceManager.lastLandmarkImageUrl
        if (!cachedUrl.isNullOrBlank()) {
            binding.tvGreetingSubtitle.text = cachedName ?: "Famous Landmark"
            binding.ivCurrentLocationFamousPlace.load(cachedUrl) {
                setHeader("User-Agent", com.autonoumus.ai.core.util.AppLinks.userAgent)
                setHeader("Accept", "image/*")
                crossfade(300)
                placeholder(R.drawable.img_home_banner_active)
                error(R.drawable.img_home_banner_active)
                fallback(R.drawable.img_home_banner_active)
                listener(
                    onSuccess = { _, result ->
                        (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let {
                            CityLandmarkProvider.lastResolvedLandmarkBitmap = it
                        }
                    }
                )
            }
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!preferenceManager.hasRatedApp) {
                    showRateUsBottomSheet()
                } else {
                    showExitConfirmationDialog()
                }
            }
        })
    }

    private fun showRateUsBottomSheet() {
        val existing = supportFragmentManager.findFragmentByTag(RateUsBottomSheetDialog.TAG)
        if (existing != null && existing.isAdded) return
        val sheet = RateUsBottomSheetDialog.newInstance(isFromSettings = false) {
            finishAffinity()
        }
        sheet.show(supportFragmentManager, RateUsBottomSheetDialog.TAG)
    }

    private fun showExitConfirmationDialog() {
        ExitAppDialog.show(supportFragmentManager) {
            finishAffinity()
        }
    }

    private fun showLocationPermissionSheet() {
        PermissionRequestBottomSheetDialog.showLocationRationale(
            fragmentManager = supportFragmentManager,
            onAllow = {
                preferenceManager.hasRequestedLocationPermission = true
                requestLocationPermission()
            }
        )
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

    private var hasAutoRequestedLocationPermission = false

    override fun onResume() {
        super.onResume()
        viewModel.refreshRecentCaptures()
        viewModel.checkLocationPermissionAndUpdate()
        // Pre-download map tiles around the user (background, throttled, skips cached tiles) so
        // any map screen opened from here draws instantly from the disk cache.
        com.autonoumus.ai.core.util.MapTilePrefetcher.warmUp(this)

        if (!hasAutoRequestedLocationPermission && !viewModel.uiState.value.hasLocationPermission && !preferenceManager.hasRequestedLocationPermission) {
            hasAutoRequestedLocationPermission = true
            showLocationPermissionSheet()
        }
    }

    private fun setupRecyclerViews() {
        quickToolsAdapter = QuickToolsAdapter { item ->
            handleQuickToolClick(item)
        }
        binding.rvQuickTools.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 5)
            adapter = quickToolsAdapter
            isNestedScrollingEnabled = false
        }

        streetViewCameraAdapter = StreetViewCameraAdapter { item ->
            handleStreetViewClick(item)
        }
        binding.rvStreetViewCameras.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = streetViewCameraAdapter
        }

        recentCapturesAdapter = CaptureGridAdapter { item ->
            handleRecentCaptureClick(item)
        }
        binding.rvRecentCaptures.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = recentCapturesAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupListeners() {
        binding.btnEnableGps.setOnClickListener {
            handleEnableGpsClick()
        }
        binding.cardPermissionRequest.setOnClickListener {
            handleEnableGpsClick()
        }

        binding.cardTakePhoto.setOnClickListener {
            showSelectSourceBottomSheet(CaptureMode.PHOTO)
        }

        binding.cardRecordVideo.setOnClickListener {
            showSelectSourceBottomSheet(CaptureMode.VIDEO)
        }

        binding.cardGpsActive.setOnClickListener {
            openLocationOnMap()
        }

        binding.innerLocationCard.setOnClickListener {
            openLocationOnMap()
        }

        binding.ivFocusIcon.setOnClickListener {
            openLocationOnMap()
        }

        binding.ivMapThumb.setOnClickListener {
            openLocationOnMap()
        }

        binding.btnViewAllTools.setOnClickListener {
            startActivity(com.autonoumus.ai.presentation.tools.GpsQuickToolsActivity.newIntent(this))
        }

        binding.btnViewAllStreetView.setOnClickListener {
            startActivity(StreetCameraActivity.newIntent(this))
        }

        binding.btnSeeAllCaptures.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        binding.btnEmptyTakePhoto.setOnClickListener {
            showSelectSourceBottomSheet(CaptureMode.PHOTO)
        }

        setupBottomNavigation()
    }

    private fun setupBottomNavigation() {
        binding.navHome.setOnClickListener {
            // Already on Home
        }

        binding.navRouteFinder.setOnClickListener {
            openRouteFinder()
        }

        binding.navCameraCenter.setOnClickListener {
            showSelectSourceBottomSheet(CaptureMode.PHOTO)
        }

        binding.navTemplates.setOnClickListener {
            startActivity(Intent(this, TemplatesActivity::class.java))
        }

        binding.navSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun showSelectSourceBottomSheet(preferredMode: CaptureMode = CaptureMode.PHOTO) {
        val bottomSheet = SelectSourceBottomSheetDialog.newInstance(
            onTakePhotoVideo = {
                openCamera(preferredMode)
            },
            onChooseGallery = {
                pickMediaFromGalleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            }
        )
        bottomSheet.show(supportFragmentManager, SelectSourceBottomSheetDialog.TAG)
    }

    private fun openCamera(mode: CaptureMode) {
        val intent = Intent(this, CameraActivity::class.java).apply {
            putExtra(CameraActivity.EXTRA_INITIAL_MODE, mode.name)
        }
        startActivity(intent)
    }

    private fun renderUiState(state: HomeUiState) {
        Log.d("FamousPlaceDebug", "🏠 MainActivity: renderUiState - hasPermission=${state.hasLocationPermission}, hasLocationData=${state.locationData != null}, city='${state.locationData?.cityName}'")

        if (state.hasLocationPermission) {
            binding.cardPermissionRequest.visibility = View.GONE
            binding.cardGpsActive.visibility = View.VISIBLE

            // Permission just granted but no fix has come back yet - shimmer instead of
            // showing nothing (cardGpsActive's fields would just be blank/stale otherwise).
            binding.viewHomeLocationShimmer.visibility = if (state.locationData == null) View.VISIBLE else View.GONE

            state.locationData?.let { loc ->
                binding.tvCityName.text = loc.cityName
                binding.tvStreetAddress.text = loc.streetAddress
                binding.tvGpsCoordinates.text = loc.coordinatesFormatted
                binding.tvTimestamp.text = loc.dateFormatted
                binding.tvTimestampTime.text = loc.timeFormatted

                // Set GPS Active badge background: green_bg if strong, blur_bg otherwise
                if (loc.isStrongSignal) {
                    binding.badgeGpsActive.setBackgroundResource(R.drawable.green_bg)
                } else {
                    binding.badgeGpsActive.setBackgroundResource(R.drawable.blur_bg)
                }

                // Load famous place / landmark image for current location
                val latRounded = loc.rawLatitude?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "0"
                val lonRounded = loc.rawLongitude?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "0"
                val landmarkKey = "${loc.cityName}_${latRounded}_${lonRounded}"
                Log.d("FamousPlaceDebug", "📍 Location key: '$landmarkKey', previousKey: '$lastLoadedLandmarkKey'")
                if (landmarkKey != lastLoadedLandmarkKey) {
                    lastLoadedLandmarkKey = landmarkKey
                    loadCityFamousPlaceImage(loc)
                }
            }
        } else {
            binding.cardPermissionRequest.visibility = View.VISIBLE
            binding.cardGpsActive.visibility = View.GONE
            binding.viewHomeLocationShimmer.visibility = View.GONE
        }

        // Default map thumbnail to Normal map
        binding.ivMapThumb.setImageResource(R.drawable.img_map_normal)

        if (state.recentCaptures != lastRenderedCaptures) {
            lastRenderedCaptures = state.recentCaptures
            val hasCaptures = state.recentCaptures.isNotEmpty()
            binding.rvRecentCaptures.visibility = if (hasCaptures) View.VISIBLE else View.GONE
            binding.layoutEmptyRecentCaptures.visibility = if (hasCaptures) View.GONE else View.VISIBLE
            binding.btnSeeAllCaptures.visibility = if (hasCaptures) View.VISIBLE else View.GONE
            recentCapturesAdapter.submitList(state.recentCaptures.take(4))
        }

        // Shimmer while loading; real webcams when found; hide the whole section when Windy has
        // none (it used to fall back to stock photos presented as cameras).
        val hasCameras = state.streetViewCameras.isNotEmpty()
        val showSection = state.isStreetViewLoading || hasCameras
        binding.layoutStreetViewHeader.visibility = if (showSection) View.VISIBLE else View.GONE
        binding.rvStreetViewCameras.visibility = if (!state.isStreetViewLoading && hasCameras) View.VISIBLE else View.GONE
        binding.layoutStreetViewShimmer.visibility = if (state.isStreetViewLoading) View.VISIBLE else View.GONE

        if (state.quickTools != lastRenderedQuickTools) {
            lastRenderedQuickTools = state.quickTools
            quickToolsAdapter.submitList(state.quickTools)
        }

        if (state.streetViewCameras != lastRenderedStreetCameras) {
            lastRenderedStreetCameras = state.streetViewCameras
            streetViewCameraAdapter.submitList(state.streetViewCameras)
        }
    }

    private fun loadCityFamousPlaceImage(loc: UserLocationData) {
        lifecycleScope.launch {
            Log.d("FamousPlaceDebug", "🚀 [Fetch Request] Calling CityLandmarkProvider for city='${loc.cityName}', lat=${loc.rawLatitude}, lon=${loc.rawLongitude}")
            val landmarkResult = CityLandmarkProvider.getNearbyLandmark(
                lat = loc.rawLatitude,
                lon = loc.rawLongitude,
                cityName = loc.cityName,
                fullAddress = loc.streetAddress
            )

            Log.d("FamousPlaceDebug", "🖼️ [Fetch Result] Received landmark: '${landmarkResult.placeName}' (${landmarkResult.source}) -> '${landmarkResult.imageUrl}'")

            if (landmarkResult.imageUrl.isNotEmpty()) {
                preferenceManager.lastLandmarkName = landmarkResult.placeName
                preferenceManager.lastLandmarkImageUrl = landmarkResult.imageUrl

                binding.tvGreetingSubtitle.text = landmarkResult.placeName.ifBlank { loc.cityName.ifBlank { "Famous Landmark" } }

                Log.d("FamousPlaceDebug", "🎨 Loading image with Coil into ivCurrentLocationFamousPlace...")
                binding.ivCurrentLocationFamousPlace.load(landmarkResult.imageUrl) {
                    setHeader("User-Agent", com.autonoumus.ai.core.util.AppLinks.userAgent)
                    setHeader("Accept", "image/*")
                    crossfade(300)
                    error(R.drawable.img_home_banner_active)
                    fallback(R.drawable.img_home_banner_active)
                    listener(
                        onStart = { Log.d("FamousPlaceDebug", "⏳ Coil started loading image: ${landmarkResult.imageUrl}") },
                        onSuccess = { _, result ->
                            Log.d("FamousPlaceDebug", "🎉 Coil image load SUCCESS! Source: ${result.dataSource}")
                            (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let {
                                CityLandmarkProvider.lastResolvedLandmarkBitmap = it
                            }
                        },
                        onError = { _, result -> Log.e("FamousPlaceDebug", "❌ Coil image load FAILED: ${result.throwable.message}", result.throwable) }
                    )
                }
            } else {
                Log.d("FamousPlaceDebug", "⚠️ landmarkUrl is null/empty. Setting default drawable placeholder.")
                binding.ivCurrentLocationFamousPlace.setImageResource(R.drawable.img_home_banner_active)
                binding.tvGreetingSubtitle.text = loc.cityName.ifBlank { "Famous Landmark" }
            }
        }
    }

    private fun handleStreetViewClick(item: StreetViewCameraItem) {
        startActivity(StreetCameraDetailActivity.newIntent(this, item.id, item.title, item.imageUrl))
    }

    private fun handleEnableGpsClick() {
        if (viewModel.uiState.value.hasLocationPermission) {
            viewModel.onLocationPermissionGranted()
            return
        }
        val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        if (preferenceManager.hasRequestedLocationPermission && !shouldShowRationale) {
            com.autonoumus.ai.core.util.PermissionSettingsDialog.show(
                context = this,
                message = "Location permission is disabled. Please enable it in Settings to turn on real-time GPS geotagging."
            )
        } else {
            showLocationPermissionSheet()
        }
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun handleQuickToolClick(item: QuickToolItem) {
        when (item.title.lowercase()) {
            "speedometer" -> {
                startActivity(Intent(this, com.autonoumus.ai.presentation.speedometer.SpeedometerActivity::class.java))
            }
            "compass" -> {
                startActivity(Intent(this, CompassAltimeterActivity::class.java))
            }
            "altimeter" -> {
                startActivity(Intent(this, AltimeterElevationActivity::class.java))
            }
            "area measure", "area measurement", "area" -> {
                startActivity(Intent(this, com.autonoumus.ai.presentation.area.AreaMeasurementActivity::class.java))
            }
            "satellite map" -> {
                val locationData = viewModel.uiState.value.locationData
                val intent = PickLocationMapActivity.createSatelliteIntent(this).apply {
                    locationData?.rawLatitude?.let { lat ->
                        putExtra(PickLocationMapActivity.EXTRA_INITIAL_LAT, lat)
                    }
                    locationData?.rawLongitude?.let { lon ->
                        putExtra(PickLocationMapActivity.EXTRA_INITIAL_LON, lon)
                    }
                }
                startActivity(intent)
            }
            "trip history", "tag history" -> {
                startActivity(Intent(this, com.autonoumus.ai.presentation.speedometer.TripHistoryActivity::class.java))
            }
            "nearby place", "nearby places", "nearby" -> {
                val locationData = viewModel.uiState.value.locationData
                val intent = com.autonoumus.ai.presentation.nearby.NearbyPlacesActivity.newIntent(
                    context = this,
                    latitude = locationData?.rawLatitude ?: 0.0,
                    longitude = locationData?.rawLongitude ?: 0.0,
                    cityName = locationData?.cityName ?: ""
                )
                startActivity(intent)
            }
            "route finder" -> {
                openRouteFinder()
            }
            "image on map", "images on map" -> {
                val locationData = viewModel.uiState.value.locationData
                val intent = com.autonoumus.ai.presentation.map.ImagesOnMapActivity.newIntent(
                    context = this,
                    latitude = locationData?.rawLatitude ?: 0.0,
                    longitude = locationData?.rawLongitude ?: 0.0
                )
                startActivity(intent)
            }
            "live street view" -> {
                startActivity(StreetCameraActivity.newIntent(this))
            }
            else -> {
                Toast.makeText(this, item.title, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openRouteFinder() {
        val locationData = viewModel.uiState.value.locationData
        val intent = com.autonoumus.ai.presentation.routefinder.RouteFinderActivity.newIntent(
            context = this,
            startLat = locationData?.rawLatitude ?: 0.0,
            startLon = locationData?.rawLongitude ?: 0.0,
            startName = locationData?.cityName?.let { "Current Location ($it)" } ?: "Current Location (GPS)"
        )
        startActivity(intent)
    }

    private fun openLocationOnMap() {
        val locationData = viewModel.uiState.value.locationData
        val intent = Intent(this, PickLocationMapActivity::class.java).apply {
            locationData?.rawLatitude?.let { lat ->
                putExtra(PickLocationMapActivity.EXTRA_INITIAL_LAT, lat)
            }
            locationData?.rawLongitude?.let { lon ->
                putExtra(PickLocationMapActivity.EXTRA_INITIAL_LON, lon)
            }
            putExtra(PickLocationMapActivity.EXTRA_HIDE_CAPTURE_BUTTON, true)
            putExtra(PickLocationMapActivity.EXTRA_HIDE_SELECT_ARROW, true)
        }
        startActivity(intent)
    }

    private fun handleRecentCaptureClick(item: RecentCaptureItem) {
        startActivity(MediaViewerActivity.newIntent(this, item))
    }
}
