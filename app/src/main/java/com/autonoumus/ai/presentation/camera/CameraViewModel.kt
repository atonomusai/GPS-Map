package com.autonoumus.ai.presentation.camera

import android.content.Context
import android.location.Location
import androidx.camera.core.MeteringPoint
import androidx.camera.video.Quality
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.autonoumus.ai.core.base.BaseViewModel
import com.autonoumus.ai.core.camera.CameraController
import com.autonoumus.ai.core.stamp.StampRenderer
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.domain.model.CameraCapabilities
import com.autonoumus.ai.domain.model.CameraCaptureResult
import com.autonoumus.ai.domain.model.CameraLensFacing
import com.autonoumus.ai.domain.model.CaptureMode
import com.autonoumus.ai.domain.model.FlashMode
import com.autonoumus.ai.domain.model.PhotoResolutionOption
import com.autonoumus.ai.domain.model.UserLocationData
import com.autonoumus.ai.domain.repository.MediaCaptureRepository
import com.autonoumus.ai.domain.usecase.BuildStampDataUseCase
import com.autonoumus.ai.domain.usecase.GetStampSettingsUseCase
import com.autonoumus.ai.domain.usecase.GetUserLocationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    @ApplicationContext context: Context,
    stampRenderer: StampRenderer,
    private val getUserLocationUseCase: GetUserLocationUseCase,
    private val getStampSettingsUseCase: GetStampSettingsUseCase,
    private val buildStampDataUseCase: BuildStampDataUseCase,
    private val locationHelper: LocationHelper,
    private val preferenceManager: PreferenceManager,
    private val mediaCaptureRepository: MediaCaptureRepository
) : BaseViewModel() {

    val cameraController = CameraController(context, stampRenderer)

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _captureEvents = MutableSharedFlow<CameraCaptureResult>()
    val captureEvents: SharedFlow<CameraCaptureResult> = _captureEvents.asSharedFlow()

    private var previewRefreshJob: Job? = null
    private var recordingTimerJob: Job? = null
    private var locationUpdatesJob: Job? = null
    private var lastLocation: UserLocationData = UserLocationData()
    private var boundLifecycleOwner: LifecycleOwner? = null
    private var boundPreviewView: PreviewView? = null

    init {
        val flash = enumOrDefault(preferenceManager.cameraFlashMode, FlashMode.AUTO)
        val lens = enumOrDefault(preferenceManager.cameraLensFacing, CameraLensFacing.BACK)
        _uiState.update { it.copy(flashMode = flash, lensFacing = lens) }
        loadLatestThumbnail()
    }

    fun loadLatestThumbnail() {
        viewModelScope.launch {
            val latest = mediaCaptureRepository.getLatestCaptureUri()
            if (latest != null) {
                _uiState.update {
                    it.copy(
                        latestThumbnailUri = latest.first,
                        latestThumbnailIsVideo = latest.second
                    )
                }
            }
        }
    }

    fun setInitialMode(mode: CaptureMode) {
        _uiState.update { it.copy(captureMode = mode) }
    }

    fun onPermissionsResult(cameraGranted: Boolean, audioGranted: Boolean) {
        _uiState.update {
            it.copy(hasCameraPermission = cameraGranted, hasAudioPermission = audioGranted, permissionsRequested = true)
        }
        if (cameraGranted) {
            loadLatestThumbnail()
        }
    }

    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        boundLifecycleOwner = lifecycleOwner
        boundPreviewView = previewView
        viewModelScope.launch {
            val state = _uiState.value
            try {
                cameraController.bind(
                    lifecycleOwner,
                    previewView,
                    state.lensFacing,
                    selectedPhotoResolution(),
                    selectedVideoQuality()
                )
                cameraController.setFlashMode(state.flashMode)
                cameraController.setFrameRate(preferenceManager.cameraFrameRateFps)
                val capabilities = cameraController.getCameraCapabilities()
                _uiState.update {
                    it.copy(
                        cameraCapabilities = capabilities,
                        hasFlashUnit = cameraController.hasFlash(),
                        hasFrontCamera = cameraController.hasFrontCamera(),
                        zoomRatio = cameraController.currentZoomRatio(),
                        minZoomRatio = cameraController.minZoomRatio(),
                        maxZoomRatio = cameraController.maxZoomRatio(),
                        resolutionStatusLabel = buildResolutionStatusLabel(capabilities)
                    )
                }
                startLocationUpdates()
                startPreviewStampRefresh()
            } catch (e: Exception) {
                _captureEvents.emit(
                    CameraCaptureResult.Error("Unable to start camera: ${e.javaClass.simpleName}: ${e.message}", e)
                )
            }
        }
    }

    fun onModeSelected(mode: CaptureMode) {
        if (_uiState.value.isRecording) return
        _uiState.update { it.copy(captureMode = mode) }
    }

    fun onFlashToggleClicked() {
        val next = when (_uiState.value.flashMode) {
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.OFF
            FlashMode.OFF, FlashMode.TORCH -> FlashMode.AUTO
        }
        preferenceManager.cameraFlashMode = next.name
        cameraController.setFlashMode(next)
        _uiState.update { it.copy(flashMode = next) }
    }

    fun onSwitchCameraClicked() {
        val next = if (_uiState.value.lensFacing == CameraLensFacing.BACK) CameraLensFacing.FRONT else CameraLensFacing.BACK
        preferenceManager.cameraLensFacing = next.name
        _uiState.update { it.copy(lensFacing = next) }
        rebindCamera()
    }

    fun onZoomChanged(newRatio: Float) {
        cameraController.setZoomRatio(newRatio)
        _uiState.update { it.copy(zoomRatio = cameraController.currentZoomRatio()) }
    }

    fun onTapToFocus(point: MeteringPoint) {
        cameraController.focusAt(point)
    }

    fun onOrientationChanged(surfaceRotation: Int) {
        cameraController.setTargetRotation(surfaceRotation)
    }

    fun onVideoQualitySelected(quality: Quality) {
        preferenceManager.cameraVideoQualityName = nameForQuality(quality)
        rebindCamera()
    }

    fun onPhotoResolutionSelected(option: PhotoResolutionOption) {
        preferenceManager.cameraPhotoResolution = "${option.width}x${option.height}"
        rebindCamera()
    }

    fun onFrameRateSelected(fps: Int) {
        preferenceManager.cameraFrameRateFps = fps
        cameraController.setFrameRate(fps)
        _uiState.update { it.copy(resolutionStatusLabel = buildResolutionStatusLabel(it.cameraCapabilities)) }
    }

    /** Applies (and persists) a batch of capture settings at once - used by the "Save Preference" action. */
    fun applyCameraSettings(videoQuality: Quality?, photoResolution: PhotoResolutionOption?, frameRateFps: Int?) {
        if (videoQuality != null) preferenceManager.cameraVideoQualityName = nameForQuality(videoQuality)
        if (photoResolution != null) preferenceManager.cameraPhotoResolution = "${photoResolution.width}x${photoResolution.height}"
        if (frameRateFps != null) preferenceManager.cameraFrameRateFps = frameRateFps
        rebindCamera()
    }

    /** Clears every persisted capture-setting override, reverting to device defaults (highest quality/resolution). */
    fun resetCameraSettings() {
        preferenceManager.cameraVideoQualityName = null
        preferenceManager.cameraPhotoResolution = null
        preferenceManager.cameraFrameRateFps = 0
        rebindCamera()
    }

    private fun buildResolutionStatusLabel(capabilities: CameraCapabilities): String {
        // No explicit choice: CameraController records FHD (falling back lower), so label that -
        // it used to show the device maximum ("2160P · 60 FPS") while videos were saved in 1080p.
        val supported = capabilities.videoQualities.map { it.quality }
        val quality = qualityFromName(preferenceManager.cameraVideoQualityName)
            ?: (if (Quality.FHD in supported || supported.isEmpty()) Quality.FHD else supported.firstOrNull())
        val qualityShort = when (quality) {
            Quality.UHD -> "2160P"
            Quality.FHD -> "1080P"
            Quality.HD -> "720P"
            Quality.SD -> "480P"
            else -> "AUTO"
        }
        // Without a fixed frame rate the camera runs its default auto-exposure range (30 fps).
        val fps = preferenceManager.cameraFrameRateFps.takeIf { it > 0 } ?: 30
        return "$qualityShort · $fps FPS"
    }

    fun onCapturePhotoClicked() {
        if (_uiState.value.isCapturing) return
        viewModelScope.launch {
            // Do NOT await a fresh GPS/geocode refresh here: startPreviewStampRefresh() already
            // keeps cameraController's stamp data warm every 5s while the preview is showing, so
            // the shutter can fire immediately against that already-current data. Awaiting a fresh
            // fix here used to block the very first capture for up to ~8s (GPS cold-start) plus a
            // synchronous reverse-geocode call, which is why only the first shot felt slow.
            stopPreviewStampRefresh()
            _uiState.update { it.copy(isCapturing = true) }
            try {
                val uri = cameraController.takePhoto(androidLocationFromLastFix())
                _uiState.update {
                    it.copy(
                        latestThumbnailUri = uri,
                        latestThumbnailIsVideo = false
                    )
                }
                _captureEvents.emit(CameraCaptureResult.PhotoSaved(uri))
            } catch (e: Exception) {
                _captureEvents.emit(CameraCaptureResult.Error(e.message ?: "Photo capture failed", e))
            } finally {
                _uiState.update { it.copy(isCapturing = false) }
                startPreviewStampRefresh()
            }
        }
    }

    fun onStartRecordingClicked() {
        if (_uiState.value.isRecording) return
        viewModelScope.launch {
            stopPreviewStampRefresh()
            cameraController.startRecording(
                androidLocationFromLastFix(),
                withAudio = _uiState.value.hasAudioPermission
            ) { event -> handleVideoRecordEvent(event) }
            _uiState.update { it.copy(isRecording = true, isPaused = false, recordingElapsedMs = 0L) }
            startRecordingTimer()
        }
    }

    fun onStopRecordingClicked() {
        if (_uiState.value.isSavingVideo) return
        _uiState.update { it.copy(isSavingVideo = true) }
        cameraController.stopRecording()
    }

    fun onPauseResumeRecordingClicked() {
        if (_uiState.value.isPaused) {
            cameraController.resumeRecording()
            _uiState.update { it.copy(isPaused = false) }
        } else {
            cameraController.pauseRecording()
            _uiState.update { it.copy(isPaused = true) }
        }
    }

    private fun handleVideoRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Pause -> {
                _uiState.update { it.copy(isPaused = true) }
            }
            is VideoRecordEvent.Resume -> {
                _uiState.update { it.copy(isPaused = false) }
            }
            is VideoRecordEvent.Status -> {
                val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
                _uiState.update { it.copy(recordingElapsedMs = durationMs) }
            }
            is VideoRecordEvent.Finalize -> {
                recordingTimerJob?.cancel()
                val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
                _uiState.update {
                    it.copy(isRecording = false, isPaused = false, recordingElapsedMs = 0L, isSavingVideo = false)
                }
                viewModelScope.launch {
                    if (!event.hasError()) {
                        val outputUri = event.outputResults.outputUri
                        _uiState.update {
                            it.copy(
                                latestThumbnailUri = outputUri,
                                latestThumbnailIsVideo = true
                            )
                        }
                        _captureEvents.emit(CameraCaptureResult.VideoSaved(outputUri, durationMs))
                    } else {
                        _captureEvents.emit(
                            CameraCaptureResult.Error(event.cause?.message ?: "Recording failed", event.cause)
                        )
                    }
                    startPreviewStampRefresh()
                }
            }
        }
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(500)
                if (!_uiState.value.isPaused) {
                    _uiState.update { it.copy(recordingElapsedMs = it.recordingElapsedMs + 500) }
                }
            }
        }
    }

    private fun rebindCamera() {
        val owner = boundLifecycleOwner
        val previewView = boundPreviewView
        if (owner != null && previewView != null) {
            bindCamera(owner, previewView)
        }
    }

    fun onRefreshStampSettings() {
        startLocationUpdates()
    }

    /** Re-arms the loading shimmer and immediately re-fetches, instead of waiting for the
     *  periodic timer, so the template reflects the real location right after the user
     *  grants location permission. */
    fun onLocationPermissionGranted() {
        _uiState.update { it.copy(isFetchingLocation = true) }
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        locationUpdatesJob?.cancel()
        locationUpdatesJob = viewModelScope.launch {
            if (preferenceManager.isCustomLocationOverrideEnabled) {
                val customLoc = locationHelper.getCameraStampLocationData()
                updateStampWithLocation(customLoc)
                return@launch
            }

            // Immediately load cached fix for instantaneous stamp on frame 1
            val initial = locationHelper.getCameraStampLocationData()
            updateStampWithLocation(initial)

            // Then observe live GPS stream non-blockingly
            locationHelper.observeLocationUpdates().collect { liveLoc ->
                if (!preferenceManager.isCustomLocationOverrideEnabled) {
                    updateStampWithLocation(liveLoc)
                }
            }
        }
    }

    private suspend fun updateStampWithLocation(location: UserLocationData) {
        lastLocation = location
        val settings = getStampSettingsUseCase()
        val data = buildStampDataUseCase(location, settings)
        cameraController.setStampSettings(settings)
        cameraController.setStampData(data)
        _uiState.update {
            it.copy(
                stampSettings = settings,
                stampData = data,
                isFetchingLocation = false
            )
        }
    }

    private fun startPreviewStampRefresh() {
        previewRefreshJob?.cancel()
        previewRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                val updatedLoc = lastLocation.copy(
                    timestampFormatted = locationHelper.getFormattedCurrentTime(),
                    dateFormatted = locationHelper.getFormattedCurrentDate(),
                    timeFormatted = locationHelper.getFormattedCurrentTimeOnly()
                )
                lastLocation = updatedLoc
                val settings = _uiState.value.stampSettings ?: getStampSettingsUseCase()
                val data = buildStampDataUseCase(updatedLoc, settings)
                cameraController.setStampData(data)
                _uiState.update { it.copy(stampData = data) }
            }
        }
    }

    private fun stopPreviewStampRefresh() {
        previewRefreshJob?.cancel()
        previewRefreshJob = null
    }

    private fun androidLocationFromLastFix(): Location? {
        val lat = lastLocation.rawLatitude ?: return null
        val lon = lastLocation.rawLongitude ?: return null
        return Location("gps_camera").apply {
            latitude = lat
            longitude = lon
            lastLocation.rawAltitudeMeters?.let { altitude = it }
        }
    }

    private fun selectedVideoQuality(): Quality? = qualityFromName(preferenceManager.cameraVideoQualityName)

    private fun selectedPhotoResolution(): android.util.Size? {
        val raw = preferenceManager.cameraPhotoResolution ?: return null
        val parts = raw.split("x")
        if (parts.size != 2) return null
        val width = parts[0].toIntOrNull() ?: return null
        val height = parts[1].toIntOrNull() ?: return null
        return android.util.Size(width, height)
    }

    private fun qualityFromName(name: String?): Quality? = when (name) {
        "UHD" -> Quality.UHD
        "FHD" -> Quality.FHD
        "HD" -> Quality.HD
        "SD" -> Quality.SD
        else -> null
    }

    private fun nameForQuality(quality: Quality): String = when (quality) {
        Quality.UHD -> "UHD"
        Quality.FHD -> "FHD"
        Quality.HD -> "HD"
        Quality.SD -> "SD"
        else -> "UHD"
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, default: T): T = try {
        java.lang.Enum.valueOf(T::class.java, name)
    } catch (e: IllegalArgumentException) {
        default
    }

    override fun onCleared() {
        super.onCleared()
        locationUpdatesJob?.cancel()
        previewRefreshJob?.cancel()
        recordingTimerJob?.cancel()
        cameraController.unbind()
    }
}
