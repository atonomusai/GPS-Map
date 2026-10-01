package com.autonoumus.ai.data.repository

import android.location.Location
import com.autonoumus.ai.R
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.domain.model.QuickToolItem
import com.autonoumus.ai.domain.model.StreetCamera
import com.autonoumus.ai.domain.model.StreetViewCameraItem
import com.autonoumus.ai.domain.model.UserLocationData
import com.autonoumus.ai.domain.repository.HomeRepository
import com.autonoumus.ai.domain.repository.StreetCameraRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeRepositoryImpl @Inject constructor(
    private val locationHelper: LocationHelper,
    private val streetCameraRepository: StreetCameraRepository
) : HomeRepository {

    companion object {
        private const val NEARBY_RADIUS_KM = 100
        private const val HOME_CAMERA_COUNT = 6
        // Fetch extra so cameras missing a loadable preview image can be dropped without
        // starving the "nearby" list down below HOME_CAMERA_COUNT.
        private const val HOME_CAMERA_FETCH_LIMIT = 20
    }

    override fun getQuickTools(): List<QuickToolItem> {
        return listOf(
            QuickToolItem("1", "Speedometer", R.drawable.ic_speedo_meter),
            QuickToolItem("2", "Route Finder", R.drawable.ic_route_finder),
            QuickToolItem("3", "Area Measure", R.drawable.ic_area_measure),
            QuickToolItem("4", "Compass", R.drawable.ic_compas),
            QuickToolItem("5", "Trip History", R.drawable.ic_trip_history),
            QuickToolItem("6", "Altimeter", R.drawable.ic_altimeter),
            QuickToolItem("7", "Nearby Place", R.drawable.boxicons_location),
            QuickToolItem("8", "Satellite Map", R.drawable.ic_settlite),
            QuickToolItem("9", "Image on Map", R.drawable.ic_images_on_map),
            QuickToolItem("10", "Live Street View", R.drawable.ic_street_view)
        )
    }

    override suspend fun getStreetViewCameras(): List<StreetViewCameraItem> {
        val userLocation = locationHelper.getRealLiveLocationData()
        val userLat = userLocation.rawLatitude
        val userLon = userLocation.rawLongitude

        val liveCameras = if (userLat != null && userLon != null) {
            streetCameraRepository.getWebcams(
                limit = HOME_CAMERA_FETCH_LIMIT,
                nearbyLatitude = userLat,
                nearbyLongitude = userLon,
                radiusKm = NEARBY_RADIUS_KM
            ).getOrNull()
        } else {
            streetCameraRepository.getWebcams(limit = HOME_CAMERA_FETCH_LIMIT).getOrNull()
        }

        // Drop cameras with no loadable preview so the home screen never shows a broken/blank tile.
        var usableCameras = liveCameras.orEmpty().filter { !it.imageUrl.isNullOrBlank() }

        // Nothing within NEARBY_RADIUS_KM (common outside big cities): show real webcams from
        // anywhere instead of an empty section.
        if (usableCameras.isEmpty() && userLat != null && userLon != null) {
            usableCameras = streetCameraRepository.getWebcams(limit = HOME_CAMERA_FETCH_LIMIT).getOrNull()
                .orEmpty().filter { !it.imageUrl.isNullOrBlank() }
        }

        if (userLat != null && userLon != null) {
            usableCameras = usableCameras.sortedBy { camera -> distanceMeters(userLat, userLon, camera) }
        }

        if (usableCameras.isNotEmpty()) {
            return usableCameras.take(HOME_CAMERA_COUNT).map { camera ->
                StreetViewCameraItem(
                    id = camera.id,
                    title = camera.title.ifBlank { camera.getLocationSubtitle() },
                    imageRes = R.drawable.bg_camera_placeholder,
                    imageUrl = camera.imageUrl
                )
            }
        }
        return getFallbackStreetViewCameras()
    }

    private fun distanceMeters(userLat: Double, userLon: Double, camera: StreetCamera): Float {
        val result = FloatArray(1)
        Location.distanceBetween(userLat, userLon, camera.latitude, camera.longitude, result)
        return result[0]
    }

    /**
     * No webcams from Windy: show none (the home section hides itself). This used to return six
     * stock photos with invented altitude/accuracy values presented as street cameras.
     */
    private fun getFallbackStreetViewCameras(): List<StreetViewCameraItem> = emptyList()

    override suspend fun getUserLocation(): UserLocationData {
        return locationHelper.getRealLiveLocationData()
    }
}
