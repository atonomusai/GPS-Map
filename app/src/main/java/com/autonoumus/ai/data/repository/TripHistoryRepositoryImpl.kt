package com.autonoumus.ai.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.autonoumus.ai.domain.model.TripFilter
import com.autonoumus.ai.domain.model.TripRecord
import com.autonoumus.ai.domain.repository.TripHistoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TripHistoryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : TripHistoryRepository {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val tripsState = MutableStateFlow<List<TripRecord>>(emptyList())

    // App-lifetime scope for this singleton's own async init - loading/parsing the whole trip
    // history (including every trip's waypoints JSON) used to happen synchronously in init{},
    // which blocked whichever thread first constructed this Hilt singleton (typically the main
    // thread, during a ViewModel/Activity's own onCreate).
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()

    init {
        repositoryScope.launch {
            tripsState.value = loadTripsFromStorage()
        }
    }

    override fun getAllTrips(): Flow<List<TripRecord>> = tripsState

    override fun getTripsByFilter(filter: TripFilter): Flow<List<TripRecord>> {
        return tripsState.map { list ->
            when (filter) {
                TripFilter.ALL -> list
                TripFilter.TODAY -> {
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    val todayStartMs = cal.timeInMillis
                    list.filter { it.timestamp >= todayStartMs }
                }
                TripFilter.THIS_WEEK -> {
                    val oneWeekAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
                    list.filter { it.timestamp >= oneWeekAgo }
                }
                TripFilter.THIS_MONTH -> {
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.DAY_OF_MONTH, 1)
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    val monthStartMs = cal.timeInMillis
                    list.filter { it.timestamp >= monthStartMs }
                }
            }
        }
    }

    override suspend fun saveTrip(trip: TripRecord) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val current = tripsState.value.toMutableList()
            // Insert at beginning (newest first)
            val existingIndex = current.indexOfFirst { it.id == trip.id }
            if (existingIndex >= 0) {
                current[existingIndex] = trip
            } else {
                current.add(0, trip)
            }
            persistTrips(current)
            tripsState.value = current
        }
    }

    override suspend fun deleteTrip(tripId: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val current = tripsState.value.filter { it.id != tripId }
            persistTrips(current)
            tripsState.value = current
        }
    }

    override suspend fun clearHistory() = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            persistTrips(emptyList())
            tripsState.value = emptyList()
        }
    }

    private fun loadTripsFromStorage(): List<TripRecord> {
        val jsonStr = prefs.getString(KEY_TRIPS_JSON, null) ?: return emptyList()
        val list = mutableListOf<TripRecord>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    TripRecord(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        durationSec = obj.optLong("durationSec", 0L),
                        distanceMeters = obj.optDouble("distanceMeters", 0.0),
                        avgSpeedKmh = obj.optDouble("avgSpeedKmh", 0.0).toFloat(),
                        maxSpeedKmh = obj.optDouble("maxSpeedKmh", 0.0).toFloat(),
                        startAddress = obj.optString("startAddress", "Start Location"),
                        endAddress = obj.optString("endAddress", "Destination"),
                        startLat = obj.optDouble("startLat", 0.0),
                        startLon = obj.optDouble("startLon", 0.0),
                        endLat = obj.optDouble("endLat", 0.0),
                        endLon = obj.optDouble("endLon", 0.0),
                        rating = obj.optInt("rating", 5),
                        waypointsJson = obj.optString("waypointsJson", ""),
                        tripSource = obj.optString("tripSource", TripRecord.TRIP_SOURCE_SPEEDOMETER),
                        limitExceededCount = obj.optInt("limitExceededCount", 0),
                        topSpeedLocation = obj.optString("topSpeedLocation", ""),
                        safePercent = obj.optInt("safePercent", 0),
                        warningPercent = obj.optInt("warningPercent", 0),
                        overSpeedPercent = obj.optInt("overSpeedPercent", 0),
                        safetyScore = obj.optInt("safetyScore", 0)
                    )
                )
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return list
    }

    private fun persistTrips(list: List<TripRecord>) {
        try {
            val array = JSONArray()
            for (trip in list) {
                val obj = JSONObject().apply {
                    put("id", trip.id)
                    put("timestamp", trip.timestamp)
                    put("durationSec", trip.durationSec)
                    put("distanceMeters", trip.distanceMeters)
                    put("avgSpeedKmh", trip.avgSpeedKmh.toDouble())
                    put("maxSpeedKmh", trip.maxSpeedKmh.toDouble())
                    put("startAddress", trip.startAddress)
                    put("endAddress", trip.endAddress)
                    put("startLat", trip.startLat)
                    put("startLon", trip.startLon)
                    put("endLat", trip.endLat)
                    put("endLon", trip.endLon)
                    put("rating", trip.rating)
                    put("waypointsJson", trip.waypointsJson)
                    put("tripSource", trip.tripSource)
                    put("limitExceededCount", trip.limitExceededCount)
                    put("topSpeedLocation", trip.topSpeedLocation)
                    put("safePercent", trip.safePercent)
                    put("warningPercent", trip.warningPercent)
                    put("overSpeedPercent", trip.overSpeedPercent)
                    put("safetyScore", trip.safetyScore)
                }
                array.put(obj)
            }
            prefs.edit().putString(KEY_TRIPS_JSON, array.toString()).apply()
        } catch (e: Exception) {}
    }

    companion object {
        private const val PREF_NAME = "gsm_trip_history_prefs"
        private const val KEY_TRIPS_JSON = "key_trips_json"
    }
}
