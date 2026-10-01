package com.autonoumus.ai.presentation.nearby

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
import com.autonoumus.ai.domain.model.NearbyCategory
import com.autonoumus.ai.domain.model.NearbyPlace
import com.autonoumus.ai.domain.model.NearbySort
import com.autonoumus.ai.domain.model.TripRecord
import com.autonoumus.ai.domain.repository.NearbyPlacesRepository
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

data class NearbyPlacesUiState(
    val userLatitude: Double = 0.0,
    val userLongitude: Double = 0.0,
    val userCity: String = "Current Location",
    val searchCenterLat: Double = 0.0,
    val searchCenterLon: Double = 0.0,
    val searchRadiusMeters: Int = 2000,
    val selectedCategory: NearbyCategory = NearbyCategory.ALL,
    val searchQuery: String = "",
    val searchResults: List<OsmSearchResult> = emptyList(),
    val isSearchingAddress: Boolean = false,
    /** Places to show: [totalFound] category results after the sort / open-now / has-phone filters. */
    val places: List<NearbyPlace> = emptyList(),
    val totalFound: Int = 0,
    /** Of [totalFound]: how many have opening hours / a phone number at all (for the filters' empty state). */
    val withHoursCount: Int = 0,
    val withPhoneCount: Int = 0,
    val sortOption: NearbySort = NearbySort.NEAREST,
    val openNowOnly: Boolean = false,
    val hasPhoneOnly: Boolean = false,
    val selectedPlace: NearbyPlace? = null,
    val activeRoute: RouteResult? = null,
    val isRouteLoading: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    fun getFormattedRadius(): String {
        return if (searchRadiusMeters < 1000) {
            "Within ${searchRadiusMeters}m"
        } else {
            "Within ${searchRadiusMeters / 1000} km"
        }
    }

    fun hasActiveFilters(): Boolean = openNowOnly || hasPhoneOnly

    fun canExpandRadius(): Boolean = NearbyRadius.next(searchRadiusMeters) != null
}

object NearbyRadius {
    val OPTIONS = listOf(1000, 2000, 5000, 10000, 20000, 30000)

    fun next(current: Int): Int? = OPTIONS.firstOrNull { it > current }
}

