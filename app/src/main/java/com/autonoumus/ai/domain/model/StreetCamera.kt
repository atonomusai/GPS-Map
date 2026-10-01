package com.autonoumus.ai.domain.model

data class StreetCamera(
    val id: String,
    val title: String,
    val city: String = "",
    val country: String = "",
    val countryCode: String = "",
    val imageUrl: String? = null,
    val thumbnailUrl: String? = null,
    val categories: List<String> = emptyList(),
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val playerUrl: String? = null,
    val lastUpdatedEpochMillis: Long = 0L
) {
    fun getLocationSubtitle(): String {
        return listOf(city, country).filter { it.isNotBlank() }.joinToString(", ")
    }

    /** Human-readable freshness of the current still frame, e.g. "Updated 12m ago". */
    fun getUpdatedAgoLabel(): String? {
        if (lastUpdatedEpochMillis <= 0L) return null
        val diffMinutes = (System.currentTimeMillis() - lastUpdatedEpochMillis) / 60_000L
        return when {
            diffMinutes < 1 -> "Updated just now"
            diffMinutes < 60 -> "Updated ${diffMinutes}m ago"
            else -> "Updated ${diffMinutes / 60}h ago"
        }
    }
}
