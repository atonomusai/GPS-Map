package com.autonoumus.ai.presentation.camera

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.camera.video.Quality
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseBottomSheetDialogFragment
import com.autonoumus.ai.databinding.BottomSheetCameraSettingsBinding
import com.autonoumus.ai.databinding.ItemCameraSettingsChipBinding
import com.autonoumus.ai.databinding.ItemFrameRateChipBinding
import com.autonoumus.ai.domain.model.CameraCapabilities
import com.autonoumus.ai.domain.model.PhotoResolutionOption
import com.autonoumus.ai.domain.model.VideoQualityOption
import com.autonoumus.ai.data.local.PreferenceManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Shows capture options (Photo Resolution, Video Quality, Frame Rate) matching the reference UI design.
 * Selections are staged in the sheet and committed together via "Save Preference"; "Reset
 * Defaults" clears every persisted override immediately.
 */
@AndroidEntryPoint
class CameraSettingsBottomSheet : BaseBottomSheetDialogFragment<BottomSheetCameraSettingsBinding>() {

    @Inject
    lateinit var preferenceManager: PreferenceManager

    private val viewModel: CameraViewModel by activityViewModels()

    private var stagedVideoQuality: Quality? = null
    private var stagedPhotoResolution: PhotoResolutionOption? = null
    private var stagedFrameRate: Int = 0

