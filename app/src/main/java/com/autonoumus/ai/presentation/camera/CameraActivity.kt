package com.autonoumus.ai.presentation.camera

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.core.util.VideoThumbnailModel
import com.autonoumus.ai.databinding.ActivityCameraBinding
import com.autonoumus.ai.domain.model.CameraCaptureResult
import com.autonoumus.ai.domain.model.CaptureMode
import com.autonoumus.ai.domain.model.FlashMode
import com.autonoumus.ai.core.stamp.StampRenderer
import androidx.core.view.updateLayoutParams
import android.widget.FrameLayout
import android.widget.ImageView
import coil.transform.CircleCropTransformation
import com.autonoumus.ai.presentation.gallery.GalleryActivity
import com.autonoumus.ai.presentation.gallery.MediaViewerActivity
import com.autonoumus.ai.presentation.settings.TimestampSettingsActivity
import com.autonoumus.ai.presentation.templates.CustomLocationStampBottomSheetDialog
import com.autonoumus.ai.presentation.templates.TemplatesActivity
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.presentation.common.dialog.PermissionRequestBottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class CameraActivity : BaseActivity<ActivityCameraBinding>() {

    private val viewModel: CameraViewModel by viewModels()
    private var isCameraBound = false

    @Inject
    lateinit var stampRenderer: StampRenderer

    @Inject
    lateinit var locationHelper: LocationHelper

    @Inject
    lateinit var preferenceManager: PreferenceManager

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var orientationEventListener: OrientationEventListener? = null
    private var currentOrientationDegrees: Int = 0

    // Once true, the system will never show its own permission dialog again for camera/audio -
    // the overlay switches to Settings-only so "Grant Access" isn't left as a dead button.
    private var isCameraPermissionPermanentlyDenied = false

    // Tracks whether this Activity instance has shown the initial permission bottom sheet
    private var hasShownInitialCameraPermissionSheet = false

    // Tracks whether location permission was already prompted in this camera session
    private var hasPromptedLocationInCamera = false

    // Tracks if the permission bottom sheet is currently open so background screens/banners remain hidden
    private var isPermissionSheetShowing = false

    private var lastRenderedThumbnailUri: Uri? = null
    private var lastRenderedThumbnailIsVideo: Boolean = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        isPermissionSheetShowing = false
        val cameraGranted = results[Manifest.permission.CAMERA] ?: hasCameraPermission()
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission()
        viewModel.onPermissionsResult(cameraGranted, audioGranted)
        if (!cameraGranted) {
            isCameraPermissionPermanentlyDenied = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        }
        updatePermissionUi(cameraGranted)
        updateGpsDisabledBanner()
        if (cameraGranted) {
            startCameraIfReady()
            // After taking permission of camera and microphone, immediately request location permission on camera screen
            if (!locationHelper.hasLocationPermission()) {
                hasPromptedLocationInCamera = true
                preferenceManager.hasRequestedLocationPermission = true
                locationPermissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
        }
    }

    // The GPS stamp burned into every photo/video needs location permission too - without it,
    // the stamp silently falls back to a fake placeholder location. Ask right after camera
    // access is granted (or on return visits if it was somehow never granted).
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        updateGpsDisabledBanner()
        if (granted) {
            // Don't wait for the 5s periodic refresh - update the live stamp template
            // immediately (with a loading shimmer) so it reflects the real location as soon
            // as permission is granted.
            viewModel.onLocationPermissionGranted()
        }
    }

    private fun requestLocationPermissionIfNeeded() {
        if (!locationHelper.hasLocationPermission() && !hasPromptedLocationInCamera) {
            hasPromptedLocationInCamera = true
            preferenceManager.hasRequestedLocationPermission = true
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            val isVideo = isUriVideo(selectedUri)
            startActivity(ImportImageStampActivity.newIntent(this, selectedUri, isVideo = isVideo))
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

    companion object {
        const val EXTRA_INITIAL_MODE = "extra_initial_capture_mode"
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityCameraBinding {
        return ActivityCameraBinding.inflate(inflater)
    }

    override fun applyWindowInsets() {
        // The preview must extend fully edge-to-edge; only the control bars get inset padding.
        ViewCompat.setOnApplyWindowInsetsListener(binding.cameraRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            binding.topControlsBar.updatePadding(top = bars.top)
            binding.bottomControlsContainer.updatePadding(bottom = bars.bottom + (16 * density).toInt())
            insets
        }
    }

    override fun initViews() {
        val initialMode = intent.getStringExtra(EXTRA_INITIAL_MODE)
            ?.let { runCatching { CaptureMode.valueOf(it) }.getOrNull() }
            ?: CaptureMode.PHOTO
        viewModel.setInitialMode(initialMode)
        binding.stampOverlayView.stampRenderer = stampRenderer

        setupModeToggle()
        setupCaptureButton()
        setupTopControls()
        setupGpsDisabledBanner()
        setupPermissionOverlay()
        setupGestures()
        setupOrientationListener()
        updateGpsDisabledBanner()
    }

    override fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { renderUiState(it) } }
                launch { viewModel.captureEvents.collect { handleCaptureEvent(it) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndStart()
        updateGpsDisabledBanner()
        viewModel.onRefreshStampSettings()
        viewModel.loadLatestThumbnail()
        orientationEventListener?.enable()
    }

    override fun onPause() {
        super.onPause()
        orientationEventListener?.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationEventListener?.disable()
    }

    private fun setupOrientationListener() {
        orientationEventListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_UI) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val newDegrees = when (orientation) {
                    in 45..134 -> 90   // Phone tilted RIGHT (90° CW, top is to the right)
                    in 135..224 -> 180 // Upside down (180°)
                    in 225..314 -> 270 // Phone tilted LEFT (90° CCW, top is to the left)
                    else -> 0          // Normal portrait (0°)
                }

                if (newDegrees != currentOrientationDegrees) {
                    currentOrientationDegrees = newDegrees
                    onDeviceOrientationChanged(newDegrees)
                }
            }
        }
        if (orientationEventListener?.canDetectOrientation() == true) {
            orientationEventListener?.enable()
        }
    }

    private fun onDeviceOrientationChanged(orientationDegrees: Int) {
        val targetIconRotation = when (orientationDegrees) {
            270 -> 90f   // Tilted LEFT: rotate views +90° CW so they appear upright to user
            90 -> -90f   // Tilted RIGHT: rotate views -90° CCW so they appear upright to user
            180 -> 180f  // Upside down: rotate views 180°
            else -> 0f   // Portrait: 0°
        }

        val isLandscape = orientationDegrees == 90 || orientationDegrees == 270

        // In landscape, show icons (Camera & Video) instead of text to avoid text cutting/clipping
        binding.tvModePhoto.visibility = if (isLandscape) View.GONE else View.VISIBLE
        binding.ivModePhoto.visibility = if (isLandscape) View.VISIBLE else View.GONE
        binding.tvModeVideo.visibility = if (isLandscape) View.GONE else View.VISIBLE
        binding.ivModeVideo.visibility = if (isLandscape) View.VISIBLE else View.GONE

        val rotatableViews = listOf(
            binding.btnFlash,
            binding.btnSwitchCamera,
            binding.tvResolutionStatus,
            binding.btnCameraSettings,
            binding.tvRecordingTimer,
            binding.bannerGpsDisabled,
            binding.ivModePhoto,
            binding.ivModeVideo,
            binding.flThumbnailCircle,
            binding.flTemplatesCircle,
            binding.btnPauseResume,
            binding.tvGalleryLabel,
            binding.tvTemplatesLabel
        )

        for (view in rotatableViews) {
            view.animate().rotation(targetIconRotation).setDuration(250).start()
        }

        val topControlsTranslationY = if (isLandscape) 20f * resources.displayMetrics.density else 0f

        // Mode toggle container rotates 180° when upside-down so PHOTO/VIDEO text is right-side up; stays 0° in portrait/landscape
        val modeToggleRotation = if (orientationDegrees == 180) 180f else 0f
        binding.layoutModeToggle.animate()
            .rotation(modeToggleRotation)
            .translationY(0f)
            .setDuration(250)
            .start()

        // Clean up gallery & templates text labels in landscape to prevent text clipping
        binding.tvGalleryLabel.visibility = if (isLandscape) View.GONE else View.VISIBLE
        binding.tvTemplatesLabel.visibility = if (isLandscape) View.GONE else View.VISIBLE

        binding.topControlsBar.animate()
            .translationY(topControlsTranslationY)
            .setDuration(250)
            .start()

        binding.stampOverlayView.setOrientation(orientationDegrees)

        val surfaceRotation = when (orientationDegrees) {
            270 -> Surface.ROTATION_90
            90 -> Surface.ROTATION_270
            180 -> Surface.ROTATION_180
            else -> Surface.ROTATION_0
        }
        viewModel.onOrientationChanged(surfaceRotation)
    }

    private fun checkPermissionsAndStart() {
        val cameraGranted = hasCameraPermission()
        val audioGranted = hasAudioPermission()
        viewModel.onPermissionsResult(cameraGranted, audioGranted)

        if (cameraGranted) {
            updatePermissionUi(true)
            updateGpsDisabledBanner()
            startCameraIfReady()
            requestLocationPermissionIfNeeded()
            return
        }

        if (!hasShownInitialCameraPermissionSheet) {
            hasShownInitialCameraPermissionSheet = true
            showCameraPermissionSheet()
        } else {
            isCameraPermissionPermanentlyDenied = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            updatePermissionUi(false)
            updateGpsDisabledBanner()
        }
    }

    private fun showCameraPermissionSheet() {
        isPermissionSheetShowing = true
        binding.layoutPermissionRequired.visibility = View.GONE
        binding.bannerGpsDisabled.visibility = View.GONE
        PermissionRequestBottomSheetDialog.showCameraRationale(
            fragmentManager = supportFragmentManager,
            onAllow = {
                isPermissionSheetShowing = false
                permissionLauncher.launch(requiredPermissions())
            },
            onContinueWithout = {
                isPermissionSheetShowing = false
                updatePermissionUi(hasCameraPermission())
                updateGpsDisabledBanner()
            }
        ).apply {
            onDismissListener = {
                isPermissionSheetShowing = false
                updatePermissionUi(hasCameraPermission())
                updateGpsDisabledBanner()
            }
        }
    }

    private fun setupGpsDisabledBanner() {
        binding.bannerGpsDisabled.setOnClickListener {
            handleGpsBannerClick()
        }
        binding.tvGpsDisabledText.setOnClickListener {
            handleGpsBannerClick()
        }
        binding.ivGpsDisabledIcon.setOnClickListener {
            handleGpsBannerClick()
        }
       /* binding.btnEditCustomLocation.setOnClickListener {
            openCustomLocationBottomSheet()
        }*/
    }

    private fun handleGpsBannerClick() {
        if (locationHelper.hasLocationPermission()) {
            updateGpsDisabledBanner()
            return
        }
        val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        if (preferenceManager.hasRequestedLocationPermission && !shouldShowRationale) {
            com.autonoumus.ai.core.util.PermissionSettingsDialog.show(
                context = this,
                message = "Location permission is disabled. Please enable it in Settings to turn on real-time GPS geotagging."
            )
        } else {
            preferenceManager.hasRequestedLocationPermission = true
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun updateGpsDisabledBanner() {
        val showBanner = hasCameraPermission() &&
            !locationHelper.hasLocationPermission() &&
            !preferenceManager.isCustomLocationOverrideEnabled &&
            !isPermissionSheetShowing
        binding.bannerGpsDisabled.visibility = if (showBanner) View.VISIBLE else View.GONE
    }

    private fun startCameraIfReady() {
        if (isCameraBound) return
        isCameraBound = true
        viewModel.bindCamera(this, binding.previewView)
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        return perms.toTypedArray()
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun setupPermissionOverlay() {
        binding.btnGrantPermission.setOnClickListener {
            if (isCameraPermissionPermanentlyDenied) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                startActivity(intent)
            } else {
                showCameraPermissionSheet()
            }
        }
        binding.btnOpenAppSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            startActivity(intent)
        }
    }

    private fun updatePermissionUi(cameraGranted: Boolean) {
        if (isPermissionSheetShowing) {
            binding.layoutPermissionRequired.visibility = View.GONE
            return
        }
        binding.layoutPermissionRequired.visibility = if (cameraGranted) View.GONE else View.VISIBLE
        if (cameraGranted) return

        if (isCameraPermissionPermanentlyDenied) {
            // Tapping "Grant Access" again would do nothing - Android won't show its own
            // dialog anymore - so only offer the one path that actually works.
            binding.btnGrantPermission.visibility = View.GONE
            binding.tvPermissionMessage.text =
                "Camera and microphone access was denied. Please turn it on from Settings to use the camera."
        } else {
            binding.btnGrantPermission.visibility = View.VISIBLE
            binding.tvPermissionMessage.text =
                "Camera and microphone access are required to take GPS-stamped photos and videos."
        }
    }

    private fun setupModeToggle() {
        val onPhotoClick = View.OnClickListener { viewModel.onModeSelected(CaptureMode.PHOTO) }
        val onVideoClick = View.OnClickListener { viewModel.onModeSelected(CaptureMode.VIDEO) }
        binding.btnModePhoto.setOnClickListener(onPhotoClick)
        binding.tvModePhoto.setOnClickListener(onPhotoClick)
        binding.ivModePhoto.setOnClickListener(onPhotoClick)
        binding.btnModeVideo.setOnClickListener(onVideoClick)
        binding.tvModeVideo.setOnClickListener(onVideoClick)
        binding.ivModeVideo.setOnClickListener(onVideoClick)
    }

    private fun setupCaptureButton() {
        binding.captureButtonContainer.setOnClickListener {
            val state = viewModel.uiState.value
            when (state.captureMode) {
                CaptureMode.PHOTO -> {
                    // Shutter flash animation for instantaneous visual feedback
                    binding.previewView.animate().alpha(0.35f).setDuration(40).withEndAction {
                        binding.previewView.animate().alpha(1.0f).setDuration(80).start()
                    }.start()
                    viewModel.onCapturePhotoClicked()
                }
                CaptureMode.VIDEO -> if (state.isRecording) {
                    viewModel.onStopRecordingClicked()
                } else {
                    viewModel.onStartRecordingClicked()
                }
            }
        }
        binding.btnPauseResume.setOnClickListener { viewModel.onPauseResumeRecordingClicked() }
    }

    private fun setupTopControls() {
        binding.btnFlash.setOnClickListener { viewModel.onFlashToggleClicked() }
        binding.btnSwitchCamera.setOnClickListener { viewModel.onSwitchCameraClicked() }
        binding.btnCameraSettings.setOnClickListener {
            CameraSettingsBottomSheet().show(supportFragmentManager, "camera_settings")
        }
        binding.tvResolutionStatus.setOnClickListener {
            CameraSettingsBottomSheet().show(supportFragmentManager, "camera_settings")
        }
        binding.btnThumbnail.setOnClickListener {
            val state = viewModel.uiState.value
            val currentUri = state.latestThumbnailUri
            if (currentUri != null) {
                val stamp = state.stampData
                startActivity(
                    MediaViewerActivity.newIntent(
                        context = this,
                        uri = currentUri,
                        isVideo = state.latestThumbnailIsVideo,
                        locationTitle = stamp?.titleLine,
                        locationCoordinates = stamp?.coordinatesLine,
                        captureTime = stamp?.timestampLine,
                        rawLat = stamp?.rawLatitude,
                        rawLon = stamp?.rawLongitude
                    )
                )
            } else {
                pickMediaLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            }
        }
        binding.btnTemplatesQuick.setOnClickListener {
            startActivity(Intent(this, TemplatesActivity::class.java))
        }
        binding.stampOverlayView.onEditClickListener = {
            openCustomLocationBottomSheet()
        }
        binding.stampOverlayView.onTemplateClickListener = {
            val intent = Intent(this, TimestampSettingsActivity::class.java).apply {
                putExtra(TimestampSettingsActivity.EXTRA_SCREEN_MODE, TimestampSettingsActivity.MODE_CAMERA)
            }
            startActivity(intent)
        }
    }

    private fun openCustomLocationBottomSheet() {
        val hasPermission = locationHelper.hasLocationPermission()
        val isCustomOverride = preferenceManager.isCustomLocationOverrideEnabled
        val currentStamp = if (hasPermission || isCustomOverride) viewModel.uiState.value.stampData else null

        val hasValidData = currentStamp != null &&
            (!currentStamp.titleLine.isNullOrBlank() || !currentStamp.addressLine.isNullOrBlank() || currentStamp.rawLatitude != null)

        val sheet = if (hasValidData && (hasPermission || isCustomOverride)) {
            val latStr = currentStamp?.rawLatitude?.let { String.format(Locale.US, "%.4f° N", it) }
                ?: currentStamp?.latitudeFormatted
            val lonStr = currentStamp?.rawLongitude?.let { String.format(Locale.US, "%.4f° E", it) }
                ?: currentStamp?.longitudeFormatted

            CustomLocationStampBottomSheetDialog.newInstance(
                initialCountry = currentStamp?.titleLine,
                initialAddress = currentStamp?.addressLine,
                initialLat = latStr,
                initialLon = lonStr
            )
        } else {
            CustomLocationStampBottomSheetDialog.newInstance()
        }
        sheet.onLocationApplied = { _, _, _, _ ->
            viewModel.onRefreshStampSettings()
            updateGpsDisabledBanner()
        }
        sheet.show(supportFragmentManager, CustomLocationStampBottomSheetDialog.TAG)
    }

    private fun setupGestures() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = viewModel.uiState.value.zoomRatio
                viewModel.onZoomChanged(current * detector.scaleFactor)
                return true
            }
        })
        binding.previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !scaleGestureDetector.isInProgress) {
                val point = binding.previewView.meteringPointFactory.createPoint(event.x, event.y)
                viewModel.onTapToFocus(point)
                showFocusRing(event.x, event.y)
            }
            true
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        binding.ivFocusRing.apply {
            translationX = x - width / 2f
            translationY = y - height / 2f
            alpha = 1f
            scaleX = 1.3f
            scaleY = 1.3f
            animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
                animate().alpha(0f).setStartDelay(500).setDuration(300).start()
            }.start()
        }
    }

    private fun renderUiState(state: CameraUiState) {
        updateModeToggleUi(state.captureMode)
        updatePermissionUi(state.hasCameraPermission)

        // Capture button drawable
        val captureBgRes = when {
            state.captureMode == CaptureMode.PHOTO -> R.drawable.bg_capture_button_photo
            state.isRecording -> R.drawable.bg_capture_button_video_stop
            else -> R.drawable.bg_capture_button_video_record
        }
        binding.btnCapture.setBackgroundResource(captureBgRes)
        binding.btnCapture.isEnabled = !state.isCapturing && !state.isSavingVideo

        // Flash pill icon & text
        when (state.flashMode) {
            FlashMode.AUTO -> {
                binding.ivFlashIcon.setImageResource(R.drawable.auto_flash)
                val amberColor = ContextCompat.getColor(this, R.color.amber_500)
                binding.ivFlashIcon.imageTintList = android.content.res.ColorStateList.valueOf(amberColor)
                binding.tvFlashText.setTextColor(amberColor)
                binding.tvFlashText.text = "AUTO"
            }
            FlashMode.ON -> {
                binding.ivFlashIcon.setImageResource(R.drawable.ic_flash_bolt)
                val amberColor = ContextCompat.getColor(this, R.color.amber_500)
                binding.ivFlashIcon.imageTintList = android.content.res.ColorStateList.valueOf(amberColor)
                binding.tvFlashText.setTextColor(amberColor)
                binding.tvFlashText.text = "ON"
            }
            FlashMode.OFF, FlashMode.TORCH -> {
                binding.ivFlashIcon.setImageResource(R.drawable.ic_flash_off)
                binding.ivFlashIcon.imageTintList = null
                val offColor = ContextCompat.getColor(this, R.color.white)
                binding.tvFlashText.setTextColor(offColor)
                binding.tvFlashText.text = "OFF"
            }
        }

        binding.btnFlash.visibility = if (state.hasFlashUnit) View.VISIBLE else View.GONE
        binding.btnSwitchCamera.visibility = if (state.hasFrontCamera) View.VISIBLE else View.GONE
        binding.tvResolutionStatus.text = state.resolutionStatusLabel.replace(" · ", " • ")

        // Mode toggle is always visible per reference UI
        binding.layoutModeToggle.visibility = View.VISIBLE

        // Recording timer
        binding.tvRecordingTimer.visibility = if (state.isRecording) View.VISIBLE else View.GONE
        val timerPrefix = if (state.isPaused) "❚❚" else "●"
        binding.tvRecordingTimer.text = "$timerPrefix ${formatElapsed(state.recordingElapsedMs)}"
        binding.tvRecordingTimer.setTextColor(
            ContextCompat.getColor(this, if (state.isPaused) R.color.amber_500 else android.R.color.holo_red_light)
        )

        // Pause/Resume button (shown during recording on left, hidden while the clip is saving)
        val showPauseResume = state.isRecording && !state.isSavingVideo
        binding.btnPauseResume.visibility = if (showPauseResume) View.VISIBLE else View.GONE
        binding.btnPauseResume.setImageResource(if (state.isPaused) R.drawable.ic_play_white else R.drawable.ic_pause_white)
        binding.btnThumbnail.visibility = if (state.isRecording) View.GONE else View.VISIBLE
        binding.btnTemplatesQuick.visibility = View.VISIBLE

        renderThumbnail(state.latestThumbnailUri, state.latestThumbnailIsVideo)
        binding.stampOverlayView.updateStamp(state.stampData, state.stampSettings, isLoading = state.isFetchingLocation)

        // Capture/save loading overlay - shown when stopping video recording to indicate clip finalizing.
        // For photos, the shutter flash and thumbnail update provide instant feedback without blocking the viewfinder.
        val isSaving = state.isSavingVideo
        binding.layoutCaptureSaving.visibility = if (isSaving) View.VISIBLE else View.GONE
        if (isSaving) {
            binding.ivCaptureSavingIcon.setImageResource(R.drawable.ic_video_home)
            binding.tvCaptureSavingText.text = "Saving your video…"
        }
    }

    private fun renderThumbnail(uri: Uri?, isVideo: Boolean) {
        if (uri == null) {
            lastRenderedThumbnailUri = null
            lastRenderedThumbnailIsVideo = false
            binding.ivThumbnail.updateLayoutParams<FrameLayout.LayoutParams> {
                width = (22 * resources.displayMetrics.density).toInt()
                height = (22 * resources.displayMetrics.density).toInt()
            }
            binding.ivThumbnail.scaleType = ImageView.ScaleType.CENTER_INSIDE
            binding.ivThumbnail.setImageResource(R.drawable.ic_gallery)
            binding.ivThumbnail.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            binding.tvGalleryLabel.text = "GALLERY"
            return
        }

        if (uri == lastRenderedThumbnailUri && isVideo == lastRenderedThumbnailIsVideo) {
            return
        }
        lastRenderedThumbnailUri = uri
        lastRenderedThumbnailIsVideo = isVideo

        binding.ivThumbnail.updateLayoutParams<FrameLayout.LayoutParams> {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = FrameLayout.LayoutParams.MATCH_PARENT
        }
        binding.flThumbnailCircle.clipToOutline = true
        binding.ivThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
        binding.ivThumbnail.imageTintList = null
        binding.tvGalleryLabel.text = "RECENT"
        val request: Any = if (isVideo) VideoThumbnailModel(uri) else uri
        binding.ivThumbnail.load(request) {
            crossfade(false)
            size(128, 128)
            precision(coil.size.Precision.INEXACT)
            memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            diskCachePolicy(coil.request.CachePolicy.ENABLED)
            transformations(CircleCropTransformation())
            placeholder(R.drawable.ic_gallery)
            error(R.drawable.ic_gallery)
        }
    }

    private fun updateModeToggleUi(mode: CaptureMode) {
        val photoActive = mode == CaptureMode.PHOTO
        val activeBg = R.drawable.bg_mode_pill_active_amber
        val inactiveBg = android.R.color.transparent
        val activeColor = android.graphics.Color.BLACK
        val inactiveColor = android.graphics.Color.parseColor("#94A3B8")

        binding.btnModePhoto.setBackgroundResource(if (photoActive) activeBg else inactiveBg)
        binding.tvModePhoto.setTextColor(if (photoActive) activeColor else inactiveColor)
        binding.ivModePhoto.imageTintList = android.content.res.ColorStateList.valueOf(if (photoActive) activeColor else inactiveColor)

        binding.btnModeVideo.setBackgroundResource(if (!photoActive) activeBg else inactiveBg)
        binding.tvModeVideo.setTextColor(if (!photoActive) activeColor else inactiveColor)
        binding.ivModeVideo.imageTintList = android.content.res.ColorStateList.valueOf(if (!photoActive) activeColor else inactiveColor)
    }

    private fun formatElapsed(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun handleCaptureEvent(result: CameraCaptureResult) {
        when (result) {
            is CameraCaptureResult.PhotoSaved -> Toast.makeText(this, "Photo saved", Toast.LENGTH_SHORT).show()
            is CameraCaptureResult.VideoSaved -> Toast.makeText(this, "Video saved", Toast.LENGTH_SHORT).show()
            is CameraCaptureResult.Error -> showErrorDialog(result.message, result.throwable)
        }
    }

    /** Shown in full (not Toast-truncated) since these are diagnostic camera-pipeline failures, not routine feedback. */
    private fun showErrorDialog(message: String, throwable: Throwable?) {
        val fullText = if (throwable != null) "$message\n\n${Log.getStackTraceString(throwable)}" else message
        AlertDialog.Builder(this)
            .setTitle("Camera error")
            .setMessage(fullText)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Camera error", fullText))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
