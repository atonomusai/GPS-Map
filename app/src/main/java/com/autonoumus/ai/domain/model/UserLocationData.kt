package com.autonoumus.ai.domain.model

import java.util.Locale

data class UserLocationData(
    val countryName: String = "",
    val cityName: String = "Location Disabled",
    val streetAddress: String = "Location Disabled",
    val coordinatesFormatted: String = "NO GEOTAG",
    val timestampFormatted: String = "",
    val dateFormatted: String = "",
    val timeFormatted: String = "",
    val altitude: String = "--",
    val accuracy: String = "--",
    val rawLatitude: Double? = null,
    val rawLongitude: Double? = null,
    val rawAltitudeMeters: Double? = null,
    val rawAccuracyMeters: Float? = null,
    val rawSpeedMps: Float? = 0f,
    val magneticDeclination: Float = 0f,
    val isLiveGpsFix: Boolean = false
) {
    val isStrongSignal: Boolean
        get() = isLiveGpsFix && (rawAccuracyMeters != null && rawAccuracyMeters <= 35f)

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
