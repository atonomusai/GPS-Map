package com.autonoumus.ai.domain.model

data class TripRecord(
    val id: String,
    val timestamp: Long,
    val durationSec: Long,
    val distanceMeters: Double,
    val avgSpeedKmh: Float,
    val maxSpeedKmh: Float,
    val startAddress: String,
    val endAddress: String,
    val startLat: Double = 0.0,
    val startLon: Double = 0.0,
    val endLat: Double = 0.0,
    val endLon: Double = 0.0,
    val rating: Int = 5,
    val waypointsJson: String = "",
    val tripSource: String = TRIP_SOURCE_SPEEDOMETER,
    val limitExceededCount: Int = 0,
    val topSpeedLocation: String = "",
    val safePercent: Int = 0,
    val warningPercent: Int = 0,
    val overSpeedPercent: Int = 0,
    val safetyScore: Int = 0
) {
    companion object {
        const val TRIP_SOURCE_SPEEDOMETER = "SPEEDOMETER"
        const val TRIP_SOURCE_ROUTE_FINDER = "ROUTE_FINDER"
    }
}

enum class TripFilter {
    ALL,
    TODAY,
    THIS_WEEK,
    THIS_MONTH
}
