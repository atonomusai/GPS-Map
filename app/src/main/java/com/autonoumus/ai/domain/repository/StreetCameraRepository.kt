package com.autonoumus.ai.domain.repository

import com.autonoumus.ai.domain.model.CameraCategory
import com.autonoumus.ai.domain.model.CountryOption
import com.autonoumus.ai.domain.model.StreetCamera

interface StreetCameraRepository {
    suspend fun getWebcams(
        countryCode: String? = null,
        category: CameraCategory? = null,
        offset: Int = 0,
        limit: Int = 30,
        nearbyLatitude: Double? = null,
        nearbyLongitude: Double? = null,
        radiusKm: Int? = null
    ): Result<List<StreetCamera>>

    suspend fun getWebcamDetail(id: String): Result<StreetCamera>

    fun getCachedLiveCameras(): List<StreetCamera>?

    fun getCategories(): List<CameraCategory>

    fun getCountries(): List<CountryOption>
}
