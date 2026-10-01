package com.autonoumus.ai.presentation.streetcamera

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autonoumus.ai.BuildConfig
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.domain.model.StreetCamera
import com.autonoumus.ai.domain.repository.StreetCameraRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class StreetCameraViewModel @Inject constructor(
    private val streetCameraRepository: StreetCameraRepository,
    private val locationHelper: LocationHelper
) : ViewModel() {

    companion object {
        // Matches HomeRepositoryImpl.NEARBY_RADIUS_KM so "View All" queries the same window
        // of cameras (same params -> same server-side nearby ordering) that the home screen's
        // preview strip already fetched, instead of a completely different unfiltered set.
        private const val NEARBY_RADIUS_KM = 100
    }

    private val allCategories = StreetCameraDataProvider.getPlaceCategories()
    private val allCountries = StreetCameraDataProvider.getCountries()

    private val _uiState = MutableStateFlow(
        StreetCameraUiState(
            placeCategories = allCategories,
            filteredPlaceCategories = allCategories,
            countries = allCountries,
            liveCameras = streetCameraRepository.getCachedLiveCameras()
                ?: StreetCameraDataProvider.getFallbackLiveStreetCameras()
        )
    )
    val uiState: StateFlow<StreetCameraUiState> = _uiState.asStateFlow()

    private var fetchJob: Job? = null

    init {
        loadLiveCameras()
    }

    fun selectTab(tab: StreetCameraTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun updatePlacesSearch(query: String) {
        val trimmed = query.trim()
        val lower = trimmed.lowercase(Locale.getDefault())

        if (lower.isEmpty()) {
            _uiState.update {
                it.copy(
                    placesSearchQuery = "",
                    filteredPlaceCategories = allCategories,
                    isPlacesSearchEmpty = false
                )
            }
            return
        }

        val filtered = allCategories.filter {
            it.title.lowercase(Locale.getDefault()).contains(lower)
        }

        _uiState.update {
            it.copy(
                placesSearchQuery = trimmed,
                filteredPlaceCategories = filtered,
                isPlacesSearchEmpty = filtered.isEmpty()
            )
        }
    }

    fun clearPlacesSearch() {
        updatePlacesSearch("")
    }

    fun toggleFavorite(camera: StreetCamera, isFav: Boolean) {
        _uiState.update { current ->
            val updatedFavorites = current.favoriteCameraIds.toMutableSet()
            if (isFav) {
                updatedFavorites.add(camera.id)
            } else {
                updatedFavorites.remove(camera.id)
            }
            current.copy(favoriteCameraIds = updatedFavorites)
        }
    }

    fun retry() = loadLiveCameras()

    private fun loadLiveCameras() {
        val cached = streetCameraRepository.getCachedLiveCameras()
        if (!cached.isNullOrEmpty()) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    liveCameras = cached,
                    errorMessage = null
                )
            }
            return
        }

        val fallbackCameras = StreetCameraDataProvider.getFallbackLiveStreetCameras()

        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            if (_uiState.value.liveCameras.isEmpty()) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            }

            // Same nearby-location bias as the home screen's preview strip, so "View All" reads
            // as a continuation of the same list rather than a differently-ordered fetch.
            val userLocation = locationHelper.getRealLiveLocationData()
            val userLat = userLocation.rawLatitude
            val userLon = userLocation.rawLongitude

            val result = streetCameraRepository.getWebcams(
                countryCode = null,
                category = null,
                offset = 0,
                limit = 30,
                nearbyLatitude = userLat,
                nearbyLongitude = userLon,
                radiusKm = if (userLat != null && userLon != null) NEARBY_RADIUS_KM else null
            )

            result.onSuccess { cameras ->
                val sorted = if (userLat != null && userLon != null) {
                    cameras.sortedBy { camera -> distanceMeters(userLat, userLon, camera) }
                } else {
                    cameras
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        liveCameras = if (sorted.isNotEmpty()) sorted else fallbackCameras,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        liveCameras = fallbackCameras,
                        errorMessage = null
                    )
                }
            }
        }
    }

    private fun distanceMeters(userLat: Double, userLon: Double, camera: StreetCamera): Float {
        val result = FloatArray(1)
        Location.distanceBetween(userLat, userLon, camera.latitude, camera.longitude, result)
        return result[0]
    }
}
