package com.autonoumus.ai.domain.repository

import com.autonoumus.ai.domain.model.TripFilter
import com.autonoumus.ai.domain.model.TripRecord
import kotlinx.coroutines.flow.Flow

interface TripHistoryRepository {
    fun getAllTrips(): Flow<List<TripRecord>>
    fun getTripsByFilter(filter: TripFilter): Flow<List<TripRecord>>
    suspend fun saveTrip(trip: TripRecord)
    suspend fun deleteTrip(tripId: String)
    suspend fun clearHistory()
}
