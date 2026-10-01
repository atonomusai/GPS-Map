package com.autonoumus.ai.presentation.compass

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.databinding.ActivityCompassAltimeterBinding
import com.autonoumus.ai.databinding.LayoutCompassOverflowMenuBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.pow
import kotlin.math.roundToInt

@AndroidEntryPoint
class CompassAltimeterActivity : BaseActivity<ActivityCompassAltimeterBinding>(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var rotationVectorSensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private var pressureSensor: Sensor? = null

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    private var currentAzimuth = 245f

    @Inject
    lateinit var preferenceManager: PreferenceManager

    @Inject
    lateinit var locationHelper: LocationHelper

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            loadEnvironmentMetrics()
        } else {
            binding.tvCoordLat.text = "Permission required"
            binding.tvCoordLong.text = "Tap to enable"
            val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            com.autonoumus.ai.core.util.PermissionSettingsDialog.handleDenied(
                context = this,
                shouldShowRationale = shouldShowRationale,
                deniedMessage = "Location permission is required to show coordinates and altitude"
            )
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityCompassAltimeterBinding {
        return ActivityCompassAltimeterBinding.inflate(inflater)
    }

    override fun initViews() {
        setupToolbar()
        setupSensors()
        setupListeners()
        applyCurrentTheme()
        updateHeadingUi(currentAzimuth)
        binding.tvCoordLong.setOnClickListener { requestLocationPermissionIfNeeded(isUserInitiated = true) }
        binding.tvCoordLat.setOnClickListener { requestLocationPermissionIfNeeded(isUserInitiated = true) }
        requestLocationPermissionIfNeeded(isUserInitiated = false)
    }

    private fun requestLocationPermissionIfNeeded(isUserInitiated: Boolean = false) {
        if (locationHelper.hasLocationPermission()) {
            loadEnvironmentMetrics()
            return
        }
        val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        if (isUserInitiated && preferenceManager.hasRequestedLocationPermission && !shouldShowRationale) {
            com.autonoumus.ai.core.util.PermissionSettingsDialog.show(
                context = this,
                message = "Location permission is disabled. Please enable it in Settings to show coordinates and altitude."
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

        binding.btnOverflowMenu.setOnClickListener {
            showCustomOverflowMenu()
        }
    }

    private fun showCustomOverflowMenu() {
        val menuBinding = LayoutCompassOverflowMenuBinding.inflate(layoutInflater)
        val popup = PopupWindow(
            menuBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 20f
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
        }

        val degInt = (currentAzimuth % 360).roundToInt()
        val (code, _) = getDirectionInfo(degInt)
        val altStr = binding.tvAltitudeValue.text.toString()
        val latStr = binding.tvCoordLat.text.toString()
        val longStr = binding.tvCoordLong.text.toString()
        val coordStr = "$latStr, $longStr"
        val declinationStr = binding.tvMagneticDeclination.text.toString()
        menuBinding.tvCopySubtitle.text = "Copies $degInt° $code + Lat/Long"

        menuBinding.itemCopyBearing.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(
                "Bearing & GPS",
                "Bearing: $degInt° $code | $coordStr | Altitude: $altStr | $declinationStr"
            )
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied Bearing & GPS to clipboard", Toast.LENGTH_SHORT).show()
            popup.dismiss()
        }

        menuBinding.itemShareDetail.setOnClickListener {
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Compass Reading:\nBearing: $degInt° $code\nCoordinates: $coordStr\nAltitude: $altStr\n$declinationStr\nBarometer: ${binding.tvBarometerValue.text}"
                )
                type = "text/plain"
            }
            startActivity(Intent.createChooser(shareIntent, "Share Detail"))
            popup.dismiss()
        }

        menuBinding.itemCompassThemes.setOnClickListener {
            startActivity(Intent(this, CompassThemesActivity::class.java))
            popup.dismiss()
        }

        menuBinding.itemCalibrateSensor.setOnClickListener {
            Toast.makeText(this, "Rotate your device in a figure-8 motion to calibrate", Toast.LENGTH_LONG).show()
            popup.dismiss()
        }

        val xOffset = (-220 * resources.displayMetrics.density).toInt()
        popup.showAsDropDown(binding.btnOverflowMenu, xOffset, 8)
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        pressureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
    }

    private fun setupListeners() {
        binding.btnOpenCameraCompass.setOnClickListener {
            startActivity(Intent(this, CompassCameraActivity::class.java))
        }

        binding.btnChangeCompass.setOnClickListener {
            startActivity(Intent(this, CompassThemesActivity::class.java))
        }

        binding.tvMagneticDeclination.setOnClickListener {
            Toast.makeText(
                this,
                "${binding.tvMagneticDeclination.text} (Offset between True North and Magnetic North)",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.cardAltitude.setOnClickListener {
            startActivity(Intent(this, com.autonoumus.ai.presentation.altimeter.AltimeterElevationActivity::class.java))
        }

        binding.cardBarometer.setOnClickListener {
            Toast.makeText(this, "Pressure: ${binding.tvBarometerValue.text}", Toast.LENGTH_SHORT).show()
        }

        binding.cardSunrise.setOnClickListener {
            Toast.makeText(this, "Sunrise & Sunset calculated for current location", Toast.LENGTH_SHORT).show()
        }

        binding.cardCoordinates.setOnClickListener {
            loadEnvironmentMetrics()
            Toast.makeText(this, "Coordinates updated", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyCurrentTheme() {
        val savedThemeName = preferenceManager.compassTheme
        val theme = try {
            CompassThemesActivity.CompassTheme.valueOf(savedThemeName)
        } catch (e: Exception) {
            CompassThemesActivity.CompassTheme.CLASSIC_DIAL
        }

        when (theme) {
            CompassThemesActivity.CompassTheme.CLASSIC_DIAL -> {
                binding.ivThemeCompassDial.setImageResource(R.drawable.img_theme_classic_dial)
                binding.ivThemeCompassDial.visibility = View.VISIBLE
                binding.compassDialView.visibility = View.GONE
                binding.centerReadoutLayout.visibility = View.GONE
                binding.ivCompassPointer.visibility = View.VISIBLE
            }
            CompassThemesActivity.CompassTheme.GEOMETRIC_STAR -> {
                binding.ivThemeCompassDial.setImageResource(R.drawable.img_theme_geometric_star)
                binding.ivThemeCompassDial.visibility = View.VISIBLE
                binding.compassDialView.visibility = View.GONE
                binding.centerReadoutLayout.visibility = View.GONE
                binding.ivCompassPointer.visibility = View.VISIBLE
            }
            CompassThemesActivity.CompassTheme.DIGITAL_HUD -> {
                binding.ivThemeCompassDial.setImageResource(R.drawable.img_theme_digital_hud)
                binding.ivThemeCompassDial.visibility = View.VISIBLE
                binding.compassDialView.visibility = View.GONE
                binding.centerReadoutLayout.visibility = View.VISIBLE
                binding.ivCompassPointer.visibility = View.VISIBLE
            }
            CompassThemesActivity.CompassTheme.TACTICAL_GAUGE -> {
                binding.ivThemeCompassDial.setImageResource(R.drawable.img_theme_tactical_gauge)
                binding.ivThemeCompassDial.visibility = View.VISIBLE
                binding.compassDialView.visibility = View.GONE
                binding.centerReadoutLayout.visibility = View.VISIBLE
                binding.ivCompassPointer.visibility = View.VISIBLE
            }
            CompassThemesActivity.CompassTheme.VINTAGE_RED -> {
                binding.ivThemeCompassDial.setImageResource(R.drawable.img_theme_vintage_red)
                binding.ivThemeCompassDial.visibility = View.VISIBLE
                binding.compassDialView.visibility = View.GONE
                binding.centerReadoutLayout.visibility = View.GONE
                binding.ivCompassPointer.visibility = View.VISIBLE
            }
            CompassThemesActivity.CompassTheme.MARITIME_ROSE -> {
                binding.ivThemeCompassDial.setImageResource(R.drawable.img_theme_maritime_rose)
                binding.ivThemeCompassDial.visibility = View.VISIBLE
                binding.compassDialView.visibility = View.GONE
                binding.centerReadoutLayout.visibility = View.GONE
                binding.ivCompassPointer.visibility = View.VISIBLE
            }
        }
    }

    private fun loadEnvironmentMetrics() {
        lifecycleScope.launch {
            val locData = locationHelper.getRealLiveLocationData()
            binding.tvAltitudeValue.text = locData.altitude
            binding.tvAltitudeAccuracy.text = "Accuracy: ${locData.accuracy}"

            // No fix yet: show "--" instead of the old hard-coded Islamabad / 540 m values.
            val lat = locData.rawLatitude
            val lon = locData.rawLongitude
            if (lat != null && lon != null) {
                val latDir = if (lat >= 0) "N" else "S"
                val lonDir = if (lon >= 0) "E" else "W"
                binding.tvCoordLat.text = String.format(Locale.US, "%.4f° %s", kotlin.math.abs(lat), latDir)
                binding.tvCoordLong.text = String.format(Locale.US, "%.4f° %s", kotlin.math.abs(lon), lonDir)
            } else {
                binding.tvCoordLat.text = "--"
                binding.tvCoordLong.text = "--"
            }

            // Dynamic Magnetic Declination calculation & display
            val declination = locData.magneticDeclination
            val sign = if (declination >= 0) "+" else ""
            binding.tvMagneticDeclination.text = String.format(
                Locale.US,
                "Magnetic Declination %s%.1f°",
                sign,
                declination
            )

            // If hardware barometer is not available on this phone, calculate barometric pressure from altitude
            val altMeters = locData.rawAltitudeMeters
            if (pressureSensor == null && altMeters != null) {
                val calculatedPressure = 1013.25 * Math.pow(1.0 - (0.0000225577 * altMeters), 5.25588)
                binding.tvBarometerValue.text = String.format(Locale.US, "%.1f hPa", calculatedPressure)
                binding.tvBarometerSubtitle.text = "Standard Pressure"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyCurrentTheme()
        loadEnvironmentMetrics()

        rotationVectorSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: run {
            accelerometer?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            magnetometer?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }

        pressureSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val azimuthRad = orientation[0]
                var azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
                if (azimuthDeg < 0) azimuthDeg += 360f
                applyAzimuth(azimuthDeg)
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
            Sensor.TYPE_PRESSURE -> {
                val pressureHpa = event.values[0]
                binding.tvBarometerValue.text = String.format(Locale.US, "%.1f hPa", pressureHpa)
                binding.tvBarometerSubtitle.text = when {
                    pressureHpa > 1022f -> "High Pressure (Clear)"
                    pressureHpa < 1005f -> "Low Pressure (Rain/Storm)"
                    else -> "Standard Pressure"
                }
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
                var azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (azimuthDeg < 0) azimuthDeg += 360f
                applyAzimuth(azimuthDeg)
            }
        }
    }

    private fun applyAzimuth(newAzimuth: Float) {
        val alpha = 0.15f
        currentAzimuth += alpha * (newAzimuth - currentAzimuth)
        updateHeadingUi(currentAzimuth)
    }

    private fun updateHeadingUi(azimuth: Float) {
        val degInt = (azimuth % 360).roundToInt()
        binding.compassDialView.setHeading(azimuth)
        binding.ivThemeCompassDial.rotation = -azimuth
        binding.tvHeadingDegree.text = "$degInt°"

        val (code, name) = getDirectionInfo(degInt)
        binding.tvDirectionCode.text = code
        binding.tvDirectionName.text = name
    }

    private fun getDirectionInfo(deg: Int): Pair<String, String> {
        return when (deg) {
            in 338..360, in 0..22 -> Pair("N", "North")
            in 23..67 -> Pair("NE", "North-East")
            in 68..112 -> Pair("E", "East")
            in 113..157 -> Pair("SE", "South-East")
            in 158..202 -> Pair("S", "South")
            in 203..247 -> Pair("SW", "South-West")
            in 248..292 -> Pair("W", "West")
            in 293..337 -> Pair("NW", "North-West")
            else -> Pair("N", "North")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
