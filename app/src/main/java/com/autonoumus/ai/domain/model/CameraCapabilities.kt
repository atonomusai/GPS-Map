package com.autonoumus.ai.domain.model

import androidx.camera.video.Quality

data class VideoQualityOption(
    val quality: Quality,
    val label: String
)

data class PhotoResolutionOption(
    val width: Int,
    val height: Int
) {
    val megapixels: Float get() = (width.toLong() * height.toLong()) / 1_000_000f
}

data class CameraCapabilities(
    val videoQualities: List<VideoQualityOption> = emptyList(),
    val frameRates: List<Int> = emptyList(),
    val photoResolutions: List<PhotoResolutionOption> = emptyList()
)
