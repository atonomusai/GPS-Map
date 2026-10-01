package com.autonoumus.ai.domain.model

import android.net.Uri

sealed class CameraCaptureResult {
    data class PhotoSaved(val uri: Uri) : CameraCaptureResult()
    data class VideoSaved(val uri: Uri, val durationMs: Long) : CameraCaptureResult()
    data class Error(val message: String, val throwable: Throwable? = null) : CameraCaptureResult()
}
