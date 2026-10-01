package com.autonoumus.ai.presentation.compass

import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.databinding.ActivityCompassThemesBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CompassThemesActivity : BaseActivity<ActivityCompassThemesBinding>() {

    enum class CompassTheme(val title: String) {
        CLASSIC_DIAL("Classic Navigation"),
        GEOMETRIC_STAR("Geometric Star"),
        DIGITAL_HUD("Digital HUD"),
        TACTICAL_GAUGE("Tactical Gauge"),
        VINTAGE_RED("Vintage Rose"),
        MARITIME_ROSE("Maritime Explorer")
    }

    @Inject
    lateinit var preferenceManager: PreferenceManager

    private var selectedTheme: CompassTheme = CompassTheme.CLASSIC_DIAL

    companion object {
        const val EXTRA_THEME_ID = "extra_theme_id"
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityCompassThemesBinding {
        return ActivityCompassThemesBinding.inflate(inflater)
    }

    override fun initViews() {
        setupToolbar()
        loadCurrentTheme()
        setupListeners()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun loadCurrentTheme() {
        val savedThemeName = preferenceManager.compassTheme
        selectedTheme = try {
            CompassTheme.valueOf(savedThemeName)
        } catch (e: Exception) {
            CompassTheme.CLASSIC_DIAL
        }
        updateThemeSelectionUi()
    }

    private fun setupListeners() {
        binding.cardThemeClassicDial.setOnClickListener {
            selectedTheme = CompassTheme.CLASSIC_DIAL
            updateThemeSelectionUi()
        }

        binding.cardThemeGeometricStar.setOnClickListener {
            selectedTheme = CompassTheme.GEOMETRIC_STAR
            updateThemeSelectionUi()
        }

        binding.cardThemeDigitalHud.setOnClickListener {
            selectedTheme = CompassTheme.DIGITAL_HUD
            updateThemeSelectionUi()
        }

        binding.cardThemeTacticalGauge.setOnClickListener {
            selectedTheme = CompassTheme.TACTICAL_GAUGE
            updateThemeSelectionUi()
        }

        binding.cardThemeVintageRed.setOnClickListener {
            selectedTheme = CompassTheme.VINTAGE_RED
            updateThemeSelectionUi()
        }

        binding.cardThemeMaritimeRose.setOnClickListener {
            selectedTheme = CompassTheme.MARITIME_ROSE
            updateThemeSelectionUi()
        }

        binding.btnApply.setOnClickListener {
            preferenceManager.compassTheme = selectedTheme.name
            Toast.makeText(this, "${selectedTheme.title} applied", Toast.LENGTH_SHORT).show()
            val resultIntent = Intent().apply {
                putExtra(EXTRA_THEME_ID, selectedTheme.name)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun updateThemeSelectionUi() {
        // Theme 1: Classic Dial (Dark Card)
        val isClassic = selectedTheme == CompassTheme.CLASSIC_DIAL
        binding.cardThemeClassicDial.setBackgroundResource(
            if (isClassic) R.drawable.bg_theme_card_selected_white else R.drawable.bg_theme_card_white
        )
        binding.ivCheckClassicDial.visibility = if (isClassic) View.VISIBLE else View.GONE

        // Theme 2: Geometric Star (Dark Card)
        val isGeometric = selectedTheme == CompassTheme.GEOMETRIC_STAR
        binding.cardThemeGeometricStar.setBackgroundResource(
            if (isGeometric) R.drawable.bg_theme_card_selected_white else R.drawable.bg_theme_card_white
        )
        binding.ivCheckGeometricStar.visibility = if (isGeometric) View.VISIBLE else View.GONE

        // Theme 3: Digital HUD (Dark Card)
        val isHud = selectedTheme == CompassTheme.DIGITAL_HUD
        binding.cardThemeDigitalHud.setBackgroundResource(
            if (isHud) R.drawable.bg_theme_card_selected_white else R.drawable.bg_theme_card_white
        )
        binding.ivCheckDigitalHud.visibility = if (isHud) View.VISIBLE else View.GONE

        // Theme 4: Tactical Gauge (Dark Card)
        val isTactical = selectedTheme == CompassTheme.TACTICAL_GAUGE
        binding.cardThemeTacticalGauge.setBackgroundResource(
            if (isTactical) R.drawable.bg_theme_card_selected_white else R.drawable.bg_theme_card_white
        )
        binding.ivCheckTacticalGauge.visibility = if (isTactical) View.VISIBLE else View.GONE

        // Theme 5: Vintage Red (Light Card)
        val isVintage = selectedTheme == CompassTheme.VINTAGE_RED
        binding.cardThemeVintageRed.setBackgroundResource(
            if (isVintage) R.drawable.bg_theme_card_selected_white else R.drawable.bg_theme_card_white
        )
        binding.ivCheckVintageRed.visibility = if (isVintage) View.VISIBLE else View.GONE

        // Theme 6: Maritime Rose (Light Card)
        val isMaritime = selectedTheme == CompassTheme.MARITIME_ROSE
        binding.cardThemeMaritimeRose.setBackgroundResource(
            if (isMaritime) R.drawable.bg_theme_card_selected_white else R.drawable.bg_theme_card_white
        )
        binding.ivCheckMaritimeRose.visibility = if (isMaritime) View.VISIBLE else View.GONE
    }
}
