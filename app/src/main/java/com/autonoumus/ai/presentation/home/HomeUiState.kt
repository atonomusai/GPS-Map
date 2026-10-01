package com.autonoumus.ai.presentation.home

import com.autonoumus.ai.domain.model.QuickToolItem
import com.autonoumus.ai.domain.model.RecentCaptureItem
import com.autonoumus.ai.domain.model.StreetViewCameraItem
import com.autonoumus.ai.domain.model.UserLocationData

data class HomeUiState(
    val hasLocationPermission: Boolean = false,
    val locationData: UserLocationData? = null,
    val quickTools: List<QuickToolItem> = emptyList(),
    val recentCaptures: List<RecentCaptureItem> = emptyList(),
    val streetViewCameras: List<StreetViewCameraItem> = emptyList(),
    val isStreetViewLoading: Boolean = true
)
