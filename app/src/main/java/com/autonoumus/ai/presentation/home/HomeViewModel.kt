package com.autonoumus.ai.presentation.home
 
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.autonoumus.ai.core.base.BaseViewModel
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.domain.repository.HomeRepository
import com.autonoumus.ai.domain.usecase.GetHomeDataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
 
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val homeRepository: HomeRepository,
    private val getHomeDataUseCase: GetHomeDataUseCase,
    private val locationHelper: LocationHelper,
    private val nearbyPlacesRepository: com.autonoumus.ai.domain.repository.NearbyPlacesRepository
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(
        HomeUiState(
            hasLocationPermission = locationHelper.hasLocationPermission(),
            quickTools = homeRepository.getQuickTools()
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var locationUpdatesJob: Job? = null
    private var streetViewJob: Job? = null
    private var nearbyWarmUpDone = false

    init {
        loadHomeData()
        checkLocationPermissionAndUpdate()
    }

    fun checkLocationPermissionAndUpdate() {
        val hasPermission = locationHelper.hasLocationPermission()
        _uiState.update { it.copy(hasLocationPermission = hasPermission) }
        if (hasPermission) {
            startObservingLocationUpdates()
        }
    }

    fun refreshRecentCaptures() {
        viewModelScope.launch {
            val homeData = getHomeDataUseCase()
            _uiState.update {
                it.copy(
                    quickTools = homeData.quickTools,
                    recentCaptures = homeData.recentCaptures
                )
            }
        }
    }

    fun loadHomeData() {
        // Fast, local-only data (quick tools + recent captures) renders immediately instead of
        // waiting on the street view camera fetch below, which needs a live GPS fix plus a
        // network round-trip and can take several seconds.
        refreshRecentCaptures()
        loadStreetViewCameras()
    }

    private fun loadStreetViewCameras() {
        streetViewJob?.cancel()
        streetViewJob = viewModelScope.launch {
            _uiState.update { it.copy(isStreetViewLoading = true) }
            val cameras = homeRepository.getStreetViewCameras()
            _uiState.update { it.copy(streetViewCameras = cameras, isStreetViewLoading = false) }
        }
    }

    fun onLocationPermissionGranted() {
        _uiState.update { it.copy(hasLocationPermission = true) }
        startObservingLocationUpdates()
    }

    private fun startObservingLocationUpdates() {
        if (locationUpdatesJob?.isActive == true) return
        locationUpdatesJob = viewModelScope.launch {
            Log.d("FamousPlaceDebug", "📍 [HomeViewModel] Subscribing to live location updates...")
            locationHelper.observeLocationUpdates().collect { locationData ->
                Log.d("FamousPlaceDebug", "📍 [HomeViewModel] Real location update callback received: ${locationData.cityName} (${locationData.rawLatitude}, ${locationData.rawLongitude}), isLiveFix=${locationData.isLiveGpsFix}")
                _uiState.update { it.copy(locationData = locationData) }
                warmUpNearbyPlaces(locationData.rawLatitude, locationData.rawLongitude)
            }
        }
    }

    /**
     * Loads "All Places within 2 km" into the Nearby cache in the background (once per session,
     * only when nothing is cached for this area), so the Nearby screen opens with results instead
     * of waiting 4-12 s on the public Overpass servers.
     */
    private fun warmUpNearbyPlaces(lat: Double?, lon: Double?) {
        if (nearbyWarmUpDone || lat == null || lon == null || (lat == 0.0 && lon == 0.0)) return
        nearbyWarmUpDone = true
        viewModelScope.launch {
            try {
                val category = com.autonoumus.ai.domain.model.NearbyCategory.ALL
                if (nearbyPlacesRepository.getCachedNearbyPlaces(lat, lon, category, NEARBY_WARMUP_RADIUS, "") == null) {
                    nearbyPlacesRepository.getNearbyPlaces(lat, lon, category, NEARBY_WARMUP_RADIUS, "")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Best effort only; the Nearby screen fetches on its own.
            }
        }
    }

    private companion object {
        /** Nearby screen's default radius. */
        const val NEARBY_WARMUP_RADIUS = 2000
    }

    override fun onCleared() {
        super.onCleared()
        locationUpdatesJob?.cancel()
    }
}
