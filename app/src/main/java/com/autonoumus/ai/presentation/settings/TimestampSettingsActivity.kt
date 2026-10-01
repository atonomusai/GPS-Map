package com.autonoumus.ai.presentation.settings

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.core.stamp.StampRenderer
import com.autonoumus.ai.core.util.CityLandmarkProvider
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.databinding.ActivityTimestampSettingsBinding
import com.autonoumus.ai.domain.model.MapTypeOption
import com.autonoumus.ai.domain.model.StampFontStyle
import com.autonoumus.ai.domain.model.StampPosition
import com.autonoumus.ai.domain.model.StampSettings
import com.autonoumus.ai.domain.model.StampTemplate
import com.autonoumus.ai.domain.model.StampTextAlignment
import com.autonoumus.ai.domain.usecase.BuildStampDataUseCase
import com.autonoumus.ai.presentation.templates.CustomLocationStampBottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class TimestampSettingsActivity : BaseActivity<ActivityTimestampSettingsBinding>() {

    @Inject
    lateinit var preferenceManager: PreferenceManager

    @Inject
    lateinit var stampRenderer: StampRenderer

    @Inject
    lateinit var locationHelper: LocationHelper

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var buildStampDataUseCase: BuildStampDataUseCase

    // Local draft states (only committed to preferenceManager on "Save Preferences" click)
    private var draftShowTitleHeader: Boolean = true
    private var draftShowMapBadge: Boolean = true
    private var draftShowAddress: Boolean = false
    private var draftShowTimeZone: Boolean = false
    private var draftShowCoordinates: Boolean = true
    private var draftOpacityPercent: Int = 50
    private var draftTextColor: Int = Color.WHITE
    private var draftPosition: String = "BOTTOM_LEFT"
    private var draftMapType: String = "HYBRID"

    private val mapTypeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val mapTypeName = result.data?.getStringExtra(MapTypeActivity.EXTRA_SELECTED_MAP_TYPE)
            if (!mapTypeName.isNullOrBlank()) {
                draftMapType = mapTypeName
                updatePreviewCard()
            }
        }
    }

    companion object {
        const val EXTRA_SCREEN_MODE = "extra_screen_mode"
        const val MODE_TEMPLATES = "mode_templates"
        const val MODE_CAMERA = "mode_camera"

        private const val REQ_TEXT_COLOR = "req_text_color"
        private const val REQ_STAMP_POSITION = "req_stamp_position"

        val COLOR_SWATCHES = listOf(
            "WHITE" to Color.WHITE,
            "BLACK" to Color.BLACK,
            "PRIMARY" to Color.parseColor("#5263FF"),
            "YELLOW" to Color.parseColor("#FFC107"),
            "RED" to Color.parseColor("#FF3B30"),
            "GREEN" to Color.parseColor("#34C759")
        )
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityTimestampSettingsBinding {
        return ActivityTimestampSettingsBinding.inflate(inflater)
    }

    override fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.timestampSettingsRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            binding.toolbarLayout.updatePadding(top = bars.top)
            binding.btnSavePreferences.updateLayoutParams<ConstraintLayout.LayoutParams> {
                bottomMargin = bars.bottom + (16 * density).toInt()
            }
            insets
        }
    }

    override fun initViews() {
        setupToolbar()
        setupFragmentResultListeners()
        loadAndApplySettings()
        setupListeners()
        setupSeekBars()
    }

    override fun onResume() {
        super.onResume()
        updatePreviewCard()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            // Revert changes on back press without saving
            finish()
        }
    }

    private fun loadAndApplySettings() {
        // Load initial preferences into draft state
        draftShowTitleHeader = preferenceManager.stampShowTitleHeader
        draftShowMapBadge = preferenceManager.stampShowMapBadge
        draftShowAddress = preferenceManager.stampShowAddress
        draftShowTimeZone = preferenceManager.stampShowTimeZone
        draftShowCoordinates = preferenceManager.stampShowCoordinates
        draftOpacityPercent = preferenceManager.stampOpacityPercent
        draftTextColor = preferenceManager.stampTextColor
        draftPosition = preferenceManager.stampPosition
        draftMapType = preferenceManager.mapType

        binding.templatePreviewView.stampRenderer = stampRenderer
        binding.templatePreviewView.template = StampTemplate.fromNameOrDefault(preferenceManager.stampTemplate)

        // Set immediate background: exact landmark bitmap from Home screen if cached
        CityLandmarkProvider.lastResolvedLandmarkBitmap?.let {
            binding.templatePreviewView.customBackgroundBitmap = it
        }

        binding.switchTitleHeader.isChecked = draftShowTitleHeader
        binding.switchShowMap.isChecked = draftShowMapBadge
        binding.switchShowAddress.isChecked = draftShowAddress
        binding.switchTimeZone.isChecked = draftShowTimeZone
        binding.switchShowLocation.isChecked = draftShowCoordinates
        binding.seekBarOpacity.progress = draftOpacityPercent
        binding.tvOpacityValue.text = "$draftOpacityPercent %"

        updateColorPreviewUi(draftTextColor)
        updatePreviewCard()

        lifecycleScope.launch(Dispatchers.IO) {
            if (binding.templatePreviewView.customBackgroundBitmap == null) {
                val banner = BitmapFactory.decodeResource(resources, R.drawable.img_home_banner_active)
                withContext(Dispatchers.Main) {
                    if (binding.templatePreviewView.customBackgroundBitmap == null) {
                        binding.templatePreviewView.customBackgroundBitmap = banner
                        updatePreviewCard()
                    }
                }
            }

            val loc = locationHelper.getCurrentLocationData(forCameraStamp = true)
            val settings = buildCurrentStampSettings()
            val stampData = buildStampDataUseCase(loc, settings)

            withContext(Dispatchers.Main) {
                binding.templatePreviewView.customStampData = stampData
                val addressText = loc.streetAddress.ifBlank { loc.cityName }
                binding.tvAddressSubtitle.text = addressText
                binding.tvTimeZoneSubtitle.text = loc.cityName.ifBlank { addressText }
                val coordsText = if (loc.rawLatitude != null && loc.rawLongitude != null) {
                    String.format(Locale.US, "Lat: %.4f°, Long: %.4f°", loc.rawLatitude, loc.rawLongitude)
                } else {
                    loc.coordinatesFormatted.ifBlank { "NO GEOTAG" }
                }
                binding.tvLocationSubtitle.text = coordsText
                updatePreviewCard()
            }

            // Load landmark image matching the Home screen if available
            try {
                val landmarkResult = CityLandmarkProvider.lastResolvedLandmark ?: CityLandmarkProvider.getNearbyLandmark(
                    lat = loc.rawLatitude,
                    lon = loc.rawLongitude,
                    cityName = loc.cityName,
                    fullAddress = loc.streetAddress
                )
                if (landmarkResult.imageUrl.isNotEmpty()) {
                    val request = ImageRequest.Builder(this@TimestampSettingsActivity)
                        .data(landmarkResult.imageUrl)
                        .setHeader("User-Agent", com.autonoumus.ai.core.util.AppLinks.userAgent)
                        .setHeader("Accept", "image/*")
                        .allowHardware(false)
                        .build()
                    val result = imageLoader.execute(request)
                    if (result is SuccessResult) {
                        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                        if (bitmap != null) {
                            CityLandmarkProvider.lastResolvedLandmarkBitmap = bitmap
                            withContext(Dispatchers.Main) {
                                binding.templatePreviewView.customBackgroundBitmap = bitmap
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // If unavailable, preview maintains its default home banner background
            }
        }
    }

    private fun updatePreviewCard() {
        val currentTemplate = StampTemplate.fromNameOrDefault(preferenceManager.stampTemplate)
        binding.templatePreviewView.template = currentTemplate
        binding.templatePreviewView.customStampSettings = buildCurrentStampSettings(currentTemplate)
        binding.templatePreviewView.invalidate()
    }

    private fun buildCurrentStampSettings(template: StampTemplate = StampTemplate.fromNameOrDefault(preferenceManager.stampTemplate)): StampSettings {
        return StampSettings(
            template = template,
            showTitleHeader = draftShowTitleHeader,
            showMapBadge = draftShowMapBadge,
            mapType = enumFrom(draftMapType, MapTypeOption.HYBRID),
            showAddress = draftShowAddress,
            showTimeZone = draftShowTimeZone,
            showCoordinates = draftShowCoordinates,
            opacityPercent = draftOpacityPercent,
            fontSizePercent = preferenceManager.stampFontSizePercent,
            fontStyle = StampFontStyle.BOLD,
            textColor = draftTextColor,
            position = enumFrom(draftPosition, StampPosition.BOTTOM_LEFT),
            textAlignment = StampTextAlignment.LEFT
        )
    }

    private fun setupListeners() {
        binding.rowAddCustomLocation.setOnClickListener {
            val sheet = CustomLocationStampBottomSheetDialog.newInstance()
            sheet.onLocationApplied = { _, _, _, _ ->
                Toast.makeText(this, "Custom location updated", Toast.LENGTH_SHORT).show()
                loadAndApplySettings()
            }
            sheet.show(supportFragmentManager, CustomLocationStampBottomSheetDialog.TAG)
        }

        binding.switchTitleHeader.setOnCheckedChangeListener { _, isChecked ->
            draftShowTitleHeader = isChecked
            updatePreviewCard()
        }

        binding.switchShowMap.setOnCheckedChangeListener { _, isChecked ->
            draftShowMapBadge = isChecked
            updatePreviewCard()
        }

        binding.rowSelectMapType.setOnClickListener {
            mapTypeLauncher.launch(Intent(this, MapTypeActivity::class.java))
        }

        binding.switchShowAddress.setOnCheckedChangeListener { _, isChecked ->
            draftShowAddress = isChecked
            updatePreviewCard()
        }

        binding.switchTimeZone.setOnCheckedChangeListener { _, isChecked ->
            draftShowTimeZone = isChecked
            updatePreviewCard()
        }

        binding.switchShowLocation.setOnCheckedChangeListener { _, isChecked ->
            draftShowCoordinates = isChecked
            updatePreviewCard()
        }

        binding.rowTextColor.setOnClickListener {
            StampTextColorBottomSheet.show(
                supportFragmentManager,
                draftTextColor,
                REQ_TEXT_COLOR
            )
        }

        binding.rowStampPosition.setOnClickListener {
            StampPositionBottomSheet.show(
                supportFragmentManager,
                draftPosition,
                REQ_STAMP_POSITION
            )
        }

        binding.btnSavePreferences.setOnClickListener {
            // Commit all draft settings to persistent storage
            preferenceManager.stampShowTitleHeader = draftShowTitleHeader
            preferenceManager.stampShowMapBadge = draftShowMapBadge
            preferenceManager.stampShowAddress = draftShowAddress
            preferenceManager.stampShowTimeZone = draftShowTimeZone
            preferenceManager.stampShowCoordinates = draftShowCoordinates
            preferenceManager.stampOpacityPercent = draftOpacityPercent
            preferenceManager.stampTextColor = draftTextColor
            preferenceManager.stampPosition = draftPosition
            preferenceManager.mapType = draftMapType

            Toast.makeText(this, "Preferences saved", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun setupFragmentResultListeners() {
        supportFragmentManager.setFragmentResultListener(REQ_TEXT_COLOR, this) { _, bundle ->
            val color = bundle.getInt(StampTextColorBottomSheet.BUNDLE_KEY_COLOR_INT, Color.WHITE)
            draftTextColor = color
            updateColorPreviewUi(color)
            updatePreviewCard()
        }

        supportFragmentManager.setFragmentResultListener(REQ_STAMP_POSITION, this) { _, bundle ->
            val id = bundle.getString(StampPositionBottomSheet.BUNDLE_KEY_POSITION)
                ?: return@setFragmentResultListener
            draftPosition = id
            updatePreviewCard()
        }
    }

    private fun setupSeekBars() {
        binding.seekBarOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvOpacityValue.text = "$progress %"
                if (fromUser) {
                    draftOpacityPercent = progress
                    updatePreviewCard()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun colorIdFor(colorInt: Int): String =
        COLOR_SWATCHES.firstOrNull { it.second == colorInt }?.first ?: "WHITE"

    private fun updateColorPreviewUi(colorInt: Int) {
        binding.viewColorPreview.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 5f * resources.displayMetrics.density
            setColor(colorInt)
            setStroke((1.5f * resources.displayMetrics.density).toInt(), Color.parseColor("#9CA3AF"))
        }
    }

    private inline fun <reified T : Enum<T>> enumFrom(name: String, default: T): T {
        return try {
            java.lang.Enum.valueOf(T::class.java, name)
        } catch (e: IllegalArgumentException) {
            default
        }
    }
}
