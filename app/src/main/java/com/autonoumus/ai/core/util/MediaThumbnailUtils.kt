package com.autonoumus.ai.core.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun loadVideoThumbnailBitmap(context: Context, uri: Uri, size: Size): Bitmap? =
    withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return@withContext context.contentResolver.loadThumbnail(uri, size, null)
            } catch (e: Exception) {
                // Fall through to the legacy path below.
            }
        }
        legacyVideoThumbnail(context, uri)
    }

private fun legacyVideoThumbnail(context: Context, uri: Uri): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.frameAtTime
    } catch (e: Exception) {
        null
    } finally {
        try {
            retriever.release()
        } catch (e: Exception) {
            // Ignore - already released or never opened.
        }
    }
}
