package com.autonoumus.ai.presentation.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseBottomSheetDialogFragment
import com.autonoumus.ai.databinding.BottomSheetStampPositionBinding
import com.autonoumus.ai.domain.model.StampPosition

/**
 * Redesigned Stamp Positioning bottom sheet dialog featuring a 2x2 visual selection grid
 * with rounded top corners only and dynamic selection state styling.
 */
class StampPositionBottomSheet : BaseBottomSheetDialogFragment<BottomSheetStampPositionBinding>() {

    private var stagedPosition: StampPosition = StampPosition.BOTTOM_LEFT
    private var requestKey: String = REQ_KEY_STAMP_POSITION

    override fun inflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): BottomSheetStampPositionBinding {
        return BottomSheetStampPositionBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val positionName = arguments?.getString(ARG_SELECTED_POSITION).orEmpty()
        stagedPosition = try {
            StampPosition.valueOf(positionName)
        } catch (e: Exception) {
            StampPosition.BOTTOM_LEFT
        }
        requestKey = arguments?.getString(ARG_REQUEST_KEY) ?: REQ_KEY_STAMP_POSITION

        setupClickListeners()
        updateCardStyles()
    }

    private fun setupClickListeners() {
        binding.btnClose.setOnClickListener {
            dismiss()
        }

        binding.cardTopLeft.setOnClickListener {
            stagedPosition = StampPosition.TOP_LEFT
            updateCardStyles()
        }

        binding.cardTopRight.setOnClickListener {
            stagedPosition = StampPosition.TOP_RIGHT
            updateCardStyles()
        }

        binding.cardBottomLeft.setOnClickListener {
            stagedPosition = StampPosition.BOTTOM_LEFT
            updateCardStyles()
        }

        binding.cardBottomRight.setOnClickListener {
            stagedPosition = StampPosition.BOTTOM_RIGHT
            updateCardStyles()
        }

        binding.btnApply.setOnClickListener {
            setFragmentResult(requestKey, bundleOf(BUNDLE_KEY_POSITION to stagedPosition.name))
            dismiss()
        }
    }

    private fun updateCardStyles() {
        val selectedBg = ColorStateList.valueOf(Color.parseColor("#EEF2FF"))
        val unselectedBg = ColorStateList.valueOf(Color.parseColor("#F8FAFC"))
        val selectedStroke = ColorStateList.valueOf(Color.parseColor("#5865F2"))
        val transparentStroke = ColorStateList.valueOf(Color.TRANSPARENT)
        val selectedTextColor = Color.parseColor("#5865F2")
        val unselectedTextColor = Color.parseColor("#1F2937")
        val strokeWidthPx = (1.5f * resources.displayMetrics.density).toInt()

        // 1. Top-Left
        val isTopLeft = stagedPosition == StampPosition.TOP_LEFT
        binding.cardTopLeft.setCardBackgroundColor(if (isTopLeft) selectedBg else unselectedBg)
        binding.cardTopLeft.strokeColor = if (isTopLeft) selectedStroke.defaultColor else transparentStroke.defaultColor
        binding.cardTopLeft.strokeWidth = if (isTopLeft) strokeWidthPx else 0
        binding.tvTopLeftLabel.setTextColor(if (isTopLeft) selectedTextColor else unselectedTextColor)
        binding.ivTopLeftIcon.setImageResource(
            if (isTopLeft) R.drawable.ic_stamp_pos_top_left_selected else R.drawable.ic_stamp_pos_top_left_unselected
        )

        // 2. Top-Right
        val isTopRight = stagedPosition == StampPosition.TOP_RIGHT
        binding.cardTopRight.setCardBackgroundColor(if (isTopRight) selectedBg else unselectedBg)
        binding.cardTopRight.strokeColor = if (isTopRight) selectedStroke.defaultColor else transparentStroke.defaultColor
        binding.cardTopRight.strokeWidth = if (isTopRight) strokeWidthPx else 0
        binding.tvTopRightLabel.setTextColor(if (isTopRight) selectedTextColor else unselectedTextColor)
        binding.ivTopRightIcon.setImageResource(
            if (isTopRight) R.drawable.ic_stamp_pos_top_right_selected else R.drawable.ic_stamp_pos_top_right_unselected
        )

        // 3. Bottom-Left
        val isBottomLeft = stagedPosition == StampPosition.BOTTOM_LEFT || stagedPosition == StampPosition.BOTTOM_FULL_WIDTH
        binding.cardBottomLeft.setCardBackgroundColor(if (isBottomLeft) selectedBg else unselectedBg)
        binding.cardBottomLeft.strokeColor = if (isBottomLeft) selectedStroke.defaultColor else transparentStroke.defaultColor
        binding.cardBottomLeft.strokeWidth = if (isBottomLeft) strokeWidthPx else 0
        binding.tvBottomLeftLabel.setTextColor(if (isBottomLeft) selectedTextColor else unselectedTextColor)
        binding.ivBottomLeftIcon.setImageResource(
            if (isBottomLeft) R.drawable.ic_stamp_pos_bottom_left_selected else R.drawable.ic_stamp_pos_bottom_left_unselected
        )

        // 4. Bottom-Right
        val isBottomRight = stagedPosition == StampPosition.BOTTOM_RIGHT
        binding.cardBottomRight.setCardBackgroundColor(if (isBottomRight) selectedBg else unselectedBg)
        binding.cardBottomRight.strokeColor = if (isBottomRight) selectedStroke.defaultColor else transparentStroke.defaultColor
        binding.cardBottomRight.strokeWidth = if (isBottomRight) strokeWidthPx else 0
        binding.tvBottomRightLabel.setTextColor(if (isBottomRight) selectedTextColor else unselectedTextColor)
        binding.ivBottomRightIcon.setImageResource(
            if (isBottomRight) R.drawable.ic_stamp_pos_bottom_right_selected else R.drawable.ic_stamp_pos_bottom_right_unselected
        )
    }

    companion object {
        const val REQ_KEY_STAMP_POSITION = "req_stamp_position"
        const val BUNDLE_KEY_POSITION = "bundle_key_position"
        private const val ARG_SELECTED_POSITION = "arg_selected_position"
        private const val ARG_REQUEST_KEY = "arg_request_key"

        fun show(
            fragmentManager: FragmentManager,
            currentPosition: String,
            requestKey: String = REQ_KEY_STAMP_POSITION
        ) {
            val sheet = StampPositionBottomSheet().apply {
                arguments = bundleOf(
                    ARG_SELECTED_POSITION to currentPosition,
                    ARG_REQUEST_KEY to requestKey
                )
            }
            sheet.show(fragmentManager, requestKey)
        }
    }
}
