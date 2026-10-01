package com.autonoumus.ai.domain.usecase

import com.autonoumus.ai.domain.model.StampData
import com.autonoumus.ai.domain.model.StampSettings
import com.autonoumus.ai.domain.model.UserLocationData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * Composes the frozen, per-shot [StampData] from a location fix and the current [StampSettings].
 * Called once at the instant a photo is captured or a video recording starts - never re-invoked
 * mid-shot, so the burned-in stamp reflects a single, stable moment.
 */
class BuildStampDataUseCase @Inject constructor() {

    operator fun invoke(location: UserLocationData, settings: StampSettings): StampData {
        val isLocationDisabled = !location.isLiveGpsFix && location.rawLatitude == null

        val city = if (isLocationDisabled) "Location Disabled" else location.cityName.ifBlank { "Current Location" }
        val street = if (isLocationDisabled) "Location Disabled" else location.streetAddress.ifBlank { "Current Location" }
        val now = Date()
        val tz = TimeZone.getDefault()

        val dateSdf = SimpleDateFormat("dd MMM yyyy", Locale.US).apply { timeZone = tz }
        val timeSdf = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }
        val tzSdf = SimpleDateFormat("z", Locale.US).apply { timeZone = tz }

        val dateFormatted = dateSdf.format(now)
        val timeFormatted = timeSdf.format(now)
        val tzFormatted = tzSdf.format(now)

        val coordsLine = if (isLocationDisabled) {
            "NO GEOTAG"
        } else {
            buildCoordinatesLine(location)
        }

        val gpsStatus = if (isLocationDisabled) {
            "NO GEOTAG"
        } else {
            buildGpsStatusLine(location)
        }

        val timestampLine = if (isLocationDisabled) {
            "$dateFormatted • $timeFormatted"
        } else {
            buildTimestampLine(settings.showTimeZone)
        }

        return StampData(
            titleLine = city,
            addressLine = street,
            coordinatesLine = coordsLine,
            timestampLine = timestampLine,
            gpsStatusLine = gpsStatus,
            dateLine = dateFormatted,
            timeLine = timeFormatted,
            timeZoneLine = tzFormatted,
            rawLatitude = location.rawLatitude,
            rawLongitude = location.rawLongitude,
            rawAltitudeMeters = location.rawAltitudeMeters
        )
    }

    private fun buildCoordinatesLine(location: UserLocationData): String {
        if (location.rawLatitude == null || location.rawLongitude == null) {
            return "NO GEOTAG"
        }
        val baseCoords = if (location.coordinatesFormatted.isNotBlank() && location.coordinatesFormatted != "NO GEOTAG") {
            location.coordinatesFormatted
        } else {
            val latDir = if (location.rawLatitude >= 0) "N" else "S"
            val lonDir = if (location.rawLongitude >= 0) "E" else "W"
            String.format(Locale.US, "%.4f° %s, %.4f° %s", Math.abs(location.rawLatitude), latDir, Math.abs(location.rawLongitude), lonDir)
        }
        val altStr = if (location.rawAltitudeMeters != null) {
            " • Alt: ${location.rawAltitudeMeters.toInt()}m"
        } else if (location.altitude.isNotBlank() && location.altitude != "--") {
            " • Alt: ${location.altitude.replace(" ", "")}"
        } else {
            ""
        }
        return "$baseCoords$altStr"
    }

    private fun buildGpsStatusLine(location: UserLocationData): String {
        if (location.accuracy == com.autonoumus.ai.core.util.LocationHelper.CUSTOM_LOCATION_ACCURACY) return "CUSTOM LOCATION"
        if (location.rawLatitude == null) return "NO GEOTAG"
        val accuracy = location.rawAccuracyMeters
        return when {
            accuracy != null -> String.format(Locale.US, "GPS LOCKED (±%.1fM)", accuracy)
            else -> "GPS LOCKED"
        }
    }

    private fun buildTimestampLine(showTimeZone: Boolean): String {
        val pattern = if (showTimeZone) "dd MMM yyyy • HH:mm z" else "dd MMM yyyy • HH:mm"
        val tz = TimeZone.getDefault()
        val sdf = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = tz
        }
        return sdf.format(Date())
    }
}
