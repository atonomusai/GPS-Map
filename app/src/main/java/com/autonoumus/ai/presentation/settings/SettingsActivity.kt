package com.autonoumus.ai.presentation.settings

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.databinding.ActivitySettingsBinding
import com.autonoumus.ai.presentation.home.RateUsBottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : BaseActivity<ActivitySettingsBinding>() {

    @Inject
    lateinit var preferenceManager: PreferenceManager

    override fun inflateBinding(inflater: LayoutInflater): ActivitySettingsBinding {
        return ActivitySettingsBinding.inflate(inflater)
    }

    override fun initViews() {
        setupToolbar()
        loadUnitPreferences()
        updateRateUsVisibility()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        loadUnitPreferences()
        updateRateUsVisibility()
    }

    private fun updateRateUsVisibility() {
        val hasRated = preferenceManager.hasRatedApp
        binding.rowRateUs.visibility = if (hasRated) View.GONE else View.VISIBLE
        binding.dividerRateUs.visibility = if (hasRated) View.GONE else View.VISIBLE
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun loadUnitPreferences() {
        updateDistanceUnitUi(preferenceManager.isDistanceUnitKm)
        updateSpeedUnitUi(preferenceManager.isSpeedUnitKmh)
    }

    private fun setupListeners() {
        // General Section
        binding.rowStampSettings.setOnClickListener {
            val intent = Intent(this, TimestampSettingsActivity::class.java).apply {
                putExtra(TimestampSettingsActivity.EXTRA_SCREEN_MODE, TimestampSettingsActivity.MODE_CAMERA)
            }
            startActivity(intent)
        }


        // Units Section - Distance
        binding.tvUnitKilometers.setOnClickListener {
            preferenceManager.isDistanceUnitKm = true
            updateDistanceUnitUi(true)
        }

        binding.tvUnitMiles.setOnClickListener {
            preferenceManager.isDistanceUnitKm = false
            updateDistanceUnitUi(false)
        }

        // Units Section - Speed
        binding.tvUnitKmh.setOnClickListener {
            preferenceManager.isSpeedUnitKmh = true
            updateSpeedUnitUi(true)
        }

        binding.tvUnitMph.setOnClickListener {
            preferenceManager.isSpeedUnitKmh = false
            updateSpeedUnitUi(false)
        }

        // Help & Support Section
        binding.rowShareApp.setOnClickListener {
            shareApp()
        }

        binding.rowRateUs.setOnClickListener {
            showRateUsSheet()
        }

        binding.rowPrivacyPolicy.setOnClickListener {
            com.autonoumus.ai.core.util.AppLinks.openUrl(this, com.autonoumus.ai.core.util.AppLinks.privacyPolicyUrl)
        }

        // Real version from the build (versionName in app/build.gradle.kts), e.g. "v1.0"
        binding.tvAppVersionValue.text = "${com.autonoumus.ai.BuildConfig.VERSION_NAME}"

        // About Section (hidden until a Terms of Service page exists - TERMS_URL in local.properties)
        binding.rowTermsOfService.visibility =
            if (com.autonoumus.ai.core.util.AppLinks.termsUrl.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        binding.rowTermsOfService.setOnClickListener {
            com.autonoumus.ai.core.util.AppLinks.openUrl(this, com.autonoumus.ai.core.util.AppLinks.termsUrl)
        }
    }

    private fun updateDistanceUnitUi(isKm: Boolean) {
        if (isKm) {
            binding.tvUnitKilometers.setBackgroundResource(R.drawable.bg_segment_active)
            binding.tvUnitKilometers.setTextColor(ContextCompat.getColor(this, R.color.white))

            binding.tvUnitMiles.setBackgroundResource(R.drawable.bg_segment_inactive)
            binding.tvUnitMiles.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            binding.tvUnitMiles.setBackgroundResource(R.drawable.bg_segment_active)
            binding.tvUnitMiles.setTextColor(ContextCompat.getColor(this, R.color.white))

            binding.tvUnitKilometers.setBackgroundResource(R.drawable.bg_segment_inactive)
            binding.tvUnitKilometers.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun updateSpeedUnitUi(isKmh: Boolean) {
        if (isKmh) {
            binding.tvUnitKmh.setBackgroundResource(R.drawable.bg_segment_active)
            binding.tvUnitKmh.setTextColor(ContextCompat.getColor(this, R.color.white))

            binding.tvUnitMph.setBackgroundResource(R.drawable.bg_segment_inactive)
            binding.tvUnitMph.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            binding.tvUnitMph.setBackgroundResource(R.drawable.bg_segment_active)
            binding.tvUnitMph.setTextColor(ContextCompat.getColor(this, R.color.white))

            binding.tvUnitKmh.setBackgroundResource(R.drawable.bg_segment_inactive)
            binding.tvUnitKmh.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun shareApp() {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(
                Intent.EXTRA_TEXT,
                "Check out ${getString(com.autonoumus.ai.R.string.app_name)} app: https://play.google.com/store/apps/details?id=$packageName"
            )
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Share App"))
    }

    private fun showRateUsSheet() {
        val sheet = RateUsBottomSheetDialog.newInstance(isFromSettings = true)
        sheet.onDismissedOrRated = {
            updateRateUsVisibility()
        }
        sheet.show(supportFragmentManager, RateUsBottomSheetDialog.TAG)
    }
}
