package com.autonoumus.ai.presentation.map

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import coil.transform.RoundedCornersTransformation
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.core.util.FastTileSources
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.databinding.ActivityImagesOnMapBinding
import com.autonoumus.ai.domain.model.RecentCaptureItem
import com.autonoumus.ai.presentation.gallery.MediaViewerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.InputStream
import javax.inject.Inject

@AndroidEntryPoint
class ImagesOnMapActivity : BaseActivity<ActivityImagesOnMapBinding>() {

    @Inject
    lateinit var locationHelper: LocationHelper

    private val viewModel: ImagesOnMapViewModel by viewModels()

    private val captureMarkers = mutableListOf<Marker>()
    private var haloCircleOverlay: Polygon? = null
    private var hasAutoFittedBounds = false
    private var currentMapType: SelectedMapType = SelectedMapType.STREET
    private var locationJob: kotlinx.coroutines.Job? = null
    private var markerJob: kotlinx.coroutines.Job? = null
    private var renderedMarkerKey: Pair<List<RecentCaptureItem>, String?>? = null
    // Rendered marker bitmaps keyed by "id|selected|cluster" - decoding photo thumbnails is the
    // expensive part, so reuse them across state updates and return visits (onResume reload).
    private val markerDrawableCache = HashMap<String, Drawable>()

    companion object {
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LON = "extra_lon"

        fun newIntent(context: Context, latitude: Double = 0.0, longitude: Double = 0.0): Intent {
            return Intent(context, ImagesOnMapActivity::class.java).apply {
                putExtra(EXTRA_LAT, latitude)
                putExtra(EXTRA_LON, longitude)
            }
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityImagesOnMapBinding {
        return ActivityImagesOnMapBinding.inflate(inflater)
    }

    override fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.imagesOnMapRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            binding.topBarContainer.updatePadding(top = bars.top)
            binding.bottomSectionContainer.updateLayoutParams<RelativeLayout.LayoutParams> {
                bottomMargin = bars.bottom + (4 * density).toInt()
            }
            insets
        }
    }

    override fun initViews() {
        setupMap()
        setupTopBar()
        setupMapTypeSelector()
        setupMapControls()
        setupBottomPreviewCard()
        resolveInitialLocation()
    }

    private fun setupMap() {
        try {
            FastTileSources.optimizeConfiguration(this)

            binding.mapView.apply {
                FastTileSources.setBaseMap(this, FastTileSources.STREET)
                FastTileSources.run { applyHighPerformanceSettings(true) }
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                minZoomLevel = 3.0
                maxZoomLevel = 20.0
            }
        } catch (e: Exception) {
            // Ignore config errors
        }
    }

    private fun setupTopBar() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.etSearchCaptures.doAfterTextChanged { editable ->
            val query = editable?.toString().orEmpty()
            binding.btnClearSearch.visibility = if (query.isNotBlank()) View.VISIBLE else View.GONE
            viewModel.onSearchQueryChanged(query)
        }

        binding.btnClearSearch.setOnClickListener {
            binding.etSearchCaptures.text = null
            hideKeyboard()
        }

