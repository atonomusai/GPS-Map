package com.autonoumus.ai.data.repository

import android.content.ContentUris
import android.content.Context
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.autonoumus.ai.core.camera.CameraStorageConstants
import com.autonoumus.ai.core.util.DispatcherProvider
import com.autonoumus.ai.domain.model.RecentCaptureItem
import com.autonoumus.ai.domain.repository.MediaCaptureRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class MediaCaptureRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider
) : MediaCaptureRepository {

    // Simple cache to avoid re-geocoding identical coordinates
    private val geocodeCache = ConcurrentHashMap<String, Pair<String, String>>()

    override suspend fun getRecentCaptures(limit: Int): List<RecentCaptureItem> =
        withContext(dispatcherProvider.io) { queryAll(limit) }

    override suspend fun getAllCaptures(): List<RecentCaptureItem> =
        withContext(dispatcherProvider.io) { queryAll(Int.MAX_VALUE) }

    override suspend fun deleteCaptures(uris: List<Uri>): Boolean = withContext(dispatcherProvider.io) {
        var successCount = 0
        for (uri in uris) {
            try {
                val deleted = context.contentResolver.delete(uri, null, null)
                if (deleted > 0) successCount++
            } catch (e: Exception) {
                // Ignore or handle scoped storage exceptions
            }
        }
        successCount > 0
    }

    override suspend fun getLatestCaptureUri(): Pair<Uri, Boolean>? = withContext(dispatcherProvider.io) {
        val images = queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false)
        val videos = queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true)
        val latest = (images + videos).maxByOrNull { it.dateAddedSeconds }
        latest?.let { Pair(it.uri, it.isVideo) }
    }


    private fun queryAll(limit: Int): List<RecentCaptureItem> {
        val images = queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false)
        val videos = queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true)
        return (images + videos)
            .sortedByDescending { it.dateAddedSeconds }
            .take(limit)
            .map { it.toRecentCaptureItem() }
    }

    private inner class InternalRow(
        val uri: Uri,
        val dateAddedSeconds: Long,
        val isVideo: Boolean,
        val durationMs: Long = 0L
    ) {
        fun toRecentCaptureItem(): RecentCaptureItem {
            val locationInfo = resolveLocation(uri)
            return RecentCaptureItem(
                id = uri.toString(),
                contentUri = uri,
                dateFormatted = formatDate(dateAddedSeconds),
                locationFormatted = locationInfo.formattedCoordinates,
                isVideo = isVideo,
                timestampMillis = dateAddedSeconds * 1000L,
                durationFormatted = if (isVideo) formatDuration(durationMs) else null,
                locationName = locationInfo.locationName,
                cityCountry = locationInfo.cityCountry,
                latitude = locationInfo.latitude,
                longitude = locationInfo.longitude
            )
        }
    }

    private fun queryCollection(collection: Uri, isVideo: Boolean): List<InternalRow> {
        val idColumn = MediaStore.MediaColumns._ID
        val dateColumn = MediaStore.MediaColumns.DATE_ADDED
        val durationColumn = if (isVideo) MediaStore.Video.VideoColumns.DURATION else null
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            MediaStore.MediaColumns.DATA
        }
        val dataColumnLegacy = MediaStore.MediaColumns.DATA
        val nameColumn = MediaStore.MediaColumns.DISPLAY_NAME

        val projectionList = mutableListOf(idColumn, dateColumn, pathColumn, dataColumnLegacy, nameColumn)
        if (durationColumn != null) {
            projectionList.add(durationColumn)
        }
        val projection = projectionList.toTypedArray()

        val folderPatterns = listOf(
            "%GPS Camera%",
            "%GPSCamera%",
            "%GSMCAMER%",
            "%GPS_Camera%",
            "%GSM_Camera%",
            "%GSM Camera%"
        )
        val namePatterns = listOf(
            "IMG_%",
            "VID_%",
            "STAMP_%",
            "GPS_%"
        )

        val selectionClauses = mutableListOf<String>()
        val selectionArgsList = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (pattern in folderPatterns) {
                selectionClauses.add("$pathColumn LIKE ?")
                selectionArgsList.add(pattern)
            }
        }
        for (pattern in folderPatterns) {
            selectionClauses.add("$dataColumnLegacy LIKE ?")
            selectionArgsList.add(pattern)
        }
        for (pattern in namePatterns) {
            selectionClauses.add("$nameColumn LIKE ?")
            selectionArgsList.add(pattern)
        }

        val selection = selectionClauses.joinToString(" OR ")
        val selectionArgs = selectionArgsList.toTypedArray()
        val sortOrder = "$dateColumn DESC"

        val rows = mutableListOf<InternalRow>()
        val seenIds = mutableSetOf<Long>()
        try {
            context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(idColumn)
                val dateIndex = cursor.getColumnIndexOrThrow(dateColumn)
                val durIndex = if (durationColumn != null) cursor.getColumnIndex(durationColumn) else -1
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    if (seenIds.add(id)) {
                        val dateAdded = cursor.getLong(dateIndex)
                        val duration = if (durIndex >= 0 && !cursor.isNull(durIndex)) cursor.getLong(durIndex) else 0L
                        val uri = ContentUris.withAppendedId(collection, id)
                        rows += InternalRow(uri, dateAdded, isVideo, duration)
                    }
                }
            }
        } catch (e: Exception) {
            // Defensive: fallback if query fails
        }
        return rows
    }

    private fun formatDate(epochSeconds: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.US)
        return sdf.format(Date(epochSeconds * 1000L))
    }

    private fun formatDuration(millis: Long): String {
        if (millis <= 0) return "0:00"
        val totalSec = millis / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.US, "%d:%02d", min, sec)
    }

    private data class LocationResult(
        val formattedCoordinates: String,
        val locationName: String,
        val cityCountry: String,
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    private fun resolveLocation(uri: Uri): LocationResult {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLong: DoubleArray? = exif.latLong
                if (latLong != null) {
                    val lat = latLong[0]
                    val lon = latLong[1]
                    val coordStr = formatCoordinates(lat, lon)
                    val (locName, cityCountry) = geocodeCoordinates(lat, lon)
                    LocationResult(coordStr, locName, cityCountry, lat, lon)
                } else {
                    LocationResult("Unknown location", "Capture Location", "Local Album")
                }
            } ?: LocationResult("Unknown location", "Capture Location", "Local Album")
        } catch (e: Exception) {
            LocationResult("Unknown location", "Capture Location", "Local Album")
        }
    }

    private fun geocodeCoordinates(lat: Double, lon: Double): Pair<String, String> {
        val key = String.format(Locale.US, "%.3f,%.3f", lat, lon)
        geocodeCache[key]?.let { return it }

        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(lat, lon, 1)
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 1)
            }
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val locName = addr.featureName
                    ?: addr.subLocality
                    ?: addr.thoroughfare
                    ?: addr.locality
                    ?: formatCoordinates(lat, lon)
                val locality = addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: ""
                val country = addr.countryCode ?: addr.countryName ?: ""
                val cityCountry = when {
                    locality.isNotBlank() && country.isNotBlank() -> "$locality, $country"
                    locality.isNotBlank() -> locality
                    country.isNotBlank() -> country
                    else -> "Location"
                }
                val result = Pair(locName, cityCountry)
                geocodeCache[key] = result
                result
            } else {
                Pair(formatCoordinates(lat, lon), "Location")
            }
        } catch (e: Exception) {
            Pair(formatCoordinates(lat, lon), "Location")
        }
    }

    private fun formatCoordinates(lat: Double, lon: Double): String {
        val latDir = if (lat >= 0) "N" else "S"
        val lonDir = if (lon >= 0) "E" else "W"
        return String.format(Locale.US, "%.4f° %s, %.4f° %s", abs(lat), latDir, abs(lon), lonDir)
    }
}
