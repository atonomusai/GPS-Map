package com.autonoumus.ai.core.util

import android.content.Context
import android.net.Uri
import android.util.Size
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options

data class VideoThumbnailModel(val uri: Uri)

/** Lets Coil decode a thumbnail bitmap for a video content Uri (Coil's default fetchers only handle images). */
class VideoThumbnailFetcher(
    private val model: VideoThumbnailModel,
    private val context: Context
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val bitmap = loadVideoThumbnailBitmap(context, model.uri, Size(512, 512))
            ?: throw IllegalStateException("Unable to decode video thumbnail for ${model.uri}")
        return DrawableResult(
            drawable = bitmap.toDrawable(context.resources),
            isSampled = false,
            dataSource = DataSource.DISK
        )
    }

    class Factory : Fetcher.Factory<VideoThumbnailModel> {
        override fun create(data: VideoThumbnailModel, options: Options, imageLoader: ImageLoader): Fetcher {
            return VideoThumbnailFetcher(data, options.context)
        }
    }
}
