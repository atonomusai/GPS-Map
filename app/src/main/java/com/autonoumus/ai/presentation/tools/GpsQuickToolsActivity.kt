package com.autonoumus.ai.presentation.tools

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.databinding.ActivityGpsQuickToolsBinding
import com.autonoumus.ai.presentation.altimeter.AltimeterElevationActivity
import com.autonoumus.ai.presentation.area.AreaMeasurementActivity
import com.autonoumus.ai.presentation.compass.CompassAltimeterActivity
import com.autonoumus.ai.presentation.map.ImagesOnMapActivity
import com.autonoumus.ai.presentation.map.PickLocationMapActivity
import com.autonoumus.ai.presentation.nearby.NearbyPlacesActivity
import com.autonoumus.ai.presentation.routefinder.RouteFinderActivity
import com.autonoumus.ai.presentation.speedometer.SpeedometerActivity
import com.autonoumus.ai.presentation.speedometer.TripHistoryActivity
import com.autonoumus.ai.presentation.streetcamera.StreetCameraActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class GpsQuickToolsActivity : BaseActivity<ActivityGpsQuickToolsBinding>() {

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, GpsQuickToolsActivity::class.java)
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityGpsQuickToolsBinding {
        return ActivityGpsQuickToolsBinding.inflate(inflater)
    }

    override fun initViews() {
        binding.btnBack.setOnClickListener { finish() }

        // Top Featured Card: Compass & Altimeter
        val openCompass = {
            startActivity(Intent(this, CompassAltimeterActivity::class.java))
        }
        binding.cardFeaturedCompass.setOnClickListener { openCompass() }
        binding.btnFeaturedGo.setOnClickListener { openCompass() }

        // 1. GPS Speedometer
        binding.cardSpeedometer.setOnClickListener {
            startActivity(Intent(this, SpeedometerActivity::class.java))
        }

        // 2. Route Finder
        binding.cardRouteFinder.setOnClickListener {
            startActivity(Intent(this, RouteFinderActivity::class.java))
        }

        // 3. Area Measure
        binding.cardAreaMeasure.setOnClickListener {
            startActivity(Intent(this, AreaMeasurementActivity::class.java))
        }

        // 4. Satellite Map
        binding.cardSatelliteMap.setOnClickListener {
            startActivity(PickLocationMapActivity.createSatelliteIntent(this))
        }

        // 5. Compass
        binding.cardCompassAltimeter.setOnClickListener {
            startActivity(Intent(this, CompassAltimeterActivity::class.java))
        }

        // 6. Altimeter Elevation
        binding.cardAltimeter.setOnClickListener {
            startActivity(Intent(this, AltimeterElevationActivity::class.java))
        }

        // 7. Nearby Places
        binding.cardNearbyPlaces.setOnClickListener {
            startActivity(Intent(this, NearbyPlacesActivity::class.java))
        }

        // 8. Trip History
        binding.cardTripHistory.setOnClickListener {
            startActivity(Intent(this, TripHistoryActivity::class.java))
        }

        // 9. Images on Map
        binding.cardImagesOnMap.setOnClickListener {
            startActivity(Intent(this, ImagesOnMapActivity::class.java))
        }

        // 10. Live Street View
        binding.cardLiveStreetView.setOnClickListener {
            startActivity(StreetCameraActivity.newIntent(this))
        }
    }
}
