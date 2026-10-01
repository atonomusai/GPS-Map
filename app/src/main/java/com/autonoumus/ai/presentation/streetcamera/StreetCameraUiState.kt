package com.autonoumus.ai.presentation.streetcamera

import com.autonoumus.ai.domain.model.StreetCamera

data class StreetCameraUiState(
    val activeTab: StreetCameraTab = StreetCameraTab.LIVE,
    val liveCameras: List<StreetCamera> = emptyList(),
    val placeCategories: List<PlaceCategoryModel> = emptyList(),
    val countries: List<CountryCameraModel> = emptyList(),
    val placesSearchQuery: String = "",
    val filteredPlaceCategories: List<PlaceCategoryModel> = emptyList(),
    val isPlacesSearchEmpty: Boolean = false,
    val favoriteCameraIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
