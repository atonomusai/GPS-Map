package com.autonoumus.ai.presentation.compass

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.databinding.ActivityCompassCameraBinding
import com.autonoumus.ai.databinding.LayoutCompassOverflowMenuBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class CompassCameraActivity : BaseActivity<ActivityCompassCameraBinding>(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var rotationVectorSensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    private var currentAzimuth = 245f
    private var currentCoordinatesFormatted = "--"
    private var currentAltitudeFormatted = "--"
    private var currentDeclinationFormatted = "+1.8°"

    private val hidePillHandler = Handler(Looper.getMainLooper())
    private val hidePillRunnable = Runnable {
        binding.layoutPillNotification.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                binding.layoutPillNotification.visibility = View.GONE
            }
            .start()
    }

    @Inject
    lateinit var preferenceManager: PreferenceManager

    @Inject
    lateinit var locationHelper: LocationHelper

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required for Compass on Camera", Toast.LENGTH_SHORT).show()
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityCompassCameraBinding {
        return ActivityCompassCameraBinding.inflate(inflater)
    }

    override fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.compassCameraRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.layoutTopBar.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                topMargin = bars.top
            }
            binding.layoutPillNotification.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                bottomMargin = bars.bottom + (36 * resources.displayMetrics.density).toInt()
            }
            insets
        }
    }

    override fun initViews() {
        setupToolbar()
        setupSensors()
        applyCurrentTheme()
        updateHeadingUi(currentAzimuth)
        loadLocationMetrics()
        checkCameraPermissionAndStart()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnOverflowMenu.setOnClickListener {
            showCustomOverflowMenu()
        }
    }

    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            } catch (e: Exception) {
                Log.e("CompassCamera", "Camera binding failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
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
                binding.centerReadoutLayout.visibility = View.VISIBLE
                binding.ivCompassPointer.visibility = View.VISIBLE
            }
            CompassThemesActivity.CompassTheme.GEOMETRIC_STAR -> {
                binding.ivThemeCompassDial.setImageResource(R.drawable.img_theme_geometric_star)
                binding.ivThemeCompassDial.visibility = View.VISIBLE
                binding.compassDialView.visibility = View.GONE
                binding.centerReadoutLayout.visibility = View.VISIBLE
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
                binding.centerReadoutLayout.visibility = View.VISIBLE
                binding.ivCompassPointer.visibility = View.VISIBLE
            }
            CompassThemesActivity.CompassTheme.MARITIME_ROSE -> {
                binding.ivThemeCompassDial.setImageResource(R.drawable.img_theme_maritime_rose)
                binding.ivThemeCompassDial.visibility = View.VISIBLE
                binding.compassDialView.visibility = View.GONE
                binding.centerReadoutLayout.visibility = View.VISIBLE
                binding.ivCompassPointer.visibility = View.VISIBLE
            }
        }
    }

    private fun loadLocationMetrics() {
        lifecycleScope.launch {
            val locData = locationHelper.getRealLiveLocationData()
            val lat = locData.rawLatitude
            val lon = locData.rawLongitude
            currentCoordinatesFormatted = if (lat != null && lon != null) {
                val latDir = if (lat >= 0) "N" else "S"
                val lonDir = if (lon >= 0) "E" else "W"
                String.format(Locale.US, "%.4f° %s, %.4f° %s", kotlin.math.abs(lat), latDir, kotlin.math.abs(lon), lonDir)
            } else {
                "--" // no fix: don't show placeholder Islamabad coordinates
            }
            currentAltitudeFormatted = locData.altitude

            val declination = locData.magneticDeclination
            val sign = if (declination >= 0) "+" else ""
            currentDeclinationFormatted = String.format(Locale.US, "%s%.1f°", sign, declination)
            binding.tvMagneticDeclination.text = "Magnetic Declination $currentDeclinationFormatted"
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
        menuBinding.tvCopySubtitle.text = "Copies $degInt° $code + Lat/Long"

        menuBinding.itemCopyBearing.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(
                "Bearing & GPS",
                "Bearing: $degInt° $code | $currentCoordinatesFormatted | Altitude: $currentAltitudeFormatted | Magnetic Declination: $currentDeclinationFormatted"
            )
            clipboard.setPrimaryClip(clip)
            showPillToast("Compass telemetry copied to clipboard!")
            popup.dismiss()
        }

        menuBinding.itemShareDetail.setOnClickListener {
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Compass on Camera:\nBearing: $degInt° $code\nCoordinates: $currentCoordinatesFormatted\nAltitude: $currentAltitudeFormatted\nMagnetic Declination: $currentDeclinationFormatted"
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

    private fun showPillToast(message: String) {
        hidePillHandler.removeCallbacks(hidePillRunnable)
        binding.tvPillMessage.text = message
        binding.layoutPillNotification.apply {
            alpha = 0f
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .setDuration(250)
                .start()
        }
        hidePillHandler.postDelayed(hidePillRunnable, 2500)
    }

    override fun onResume() {
        super.onResume()
        applyCurrentTheme()
        loadLocationMetrics()

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
        hidePillHandler.removeCallbacks(hidePillRunnable)
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
        binding.tvBearingDegrees.text = "$degInt°"

        val (code, fullName) = getDirectionInfo(degInt)
        binding.tvBearingDirection.text = code
        binding.tvDirectionFullName.text = fullName
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
