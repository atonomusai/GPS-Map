package com.autonoumus.ai.presentation.camera

import android.net.Uri
import com.autonoumus.ai.domain.model.CameraCapabilities
import com.autonoumus.ai.domain.model.CameraLensFacing
import com.autonoumus.ai.domain.model.CaptureMode
import com.autonoumus.ai.domain.model.FlashMode
import com.autonoumus.ai.domain.model.StampData
import com.autonoumus.ai.domain.model.StampSettings

data class CameraUiState(
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    val lensFacing: CameraLensFacing = CameraLensFacing.BACK,
    val flashMode: FlashMode = FlashMode.OFF,
    val zoomRatio: Float = 1f,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val hasFlashUnit: Boolean = false,
    val hasFrontCamera: Boolean = true,
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val recordingElapsedMs: Long = 0L,
    val isCapturing: Boolean = false,
    // True from the moment the user taps "stop" until the recorded clip is fully finalized/saved
    // and reflected as the latest thumbnail - drives the "Saving your video..." loading overlay.
    val isSavingVideo: Boolean = false,
    val stampSettings: StampSettings = StampSettings(),
    val stampData: StampData? = null,
    val cameraCapabilities: CameraCapabilities = CameraCapabilities(),
    val hasCameraPermission: Boolean = false,
    val hasAudioPermission: Boolean = false,
    val permissionsRequested: Boolean = false,
    val latestThumbnailUri: Uri? = null,
    val latestThumbnailIsVideo: Boolean = false,
    val resolutionStatusLabel: String = "",
    // True from screen-open (or a fresh location-permission grant) until the first real GPS
    // fix for the stamp resolves - drives the shimmer placeholder instead of showing stale
    // fallback data during that window.
    val isFetchingLocation: Boolean = true
)
