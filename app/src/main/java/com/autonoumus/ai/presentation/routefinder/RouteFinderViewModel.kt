package com.autonoumus.ai.presentation.routefinder

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.core.util.OsmGeocodingHelper
import com.autonoumus.ai.core.util.OsmRoutingHelper
import com.autonoumus.ai.core.util.OsmSearchResult
import com.autonoumus.ai.core.util.RouteResult
import com.autonoumus.ai.core.util.TravelMode
import com.autonoumus.ai.domain.model.TripRecord
import com.autonoumus.ai.domain.repository.TripHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class SearchTarget {
    NONE, START, DESTINATION
}

data class RouteFinderUiState(
    val userGpsLat: Double = 0.0,
    val userGpsLon: Double = 0.0,
    val userCityName: String = "",
    val startName: String = "Current Location",
    val startLat: Double = 0.0,
    val startLon: Double = 0.0,
    val destName: String = "",
    val destLat: Double = 0.0,
    val destLon: Double = 0.0,
    val selectedTravelMode: TravelMode = TravelMode.CAR,
    val activeSearchTarget: SearchTarget = SearchTarget.NONE,
    val searchSuggestions: List<OsmSearchResult> = emptyList(),
    val isSearchingSuggestions: Boolean = false,
    val routes: List<RouteResult> = emptyList(),
    val primaryRoute: RouteResult? = null,
    val alternateRoute: RouteResult? = null,
    val selectedRouteIndex: Int = 0,
    val isLoadingRoute: Boolean = false,
    val errorMessage: String? = null
) {
    val activeRoute: RouteResult?
        get() = routes.getOrNull(selectedRouteIndex) ?: primaryRoute

    val hasRoute: Boolean
        get() = activeRoute != null

    val isReadyToRoute: Boolean
        get() = startLat != 0.0 && startLon != 0.0 && destLat != 0.0 && destLon != 0.0
}

