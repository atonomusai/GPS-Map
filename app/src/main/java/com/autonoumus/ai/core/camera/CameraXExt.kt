package com.autonoumus.ai.core.camera

import android.content.Context
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared MediaStore subfolder ("Pictures/GPS Camera", "Movies/GPS Camera") used by both capture and query paths. */
object CameraStorageConstants {
    const val APP_FOLDER_NAME = "GPS Camera"
}

suspend fun Context.getCameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
    try {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    if (continuation.isActive) {
                        continuation.resume(future.get())
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    } catch (e: Exception) {
        if (continuation.isActive) {
            continuation.resumeWithException(e)
        }
    }
}
