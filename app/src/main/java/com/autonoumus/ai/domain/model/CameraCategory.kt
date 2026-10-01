package com.autonoumus.ai.domain.model

/**
 * windySlug values are verified against the live Windy Webcams API v3
 * categories endpoint (https://api.windy.com/webcams/api/v3/categories) —
 * only a curated travel-relevant subset is exposed here.
 */
enum class CameraCategory(
    val id: String,
    val title: String,
    val emoji: String,
    val windySlug: String?
) {
    ALL("all", "All", "🌍", null),
    CITY("city", "City", "🏙️", "city"),
    BEACH("beach", "Beach", "🏖️", "beach"),
    MOUNTAIN("mountain", "Mountain", "⛰️", "mountain"),
    COAST("coast", "Coast", "🌊", "coast"),
    FOREST("forest", "Nature", "🌲", "forest"),
    LAKE("lake", "Lake", "🏞️", "lake"),
    TRAFFIC("traffic", "Traffic", "🚦", "traffic"),
    AIRPORT("airport", "Airport", "✈️", "airport"),
    VILLAGE("village", "Village", "🏘️", "village")
}
