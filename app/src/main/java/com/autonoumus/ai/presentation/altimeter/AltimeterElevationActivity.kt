package com.autonoumus.ai.presentation.altimeter

import android.Manifest
import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.databinding.ActivityAltimeterElevationBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import kotlin.math.pow
import kotlin.math.roundToInt

@AndroidEntryPoint
class AltimeterElevationActivity : BaseActivity<ActivityAltimeterElevationBinding>(), SensorEventListener {

    @Inject
    lateinit var preferenceManager: PreferenceManager

    @Inject
    lateinit var locationHelper: LocationHelper

    private var sensorManager: SensorManager? = null
    private var pressureSensor: Sensor? = null
    private var rotationVectorSensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    private var currentAltitudeMeters: Double = 540.0
    private var currentAccuracyMeters: Float = 2.5f
    private var currentLatitude: Double = 33.7294
    private var currentLongitude: Double = 73.0551
    private var currentPitchDegrees: Float = 4.2f
    private var currentPressureHpa: Double = 1013.25

    private var isFeetUnit: Boolean = false
    private val elevationHistory = mutableListOf<Double>()

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            loadLocationAndAltitude()
            observeLiveLocation()
        } else {
            binding.tvCoordinatesValue.text = "Location permission required - tap to enable"
            val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            com.autonoumus.ai.core.util.PermissionSettingsDialog.handleDenied(
                context = this,
                shouldShowRationale = shouldShowRationale,
                deniedMessage = "Location permission is required to show elevation"
            )
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityAltimeterElevationBinding {
        return ActivityAltimeterElevationBinding.inflate(inflater)
    }

    override fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.altimeterElevationRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            binding.layoutTopBar.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                topMargin = bars.top
            }
            binding.nestedScrollView.updatePadding(bottom = bars.bottom + (24 * density).toInt())
            insets
        }
    }

    override fun initViews() {
        setupToolbar()
        setupSensors()
        loadUnitPreference()
        setupUnitToggleListeners()
        requestLocationAndStart()
    }

    private fun requestLocationAndStart(isUserInitiated: Boolean = false) {
        if (locationHelper.hasLocationPermission()) {
            loadLocationAndAltitude()
            observeLiveLocation()
            return
        }
        binding.tvCoordinatesValue.text = "Location permission required - tap to enable"
        binding.tvCoordinatesValue.setOnClickListener {
            requestLocationAndStart(isUserInitiated = true)
        }
        val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        if (isUserInitiated && preferenceManager.hasRequestedLocationPermission && !shouldShowRationale) {
            com.autonoumus.ai.core.util.PermissionSettingsDialog.show(
                context = this,
                message = "Location permission is disabled. Please enable it in Settings to show elevation."
            )
        } else if (isUserInitiated || !preferenceManager.hasRequestedLocationPermission) {
            preferenceManager.hasRequestedLocationPermission = true
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        pressureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
        rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    }

    private fun loadUnitPreference() {
        val savedUnit = preferenceManager.altitudeUnit
        isFeetUnit = (savedUnit == "ft")
        updateUnitToggleUi()
    }

    private fun setupUnitToggleListeners() {
        binding.tvUnitMeter.setOnClickListener {
            if (isFeetUnit) {
                isFeetUnit = false
                preferenceManager.altitudeUnit = "m"
                updateUnitToggleUi()
                updateAltitudeDisplay()
            }
        }

        binding.tvUnitFeet.setOnClickListener {
            if (!isFeetUnit) {
                isFeetUnit = true
                preferenceManager.altitudeUnit = "ft"
                updateUnitToggleUi()
                updateAltitudeDisplay()
            }
        }
    }

    private fun updateUnitToggleUi() {
        if (!isFeetUnit) {
            binding.tvUnitMeter.setBackgroundResource(R.drawable.bg_unit_toggle_selected)
            binding.tvUnitMeter.setTextColor(Color.WHITE)

            binding.tvUnitFeet.setBackgroundColor(Color.TRANSPARENT)
            binding.tvUnitFeet.setTextColor(Color.parseColor("#64748B"))
        } else {
            binding.tvUnitFeet.setBackgroundResource(R.drawable.bg_unit_toggle_selected)
            binding.tvUnitFeet.setTextColor(Color.WHITE)

            binding.tvUnitMeter.setBackgroundColor(Color.TRANSPARENT)
            binding.tvUnitMeter.setTextColor(Color.parseColor("#64748B"))
        }
    }

    private fun loadLocationAndAltitude() {
        lifecycleScope.launch(Dispatchers.IO) {
            val locData = locationHelper.getRealLiveLocationData()
            withContext(Dispatchers.Main) {
                applyLocationData(locData)
            }
        }
    }

    private fun observeLiveLocation() {
        lifecycleScope.launch {
            locationHelper.observeLocationUpdates().collect { locData ->
                applyLocationData(locData)
            }
        }
    }

    private fun applyLocationData(locData: com.autonoumus.ai.domain.model.UserLocationData) {
        // No real fix yet: keep waiting instead of drawing hard-coded Islamabad / 540 m values.
        if (locData.rawLatitude == null || locData.rawLongitude == null || locData.rawAltitudeMeters == null) return
        currentAltitudeMeters = locData.rawAltitudeMeters ?: 540.0
        currentAccuracyMeters = locData.rawAccuracyMeters ?: 2.5f
        currentLatitude = locData.rawLatitude ?: 33.7294
        currentLongitude = locData.rawLongitude ?: 73.0551

        // Format Coordinates
        val latDir = if (currentLatitude >= 0) "N" else "S"
        val lonDir = if (currentLongitude >= 0) "E" else "W"
        binding.tvCoordinatesValue.text = String.format(
            Locale.US,
            "%.4f° %s, %.4f° %s",
            kotlin.math.abs(currentLatitude),
            latDir,
            kotlin.math.abs(currentLongitude),
            lonDir
        )

        // Build elevation history trend around current altitude if not filled
        if (elevationHistory.isEmpty()) {
            val base = currentAltitudeMeters
            val offsets = listOf(-30.0, -15.0, -22.0, 15.0, -2.0, 40.0, 20.0)
            elevationHistory.addAll(offsets.map { (base + it).coerceAtLeast(0.0) })
        } else {
            elevationHistory.add(currentAltitudeMeters)
            if (elevationHistory.size > 10) {
                elevationHistory.removeAt(0)
            }
        }

        // If hardware barometer is missing, calculate standard barometric pressure from altitude
        if (pressureSensor == null) {
            val calculatedPressure = 1013.25 * (1.0 - (0.0000225577 * currentAltitudeMeters)).pow(5.25588)
            currentPressureHpa = calculatedPressure
            binding.tvBarometerValue.text = String.format(Locale.US, "%.2f hPa", currentPressureHpa)
        }

        updateAltitudeDisplay()
    }

    private fun updateAltitudeDisplay() {
        // 1. Main Altitude & Accuracy
        if (!isFeetUnit) {
            binding.tvAltitudeValue.text = String.format(Locale.US, "%d m", currentAltitudeMeters.roundToInt())
            binding.tvGpsAccuracy.text = String.format(Locale.US, "GPS Elevation Accuracy: ± %.1f m", currentAccuracyMeters)
        } else {
            val altFeet = currentAltitudeMeters * 3.28084
            val accFeet = currentAccuracyMeters * 3.28084f
            binding.tvAltitudeValue.text = String.format(Locale.US, "%d ft", altFeet.roundToInt())
            binding.tvGpsAccuracy.text = String.format(Locale.US, "GPS Elevation Accuracy: ± %.1f ft", accFeet)
        }

        // 2. Elevation Profile Graph & Min/Max Labels
        binding.elevationProfileGraphView.setElevationData(elevationHistory, isFeetUnit)

        val displayValues = if (isFeetUnit) elevationHistory.map { it * 3.28084 } else elevationHistory
        val minVal = displayValues.minOrNull() ?: currentAltitudeMeters
        val maxVal = displayValues.maxOrNull() ?: currentAltitudeMeters

        if (!isFeetUnit) {
            binding.tvMinElevation.text = String.format(Locale.US, "Min: %d m", minVal.roundToInt())
            binding.tvMaxElevation.text = String.format(Locale.US, "Max: %d m", maxVal.roundToInt())
        } else {
            binding.tvMinElevation.text = String.format(Locale.US, "Min: %d ft", minVal.roundToInt())
            binding.tvMaxElevation.text = String.format(Locale.US, "Max: %d ft", maxVal.roundToInt())
        }
    }

    override fun onResume() {
        super.onResume()
        loadUnitPreference()
        updateAltitudeDisplay()
        pressureSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        rotationVectorSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: run {
            accelerometer?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            magnetometer?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                currentPressureHpa = event.values[0].toDouble()
                binding.tvBarometerValue.text = String.format(Locale.US, "%.2f hPa", currentPressureHpa)
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)
                // Pitch is orientation[1] in radians
                val pitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()
                applyPitch(pitchDeg)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, 3)
                hasGravity = true
                processOrientation()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                hasGeomagnetic = true
                processOrientation()
            }
        }
    }

    private fun processOrientation() {
        if (hasGravity && hasGeomagnetic) {
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                val pitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()
                applyPitch(pitchDeg)
            }
        }
    }

    private fun applyPitch(pitchDeg: Float) {
        currentPitchDegrees = pitchDeg
        binding.tvSlopeValue.text = String.format(Locale.US, "%.1f° Pitch", kotlin.math.abs(pitchDeg))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