    override fun inflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): BottomSheetCameraSettingsBinding {
        return BottomSheetCameraSettingsBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        stagedFrameRate = preferenceManager.cameraFrameRateFps

        binding.btnResetDefaults.setOnClickListener {
            viewModel.resetCameraSettings()
            stagedVideoQuality = null
            stagedPhotoResolution = null
            stagedFrameRate = 0
            render(viewModel.uiState.value.cameraCapabilities)
        }

        binding.btnSavePreference.setOnClickListener {
            viewModel.applyCameraSettings(stagedVideoQuality, stagedPhotoResolution, stagedFrameRate.takeIf { it > 0 })
            dismiss()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(state.cameraCapabilities)
                }
            }
        }
    }

    private fun render(capabilities: CameraCapabilities) {
        renderPhotoResolution(capabilities.photoResolutions)
        renderVideoQuality(capabilities.videoQualities)
        renderFrameRate(capabilities.frameRates)
    }

    private fun renderPhotoResolution(options: List<PhotoResolutionOption>) {
        val tiers = pickResolutionTiers(options)
        when {
            tiers.isEmpty() -> {
                binding.tvPhotoResolutionHeader.visibility = View.GONE
                binding.groupPhotoResolution.visibility = View.GONE
            }
            tiers.size == 1 -> {
                binding.tvPhotoResolutionHeader.visibility = View.VISIBLE
                binding.groupPhotoResolution.visibility = View.VISIBLE
                binding.scrollPhotoResolution.visibility = View.GONE
                binding.tvPhotoResolutionReadOnly.visibility = View.VISIBLE
                binding.tvPhotoResolutionReadOnly.text = tiers.first().first
            }
            else -> {
                binding.tvPhotoResolutionHeader.visibility = View.VISIBLE
                binding.groupPhotoResolution.visibility = View.VISIBLE
                binding.scrollPhotoResolution.visibility = View.VISIBLE
                binding.tvPhotoResolutionReadOnly.visibility = View.GONE

                val currentKey = stagedPhotoResolution?.let { "${it.width}x${it.height}" } ?: preferenceManager.cameraPhotoResolution
                val defaultOption = tiers.first().second
                val activeKey = currentKey ?: "${defaultOption.width}x${defaultOption.height}"
                if (stagedPhotoResolution == null) {
                    stagedPhotoResolution = defaultOption
                }

                binding.llPhotoResolutionChips.removeAllViews()
                for ((tierLabel, option) in tiers) {
                    val chip = ItemCameraSettingsChipBinding.inflate(layoutInflater, binding.llPhotoResolutionChips, false)
                    chip.tvChipLabel.text = tierLabel
                    val key = "${option.width}x${option.height}"
                    val isSelected = key == activeKey
                    applyChipStyle(chip.tvChipLabel, isSelected)
                    chip.tvChipLabel.setOnClickListener {
                        stagedPhotoResolution = option
                        renderPhotoResolution(options)
                    }
                    binding.llPhotoResolutionChips.addView(chip.root)
                }
            }
        }
    }

    private fun renderVideoQuality(options: List<VideoQualityOption>) {
        when {
            options.isEmpty() -> {
                binding.tvVideoQualityHeader.visibility = View.GONE
                binding.groupVideoQuality.visibility = View.GONE
            }
            options.size == 1 -> {
                binding.tvVideoQualityHeader.visibility = View.VISIBLE
                binding.groupVideoQuality.visibility = View.VISIBLE
                binding.scrollVideoQuality.visibility = View.GONE
                binding.tvVideoQualityReadOnly.visibility = View.VISIBLE
                binding.tvVideoQualityReadOnly.text = labelForVideoQuality(options.first().quality)
            }
            else -> {
                binding.tvVideoQualityHeader.visibility = View.VISIBLE
                binding.groupVideoQuality.visibility = View.VISIBLE
                binding.scrollVideoQuality.visibility = View.VISIBLE
                binding.tvVideoQualityReadOnly.visibility = View.GONE

                val currentName = stagedVideoQuality?.let { nameForQuality(it) } ?: preferenceManager.cameraVideoQualityName
                val defaultQuality = options.firstOrNull { it.quality == Quality.FHD }?.quality ?: options.first().quality
                val activeQualityName = currentName ?: nameForQuality(defaultQuality)
                if (stagedVideoQuality == null) {
                    stagedVideoQuality = defaultQuality
                }

                binding.llVideoQualityChips.removeAllViews()
                for (option in options) {
                    val chip = ItemCameraSettingsChipBinding.inflate(layoutInflater, binding.llVideoQualityChips, false)
                    chip.tvChipLabel.text = labelForVideoQuality(option.quality)
                    val isSelected = nameForQuality(option.quality) == activeQualityName
                    applyChipStyle(chip.tvChipLabel, isSelected)
                    chip.tvChipLabel.setOnClickListener {
                        stagedVideoQuality = option.quality
                        renderVideoQuality(options)
                    }
                    binding.llVideoQualityChips.addView(chip.root)
                }
            }
        }
    }

    private fun renderFrameRate(frameRates: List<Int>) {
        if (frameRates.isEmpty()) {
            binding.groupFrameRate.visibility = View.GONE
            binding.dividerVideoCard.visibility = View.GONE
            return
        }
        binding.groupFrameRate.visibility = View.VISIBLE
        binding.dividerVideoCard.visibility = View.VISIBLE

        val defaultFps = if (frameRates.contains(30)) 30 else (frameRates.firstOrNull() ?: 30)
        val currentFps = stagedFrameRate.takeIf { it > 0 }
            ?: preferenceManager.cameraFrameRateFps.takeIf { it > 0 }
            ?: defaultFps

        if (stagedFrameRate <= 0) {
            stagedFrameRate = currentFps
        }

        binding.llFrameRateChips.removeAllViews()
        for (fps in frameRates) {
            val chip = ItemFrameRateChipBinding.inflate(layoutInflater, binding.llFrameRateChips, false)
            chip.tvChipLabel.text = String.format(Locale.US, "%d FPS", fps)
            applyChipStyle(chip.tvChipLabel, fps == currentFps)
            chip.tvChipLabel.setOnClickListener {
                stagedFrameRate = fps
                renderFrameRate(frameRates)
            }
            binding.llFrameRateChips.addView(chip.root)
        }
    }

    /** Buckets real device-reported photo sizes into friendly tiers: High / Standard / Low (Saver). */
    private fun pickResolutionTiers(options: List<PhotoResolutionOption>): List<Pair<String, PhotoResolutionOption>> {
        if (options.isEmpty()) return emptyList()
        val sorted = options.sortedByDescending { it.megapixels }
        val high = sorted.first()
        val low = sorted.last()
        val standard = sorted[sorted.size / 2]

        val result = LinkedHashMap<PhotoResolutionOption, String>()
        result[high] = "High (${classify(high)})"
        if (standard != high && standard != low) {
            result[standard] = "Standard (${classify(standard)})"
        }
        if (low != high) {
            result[low] = "Low (Saver)"
        }
        return result.map { (option, label) -> label to option }
    }

    private fun classify(option: PhotoResolutionOption): String {
        val minDim = minOf(option.width, option.height)
        return when {
            minDim >= 2160 -> "4K"
            minDim >= 1080 -> "1080p"
            minDim >= 720 -> "720p"
            else -> "${minDim}p"
        }
    }

    private fun labelForVideoQuality(quality: Quality): String = when (quality) {
        Quality.UHD -> "4K Ultra HD"
        Quality.FHD -> "1080p Full HD"
        Quality.HD -> "720p HD"
        Quality.SD -> "480p SD"
        else -> quality.toString()
    }

    private fun applyChipStyle(view: TextView, selected: Boolean) {
        view.setBackgroundResource(if (selected) R.drawable.bg_segment_pill_selected else R.drawable.bg_segment_pill_unselected)
        view.setTextColor(
            ContextCompat.getColor(requireContext(), if (selected) R.color.white else R.color.text_primary)
        )
        try {
            view.typeface = androidx.core.content.res.ResourcesCompat.getFont(
                requireContext(),
                if (selected) R.font.instrument_sans_bold else R.font.instrument_sans_medium
            )
        } catch (e: Exception) {
            // fallback
        }
    }

    private fun nameForQuality(quality: Quality): String = when (quality) {
        Quality.UHD -> "UHD"
        Quality.FHD -> "FHD"
        Quality.HD -> "HD"
        Quality.SD -> "SD"
        else -> "UHD"
    }
}
