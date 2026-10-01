package com.autonoumus.ai.core.util

import org.osmdroid.util.GeoPoint
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class AreaUnit(val symbol: String, val displayName: String, val factorFromSqMeters: Double) {
    SQ_METERS("m²", "Square Meters (m²)", 1.0),
    SQ_KILOMETERS("km²", "Square Kilometers (km²)", 1e-6),
    SQ_FEET("ft²", "Square Feet (ft²)", 10.7639104),
    ACRES("Acres", "Acres", 0.000247105),
    HECTARES("Hectares", "Hectares", 0.0001),
    SQ_YARDS("sq yd", "Square Yards (sq yd)", 1.19599005)
}

object AreaCalculator {

    private const val EARTH_RADIUS_METERS = 6378137.0 // WGS-84 equatorial radius

    /**
     * Calculates the spherical geodesic area of a polygon on Earth's surface (in square meters).
     * Uses the standard spherical excess / trapezoid algorithm.
     */
    fun calculatePolygonArea(points: List<GeoPoint>): Double {
        if (points.size < 3) return 0.0

        var total = 0.0
        val size = points.size

        for (i in 0 until size) {
            val p1 = points[i]
            val p2 = points[(i + 1) % size]

            val lat1 = Math.toRadians(p1.latitude)
            val lat2 = Math.toRadians(p2.latitude)
            val lonDelta = Math.toRadians(p2.longitude - p1.longitude)

            total += (lonDelta * (2.0 + sin(lat1) + sin(lat2)))
        }

        val areaM2 = abs(total * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS / 2.0)
        return areaM2
    }

    /**
     * Calculates the total perimeter of a polygon in meters (Haversine formula sum of all edges).
     */
    fun calculatePerimeter(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0

        var perimeter = 0.0
        val size = points.size

        for (i in 0 until size) {
            val p1 = points[i]
            val p2 = points[(i + 1) % size]
            perimeter += haversineDistanceMeters(p1, p2)
        }

        return perimeter
    }

    /**
     * Great-circle distance between two GeoPoints in meters.
     */
    fun haversineDistanceMeters(p1: GeoPoint, p2: GeoPoint): Double {
        val lat1 = Math.toRadians(p1.latitude)
        val lon1 = Math.toRadians(p1.longitude)
        val lat2 = Math.toRadians(p2.latitude)
        val lon2 = Math.toRadians(p2.longitude)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        val a = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_METERS * c
    }

    /**
     * Formats area according to unit with thousands separator and proper decimal rounding.
     */
    fun formatArea(areaSqMeters: Double, unit: AreaUnit): String {
        val converted = areaSqMeters * unit.factorFromSqMeters
        val formatter = DecimalFormat("#,##0.0#", DecimalFormatSymbols(Locale.US))
        return "${formatter.format(converted)} ${unit.symbol}"
    }

    /**
     * Formats perimeter (in meters or km).
     */
    fun formatPerimeter(perimeterMeters: Double): String {
        val formatter = DecimalFormat("#,##0.0#", DecimalFormatSymbols(Locale.US))
        return if (perimeterMeters >= 1000.0) {
            "${formatter.format(perimeterMeters / 1000.0)} km"
        } else {
            "${formatter.format(perimeterMeters)} m"
        }
    }

    /**
     * Converts Decimal Degrees to DMS (Degrees Minutes Seconds) string format.
     */
    fun convertDecimalToDms(latitude: Double, longitude: Double): Pair<String, String> {
        fun toDms(value: Double, isLat: Boolean): String {
            val direction = if (isLat) {
                if (value >= 0) "N" else "S"
            } else {
                if (value >= 0) "E" else "W"
            }
            val absVal = abs(value)
            val degrees = absVal.toInt()
            val minutesDecimal = (absVal - degrees) * 60.0
            val minutes = minutesDecimal.toInt()
            val seconds = (minutesDecimal - minutes) * 60.0

            return String.format(Locale.US, "%d°%02d'%04.1f\"%s", degrees, minutes, seconds, direction)
        }

        return Pair(toDms(latitude, true), toDms(longitude, false))
    }
}
