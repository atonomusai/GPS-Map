package com.autonoumus.ai.presentation.speedometer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.core.util.SpeedAlertManager
import com.autonoumus.ai.core.util.VibrationHelper
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.data.service.TripLiveState
import com.autonoumus.ai.data.service.TripTrackingService
import com.autonoumus.ai.databinding.ActivitySpeedometerBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

@AndroidEntryPoint
class SpeedometerActivity : BaseActivity<ActivitySpeedometerBinding>() {

    @Inject
    lateinit var preferenceManager: PreferenceManager

    @Inject
    lateinit var locationHelper: LocationHelper

    private lateinit var gestureDetector: GestureDetector

    private var currentSpeed: Float = 0f
    private var smoothedSpeed: Float = 0f
    private var maxSpeed: Float = 0f
    private var totalDistanceMeters: Double = 0.0
    private var isTripActive: Boolean = false
    private var isStoppingTrip: Boolean = false
    private var lastLocation: Location? = null
    private var barAutoScale: Float = BAR_SCALE_TIERS[0]

    private companion object {
        /** Auto-range steps (in the display unit) for the digital bar when no speed limit is set. */
        val BAR_SCALE_TIERS = floatArrayOf(20f, 40f, 80f, 120f, 180f, 240f, 320f)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Notifications disabled - trip tracking will continue without a persistent notification",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            hasPreciseLocationPermission()
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted) {
            startTrip()
        } else if (coarseGranted) {
            // Android 12+ "Approximate" location: fixes are ~2km fuzzed with no speed data, so the
            // speedometer would just sit at 0. Explain and send the user to enable Precise.
            com.autonoumus.ai.core.util.PermissionSettingsDialog.show(
                context = this,
                message = "Speed tracking needs Precise location. Please allow \"Precise\" location for this app in Settings."
            )
        } else {
            val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            com.autonoumus.ai.core.util.PermissionSettingsDialog.handleDenied(
                context = this,
                shouldShowRationale = shouldShowRationale,
                deniedMessage = "Location permission is required to track your trip"
            )
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivitySpeedometerBinding {
        return ActivitySpeedometerBinding.inflate(inflater)
    }

    override fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.speedometerRoot) { _, insets ->
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
        setupModeToggle()
        setupSwipeGesture()
        setupSpeedLimitBadge()
        setupTripActionButton()
        loadPreferences()
        observeTrackingServiceState()
        checkNotificationPermission()
        updateSpeedDisplay(0f)
        updateTripStats(0.0, 0f, 0f, 0L)
    }

