package com.autonoumus.ai.domain.model

import java.util.Locale

/**
 * Frozen, per-shot stamp content. Captured once (fresh GPS + clock read) at the instant of
 * a photo capture or the start of a video recording, then held static for that shot/clip.
 */
data class StampData(
    val titleLine: String,
    val addressLine: String,
    val coordinatesLine: String,
    val timestampLine: String,
    val gpsStatusLine: String,
    val dateLine: String = "",
    val timeLine: String = "",
    val timeZoneLine: String = "",
    val rawLatitude: Double? = null,
    val rawLongitude: Double? = null,
    val rawAltitudeMeters: Double? = null
) {
    val latitudeFormatted: String
        get() = rawLatitude?.let {
            val dir = if (it >= 0) "N" else "S"
            String.format(Locale.US, "%.4f° %s", Math.abs(it), dir)
        } ?: "NO GEOTAG"

    val longitudeFormatted: String
        get() = rawLongitude?.let {
            val dir = if (it >= 0) "E" else "W"
            String.format(Locale.US, "%.4f° %s", Math.abs(it), dir)
        } ?: ""
}
