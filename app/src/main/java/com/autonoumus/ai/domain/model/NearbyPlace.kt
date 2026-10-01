package com.autonoumus.ai.domain.model

enum class NearbyCategory(
    val id: String,
    val title: String,
    val emoji: String,
    /** Solid colour used for the map pin of this category. */
    val pinColor: String,
    /** Soft colour used for the badge behind the emoji in list rows. */
    val badgeColor: String
) {
    ALL("all", "All Places", "📍", "#4F46E5", "#F1F5F9"),
    HOSPITALS("hospital", "Hospitals", "🏥", "#EF4444", "#FEE2E2"),
    PHARMACY("pharmacy", "Pharmacies", "💊", "#10B981", "#DCFCE7"),
    RESTAURANTS("restaurant", "Restaurants", "🍽️", "#F97316", "#FFEDD5"),
    CAFES("cafe", "Cafes", "☕", "#D97706", "#FEF3C7"),
    FUEL("fuel", "Fuel / Gas", "⛽", "#F59E0B", "#FEF3C7"),
    EV_CHARGING("ev_charging", "EV Charging", "🔌", "#14B8A6", "#CCFBF1"),
    HOTELS("hotel", "Hotels", "🏨", "#6366F1", "#E0E7FF"),
    EDUCATION("education", "Colleges / Schools", "🏫", "#8B5CF6", "#F3E8FF"),
    BANKS("bank", "Banks / ATMs", "🏦", "#3B82F6", "#DBEAFE"),
    GROCERY("grocery", "Groceries", "🥬", "#84CC16", "#ECFCCB"),
    SHOPPING("shop", "Shopping", "🛒", "#EC4899", "#FCE7F3"),
    TOURISM("tourism", "Attractions", "🏛️", "#F59E0B", "#FEF3C7"),
    PARKS("park", "Parks", "🌳", "#22C55E", "#DCFCE7"),
    FITNESS("fitness", "Gyms / Sports", "🏋️", "#F43F5E", "#FFE4E6"),
    ENTERTAINMENT("entertainment", "Entertainment", "🎬", "#A855F7", "#F3E8FF"),
    MOSQUE("place_of_worship", "Worship", "🕌", "#06B6D4", "#E0F2FE"),
    POLICE("police", "Police / Fire", "🚓", "#1D4ED8", "#DBEAFE"),
    TRANSPORT("transport", "Transport", "🚌", "#0EA5E9", "#E0F2FE"),
    PARKING("parking", "Parking", "🅿️", "#64748B", "#E2E8F0"),
    CAR_SERVICES("car_services", "Car Services", "🔧", "#78716C", "#F5F5F4")
}

enum class NearbySort(val title: String) {
    NEAREST("Nearest"),
    NAME("Name A–Z"),
    OPEN_FIRST("Open first")
}

data class NearbyPlace(
    val id: String,
    val name: String,
    val categoryName: String,
    val categoryType: NearbyCategory,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double,
    val address: String,
    val street: String = "",
    val city: String = "",
    val phone: String? = null,
    val website: String? = null,
    val openingHours: String? = null,
    val imageUrl: String? = null,
    val rating: Float = 4.5f,
    val reviewCount: Int = 85,
    /** true/false when the place's opening_hours could be evaluated, null when unknown. */
    val isOpen: Boolean? = null,
    val tags: Map<String, String> = emptyMap()
) {
    fun getFormattedDistance(): String {
        return if (distanceMeters < 1000) {
            "${distanceMeters.toInt()} m"
        } else {
            String.format(java.util.Locale.US, "%.1f km", distanceMeters / 1000.0)
        }
    }

    fun getCategoryEmoji(): String = categoryType.emoji
}

/** Places served from the local cache, and how old that data is. */
data class CachedNearbyPlaces(
    val places: List<NearbyPlace>,
    val ageMs: Long
)
