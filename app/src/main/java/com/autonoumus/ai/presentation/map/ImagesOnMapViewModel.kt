package com.autonoumus.ai.presentation.map

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.domain.model.RecentCaptureItem
import com.autonoumus.ai.domain.repository.MediaCaptureRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class ImagesOnMapUiState(
    val allCaptures: List<RecentCaptureItem> = emptyList(),
    val filteredCaptures: List<RecentCaptureItem> = emptyList(),
    val selectedCapture: RecentCaptureItem? = null,
    val searchQuery: String = "",
    val userLat: Double = 0.0,
    val userLon: Double = 0.0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class ImagesOnMapViewModel @Inject constructor(
    private val mediaCaptureRepository: MediaCaptureRepository,
    private val locationHelper: LocationHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImagesOnMapUiState())
    val uiState: StateFlow<ImagesOnMapUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun setUserLocation(lat: Double, lon: Double) {
        if (lat == 0.0 && lon == 0.0) return
        val prevLat = _uiState.value.userLat
        val prevLon = _uiState.value.userLon
        _uiState.update { it.copy(userLat = lat, userLon = lon) }
        // Captures without GPS tags are anchored around the user - re-place them once we
        // first learn where the user is.
        if ((prevLat == 0.0 || prevLon == 0.0) && _uiState.value.allCaptures.isNotEmpty()) {
            loadCaptures()
        }
    }

    /** Called from the Activity's onResume, so it also refreshes when returning to the screen. */
    fun loadCaptures() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Only show the spinner on the very first load - on return visits keep showing the
            // existing markers while refreshing silently in the background.
            _uiState.update { it.copy(isLoading = it.allCaptures.isEmpty(), errorMessage = null) }
            try {
                // Instant cached fix only - no 8s fresh-GPS wait and no reverse geocoding here.
                // The Activity's live location stream updates userLat/userLon afterwards.
                val stateLat = _uiState.value.userLat.takeIf { it != 0.0 }
                val stateLon = _uiState.value.userLon.takeIf { it != 0.0 }
                val cached = if (stateLat == null || stateLon == null) {
                    withContext(Dispatchers.IO) { locationHelper.getLastKnownLocation() }
                } else null
                val currentLat = stateLat ?: cached?.latitude
                val currentLon = stateLon ?: cached?.longitude

                val rawCaptures = withContext(Dispatchers.IO) { mediaCaptureRepository.getAllCaptures() }

                val withCoordinates = rawCaptures.mapIndexedNotNull { index, item ->
                    if (item.latitude != null && item.longitude != null && item.latitude != 0.0 && item.longitude != 0.0) {
                        item
                    } else if (currentLat == null || currentLon == null) {
                        // No GPS tag and no known user location - don't invent a position for it.
                        null
                    } else {
                        val offsetLat = ((index % 5) - 2) * 0.0006 + (index * 0.0002)
                        val offsetLon = (((index / 5) % 5) - 2) * 0.0006 - (index * 0.0002)
                        item.copy(
                            latitude = currentLat + offsetLat,
                            longitude = currentLon + offsetLon
                        )
                    }
                }

                val dispersed = disperseOverlappingCaptures(withCoordinates)

                _uiState.update {
                    it.copy(
                        allCaptures = dispersed,
                        filteredCaptures = applyFilter(dispersed, it.searchQuery),
                        selectedCapture = it.selectedCapture
                            ?.let { prev -> dispersed.firstOrNull { d -> d.id == prev.id } }
                            ?: dispersed.firstOrNull(),
                        userLat = currentLat ?: 0.0,
                        userLon = currentLon ?: 0.0,
                        isLoading = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Unable to load captures"
                    )
                }
            }
        }
    }

    private fun disperseOverlappingCaptures(items: List<RecentCaptureItem>): List<RecentCaptureItem> {
        if (items.size <= 1) return items

        val result = mutableListOf<RecentCaptureItem>()
        val clusters = mutableListOf<MutableList<RecentCaptureItem>>()

        for (item in items) {
            val lat = item.latitude ?: 0.0
            val lon = item.longitude ?: 0.0
            val matchingCluster = clusters.firstOrNull { cluster ->
                val first = cluster.first()
                val flat = first.latitude ?: 0.0
                val flon = first.longitude ?: 0.0
                Math.abs(lat - flat) < 0.00020 && Math.abs(lon - flon) < 0.00020
            }
            if (matchingCluster != null) {
                matchingCluster.add(item)
            } else {
                clusters.add(mutableListOf(item))
            }
        }

        for (cluster in clusters) {
            if (cluster.size == 1) {
                result.add(cluster[0])
            } else {
                val centerLat = cluster.mapNotNull { it.latitude }.average()
                val centerLon = cluster.mapNotNull { it.longitude }.average()
                val radius = 0.00030 // ~30 meters dispersion on map
                val n = cluster.size
                cluster.forEachIndexed { i, itm ->
                    val angle = (2.0 * Math.PI * i) / n
                    val dLat = radius * Math.cos(angle)
                    val dLon = radius * Math.sin(angle)
                    result.add(
                        itm.copy(
                            latitude = centerLat + dLat,
                            longitude = centerLon + dLon
                        )
                    )
                }
            }
        }

        return result
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update {
            val filtered = applyFilter(it.allCaptures, query)
            it.copy(
                searchQuery = query,
                filteredCaptures = filtered,
                selectedCapture = if (filtered.isNotEmpty()) filtered.first() else null
            )
        }
    }

    fun selectCapture(item: RecentCaptureItem?) {
        _uiState.update { it.copy(selectedCapture = item) }
    }

    private fun applyFilter(list: List<RecentCaptureItem>, query: String): List<RecentCaptureItem> {
        val q = query.trim().lowercase(Locale.US)
        if (q.isEmpty()) return list

        return list.filter { item ->
            item.locationName?.lowercase(Locale.US)?.contains(q) == true ||
                    item.cityCountry?.lowercase(Locale.US)?.contains(q) == true ||
                    item.locationFormatted.lowercase(Locale.US).contains(q) ||
                    item.dateFormatted.lowercase(Locale.US).contains(q)
        }
    }
}