    private fun setupSwipeGesture() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 80
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                if (abs(diffX) > abs(diffY) && abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        // Swiped Right -> Switch to left tab (Analog Gauge)
                        val isAnalog = preferenceManager.speedometerMode == "ANALOG_GAUGE"
                        if (!isAnalog) {
                            setMode(analog = true)
                            return true
                        }
                    } else {
                        // Swiped Left -> Switch to right tab (Digital HUD)
                        val isAnalog = preferenceManager.speedometerMode == "ANALOG_GAUGE"
                        if (isAnalog) {
                            setMode(analog = false)
                            return true
                        }
                    }
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::gestureDetector.isInitialized) {
            gestureDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        isStoppingTrip = false
        loadPreferences()
        val isTracking = TripTrackingService.isTrackingState.value
        isTripActive = isTracking
        if (!isTracking) {
            currentSpeed = 0f
            smoothedSpeed = 0f
            updateSpeedDisplay(0f)
            updateTripStats(0.0, 0f, 0f, 0L)
            updateSpeedLimitBadgeUi()
            binding.btnStartStopTracking.isEnabled = true
            binding.btnStartStopTracking.text = "Start"
            binding.btnStartStopTracking.setBackgroundResource(R.drawable.bg_speed_start_button)
        } else {
            binding.btnStartStopTracking.isEnabled = true
            binding.btnStartStopTracking.text = "Stop"
            binding.btnStartStopTracking.setBackgroundResource(R.drawable.bg_speed_stop_button)
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, TripHistoryActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SpeedometerSettingsActivity::class.java))
        }
    }

    private fun setupModeToggle() {
        binding.tvModeAnalog.setOnClickListener {
            setMode(analog = true)
        }

        binding.tvModeDigital.setOnClickListener {
            setMode(analog = false)
        }
    }

    private fun setMode(analog: Boolean) {
        val currentIsAnalog = preferenceManager.speedometerMode == "ANALOG_GAUGE"
        if (currentIsAnalog == analog) return
        preferenceManager.speedometerMode = if (analog) "ANALOG_GAUGE" else "DIGITAL_HUD"
        updateModeToggleUi(analog, animated = true)
    }

    private fun updateModeToggleUi(isAnalog: Boolean, animated: Boolean = false) {
        if (isAnalog) {
            binding.tvModeAnalog.setBackgroundResource(R.drawable.bg_speed_toggle_selected)
            binding.tvModeAnalog.setTextColor(Color.WHITE)

            binding.tvModeDigital.setBackgroundColor(Color.TRANSPARENT)
            binding.tvModeDigital.setTextColor(Color.parseColor("#64748B"))

            binding.speedometerDialView.animate().cancel()
            binding.layoutDigitalHud.animate().cancel()

            if (animated && binding.layoutDigitalHud.visibility == View.VISIBLE) {
                // Incoming: speedometerDialView (fades in and slides from left)
                binding.speedometerDialView.visibility = View.VISIBLE
                binding.speedometerDialView.alpha = 0f
                binding.speedometerDialView.scaleX = 0.95f
                binding.speedometerDialView.scaleY = 0.95f
                binding.speedometerDialView.translationX = -30f

                binding.speedometerDialView.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(0f)
                    .setDuration(240)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()

                // Outgoing: layoutDigitalHud (fades out and slides to right)
                binding.layoutDigitalHud.animate()
                    .alpha(0f)
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .translationX(30f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction {
                        binding.layoutDigitalHud.visibility = View.GONE
                        binding.layoutDigitalHud.scaleX = 1f
                        binding.layoutDigitalHud.scaleY = 1f
                        binding.layoutDigitalHud.translationX = 0f
                    }
                    .start()
            } else {
                binding.layoutDigitalHud.visibility = View.GONE
                binding.layoutDigitalHud.alpha = 1f
                binding.layoutDigitalHud.scaleX = 1f
                binding.layoutDigitalHud.scaleY = 1f
                binding.layoutDigitalHud.translationX = 0f

                binding.speedometerDialView.visibility = View.VISIBLE
                binding.speedometerDialView.alpha = 1f
                binding.speedometerDialView.scaleX = 1f
                binding.speedometerDialView.scaleY = 1f
                binding.speedometerDialView.translationX = 0f
            }
            binding.speedometerDialView.setMode(true)
        } else {
            binding.tvModeDigital.setBackgroundResource(R.drawable.bg_speed_toggle_selected)
            binding.tvModeDigital.setTextColor(Color.WHITE)

            binding.tvModeAnalog.setBackgroundColor(Color.TRANSPARENT)
            binding.tvModeAnalog.setTextColor(Color.parseColor("#64748B"))

            binding.speedometerDialView.animate().cancel()
            binding.layoutDigitalHud.animate().cancel()

            if (animated && binding.speedometerDialView.visibility == View.VISIBLE) {
                // Incoming: layoutDigitalHud (fades in and slides from right)
                binding.layoutDigitalHud.visibility = View.VISIBLE
                binding.layoutDigitalHud.alpha = 0f
                binding.layoutDigitalHud.scaleX = 0.95f
                binding.layoutDigitalHud.scaleY = 0.95f
                binding.layoutDigitalHud.translationX = 30f

                binding.layoutDigitalHud.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(0f)
                    .setDuration(240)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()

                // Outgoing: speedometerDialView (fades out and slides to left)
                binding.speedometerDialView.animate()
                    .alpha(0f)
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .translationX(-30f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction {
                        binding.speedometerDialView.visibility = View.GONE
                        binding.speedometerDialView.scaleX = 1f
                        binding.speedometerDialView.scaleY = 1f
                        binding.speedometerDialView.translationX = 0f
                    }
                    .start()
            } else {
                binding.speedometerDialView.visibility = View.GONE
                binding.speedometerDialView.alpha = 1f
                binding.speedometerDialView.scaleX = 1f
                binding.speedometerDialView.scaleY = 1f
                binding.speedometerDialView.translationX = 0f

                binding.layoutDigitalHud.visibility = View.VISIBLE
                binding.layoutDigitalHud.alpha = 1f
                binding.layoutDigitalHud.scaleX = 1f
                binding.layoutDigitalHud.scaleY = 1f
                binding.layoutDigitalHud.translationX = 0f
            }
            binding.speedometerDialView.setMode(false)
        }
    }

    private fun setupSpeedLimitBadge() {
        binding.layoutSpeedLimitBadge.setOnClickListener {
            showSpeedLimitBottomSheet()
        }
    }

    private fun showSpeedLimitBottomSheet() {
        val sheet = SpeedLimitBottomSheetDialog.newInstance()
        sheet.onLimitAppliedListener = { _, _ ->
            loadPreferences()
        }
        sheet.show(supportFragmentManager, SpeedLimitBottomSheetDialog.TAG)
    }

    private fun setupTripActionButton() {
        binding.btnStartStopTracking.setOnClickListener {
            if (!isTripActive) {
                if (preferenceManager.hasAcceptedTripLocationDisclosure) {
                    requestLocationAndStartTrip()
                } else {
                    // Prominent disclosure before the system prompt / first background tracking.
                    com.autonoumus.ai.presentation.common.dialog.PermissionRequestBottomSheetDialog
                        .showTripLocationRationale(supportFragmentManager) {
                            preferenceManager.hasAcceptedTripLocationDisclosure = true
                            requestLocationAndStartTrip()
                        }
                }
            } else {
                stopTrip()
            }
        }
    }

    private fun requestLocationAndStartTrip() {
        if (hasPreciseLocationPermission()) {
            startTrip()
        } else if (locationHelper.hasLocationPermission()) {
            // Only "Approximate" granted - ask to upgrade to precise (system shows the
            // precise/approximate upgrade dialog on Android 12+).
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        } else {
            val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            if (preferenceManager.hasRequestedLocationPermission && !shouldShowRationale) {
                com.autonoumus.ai.core.util.PermissionSettingsDialog.show(
                    context = this,
                    message = "Location permission is disabled. Please enable it in Settings to track your speed and trip."
                )
            } else {
                preferenceManager.hasRequestedLocationPermission = true
                locationPermissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
        }
    }

    private fun hasPreciseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isGpsEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return false
        return try {
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }
    }

    private fun startTrip() {
        // Without the GPS provider only network (Wi-Fi/cell) locations arrive: no speed data and
        // 50m+ accuracy, so the meter would never move. Ask the user to switch GPS on first.
        if (!isGpsEnabled()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Turn on GPS")
                .setMessage("Speed measurement needs GPS (Location) to be turned on.")
                .setPositiveButton("Open Settings") { _, _ ->
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    } catch (e: Exception) {}
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        isStoppingTrip = false
        isTripActive = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.btnStartStopTracking.isEnabled = true
        binding.btnStartStopTracking.text = "Stop"
        binding.btnStartStopTracking.setBackgroundResource(R.drawable.bg_speed_stop_button)
        Toast.makeText(this, "Trip tracking started (Background supported)", Toast.LENGTH_SHORT).show()

        // Start Foreground Service for rock-solid background tracking
        TripTrackingService.start(this)
        maybeShowOemBatteryHint()
    }

    /**
     * MIUI/HyperOS (Xiaomi, Redmi, POCO) applies its own "Battery saver" on top of Android and cuts
     * GPS to apps once the screen turns off - exactly how people jog with the phone in a pocket -
     * even with a foreground service running. Code can't lift that restriction, so tell the user
     * once where to switch it to "No restrictions".
     */
    private fun maybeShowOemBatteryHint() {
        if (preferenceManager.hasShownOemBatteryHint) return
        val maker = Build.MANUFACTURER.lowercase(Locale.US)
        val isXiaomiFamily = maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco")
        // Samsung, BBK (Oppo/Vivo/Realme/OnePlus) and Huawei/Honor have similar background killers.
        // Skip them once the user has already exempted the app from battery optimization.
        val isOtherAggressiveOem = listOf("samsung", "oppo", "vivo", "realme", "oneplus", "huawei", "honor")
            .any { maker.contains(it) }
        val alreadyUnrestricted = (getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
            ?.isIgnoringBatteryOptimizations(packageName) == true
        if (!isXiaomiFamily && (!isOtherAggressiveOem || alreadyUnrestricted)) return
        preferenceManager.hasShownOemBatteryHint = true

        val message = if (isXiaomiFamily) {
            "On Xiaomi / Redmi / POCO phones, the battery saver stops GPS when the screen is off.\n\n" +
                "Open App info > Battery saver and choose \"No restrictions\" so speed keeps updating while you jog."
        } else {
            "This phone's battery manager may pause GPS when the screen is off.\n\n" +
                "Open App info > Battery and choose \"Unrestricted\" so speed keeps updating in your pocket."
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Keep tracking with screen off")
            .setMessage(message)
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", packageName, null))
                    )
                } catch (e: Exception) {}
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun stopTrip() {
        isStoppingTrip = true
        isTripActive = false
        binding.btnStartStopTracking.isEnabled = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Delegate to TripStopRoutingActivity to stop service, save trip, and launch TripSummaryActivity
        SpeedAlertManager.stopAlert()
        startActivity(Intent(this, TripStopRoutingActivity::class.java))

        // Reset local stats
        totalDistanceMeters = 0.0
        maxSpeed = 0f
        currentSpeed = 0f
        updateSpeedDisplay(0f)
        updateTripStats(0.0, 0f, 0f, 0L)
    }

    private fun observeTrackingServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    TripTrackingService.isTrackingState.collect { tracking ->
                        isTripActive = tracking
                        if (tracking) {
                            isStoppingTrip = false
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            binding.btnStartStopTracking.isEnabled = true
                            binding.btnStartStopTracking.text = "Stop"
                            binding.btnStartStopTracking.setBackgroundResource(R.drawable.bg_speed_stop_button)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            if (!isStoppingTrip) {
                                binding.btnStartStopTracking.isEnabled = true
                                binding.btnStartStopTracking.text = "Start"
                                binding.btnStartStopTracking.setBackgroundResource(R.drawable.bg_speed_start_button)
                            }
                        }
                    }
                }

                launch {
                    TripTrackingService.liveTripState.collect { state ->
                        if (state.isTracking && isTripActive) {
                            applyServiceTripUpdate(state)
                        } else if (!state.isTracking) {
                            currentSpeed = 0f
                            updateSpeedDisplay(0f)
                            updateTripStats(0.0, 0f, 0f, 0L)
                            updateSpeedLimitBadgeUi()
                        }
                    }
                }
            }
        }
    }

    private fun applyServiceTripUpdate(state: TripLiveState) {
        if (!isTripActive) {
            updateSpeedDisplay(0f)
            updateTripStats(0.0, 0f, 0f, 0L)
            return
        }
        val isKmh = preferenceManager.isSpeedUnitKmh
        currentSpeed = if (isKmh) state.currentSpeedKmh else state.currentSpeedKmh * 0.621371f
        maxSpeed = state.maxSpeedKmh
        totalDistanceMeters = state.totalDistanceMeters

        updateSpeedDisplay(currentSpeed)
        if (state.isStepBased) {
            // Indoors GPS can't measure walking; say where the number comes from
            val unit = if (isKmh) "KM/H" else "MPH"
            binding.tvDigitalBarMin.text = "${currentSpeed.roundToInt()} $unit · steps (indoor)"
        } else if (!state.hasGpsFix) {
            // GPS cold start (10-60s on many phones) or signal lost - explain the 0 instead of
            // leaving the user thinking the speedometer is broken.
            binding.tvDigitalBarMin.text = "Waiting for GPS..."
        }
        updateSpeedLimitBadgeUi()
        updateTripStats(
            distanceMeters = state.totalDistanceMeters,
            maxSpeed = state.maxSpeedKmh,
            avgSpeed = if (state.durationSec > 0) ((state.totalDistanceMeters / state.durationSec) * 3.6).toFloat() else 0f,
            durationSec = state.durationSec
        )
        checkSpeedLimitAlert(currentSpeed)
    }

    private fun loadPreferences() {
        val isAnalog = preferenceManager.speedometerMode == "ANALOG_GAUGE"
        updateModeToggleUi(isAnalog)

        val unit = if (preferenceManager.isSpeedUnitKmh) "KM/H" else "MPH"
        binding.speedometerDialView.setUnit(unit)
        binding.tvDigitalSpeedUnit.text = unit

        val displaySpeed = if (isTripActive) currentSpeed else 0f
        updateSpeedDisplay(displaySpeed)
        updateSpeedLimitBadgeUi()
    }

    private fun updateSpeedDisplay(speed: Float) {
        val unit = if (preferenceManager.isSpeedUnitKmh) "KM/H" else "MPH"
        val rounded = speed.roundToInt()
        binding.tvDigitalBarMin.text = "$rounded $unit"
        binding.speedometerDialView.setSpeed(speed)
        binding.sevenSegmentSpeedView.setSpeed(rounded)

        val limit = preferenceManager.speedLimit
        val maxScale = barScaleFor(speed)
        binding.tvDigitalBarLimit.text = if (limit > 0) "Limit: $limit $unit" else "Limit: Off (0-${maxScale.toInt()})"
        binding.horizontalSpeedBar.setSpeed(speed, maxScale)
    }

    /**
     * Full-scale value of the digital progress bar. With a speed limit set the bar fills up to the
     * limit. Without one it used to be a fixed 0-180 scale, so walking/jogging (3-10 km/h) filled
     * only ~2% of the bar - a sliver thinner than its own rounded end, i.e. "no progress" at all.
     * Now it auto-ranges through tiers: grows at once when speed nears the top, shrinks one tier at
     * a time only when speed is well below the lower tier (hysteresis, so it doesn't flicker).
     */
    private fun barScaleFor(speed: Float): Float {
        val limit = preferenceManager.speedLimit
        if (limit > 0) return limit.toFloat()
        var index = BAR_SCALE_TIERS.indexOfFirst { it >= barAutoScale }.coerceAtLeast(0)
        while (index < BAR_SCALE_TIERS.lastIndex && speed > BAR_SCALE_TIERS[index] * 0.9f) index++
        if (index > 0 && speed < BAR_SCALE_TIERS[index - 1] * 0.6f) index--
        barAutoScale = BAR_SCALE_TIERS[index]
        return barAutoScale
    }

    private fun updateSpeedLimitBadgeUi() {
        val limit = preferenceManager.speedLimit
        val isKmh = preferenceManager.isSpeedUnitKmh
        val unit = if (isKmh) "KM/H" else "MPH"
        val displaySpeed = if (isTripActive) currentSpeed else 0f

        if (limit <= 0) {
            binding.layoutSpeedLimitBadge.setBackgroundResource(R.drawable.bg_speed_pill_set)
            binding.tvSpeedLimitText.text = "SET SPEED LIMIT: OFF"
            binding.tvSpeedLimitText.setTextColor(Color.parseColor("#5263FF"))
            binding.ivSpeedLimitAlert.visibility = View.GONE
        } else if (displaySpeed > limit) {
            binding.layoutSpeedLimitBadge.setBackgroundResource(R.drawable.bg_speed_pill_danger)
            binding.tvSpeedLimitText.text = String.format(Locale.US, "REDUCE LIMIT: %d %s", displaySpeed.toInt(), unit)
            binding.tvSpeedLimitText.setTextColor(Color.parseColor("#DC2626"))
            binding.ivSpeedLimitAlert.visibility = View.VISIBLE
        } else if (displaySpeed > limit * 0.8f) {
            binding.layoutSpeedLimitBadge.setBackgroundResource(R.drawable.bg_speed_pill_warning)
            binding.tvSpeedLimitText.text = "SPEED LIMIT: $limit $unit"
            binding.tvSpeedLimitText.setTextColor(Color.parseColor("#D97706"))
            binding.ivSpeedLimitAlert.visibility = View.GONE
        } else {
            binding.layoutSpeedLimitBadge.setBackgroundResource(R.drawable.bg_speed_pill_safe)
            binding.tvSpeedLimitText.text = "SPEED LIMIT: $limit $unit"
            binding.tvSpeedLimitText.setTextColor(Color.parseColor("#0F9D58"))
            binding.ivSpeedLimitAlert.visibility = View.GONE
        }
    }

    private fun updateTripStats(
        distanceMeters: Double,
        maxSpeed: Float,
        avgSpeed: Float,
        durationSec: Long
    ) {
        val isKm = preferenceManager.isDistanceUnitKm
        val isKmh = preferenceManager.isSpeedUnitKmh
        val unit = if (isKmh) "km/h" else "mph"
        val distUnit = if (isKm) "km" else "mi"

        val distanceConverted = if (isKm) distanceMeters / 1000.0 else (distanceMeters / 1000.0) * 0.621371
        val avgSpeedConverted = if (isKmh) avgSpeed else avgSpeed * 0.621371f
        val maxSpeedConverted = if (isKmh) maxSpeed else maxSpeed * 0.621371f

        binding.tvAvgSpeedValue.text = String.format(Locale.US, "%.1f %s", avgSpeedConverted, unit)
        binding.tvMaxSpeedValue.text = String.format(Locale.US, "%.1f %s", maxSpeedConverted, unit)
        binding.tvDistanceValue.text = String.format(Locale.US, "%.1f %s", distanceConverted, distUnit)
    }

    private fun checkSpeedLimitAlert(speed: Float) {
        if (!isTripActive) {
            SpeedAlertManager.stopAlert()
            return
        }

        val baseLimit = preferenceManager.speedLimit
        val alertToneEnabled = preferenceManager.isSpeedLimitAlertEnabled
        val vibrationEnabled = preferenceManager.isVibrationAlertEnabled
        val toleranceBuffer = if (preferenceManager.isSpeedToleranceBufferEnabled) 5 else 0
        val effectiveLimit = baseLimit + toleranceBuffer

        val isOverspeed = baseLimit > 0 && speed > effectiveLimit

        SpeedAlertManager.updateOverspeedStatus(
            context = this,
            isOverspeed = isOverspeed,
            isAudioEnabled = alertToneEnabled,
            isVibrationEnabled = vibrationEnabled
        )
    }

    override fun onDestroy() {
        try {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            SpeedAlertManager.stopAlert()
        } catch (e: Exception) {
            // Safe ignore
        }
        super.onDestroy()
    }
}
