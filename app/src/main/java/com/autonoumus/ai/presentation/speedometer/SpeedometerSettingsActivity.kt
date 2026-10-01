package com.autonoumus.ai.presentation.speedometer

import android.graphics.Color
import android.view.LayoutInflater
import android.widget.SeekBar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.databinding.ActivitySpeedometerSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SpeedometerSettingsActivity : BaseActivity<ActivitySpeedometerSettingsBinding>() {

    @Inject
    lateinit var preferenceManager: PreferenceManager

    private var currentLimit: Int = 60

    override fun inflateBinding(inflater: LayoutInflater): ActivitySpeedometerSettingsBinding {
        return ActivitySpeedometerSettingsBinding.inflate(inflater)
    }

    override fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.speedometerSettingsRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            binding.layoutTopBar.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                topMargin = bars.top
            }
            binding.nestedScrollView.updatePadding(bottom = bars.bottom + (32 * density).toInt())
            insets
        }
    }

    override fun initViews() {
        setupToolbar()
        loadPreferences()
        setupSpeedLimitControls()
        setupPreferenceSwitches()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }







    private fun loadPreferences() {
        currentLimit = preferenceManager.speedLimit
        if (currentLimit > 0) {
            currentLimit = currentLimit.coerceIn(30, 180)
            binding.tvSpeedLimitLarge.text = currentLimit.toString()
            binding.seekBarSpeedLimit.progress = currentLimit - 30
            binding.switchSpeedAlert.isEnabled = true
            binding.switchSpeedAlert.alpha = 1.0f
            binding.switchSpeedAlert.isChecked = preferenceManager.isSpeedLimitAlertEnabled
            binding.switchVibrationAlert.isEnabled = true
            binding.switchVibrationAlert.alpha = 1.0f
            binding.switchVibrationAlert.isChecked = preferenceManager.isVibrationAlertEnabled
        } else {
            currentLimit = 0
            binding.tvSpeedLimitLarge.text = "OFF"
            binding.seekBarSpeedLimit.progress = 0
            binding.switchSpeedAlert.isChecked = false
            binding.switchSpeedAlert.isEnabled = false
            binding.switchSpeedAlert.alpha = 0.5f
            binding.switchVibrationAlert.isChecked = false
            binding.switchVibrationAlert.isEnabled = false
            binding.switchVibrationAlert.alpha = 0.5f
        }

        val isKmh = preferenceManager.isSpeedUnitKmh
        val unit = if (isKmh) "KM/H" else "MPH"
        binding.tvToleranceSubtitle.text = "Allow +5 $unit buffer before alert"

        binding.switchTolerance.isChecked = preferenceManager.isSpeedToleranceBufferEnabled
    }

    private fun setupSpeedLimitControls() {
        binding.btnMinusLimit.setOnClickListener {
            if (currentLimit <= 30) {
                currentLimit = 0
            } else {
                currentLimit = (currentLimit - 5).coerceAtLeast(30)
            }
            updateLimitDisplay()
        }

        binding.btnPlusLimit.setOnClickListener {
            if (currentLimit == 0) {
                currentLimit = 30
            } else if (currentLimit < 180) {
                currentLimit = (currentLimit + 5).coerceAtMost(180)
            }
            updateLimitDisplay()
        }

        binding.seekBarSpeedLimit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentLimit = 30 + progress
                    updateLimitDisplay()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateLimitDisplay() {
        if (currentLimit == 0) {
            binding.tvSpeedLimitLarge.text = "OFF"
            binding.seekBarSpeedLimit.progress = 0
            preferenceManager.speedLimit = 0
            preferenceManager.isSpeedLimitAlertEnabled = false
            preferenceManager.isVibrationAlertEnabled = false
            binding.switchSpeedAlert.isChecked = false
            binding.switchSpeedAlert.isEnabled = false
            binding.switchSpeedAlert.alpha = 0.5f
            binding.switchVibrationAlert.isChecked = false
            binding.switchVibrationAlert.isEnabled = false
            binding.switchVibrationAlert.alpha = 0.5f
        } else {
            binding.tvSpeedLimitLarge.text = currentLimit.toString()
            binding.seekBarSpeedLimit.progress = currentLimit - 30
            preferenceManager.speedLimit = currentLimit
            preferenceManager.isSpeedLimitAlertEnabled = true
            preferenceManager.isVibrationAlertEnabled = true
            binding.switchSpeedAlert.isEnabled = true
            binding.switchSpeedAlert.alpha = 1.0f
            binding.switchSpeedAlert.isChecked = true
            binding.switchVibrationAlert.isEnabled = true
            binding.switchVibrationAlert.alpha = 1.0f
            binding.switchVibrationAlert.isChecked = true
        }
    }

    private fun setupPreferenceSwitches() {
        binding.switchSpeedAlert.setOnCheckedChangeListener { _, isChecked ->
            if (currentLimit > 0) {
                preferenceManager.isSpeedLimitAlertEnabled = isChecked
            }
        }

        binding.switchVibrationAlert.setOnCheckedChangeListener { _, isChecked ->
            if (currentLimit > 0) {
                preferenceManager.isVibrationAlertEnabled = isChecked
            }
        }

        binding.switchTolerance.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.isSpeedToleranceBufferEnabled = isChecked
        }
    }
}