        binding.etSearchCaptures.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                true
            } else false
        }
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
                    FastTileSources.setBaseMap(binding.mapView, FastTileSources.SATELLITE)
                }
                SelectedMapType.HYBRID -> {
                    FastTileSources.setBaseMap(binding.mapView, FastTileSources.HYBRID)
                }
                SelectedMapType.TERRAIN -> {
                    FastTileSources.setBaseMap(binding.mapView, FastTileSources.TERRAIN)
                }
                SelectedMapType.STREET -> {
                    FastTileSources.setBaseMap(binding.mapView, FastTileSources.STREET)
                }
            }
        }

        binding.cardSatellite.setOnClickListener { selectType(SelectedMapType.SATELLITE) }
        binding.cardHybrid.setOnClickListener { selectType(SelectedMapType.HYBRID) }
        binding.cardTerrain.setOnClickListener { selectType(SelectedMapType.TERRAIN) }
        binding.cardStreet.setOnClickListener { selectType(SelectedMapType.STREET) }

        // Layers toggle button (Hidden by default, shown on click)
        binding.btnLayerToggle.setOnClickListener {
            val isVisible = binding.panelMapTypes.visibility == View.VISIBLE
            binding.panelMapTypes.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        selectType(SelectedMapType.STREET)
    }

    private fun setupMapControls() {
        binding.btnShareMap.setOnClickListener {
            val selected = viewModel.uiState.value.selectedCapture
            if (selected != null) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, selected.locationName ?: "Geotagged Capture")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "${selected.locationName ?: "Geotagged Capture"}\n${selected.locationFormatted}\nhttps://www.google.com/maps/search/?api=1&query=${selected.latitude ?: 0.0},${selected.longitude ?: 0.0}"
                    )
                }
                startActivity(Intent.createChooser(shareIntent, "Share Location"))
            } else {
                Toast.makeText(this, "Select a capture to share", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnMyLocation.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.userLat != 0.0 && state.userLon != 0.0) {
                com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, GeoPoint(state.userLat, state.userLon), 16.0, 500L)
            } else {
                resolveInitialLocation()
            }
        }

        binding.btnZoomIn.setOnClickListener {
            binding.mapView.controller.zoomIn()
        }

        binding.btnZoomOut.setOnClickListener {
            binding.mapView.controller.zoomOut()
        }
    }

    private fun setupBottomPreviewCard() {
        binding.cardBottomPreview.setOnClickListener {
            openMediaViewer()
        }

        binding.btnOpenViewer.setOnClickListener {
            openMediaViewer()
        }
    }

    private fun openMediaViewer() {
        val selected = viewModel.uiState.value.selectedCapture ?: return
        startActivity(MediaViewerActivity.newIntent(this, selected))
    }

    private fun resolveInitialLocation() {
        val initialLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val initialLon = intent.getDoubleExtra(EXTRA_LON, 0.0)

        if (initialLat != 0.0 && initialLon != 0.0) {
            viewModel.setUserLocation(initialLat, initialLon)
            binding.mapView.controller.setCenter(GeoPoint(initialLat, initialLon))
            binding.mapView.controller.setZoom(15.0)
        } else {
            // Center immediately on the cached fix so tiles load for the right area right away,
            // instead of showing (0,0) until the first live update + reverse geocode arrives.
            val cached = locationHelper.getLastKnownLocation()
            cached?.let {
                viewModel.setUserLocation(it.latitude, it.longitude)
                if (captureMarkers.isEmpty()) {
                    binding.mapView.controller.setZoom(15.0)
                    binding.mapView.controller.setCenter(GeoPoint(it.latitude, it.longitude))
                }
            }
            var centeredOnLiveFix = false
            locationJob?.cancel()
            locationJob = lifecycleScope.launch {
                locationHelper.observeLocationUpdates().collect { data ->
                    val lat = data.rawLatitude
                    val lon = data.rawLongitude
                    if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
                        viewModel.setUserLocation(lat, lon)
                        // Center on the first live fix only, and never after the user has panned or
                        // zoomed: this ran on EVERY update (every few seconds) and kept snapping
                        // the user's zoom back to 15.
                        val userMoved = FastTileSources.run { binding.mapView.hasUserTouchedMap() }
                        if (captureMarkers.isEmpty() && !centeredOnLiveFix && !userMoved) {
                            binding.mapView.controller.setCenter(GeoPoint(lat, lon))
                            if (cached == null) binding.mapView.controller.setZoom(15.0)
                        }
                        centeredOnLiveFix = true
                    }
                }
            }
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

    private fun renderUiState(state: ImagesOnMapUiState) {
        binding.progressLoading.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        // 1. Render map markers for filtered captures
        renderCaptureMarkers(state.filteredCaptures, state.selectedCapture)

        // 2. Empty state visibility
        if (state.filteredCaptures.isEmpty() && !state.isLoading) {
            binding.layoutEmptyCaptures.visibility = View.VISIBLE
            binding.cardBottomPreview.visibility = View.GONE
        } else {
            binding.layoutEmptyCaptures.visibility = View.GONE
        }

        // 3. Render bottom preview card for selected capture
        if (state.selectedCapture != null) {
            renderSelectedPreview(state.selectedCapture)
        } else {
            binding.cardBottomPreview.visibility = View.GONE
        }
    }

    private fun renderSelectedPreview(item: RecentCaptureItem) {
        binding.cardBottomPreview.visibility = View.VISIBLE

        // Thumbnail with rounded corners
        binding.ivPreviewThumb.load(item.contentUri) {
            crossfade(true)
            transformations(RoundedCornersTransformation(14f))
            placeholder(R.drawable.img_map_normal)
            error(R.drawable.img_map_normal)
            fallback(R.drawable.img_map_normal)
        }

        val title = item.locationName?.takeIf { it.isNotBlank() && it != "Location" && it != "Capture Location" }
            ?: item.cityCountry?.takeIf { it.isNotBlank() && it != "Local Album" }
            ?: "Capture"

        binding.tvPreviewTitle.text = title
        binding.tvPreviewSubtitle.text = item.dateFormatted
        binding.tvPreviewCoords.text = item.locationFormatted
    }

    private fun renderCaptureMarkers(captures: List<RecentCaptureItem>, selected: RecentCaptureItem?) {
        // uiState emits for many reasons (loading flag, user location, ...). Only rebuild the
        // markers when the captures or the selection actually changed.
        val key = captures to selected?.id
        if (key == renderedMarkerKey) return
        renderedMarkerKey = key

        // Cancel an in-flight render so two renders can't both add markers (duplicates).
        markerJob?.cancel()

        if (captures.isEmpty()) {
            clearCaptureMarkers()
            binding.mapView.invalidate()
            return
        }

        markerJob = lifecycleScope.launch {
            val geoPoints = mutableListOf<GeoPoint>()

            // Build all marker bitmaps in parallel off the main thread (cached ones are instant),
            // then swap the old markers for the new ones in one go.
            val drawables = withContext(Dispatchers.IO) {
                captures.mapIndexed { index, item ->
                    async {
                        val isSelected = selected?.id == item.id
                        val clusterCount = if (index == 0 && captures.size > 3) 4 else 1
                        val cacheKey = "${item.id}|$isSelected|$clusterCount"
                        synchronized(markerDrawableCache) { markerDrawableCache[cacheKey] }
                            ?: createCaptureMarkerDrawable(item, isSelected, clusterCount).also {
                                synchronized(markerDrawableCache) { markerDrawableCache[cacheKey] = it }
                            }
                    }
                }.awaitAll()
            }

            clearCaptureMarkers()

            captures.forEachIndexed { index, item ->
                val lat = item.latitude ?: return@forEachIndexed
                val lon = item.longitude ?: return@forEachIndexed
                val geo = GeoPoint(lat, lon)
                geoPoints.add(geo)

                val isSelected = selected?.id == item.id
                val markerDrawable = drawables[index]

                val marker = Marker(binding.mapView).apply {
                    position = geo
                    title = item.locationName ?: "Capture"
                    snippet = item.dateFormatted
                    icon = markerDrawable
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ ->
                        viewModel.selectCapture(item)
                        com.autonoumus.ai.core.util.FastTileSources.flyTo(binding.mapView, geo, 16.5, 400L)
                        true
                    }
                }
                binding.mapView.overlays.add(marker)
                captureMarkers.add(marker)

                // If selected, add soft translucent blue halo circle around it
                if (isSelected) {
                    addHaloCircle(geo)
                }
            }

            binding.mapView.invalidate()

            // Auto-fit bounds on first load so all captures are immediately visible on map
            if (!hasAutoFittedBounds && geoPoints.isNotEmpty()) {
                hasAutoFittedBounds = true
                binding.mapView.post {
                    try {
                        if (geoPoints.size > 1) {
                            val bbox = BoundingBox.fromGeoPoints(geoPoints)
                            // Not animated: an animated zoom downloads tiles for every intermediate zoom level.
                            binding.mapView.zoomToBoundingBox(bbox, false, 180)
                        } else {
                            binding.mapView.controller.setCenter(geoPoints.first())
                            binding.mapView.controller.setZoom(16.0)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun clearCaptureMarkers() {
        captureMarkers.forEach { binding.mapView.overlays.remove(it) }
        captureMarkers.clear()
        if (haloCircleOverlay != null) {
            binding.mapView.overlays.remove(haloCircleOverlay)
            haloCircleOverlay = null
        }
    }

    private fun addHaloCircle(geoPoint: GeoPoint) {
        if (haloCircleOverlay != null) {
            binding.mapView.overlays.remove(haloCircleOverlay)
        }
        haloCircleOverlay = Polygon(binding.mapView).apply {
            points = Polygon.pointsAsCircle(geoPoint, 350.0)
            fillPaint.color = Color.parseColor("#263B82F6") // soft translucent light blue halo
            outlinePaint.apply {
                color = Color.parseColor("#4D3B82F6")
                strokeWidth = 2f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
        }
        binding.mapView.overlays.add(0, haloCircleOverlay)
    }

    private fun createCaptureMarkerDrawable(item: RecentCaptureItem, isSelected: Boolean, clusterCount: Int = 1): Drawable {
        val density = resources.displayMetrics.density
        val sizeDp = if (isSelected) 56 else 48
        val sizePx = (sizeDp * density).toInt()

        // 1. Decode thumbnail bitmap from ContentUri or fallback
        // Decode only as many pixels as the ~50dp marker needs. The old fixed inSampleSize=4 still
        // decoded ~3MP per 12MP photo, which made markers (and the whole screen) slow to appear.
        var thumbBitmap: Bitmap? = null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                // Uses MediaStore's own cached thumbnail - fastest path, works for videos too.
                thumbBitmap = contentResolver.loadThumbnail(item.contentUri, android.util.Size(sizePx, sizePx), null)
            } catch (_: Exception) {}
        }
        if (thumbBitmap == null) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(item.contentUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) {
                    sample *= 2
                }
                contentResolver.openInputStream(item.contentUri)?.use { input ->
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    thumbBitmap = BitmapFactory.decodeStream(input, null, options)
                }
            } catch (_: Exception) {}
        }

        // Fallback placeholder bitmap if decode fails
        if (thumbBitmap == null) {
            try {
                thumbBitmap = BitmapFactory.decodeResource(resources, R.drawable.img_recent_capture_1)
            } catch (_: Exception) {}
        }
        if (thumbBitmap == null) {
            thumbBitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.parseColor("#3B82F6"))
            }
        }

        // 2. Crop to circular image with optional selected pill and badge
        val extraHeight = if (isSelected) (22 * density).toInt() else 0
        val finalBitmap = Bitmap.createBitmap(sizePx, sizePx + extraHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(finalBitmap)

        val circleRadius = (sizePx / 2f) - (3 * density)
        val centerX = sizePx / 2f
        val centerY = sizePx / 2f

        // Draw Circular Cropped Thumbnail
        val cropped = getCroppedBitmap(thumbBitmap!!, (circleRadius * 2).toInt())
        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val left = centerX - circleRadius
        val top = centerY - circleRadius
        canvas.drawBitmap(cropped, left, top, imagePaint)
        if (cropped != thumbBitmap) {
            cropped.recycle()
        }
        thumbBitmap?.recycle()

        // Draw White Circular Border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
        }
        canvas.drawCircle(centerX, centerY, circleRadius, borderPaint)

        // Top-right cluster badge (e.g., "4" badge)
        if (clusterCount > 1) {
            val badgeRadius = 9 * density
            val badgeCenterX = sizePx - badgeRadius
            val badgeCenterY = badgeRadius

            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#3B82F6")
                style = Paint.Style.FILL
            }
            val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * density
            }
            val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 9.5f * density
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            canvas.drawCircle(badgeCenterX, badgeCenterY, badgeRadius, badgePaint)
            canvas.drawCircle(badgeCenterX, badgeCenterY, badgeRadius, badgeBorderPaint)
            val textY = badgeCenterY - ((badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f)
            canvas.drawText(clusterCount.toString(), badgeCenterX, textY, badgeTextPaint)
        }

        // If selected, draw active blue location pill below marker (location name)
        if (isSelected) {
            val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#4F46E5")
                style = Paint.Style.FILL
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 9.5f * density
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }

            val label = item.locationName?.takeIf { it.isNotBlank() && it != "Location" }
                ?: item.cityCountry?.takeIf { it.isNotBlank() }
                ?: "Capture"

            val truncated = if (label.length > 14) label.take(12) + "..." else label
            val textWidth = textPaint.measureText(truncated)
            val pillWidth = textWidth + (16 * density)
            val pillHeight = 18 * density
            val pillTop = sizePx.toFloat() + (2 * density)

            val pillRect = RectF(
                centerX - (pillWidth / 2f),
                pillTop,
                centerX + (pillWidth / 2f),
                pillTop + pillHeight
            )
            canvas.drawRoundRect(pillRect, 8 * density, 8 * density, pillPaint)
            val textY = pillTop + (pillHeight / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
            canvas.drawText(truncated, centerX, textY, textPaint)
        }

        return BitmapDrawable(resources, finalBitmap)
    }

    private fun getCroppedBitmap(bitmap: Bitmap, diameter: Int): Bitmap {
        val output = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, diameter, diameter)
        val rectF = RectF(rect)

        canvas.drawOval(rectF, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        // Scale & center crop original bitmap into diameter x diameter
        val srcRect = if (bitmap.width >= bitmap.height) {
            val offset = (bitmap.width - bitmap.height) / 2
            Rect(offset, 0, offset + bitmap.height, bitmap.height)
        } else {
            val offset = (bitmap.height - bitmap.width) / 2
            Rect(0, offset, bitmap.width, offset + bitmap.width)
        }

        canvas.drawBitmap(bitmap, srcRect, rect, paint)
        return output
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        currentFocus?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        viewModel.loadCaptures()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroy() {
        try {
            binding.mapView.onDetach()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
