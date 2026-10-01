package com.autonoumus.ai.data.repository

import com.autonoumus.ai.core.util.WindyWebcamJson
import com.autonoumus.ai.core.util.WindyWebcamsApi
import com.autonoumus.ai.domain.model.CameraCategory
import com.autonoumus.ai.domain.model.CountryOption
import com.autonoumus.ai.domain.model.StreetCamera
import com.autonoumus.ai.domain.repository.StreetCameraRepository
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreetCameraRepositoryImpl @Inject constructor() : StreetCameraRepository {

    // Last successful fetch, kept in memory so the "View All" screen can paint instantly on
    // reopen (getCachedLiveCameras()) instead of showing an empty/loading state while a fresh
    // fetch is still in flight.
    @Volatile
    private var cachedLiveCameras: List<StreetCamera>? = null

    override suspend fun getWebcams(
        countryCode: String?,
        category: CameraCategory?,
        offset: Int,
        limit: Int,
        nearbyLatitude: Double?,
        nearbyLongitude: Double?,
        radiusKm: Int?
    ): Result<List<StreetCamera>> {
        val categorySlug = category?.takeIf { it != CameraCategory.ALL }?.windySlug
        val webcams = WindyWebcamsApi.getWebcams(
            countryCode,
            categorySlug,
            offset,
            limit,
            nearbyLatitude,
            nearbyLongitude,
            radiusKm
        )
        return if (webcams.isNotEmpty()) {
            val domainCameras = webcams.map { it.toDomain() }
            cachedLiveCameras = domainCameras
            Result.success(domainCameras)
        } else {
            Result.failure(IllegalStateException("No live cameras available"))
        }
    }

    override suspend fun getWebcamDetail(id: String): Result<StreetCamera> {
        val webcam = WindyWebcamsApi.getWebcamDetail(id)
        return if (webcam != null) {
            Result.success(webcam.toDomain())
        } else {
            Result.failure(IllegalStateException("Camera not found"))
        }
    }

    override fun getCachedLiveCameras(): List<StreetCamera>? = cachedLiveCameras

    override fun getCategories(): List<CameraCategory> = CameraCategory.values().toList()

    override fun getCountries(): List<CountryOption> {
        return Locale.getISOCountries().mapNotNull { code ->
            try {
                val locale = Locale.Builder().setRegion(code).build()
                val name = locale.getDisplayCountry(Locale.ENGLISH)
                if (name.isBlank() || name.equals(code, ignoreCase = true)) return@mapNotNull null
                CountryOption(isoCode = code, name = name, flagEmoji = flagEmojiFor(code))
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.isoCode }.sortedBy { it.name }
    }

    private fun flagEmojiFor(isoCode: String): String {
        if (isoCode.length != 2) return "🏳️"
        val base = 0x1F1E6
        val upper = isoCode.uppercase(Locale.US)
        val first = upper[0].code - 'A'.code + base
        val second = upper[1].code - 'A'.code + base
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    private fun WindyWebcamJson.toDomain(): StreetCamera {
        return StreetCamera(
            id = id,
            title = title.ifBlank { city.ifBlank { "Live Camera" } },
            city = city,
            country = country,
            countryCode = countryCode,
            imageUrl = previewUrl ?: thumbnailUrl,
            thumbnailUrl = thumbnailUrl ?: previewUrl,
            categories = categoryIds,
            latitude = latitude,
            longitude = longitude,
            playerUrl = playerUrl,
            lastUpdatedEpochMillis = lastUpdatedEpochMillis
        )
    }
}
