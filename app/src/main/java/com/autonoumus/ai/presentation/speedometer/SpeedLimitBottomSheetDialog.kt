package com.autonoumus.ai.presentation.speedometer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseBottomSheetDialogFragment
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.databinding.BottomSheetSpeedLimitBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SpeedLimitBottomSheetDialog : BaseBottomSheetDialogFragment<BottomSheetSpeedLimitBinding>() {

    @Inject
    lateinit var preferenceManager: PreferenceManager

    private var currentLimit: Int = 60
    private var savedAudioAlertSetting: Boolean = true
    var onLimitAppliedListener: ((limit: Int, isAudioEnabled: Boolean) -> Unit)? = null

    override fun inflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): BottomSheetSpeedLimitBinding {
        return BottomSheetSpeedLimitBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        currentLimit = preferenceManager.speedLimit
        savedAudioAlertSetting = preferenceManager.isSpeedLimitAlertEnabled

        updateDisplay()
        setupListeners()
    }

    private fun setupListeners() {
        binding.btnClose.setOnClickListener {
            dismiss()
        }

        binding.btnDecrement.setOnClickListener {
            if (currentLimit > 0) {
                currentLimit = (currentLimit - 5).coerceAtLeast(0)
                if (currentLimit == 0) {
                    savedAudioAlertSetting = false
                }
                updateDisplay()
            }
        }

        binding.btnIncrement.setOnClickListener {
            if (currentLimit == 0) {
                currentLimit = 30
                savedAudioAlertSetting = true
            } else if (currentLimit < 180) {
                currentLimit = (currentLimit + 5).coerceAtMost(180)
            }
            updateDisplay()
        }

        binding.chipLimit40.setOnClickListener { selectPreset(40) }
        binding.chipLimit60.setOnClickListener { selectPreset(60) }
        binding.chipLimit80.setOnClickListener { selectPreset(80) }
        binding.chipLimit100.setOnClickListener { selectPreset(100) }
        binding.chipLimit120.setOnClickListener { selectPreset(120) }
        binding.chipLimit140.setOnClickListener { selectPreset(140) }
        binding.chipLimit160.setOnClickListener { selectPreset(160) }
        binding.chipLimitOff.setOnClickListener { selectPreset(0) }

        binding.switchAudioAlert.setOnCheckedChangeListener { _, isChecked ->
            if (currentLimit > 0) {
                savedAudioAlertSetting = isChecked
            }
        }

        binding.btnApplyLimit.setOnClickListener {
            if (currentLimit == 0) {
                preferenceManager.speedLimit = 0
                preferenceManager.isSpeedLimitAlertEnabled = false
                preferenceManager.isVibrationAlertEnabled = false
                onLimitAppliedListener?.invoke(0, false)
            } else {
                preferenceManager.speedLimit = currentLimit
                preferenceManager.isSpeedLimitAlertEnabled = binding.switchAudioAlert.isChecked
                preferenceManager.isVibrationAlertEnabled = true
                onLimitAppliedListener?.invoke(currentLimit, binding.switchAudioAlert.isChecked)
            }
            dismiss()
        }
    }

    private fun selectPreset(preset: Int) {
        currentLimit = preset
        if (preset > 0) {
            savedAudioAlertSetting = true
        } else {
            savedAudioAlertSetting = false
        }
        updateDisplay()
    }

    private fun updateDisplay() {
        val unit = if (preferenceManager.isSpeedUnitKmh) "KM/H" else "MPH"
        binding.tvCurrentLimitValue.text = if (currentLimit > 0) "$currentLimit $unit" else "Off"

        if (currentLimit == 0) {
            binding.switchAudioAlert.isChecked = false
            binding.switchAudioAlert.isEnabled = false
            binding.switchAudioAlert.alpha = 0.5f
        } else {
            binding.switchAudioAlert.isEnabled = true
            binding.switchAudioAlert.alpha = 1.0f
            binding.switchAudioAlert.isChecked = savedAudioAlertSetting
        }

        val activeBg = R.drawable.bg_segment_active
        val inactiveBg = R.drawable.bg_segment_inactive
        val activeColor = ContextCompat.getColor(requireContext(), R.color.white)
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)

        // Reset all chips
        val chips = listOf(
            Pair(binding.chipLimit40, 40),
            Pair(binding.chipLimit60, 60),
            Pair(binding.chipLimit80, 80),
            Pair(binding.chipLimit100, 100),
            Pair(binding.chipLimit120, 120),
            Pair(binding.chipLimit140, 140),
            Pair(binding.chipLimit160, 160),
            Pair(binding.chipLimitOff, 0)
        )

        for ((chip, value) in chips) {
            val isSelected = currentLimit == value
            chip.setBackgroundResource(if (isSelected) activeBg else inactiveBg)
            chip.setTextColor(if (isSelected) activeColor else inactiveColor)
        }
    }

    companion object {
        const val TAG = "SpeedLimitBottomSheet"

        fun newInstance(): SpeedLimitBottomSheetDialog {
            return SpeedLimitBottomSheetDialog()
        }
    }
}
