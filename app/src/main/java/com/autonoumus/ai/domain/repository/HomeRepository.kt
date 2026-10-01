package com.autonoumus.ai.domain.repository

import com.autonoumus.ai.domain.model.QuickToolItem
import com.autonoumus.ai.domain.model.StreetViewCameraItem
import com.autonoumus.ai.domain.model.UserLocationData

interface HomeRepository {
    fun getQuickTools(): List<QuickToolItem>
    suspend fun getStreetViewCameras(): List<StreetViewCameraItem>
    suspend fun getUserLocation(): UserLocationData
}