@HiltViewModel
class NearbyPlacesViewModel @Inject constructor(
    private val nearbyPlacesRepository: NearbyPlacesRepository,
    private val locationHelper: LocationHelper,
    private val tripHistoryRepository: TripHistoryRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(NearbyPlacesUiState())
    val uiState: StateFlow<NearbyPlacesUiState> = _uiState.asStateFlow()

    private var fetchJob: Job? = null
    private var geocodeJob: Job? = null
    private var routeJob: Job? = null

    /** Everything fetched around the current search centre / radius, across categories. */
    private var allFetchedPlaces: List<NearbyPlace> = emptyList()

    /** Places of the selected category, before the sort / open-now / has-phone filters. */
    private var categoryResults: List<NearbyPlace> = emptyList()

    /** Free-text keyword the user typed; only applied to the Overpass results, never to picked suggestions. */
    private var keywordFilter: String = ""

    /** The user position came from a cached (possibly stale) fix and awaits the first live one. */
    private var centerIsProvisional = false

    /**
     * @param isProvisional true for the phone's cached last-known fix. It lets the screen show
     * places (from the local cache, usually instantly) without waiting for a live GPS fix plus
     * a network reverse-geocode - that wait was why the screen sat empty for seconds on every
     * visit. The first live fix then only re-centers and reloads if the user really moved.
     */
    fun setUserLocation(lat: Double, lon: Double, city: String? = null, isProvisional: Boolean = false) {
        val currentLat = _uiState.value.userLatitude
        val currentLon = _uiState.value.userLongitude

        if (centerIsProvisional && !isProvisional && currentLat != 0.0 && currentLon != 0.0) {
            centerIsProvisional = false
            val state = _uiState.value
            val dist = FloatArray(1)
            Location.distanceBetween(currentLat, currentLon, lat, lon, dist)
            // Search center still where the cached fix put it (user hasn't searched elsewhere)?
            val centerFollowsUser = state.searchCenterLat == currentLat && state.searchCenterLon == currentLon
            if (dist[0] >= PROVISIONAL_RECENTER_METERS && centerFollowsUser) {
                // The cached fix was stale: move to where the user really is (cache-first again)
                allFetchedPlaces = emptyList()
                categoryResults = emptyList()
                _uiState.update {
                    it.copy(
                        userLatitude = lat,
                        userLongitude = lon,
                        userCity = city?.ifBlank { null } ?: it.userCity,
                        searchCenterLat = lat,
                        searchCenterLon = lon
                    ).withVisiblePlaces()
                }
                loadNearbyPlaces()
                return
            }
        }

        // Only reload on significant movement or first initialization
        if (currentLat != 0.0 && currentLon != 0.0) {
            val dist = FloatArray(1)
            Location.distanceBetween(currentLat, currentLon, lat, lon, dist)
            if (dist[0] < 150f) {
                if (city != null) {
                    _uiState.update { it.copy(userCity = city) }
                }
                return
            }
        }

        val isFirst = currentLat == 0.0 && currentLon == 0.0
        if (isFirst) centerIsProvisional = isProvisional
        _uiState.update {
            it.copy(
                userLatitude = lat,
                userLongitude = lon,
                userCity = city?.ifBlank { "Current Location" } ?: it.userCity,
                searchCenterLat = if (isFirst || it.searchCenterLat == 0.0) lat else it.searchCenterLat,
                searchCenterLon = if (isFirst || it.searchCenterLon == 0.0) lon else it.searchCenterLon
            )
        }

        if (isFirst) {
            loadNearbyPlaces()
        }
    }

    fun setSearchRadius(radiusMeters: Int) {
        if (_uiState.value.searchRadiusMeters == radiusMeters) return
        // A smaller radius is a subset of what we already have; a larger one is refetched
        allFetchedPlaces = allFetchedPlaces.filter { it.distanceMeters <= radiusMeters }
        categoryResults = categoryResults.filter { it.distanceMeters <= radiusMeters }
        _uiState.update { it.copy(searchRadiusMeters = radiusMeters).withVisiblePlaces() }
        loadNearbyPlaces()
    }

    fun selectCategory(category: NearbyCategory) {
        if (_uiState.value.selectedCategory == category) return

        // Fast instant local filter from existing places cache
        val localFiltered = cachedFor(category)
        categoryResults = localFiltered

        _uiState.update {
            it.copy(
                selectedCategory = category,
                selectedPlace = null,
                activeRoute = null,
                errorMessage = null
            ).withVisiblePlaces()
        }

        // Fetch expanded results for this category from Overpass API
        loadNearbyPlaces()
    }

    fun setSortOption(sort: NearbySort) {
        _uiState.update { it.copy(sortOption = sort).withVisiblePlaces() }
    }

    fun toggleOpenNow() {
        _uiState.update { it.copy(openNowOnly = !it.openNowOnly).withVisiblePlaces() }
    }

    fun toggleHasPhone() {
        _uiState.update { it.copy(hasPhoneOnly = !it.hasPhoneOnly).withVisiblePlaces() }
    }

    fun clearFilters() {
        _uiState.update { it.copy(openNowOnly = false, hasPhoneOnly = false).withVisiblePlaces() }
    }

    private fun cachedFor(category: NearbyCategory): List<NearbyPlace> =
        if (category == NearbyCategory.ALL) allFetchedPlaces
        else allFetchedPlaces.filter { it.categoryType == category }

    /** Applies the open-now / has-phone filters and the sort order to [categoryResults]. */
    private fun NearbyPlacesUiState.withVisiblePlaces(): NearbyPlacesUiState {
        var list = categoryResults
        if (openNowOnly) list = list.filter { it.isOpen == true }
        if (hasPhoneOnly) list = list.filter { !it.phone.isNullOrBlank() }
        list = when (sortOption) {
            NearbySort.NEAREST -> list.sortedBy { it.distanceMeters }
            NearbySort.NAME -> list.sortedBy { it.name.lowercase(java.util.Locale.US) }
            NearbySort.OPEN_FIRST -> list.sortedWith(
                compareBy<NearbyPlace>(
                    { when (it.isOpen) { true -> 0; null -> 1; false -> 2 } },
                    { it.distanceMeters }
                )
            )
        }
        return copy(
            places = list,
            totalFound = categoryResults.size,
            withHoursCount = categoryResults.count { it.isOpen != null },
            withPhoneCount = categoryResults.count { !it.phone.isNullOrBlank() }
        )
    }

    fun selectPlace(place: NearbyPlace?) {
        _uiState.update { it.copy(selectedPlace = place, activeRoute = null) }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        keywordFilter = query

        geocodeJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearchingAddress = false) }
            return
        }

        val qLower = trimmed.lowercase(java.util.Locale.US)
        val userLat = _uiState.value.userLatitude
        val userLon = _uiState.value.userLongitude

        val radiusMeters = _uiState.value.searchRadiusMeters

        // 1. Immediately find local nearby places matching the query (highest priority!)
        val localMatches = allFetchedPlaces.filter { place ->
            place.name.lowercase(java.util.Locale.US).contains(qLower) ||
                    place.categoryName.lowercase(java.util.Locale.US).contains(qLower) ||
                    place.address.lowercase(java.util.Locale.US).contains(qLower)
        }.map { place ->
            OsmSearchResult(
                placeName = place.name,
                fullAddress = place.address,
                country = "${place.categoryName} • ${place.getFormattedDistance()} away",
                latitude = place.latitude,
                longitude = place.longitude,
                latitudeFormatted = OsmGeocodingHelper.formatLatitude(place.latitude),
                longitudeFormatted = OsmGeocodingHelper.formatLongitude(place.longitude)
            )
        }

        if (localMatches.isNotEmpty()) {
            _uiState.update { it.copy(searchResults = localMatches) }
        }

        // 2. Fetch geocoded results constrained within the active area radius limit (e.g. 2km)
        geocodeJob = viewModelScope.launch {
            delay(300) // Debounce user typing
            _uiState.update { it.copy(isSearchingAddress = true) }
            try {
                val geocoded = OsmGeocodingHelper.searchLocations(
                    query = trimmed,
                    context = context,
                    userLat = userLat,
                    userLon = userLon,
                    radiusMeters = radiusMeters,
                    bounded = true
                )

                // Combine local matches first, followed by nearby geocoded results within the radius
                val combined = (localMatches + geocoded).distinctBy { "${it.latitude},${it.longitude}" }

                _uiState.update {
                    it.copy(
                        searchResults = combined,
                        isSearchingAddress = false
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                _uiState.update { it.copy(isSearchingAddress = false) }
            }
        }
    }

    fun selectSearchResult(result: OsmSearchResult) {
        val userLat = _uiState.value.userLatitude
        val userLon = _uiState.value.userLongitude

        val distArr = FloatArray(1)
        if (userLat != 0.0 && userLon != 0.0) {
            Location.distanceBetween(userLat, userLon, result.latitude, result.longitude, distArr)
        }

        val place = NearbyPlace(
            id = "search_${result.latitude}_${result.longitude}",
            name = result.placeName,
            categoryName = if (result.country.isNotBlank()) result.country else "Search Result",
            categoryType = NearbyCategory.ALL,
            latitude = result.latitude,
            longitude = result.longitude,
            address = result.fullAddress,
            distanceMeters = distArr[0].toDouble()
        )

        // Places cached around the old centre don't belong to the new one
        keywordFilter = ""
        allFetchedPlaces = emptyList()
        categoryResults = emptyList()

        _uiState.update {
            it.copy(
                searchQuery = result.placeName,
                searchResults = emptyList(),
                isSearchingAddress = false,
                selectedPlace = place,
                searchCenterLat = result.latitude,
                searchCenterLon = result.longitude,
                activeRoute = null
            ).withVisiblePlaces()
        }

        // Load nearby places around this searched destination
        loadNearbyPlaces(result.latitude, result.longitude)
    }

    fun clearSearch() {
        geocodeJob?.cancel()
        keywordFilter = ""

        // Leaving a searched destination: go back to searching around the user
        val movedBack = _uiState.value.let {
            it.searchCenterLat != it.userLatitude || it.searchCenterLon != it.userLongitude
        }
        if (movedBack) {
            allFetchedPlaces = emptyList()
            categoryResults = emptyList()
        }
        _uiState.update {
            it.copy(
                searchQuery = "",
                searchResults = emptyList(),
                isSearchingAddress = false,
                searchCenterLat = it.userLatitude,
                searchCenterLon = it.userLongitude
            ).withVisiblePlaces()
        }
        loadNearbyPlaces()
    }

    fun requestRoute(destinationPlace: NearbyPlace) {
        val userLat = _uiState.value.userLatitude
        val userLon = _uiState.value.userLongitude

        if (userLat == 0.0 || userLon == 0.0) return

        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            _uiState.update { it.copy(isRouteLoading = true, selectedPlace = destinationPlace) }
            try {
                val route = OsmRoutingHelper.getDrivingRoute(
                    startLat = userLat,
                    startLon = userLon,
                    destLat = destinationPlace.latitude,
                    destLon = destinationPlace.longitude
                )
                _uiState.update {
                    it.copy(
                        activeRoute = route,
                        isRouteLoading = false
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                _uiState.update { it.copy(isRouteLoading = false) }
            }
        }
    }

    fun clearRoute() {
        routeJob?.cancel()
        _uiState.update { it.copy(activeRoute = null, isRouteLoading = false) }
    }

    fun startNavigationAndSaveTrip(context: Context, destinationPlace: NearbyPlace) {
        val state = _uiState.value
        val route = state.activeRoute

        val distance = route?.distanceMeters ?: destinationPlace.distanceMeters
        val duration = route?.durationSec ?: ((distance / 11.1).toLong().coerceAtLeast(60L))

        // 1. Create and persist TripRecord marked as ROUTE_FINDER
        val tripRecord = TripRecord(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            durationSec = duration,
            distanceMeters = distance,
            avgSpeedKmh = 40f,
            maxSpeedKmh = 60f,
            startAddress = state.userCity,
            endAddress = destinationPlace.name,
            startLat = state.userLatitude,
            startLon = state.userLongitude,
            endLat = destinationPlace.latitude,
            endLon = destinationPlace.longitude,
            rating = 5,
            waypointsJson = route?.waypointsJson.orEmpty(),
            tripSource = TripRecord.TRIP_SOURCE_ROUTE_FINDER
        )

        viewModelScope.launch {
            tripHistoryRepository.saveTrip(tripRecord)
        }

        // 2. Launch Google Maps navigation
        try {
            val navUri = Uri.parse("google.navigation:q=${destinationPlace.latitude},${destinationPlace.longitude}&mode=d")
            val navIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(navIntent)
        } catch (e: Exception) {
            // Fallback to web/generic maps intent
            try {
                val geoUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${destinationPlace.latitude},${destinationPlace.longitude}")
                val mapIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(mapIntent)
            } catch (_: Exception) {}
        }
    }

    fun refreshPlaces() {
        loadNearbyPlaces(forceRefresh = true)
    }

    /**
     * Cache first: if these places were loaded before (same area / category / radius, even in an
     * earlier app session) they are shown immediately with no spinner. Fresh cache (< [CACHE_FRESH_MS])
     * means no network call at all; older cache is shown and then silently refreshed in the
     * background. Only with no usable cache does the user wait for the network.
     */
    private fun loadNearbyPlaces(
        targetLat: Double? = null,
        targetLon: Double? = null,
        forceRefresh: Boolean = false
    ) {
        val state = _uiState.value
        val lat = targetLat ?: (if (state.searchCenterLat != 0.0) state.searchCenterLat else state.userLatitude)
        val lon = targetLon ?: (if (state.searchCenterLon != 0.0) state.searchCenterLon else state.userLongitude)
        if (lat == 0.0 || lon == 0.0) return

        val currentCategory = state.selectedCategory
        val radius = state.searchRadiusMeters
        val keyword = if (state.searchResults.isEmpty()) keywordFilter else ""

        val localExisting = cachedFor(currentCategory)

        fetchJob?.cancel()
        android.util.Log.d("NearbyPlacesVM", "load $currentCategory r=$radius at $lat,$lon force=$forceRefresh")
        fetchJob = viewModelScope.launch {
            // 1. Instant result from the local cache (memory, else disk), if there is one
            val cached = if (forceRefresh) null else try {
                nearbyPlacesRepository.getCachedNearbyPlaces(lat, lon, currentCategory, radius, keyword)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            if (cached != null) {
                applyResults(cached.places, currentCategory, keyword)
                _uiState.update { it.copy(isLoading = false, errorMessage = null).withVisiblePlaces() }
                if (cached.ageMs < CACHE_FRESH_MS) return@launch
            } else if (localExisting.isEmpty()) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            }
            // else: places of this category are already on screen (filtered from "All Places") -
            // refresh them silently instead of covering them with a loading spinner.

            // 2. Network: blocking only when nothing was cached, otherwise a silent background refresh
            try {
                val places = nearbyPlacesRepository.getNearbyPlaces(
                    latitude = lat,
                    longitude = lon,
                    category = currentCategory,
                    radiusMeters = radius,
                    searchQuery = keyword
                )
                applyResults(places, currentCategory, keyword)
                _uiState.update { it.copy(isLoading = false, errorMessage = null).withVisiblePlaces() }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    return@launch
                }
                if (cached != null) {
                    // Background refresh failed: keep showing the cached places, no error
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                // If we have local places, keep showing them rather than showing an error!
                categoryResults = localExisting
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = if (localExisting.isNotEmpty()) null else LOAD_ERROR_MESSAGE
                    ).withVisiblePlaces()
                }
            }
        }
    }

    private fun applyResults(places: List<NearbyPlace>, category: NearbyCategory, keyword: String) {
        allFetchedPlaces = if (category == NearbyCategory.ALL && keyword.isBlank()) {
            places
        } else {
            (allFetchedPlaces + places).distinctBy { it.id }
        }
        categoryResults = places
    }

    private companion object {
        const val LOAD_ERROR_MESSAGE =
            "Couldn't load places. Check your connection, or try a smaller radius or a single category."

        /** Beyond the cache's own center tolerance: a cached fix this far off was stale. */
        const val PROVISIONAL_RECENTER_METERS = 250f

        /** Cached places younger than this are shown without contacting the servers at all. */
        const val CACHE_FRESH_MS = 6L * 60 * 60 * 1000
    }
}
