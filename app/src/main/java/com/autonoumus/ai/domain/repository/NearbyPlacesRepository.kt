package com.autonoumus.ai.domain.repository

import com.autonoumus.ai.domain.model.CachedNearbyPlaces
import com.autonoumus.ai.domain.model.NearbyCategory
import com.autonoumus.ai.domain.model.NearbyPlace
import kotlinx.coroutines.flow.Flow

interface NearbyPlacesRepository {
    /** Always fetches from the network (and refreshes the cache). */
    suspend fun getNearbyPlaces(
        latitude: Double,
        longitude: Double,
        category: NearbyCategory = NearbyCategory.ALL,
        radiusMeters: Int = 3000,
        searchQuery: String = ""
    ): List<NearbyPlace>

    /** Instant, network-free result from the local cache for this search, or null if none is usable. */
    suspend fun getCachedNearbyPlaces(
        latitude: Double,
        longitude: Double,
        category: NearbyCategory = NearbyCategory.ALL,
        radiusMeters: Int = 3000,
        searchQuery: String = ""
    ): CachedNearbyPlaces?
}