@HiltViewModel
class RouteFinderViewModel @Inject constructor(
    private val locationHelper: LocationHelper,
    private val tripHistoryRepository: TripHistoryRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(RouteFinderUiState())
    val uiState: StateFlow<RouteFinderUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var routingJob: Job? = null

    private var hasInitializedGpsStart: Boolean = false

    fun setUserGpsLocation(lat: Double, lon: Double, rawCityName: String?) {
        val isFirst = !hasInitializedGpsStart && _uiState.value.startLat == 0.0 && _uiState.value.startLon == 0.0
        if (isFirst) {
            hasInitializedGpsStart = true
        }

        // Callers may pass an already formatted "Current Location (City)" label (the Home screen
        // does); unwrap it so the field doesn't read "Current Location (Current Location (City))".
        val cityName = rawCityName
            ?.removePrefix("Current Location (")?.removeSuffix(")")
            ?.takeUnless { it.equals("GPS", ignoreCase = true) }
        val cityLabel = if (!cityName.isNullOrBlank()) "Current Location ($cityName)" else "Current Location (GPS)"

        _uiState.update {
            it.copy(
                userGpsLat = lat,
                userGpsLon = lon,
                userCityName = cityName.orEmpty(),
                startName = if (isFirst) cityLabel else it.startName,
                startLat = if (isFirst) lat else it.startLat,
                startLon = if (isFirst) lon else it.startLon
            )
        }
    }

    fun onStartQueryChanged(query: String) {
        val trimmed = query.trim()
        _uiState.update { it.copy(startName = query, activeSearchTarget = SearchTarget.START) }
        performSearch(trimmed, isStart = true)
    }

    fun onDestQueryChanged(query: String) {
        val trimmed = query.trim()
        _uiState.update { it.copy(destName = query, activeSearchTarget = SearchTarget.DESTINATION) }
        performSearch(trimmed, isStart = false)
    }

    private fun performSearch(query: String, isStart: Boolean) {
        searchJob?.cancel()
        if (query.length < 2) {
            _uiState.update { it.copy(searchSuggestions = emptyList(), isSearchingSuggestions = false) }
            return
        }

        searchJob = viewModelScope.launch {
            delay(280) // Debounce typing
            _uiState.update { it.copy(isSearchingSuggestions = true) }
            try {
                val state = _uiState.value
                val biasLat = if (state.userGpsLat != 0.0) state.userGpsLat else null
                val biasLon = if (state.userGpsLon != 0.0) state.userGpsLon else null

                val results = OsmGeocodingHelper.searchLocations(
                    query = query,
                    context = context,
                    userLat = biasLat,
                    userLon = biasLon
                )

                _uiState.update {
                    it.copy(
                        searchSuggestions = results,
                        isSearchingSuggestions = false
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                _uiState.update { it.copy(isSearchingSuggestions = false) }
            }
        }
    }

    fun selectSuggestion(item: OsmSearchResult, targetOverride: SearchTarget? = null) {
        searchJob?.cancel()
        val target = targetOverride ?: _uiState.value.activeSearchTarget

        if (target == SearchTarget.START) {
            _uiState.update {
                it.copy(
                    startName = item.placeName,
                    startLat = item.latitude,
                    startLon = item.longitude,
                    activeSearchTarget = SearchTarget.NONE,
                    searchSuggestions = emptyList(),
                    isSearchingSuggestions = false
                )
            }
        } else {
            val currentStartLat = _uiState.value.startLat
            val currentStartLon = _uiState.value.startLon
            val userLat = _uiState.value.userGpsLat
            val userLon = _uiState.value.userGpsLon
            val userCity = _uiState.value.userCityName

            val effectiveStartLat = if (currentStartLat != 0.0) currentStartLat else userLat
            val effectiveStartLon = if (currentStartLon != 0.0) currentStartLon else userLon
            val effectiveStartName = if (currentStartLat != 0.0) _uiState.value.startName else {
                if (userCity.isNotBlank()) "Current Location ($userCity)" else "Current Location (GPS)"
            }

            _uiState.update {
                it.copy(
                    destName = item.placeName,
                    destLat = item.latitude,
                    destLon = item.longitude,
                    startLat = effectiveStartLat,
                    startLon = effectiveStartLon,
                    startName = effectiveStartName,
                    activeSearchTarget = SearchTarget.NONE,
                    searchSuggestions = emptyList(),
                    isSearchingSuggestions = false
                )
            }
        }

        if (_uiState.value.isReadyToRoute) {
            calculateRoute()
        }
    }

    fun closeSuggestions() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                activeSearchTarget = SearchTarget.NONE,
                searchSuggestions = emptyList(),
                isSearchingSuggestions = false
            )
        }
    }

    fun swapLocations(currentStartText: String? = null, currentDestText: String? = null) {
        searchJob?.cancel()
        hasInitializedGpsStart = true
        val state = _uiState.value

        val effectiveStartName = currentStartText?.takeIf { it.isNotBlank() } ?: state.startName
        val effectiveDestName = currentDestText?.takeIf { it.isNotBlank() } ?: state.destName

        val newStartName = effectiveDestName
        val newStartLat = state.destLat
        val newStartLon = state.destLon

        val newDestName = effectiveStartName
        val newDestLat = state.startLat
        val newDestLon = state.startLon

        val reversedPrimaryRoute = state.primaryRoute?.let { r ->
            r.copy(points = r.points.reversed())
        }
        val reversedAlternateRoute = state.alternateRoute?.let { r ->
            r.copy(points = r.points.reversed())
        }

        _uiState.update {
            it.copy(
                startName = newStartName,
                startLat = newStartLat,
                startLon = newStartLon,
                destName = newDestName,
                destLat = newDestLat,
                destLon = newDestLon,
                activeSearchTarget = SearchTarget.NONE,
                searchSuggestions = emptyList(),
                isSearchingSuggestions = false,
                primaryRoute = reversedPrimaryRoute,
                alternateRoute = reversedAlternateRoute
            )
        }

        if (_uiState.value.isReadyToRoute) {
            calculateRoute()
        } else {
            _uiState.update {
                it.copy(
                    primaryRoute = null,
                    alternateRoute = null,
                    isLoadingRoute = false
                )
            }
        }
    }

    fun setTravelMode(mode: TravelMode) {
        if (_uiState.value.selectedTravelMode == mode) return
        _uiState.update { it.copy(selectedTravelMode = mode) }
        if (_uiState.value.isReadyToRoute) {
            calculateRoute()
        }
    }

    fun selectRouteOption(index: Int) {
        _uiState.update { it.copy(selectedRouteIndex = index) }
    }

    fun calculateRoute() {
        val state = _uiState.value
        if (!state.isReadyToRoute) return

        val sLat = state.startLat
        val sLon = state.startLon
        val dLat = state.destLat
        val dLon = state.destLon
        val mode = state.selectedTravelMode

        routingJob?.cancel()
        routingJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingRoute = true, errorMessage = null) }
            try {
                val routeList = OsmRoutingHelper.getRoutes(
                    startLat = sLat,
                    startLon = sLon,
                    destLat = dLat,
                    destLon = dLon,
                    mode = mode
                )

                _uiState.update {
                    it.copy(
                        routes = routeList,
                        primaryRoute = routeList.firstOrNull(),
                        alternateRoute = routeList.getOrNull(1),
                        selectedRouteIndex = 0,
                        isLoadingRoute = false,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                _uiState.update {
                    it.copy(
                        isLoadingRoute = false,
                        errorMessage = "Unable to compute route. Check connection."
                    )
                }
            }
        }
    }

    fun startNavigation(context: Context) {
        val state = _uiState.value
        val route = state.activeRoute ?: return

        // 1. Save trip record to repository
        val tripRecord = TripRecord(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            durationSec = route.durationSec,
            distanceMeters = route.distanceMeters,
            avgSpeedKmh = if (state.selectedTravelMode == TravelMode.CAR) 42f else 18f,
            maxSpeedKmh = 60f,
            startAddress = state.startName,
            endAddress = state.destName,
            startLat = state.startLat,
            startLon = state.startLon,
            endLat = state.destLat,
            endLon = state.destLon,
            rating = 5,
            waypointsJson = route.waypointsJson,
            tripSource = TripRecord.TRIP_SOURCE_ROUTE_FINDER
        )

        viewModelScope.launch {
            tripHistoryRepository.saveTrip(tripRecord)
        }

        // 2. Launch Google Maps turn-by-turn Navigation
        try {
            val modeCode = when (state.selectedTravelMode) {
                TravelMode.CAR -> "d"
                TravelMode.BIKE -> "b"
                TravelMode.WALKING -> "w"
            }
            val navUri = Uri.parse("google.navigation:q=${state.destLat},${state.destLon}&mode=$modeCode")
            val navIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(navIntent)
        } catch (e: Exception) {
            try {
                val geoUri = Uri.parse("https://www.google.com/maps/dir/?api=1&origin=${state.startLat},${state.startLon}&destination=${state.destLat},${state.destLon}")
                val mapIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(mapIntent)
            } catch (_: Exception) {}
        }
    }
}
