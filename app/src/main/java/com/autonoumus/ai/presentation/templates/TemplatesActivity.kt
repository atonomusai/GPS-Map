package com.autonoumus.ai.presentation.templates

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.databinding.ActivityTemplatesBinding
import com.autonoumus.ai.databinding.DialogDeleteCapturesConfirmationBinding
import com.autonoumus.ai.databinding.LayoutTabSavedLocationsBinding
import com.autonoumus.ai.databinding.LayoutTabSimpleTemplatesBinding
import com.autonoumus.ai.domain.model.MapTypeOption
import com.autonoumus.ai.domain.model.SavedLocationTemplate
import com.autonoumus.ai.domain.model.StampData
import com.autonoumus.ai.domain.model.StampTemplate
import com.autonoumus.ai.domain.repository.SavedLocationTemplateRepository
import com.autonoumus.ai.domain.usecase.BuildStampDataUseCase
import com.autonoumus.ai.domain.usecase.GetStampSettingsUseCase
import com.autonoumus.ai.presentation.camera.CameraActivity
import com.autonoumus.ai.presentation.settings.TimestampSettingsActivity
import com.autonoumus.ai.presentation.templates.adapter.SavedLocationsAdapter
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class TemplatesActivity : BaseActivity<ActivityTemplatesBinding>() {

    @Inject
    lateinit var preferenceManager: PreferenceManager

    @Inject
    lateinit var locationHelper: LocationHelper

    @Inject
    lateinit var getStampSettingsUseCase: GetStampSettingsUseCase

    @Inject
    lateinit var buildStampDataUseCase: BuildStampDataUseCase

    @Inject
    lateinit var savedLocationTemplateRepository: SavedLocationTemplateRepository

    private var selectedTemplate: StampTemplate = StampTemplate.GPS_FULL_CARD
    private var currentStampData: StampData? = null

    private var simpleTemplatesBinding: LayoutTabSimpleTemplatesBinding? = null
    private var savedLocationsBinding: LayoutTabSavedLocationsBinding? = null

    private val savedLocationsAdapter by lazy {
        SavedLocationsAdapter(
            onItemClick = { savedItem ->
                onSavedLocationSelected(savedItem)
            },
            onDeleteClick = { savedItem ->
                showDeleteLocationConfirmationDialog(savedItem)
            }
        )
    }
    private var savedTemplatesList: List<SavedLocationTemplate> = emptyList()

    companion object {
        const val EXTRA_SELECTED_TEMPLATE = "extra_selected_template"
        private const val TAB_TEMPLATES = 0
        private const val TAB_SAVED_LOCATIONS = 1
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityTemplatesBinding {
        return ActivityTemplatesBinding.inflate(inflater)
    }

    override fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.templatesRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            binding.toolbarLayout.updatePadding(top = bars.top)
            binding.btnAddCustomTemplate.layoutParams = (binding.btnAddCustomTemplate.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = bars.bottom + (24 * density).toInt()
            }
            insets
        }
    }

    override fun initViews() {
        loadCurrentTemplate()
        setupToolbar()
        setupTabs()
        setupViewPager()
        setupFab()
        observeSavedTemplates()
        populateTemplatePreviews()
    }

    override fun onResume() {
        super.onResume()
        populateTemplatePreviews()
        savedLocationsAdapter.submitList(ArrayList(savedTemplatesList))
        savedLocationsBinding?.let { b ->
            b.layoutEmptySavedLocations.isVisible = savedTemplatesList.isEmpty()
            b.rvSavedLocations.isVisible = savedTemplatesList.isNotEmpty()
        }
    }

    private fun loadCurrentTemplate() {
        selectedTemplate = StampTemplate.fromNameOrDefault(preferenceManager.stampTemplate)
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnStampSettings.setOnClickListener {
            val intent = Intent(this, TimestampSettingsActivity::class.java).apply {
                putExtra(TimestampSettingsActivity.EXTRA_SCREEN_MODE, TimestampSettingsActivity.MODE_TEMPLATES)
            }
            startActivity(intent)
        }
    }

    private fun setupTabs() {
        binding.tvTabTemplates.setOnClickListener {
            binding.viewPagerTemplates.currentItem = TAB_TEMPLATES
        }
        binding.tvTabSavedLocations.setOnClickListener {
            binding.viewPagerTemplates.currentItem = TAB_SAVED_LOCATIONS
        }
    }

    private fun updateTabUI(selectedPosition: Int) {
        if (selectedPosition == TAB_TEMPLATES) {
            // Tab 0: Templates
            binding.tvTabTemplates.setBackgroundResource(R.drawable.bg_template_tab_selected)
            binding.tvTabTemplates.setTextColor(Color.WHITE)

            binding.tvTabSavedLocations.setBackgroundColor(Color.TRANSPARENT)
            binding.tvTabSavedLocations.setTextColor(Color.parseColor("#64748B"))

            // No plus icon on simple templates tab
            binding.btnAddCustomTemplate.visibility = View.GONE
        } else {
            // Tab 1: Saved Locations
            binding.tvTabSavedLocations.setBackgroundResource(R.drawable.bg_template_tab_selected)
            binding.tvTabSavedLocations.setTextColor(Color.WHITE)

            binding.tvTabTemplates.setBackgroundColor(Color.TRANSPARENT)
            binding.tvTabTemplates.setTextColor(Color.parseColor("#64748B"))

            // Show plus FAB on saved locations tab
            binding.btnAddCustomTemplate.visibility = View.VISIBLE
        }
    }

    private fun setupViewPager() {
        val pagerAdapter = TemplatesPagerAdapter()
        binding.viewPagerTemplates.adapter = pagerAdapter
        binding.viewPagerTemplates.offscreenPageLimit = 2

        binding.viewPagerTemplates.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateTabUI(position)
            }
        })

        // Initial tab state
        updateTabUI(TAB_TEMPLATES)
    }

    private inner class TemplatesPagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = 2

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TAB_TEMPLATES) {
                val b = LayoutTabSimpleTemplatesBinding.inflate(inflater, parent, false)
                simpleTemplatesBinding = b
                setupSimpleTemplatesTab(b)
                TemplatesViewHolder(b.root)
            } else {
                val b = LayoutTabSavedLocationsBinding.inflate(inflater, parent, false)
                savedLocationsBinding = b
                setupSavedLocationsTab(b)
                SavedLocationsViewHolder(b.root)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position == TAB_TEMPLATES) {
                populateTemplatePreviews()
            }
        }

        inner class TemplatesViewHolder(view: View) : RecyclerView.ViewHolder(view)
        inner class SavedLocationsViewHolder(view: View) : RecyclerView.ViewHolder(view)
    }

    private fun setupSimpleTemplatesTab(b: LayoutTabSimpleTemplatesBinding) {
        b.cardParisGlass.setOnClickListener { onTemplateSelected(StampTemplate.PARIS_GLASS) }
        b.cardLondonCathedral.setOnClickListener { onTemplateSelected(StampTemplate.LONDON_CATHEDRAL) }
        b.cardPisaCapsule.setOnClickListener { onTemplateSelected(StampTemplate.PISA_CAPSULE) }
        b.cardBigBen.setOnClickListener { onTemplateSelected(StampTemplate.BIG_BEN_MINIMAL) }
        b.cardGpsFull.setOnClickListener { onTemplateSelected(StampTemplate.GPS_FULL_CARD) }
        b.cardPisaPill.setOnClickListener { onTemplateSelected(StampTemplate.PISA_PILL) }
        b.cardNotreDameAlarm.setOnClickListener { onTemplateSelected(StampTemplate.NOTRE_DAME_ALARM) }
        b.cardCathedralLeftMap.setOnClickListener { onTemplateSelected(StampTemplate.CATHEDRAL_LEFT_MAP) }
        b.cardPisaPinCard.setOnClickListener { onTemplateSelected(StampTemplate.PISA_PIN_CARD) }
        b.cardCyberHud.setOnClickListener { onTemplateSelected(StampTemplate.CYBER_HUD) }
        b.cardRetroCam.setOnClickListener { onTemplateSelected(StampTemplate.RETRO_CAM) }
        b.cardFrostedBlur.setOnClickListener { onTemplateSelected(StampTemplate.FROSTED_BLUR) }

        updateSelectionUI()
    }

    private fun setupSavedLocationsTab(b: LayoutTabSavedLocationsBinding) {
        b.rvSavedLocations.layoutManager = LinearLayoutManager(this)
        b.rvSavedLocations.adapter = savedLocationsAdapter

        // Submit current saved items immediately so view is populated right away
        savedLocationsAdapter.submitList(ArrayList(savedTemplatesList))
        b.layoutEmptySavedLocations.isVisible = savedTemplatesList.isEmpty()
        b.rvSavedLocations.isVisible = savedTemplatesList.isNotEmpty()

        b.btnEmptyAddLocation.setOnClickListener {
            openAddCustomLocationDialog()
        }
    }

    private fun observeSavedTemplates() {
        lifecycleScope.launch {
            savedLocationTemplateRepository.getSavedTemplates().collectLatest { list ->
                savedTemplatesList = list
                savedLocationsAdapter.submitList(ArrayList(list))
                savedLocationsBinding?.let { b ->
                    val isEmpty = list.isEmpty()
                    b.layoutEmptySavedLocations.isVisible = isEmpty
                    b.rvSavedLocations.isVisible = !isEmpty
                }
            }
        }
    }

    private fun updateSelectionUI() {
        val b = simpleTemplatesBinding ?: return
        val strokeWidthPx = (3 * resources.displayMetrics.density).toInt()
        val selectedColor = Color.parseColor("#5865F2")
        val transparentColor = Color.TRANSPARENT

        fun applyCardState(card: MaterialCardView, isSelected: Boolean) {
            if (isSelected) {
                card.strokeWidth = strokeWidthPx
                card.strokeColor = selectedColor
            } else {
                card.strokeWidth = 0
                card.strokeColor = transparentColor
            }
        }

        applyCardState(b.cardParisGlass, selectedTemplate == StampTemplate.PARIS_GLASS)
        applyCardState(b.cardLondonCathedral, selectedTemplate == StampTemplate.LONDON_CATHEDRAL)
        applyCardState(b.cardPisaCapsule, selectedTemplate == StampTemplate.PISA_CAPSULE)
        applyCardState(b.cardBigBen, selectedTemplate == StampTemplate.BIG_BEN_MINIMAL)
        applyCardState(b.cardGpsFull, selectedTemplate == StampTemplate.GPS_FULL_CARD)
        applyCardState(b.cardPisaPill, selectedTemplate == StampTemplate.PISA_PILL)
        applyCardState(b.cardNotreDameAlarm, selectedTemplate == StampTemplate.NOTRE_DAME_ALARM)
        applyCardState(b.cardCathedralLeftMap, selectedTemplate == StampTemplate.CATHEDRAL_LEFT_MAP)
        applyCardState(b.cardPisaPinCard, selectedTemplate == StampTemplate.PISA_PIN_CARD)
        applyCardState(b.cardCyberHud, selectedTemplate == StampTemplate.CYBER_HUD)
        applyCardState(b.cardRetroCam, selectedTemplate == StampTemplate.RETRO_CAM)
        applyCardState(b.cardFrostedBlur, selectedTemplate == StampTemplate.FROSTED_BLUR)
    }

    private fun populateTemplatePreviews() {
        val b = simpleTemplatesBinding ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val userLocation = locationHelper.getCurrentLocationData(forCameraStamp = true)
            val settings = getStampSettingsUseCase()
            val stampData = buildStampDataUseCase(userLocation, settings)
            currentStampData = stampData

            withContext(Dispatchers.Main) {
                val mapRes = getMapDrawableRes(settings.mapType)
                val locText = if (settings.showAddress) {
                    stampData.addressLine.ifBlank { stampData.titleLine }
                } else {
                    stampData.titleLine.ifBlank { stampData.addressLine }
                }

                // 1. Paris Glass
                val parisDateHeader = "${stampData.dateLine}   ${if (settings.showTimeZone) stampData.timeZoneLine else stampData.timeLine}"
                b.layoutParisGlass.tvHeaderDate.text = parisDateHeader
                b.layoutParisGlass.tvLocation.text = locText
                b.layoutParisGlass.ivMapBadge.setImageResource(mapRes)
                b.layoutParisGlass.ivMapBadge.isVisible = settings.showMapBadge
                b.layoutParisGlass.ivEditIcon.isVisible = false

                // 2. London Cathedral
                b.layoutLondonCathedral.tvLocation.text = locText
                b.layoutLondonCathedral.tvDate.text = if (settings.showTimeZone && stampData.timeZoneLine.isNotBlank()) {
                    "📅 ${stampData.dateLine} • ${stampData.timeZoneLine}"
                } else {
                    "📅 ${stampData.dateLine}"
                }
                b.layoutLondonCathedral.ivMapBadge.setImageResource(mapRes)
                b.layoutLondonCathedral.ivMapBadge.isVisible = settings.showMapBadge
                b.layoutLondonCathedral.ivEditIcon.isVisible = false

                // 3. Pisa Capsule
                val pisaTimeHeader = if (settings.showTimeZone && stampData.timeZoneLine.isNotBlank()) {
                    "⏰ ${stampData.timeLine}  ${stampData.timeZoneLine}"
                } else {
                    "⏰ ${stampData.timeLine}"
                }
                b.layoutPisaCapsule.tvTimeHeader.text = pisaTimeHeader
                b.layoutPisaCapsule.tvLocation.text = locText
                val pisaCoords = String.format(
                    Locale.US,
                    "Lat: %.4f°    |    Long: %.4f°",
                    stampData.rawLatitude ?: 43.7311,
                    stampData.rawLongitude ?: 10.3965
                )
                b.layoutPisaCapsule.tvCoordinates.text = pisaCoords
                b.layoutPisaCapsule.tvCoordinates.isVisible = settings.showCoordinates
                b.layoutPisaCapsule.ivMapBadge.setImageResource(mapRes)
                b.layoutPisaCapsule.layoutMapBadge.isVisible = settings.showMapBadge

                // 4. Big Ben Minimal
                val bigBenTime = if (settings.showTimeZone && stampData.timeZoneLine.isNotBlank()) {
                    "${stampData.timeLine} ${stampData.timeZoneLine}"
                } else {
                    stampData.timeLine
                }
                b.layoutBigBen.tvTime.text = bigBenTime
                b.layoutBigBen.tvLocation.text = locText
                b.layoutBigBen.ivMapBadge.setImageResource(mapRes)
                b.layoutBigBen.ivMapBadge.isVisible = settings.showMapBadge
                b.layoutBigBen.ivEditIcon.isVisible = false

                // 5. GPS Full Card
                b.layoutGpsFull.tvGpsStatus.text = if (stampData.gpsStatusLine.isNotBlank()) {
                    "●  ${stampData.gpsStatusLine}"
                } else {
                    "●  GPS LOCKED (±2.5M)"
                }
                b.layoutGpsFull.tvTitle.text = stampData.titleLine
                b.layoutGpsFull.tvTitle.isVisible = settings.showTitleHeader
                b.layoutGpsFull.tvAddress.text = stampData.addressLine
                b.layoutGpsFull.tvAddress.isVisible = settings.showAddress
                b.layoutGpsFull.tvCoordinates.text = stampData.coordinatesLine
                b.layoutGpsFull.tvCoordinates.isVisible = settings.showCoordinates
                b.layoutGpsFull.tvTimestamp.text = stampData.timestampLine
                b.layoutGpsFull.ivMapBadge.setImageResource(mapRes)
                b.layoutGpsFull.ivMapBadge.isVisible = settings.showMapBadge
                b.layoutGpsFull.ivEditIcon.isVisible = false

                // 6. Pisa Pill Minimal
                b.layoutPisaPill.tvTime.text = pisaTimeHeader
                b.layoutPisaPill.tvLocation.text = locText
                b.layoutPisaPill.tvCoordinates.text = pisaCoords
                b.layoutPisaPill.tvCoordinates.isVisible = settings.showCoordinates
                b.layoutPisaPill.ivEditIcon.isVisible = false

                // 7. Notre Dame Alarm
                b.layoutNotreDameAlarm.tvTime.text = pisaTimeHeader
                b.layoutNotreDameAlarm.tvLocation.text = locText
                b.layoutNotreDameAlarm.ivMapBadge.setImageResource(mapRes)
                b.layoutNotreDameAlarm.ivMapBadge.isVisible = settings.showMapBadge
                b.layoutNotreDameAlarm.ivEditIcon.isVisible = false

                // 8. Cathedral Left Map
                b.layoutCathedralLeftMap.tvLocation.text = locText
                b.layoutCathedralLeftMap.tvDate.text = if (settings.showTimeZone && stampData.timeZoneLine.isNotBlank()) {
                    "📅 ${stampData.dateLine} • ${stampData.timeZoneLine}"
                } else {
                    "📅 ${stampData.dateLine}"
                }
                b.layoutCathedralLeftMap.ivMapBadge.setImageResource(mapRes)
                b.layoutCathedralLeftMap.ivMapBadge.isVisible = settings.showMapBadge
                b.layoutCathedralLeftMap.ivEditIcon.isVisible = false

                // 9. Pisa Map Pin
                b.layoutPisaPinCard.tvLocation.text = locText
                b.layoutPisaPinCard.tvTime.text = pisaTimeHeader
                b.layoutPisaPinCard.ivMapBadge.setImageResource(mapRes)
                b.layoutPisaPinCard.layoutMapBadge.isVisible = settings.showMapBadge
                b.layoutPisaPinCard.ivEditIcon.isVisible = false

                // 10. Cyber HUD
                val altStr = stampData.rawAltitudeMeters?.toInt()?.let { "${it}M" } ?: "540M"
                b.layoutCyberHud.tvGpsLive.text = if (stampData.gpsStatusLine.isNotBlank()) {
                    "●  ${stampData.gpsStatusLine.uppercase(Locale.US)}  •  ALT: $altStr"
                } else {
                    "●  GPS LIVE  •  ALT: $altStr  •  ACC: ±2.5M"
                }
                b.layoutCyberHud.tvLocation.text = stampData.titleLine
                b.layoutCyberHud.tvLocation.isVisible = settings.showTitleHeader
                b.layoutCyberHud.tvCoordinates.text = stampData.coordinatesLine
                b.layoutCyberHud.tvCoordinates.isVisible = settings.showCoordinates
                b.layoutCyberHud.tvTimestamp.text = stampData.timestampLine
                b.layoutCyberHud.ivMapBadge.setImageResource(mapRes)
                b.layoutCyberHud.ivMapBadge.isVisible = settings.showMapBadge
                b.layoutCyberHud.ivEditIcon.isVisible = false

                // 11. Retro Cam
                val retroDate = if (stampData.dateLine.isNotBlank() && stampData.timeLine.isNotBlank()) {
                    val parts = stampData.dateLine.split("/", "-", ".")
                    val yr = if (parts.size >= 3) parts[2].takeLast(2) else "26"
                    val mo = if (parts.size >= 3) parts[1].padStart(2, '0') else "10"
                    val dy = if (parts.size >= 3) parts[0].padStart(2, '0') else "24"
                    "'$yr $mo $dy   ${stampData.timeLine}"
                } else {
                    stampData.timestampLine.ifBlank { "'26 10 24   15:09:22" }
                }
                b.layoutRetroCam.tvDigitalDate.text = retroDate
                b.layoutRetroCam.tvLocation.text = if (settings.showCoordinates && stampData.coordinatesLine.isNotBlank()) {
                    "📍 $locText  •  ${stampData.coordinatesLine}"
                } else {
                    "📍 $locText"
                }
                b.layoutRetroCam.ivEditIcon.isVisible = false

                // 12. Frosted Blur Glass Card
                b.layoutFrostedBlur.tvLocation.text = locText
                val frostedCoords = if (stampData.rawLatitude != null && stampData.rawLongitude != null) {
                    com.autonoumus.ai.core.util.OsmGeocodingHelper.formatLatitude(stampData.rawLatitude!!) + ", " +
                        com.autonoumus.ai.core.util.OsmGeocodingHelper.formatLongitude(stampData.rawLongitude!!)
                } else {
                    stampData.coordinatesLine.ifBlank { "33.7294° N, 73.0551° E" }
                }
                b.layoutFrostedBlur.tvCoordinates.text = frostedCoords
                b.layoutFrostedBlur.tvCoordinates.isVisible = settings.showCoordinates
                b.layoutFrostedBlur.tvDate.text = stampData.dateLine.ifBlank { "14 DEC 2024" }.uppercase(Locale.US)
                b.layoutFrostedBlur.tvDate.isVisible = true
                b.layoutFrostedBlur.tvTime.text = if (settings.showTimeZone && stampData.timeZoneLine.isNotBlank()) {
                    "${stampData.timeLine.ifBlank { "09:42 AM" }} ${stampData.timeZoneLine}"
                } else {
                    stampData.timeLine.ifBlank { "09:42 AM" }
                }
                b.layoutFrostedBlur.tvTime.isVisible = true
                b.layoutFrostedBlur.ivMapBadge.setImageResource(mapRes)
                b.layoutFrostedBlur.layoutMapFrame.isVisible = settings.showMapBadge
                b.layoutFrostedBlur.ivEditIcon.isVisible = false

                updateSelectionUI()
            }
        }
    }

    private fun getMapDrawableRes(mapType: MapTypeOption): Int {
        return when (mapType) {
            MapTypeOption.NORMAL -> R.drawable.img_map_normal
            MapTypeOption.HYBRID -> R.drawable.img_map_hybrid
            MapTypeOption.SATELLITE -> R.drawable.img_map_satellite
            MapTypeOption.TERRAIN -> R.drawable.img_map_terrain
        }
    }

    private fun onTemplateSelected(template: StampTemplate) {
        selectedTemplate = template
        updateSelectionUI()
        preferenceManager.stampTemplate = template.name
        Toast.makeText(this, "${template.displayTitle} template applied", Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SELECTED_TEMPLATE, template.name))
        navigateToCamera()
    }

    private fun onSavedLocationSelected(item: SavedLocationTemplate) {
        preferenceManager.isCustomLocationOverrideEnabled = true
        preferenceManager.customLocationCountry = item.country
        preferenceManager.customLocationAddress = item.address
        preferenceManager.customLocationLatitude = item.latitude
        preferenceManager.customLocationLongitude = item.longitude

        Toast.makeText(this, "${item.country} template applied", Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SELECTED_TEMPLATE, preferenceManager.stampTemplate))
        navigateToCamera()
    }

    private fun showDeleteLocationConfirmationDialog(item: SavedLocationTemplate) {
        val dialogBinding = DialogDeleteCapturesConfirmationBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val locationName = if (item.country.isNotBlank()) item.country else item.name.ifBlank { "this location" }
        dialogBinding.tvDeleteDialogTitle.text = "Delete Saved Location?"
        dialogBinding.tvDeleteDialogMessage.text = "Are you sure you want to delete \"$locationName\"? This saved location preset will be permanently removed."
        dialogBinding.layoutCheckboxRow.isVisible = false
        dialogBinding.btnConfirmDeleteCaptures.text = "Delete Location"
        dialogBinding.btnKeepFiles.text = "Cancel"

        dialogBinding.btnKeepFiles.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnConfirmDeleteCaptures.setOnClickListener {
            dialog.dismiss()
            deleteSavedLocation(item)
        }

        dialog.show()
    }

    private fun deleteSavedLocation(item: SavedLocationTemplate) {
        lifecycleScope.launch {
            savedLocationTemplateRepository.deleteTemplate(item.id)
            Toast.makeText(this@TemplatesActivity, "Location template removed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateToCamera() {
        val intent = Intent(this, CameraActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun setupFab() {
        binding.btnAddCustomTemplate.setOnClickListener {
            openAddCustomLocationDialog()
        }
    }

    private fun openAddCustomLocationDialog() {
        val sheet = CustomLocationStampBottomSheetDialog.newInstance()
        sheet.isSaveOnlyMode = false
        sheet.onTemplateSaved = { savedTemplate ->
            preferenceManager.isCustomLocationOverrideEnabled = true
            preferenceManager.customLocationCountry = savedTemplate.country
            preferenceManager.customLocationAddress = savedTemplate.address
            preferenceManager.customLocationLatitude = savedTemplate.latitude
            preferenceManager.customLocationLongitude = savedTemplate.longitude
            populateTemplatePreviews()
            Toast.makeText(this, "Saved Location: ${savedTemplate.country}", Toast.LENGTH_SHORT).show()
        }
        sheet.show(supportFragmentManager, CustomLocationStampBottomSheetDialog.TAG)
    }
}
