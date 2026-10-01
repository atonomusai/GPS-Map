package com.autonoumus.ai.presentation.settings

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import com.autonoumus.ai.core.base.BaseBottomSheetDialogFragment
import com.autonoumus.ai.databinding.BottomSheetStampTextColorBinding
import java.util.Locale

/**
 * Stamp Text Color selection bottom sheet with 6 preset swatches,
 * custom HEX text entry, real-time live preview, and styled matching the reference design.
 */
class StampTextColorBottomSheet : BaseBottomSheetDialogFragment<BottomSheetStampTextColorBinding>() {

    companion object {
        const val REQ_KEY_TEXT_COLOR = "req_text_color"
        const val BUNDLE_KEY_COLOR_INT = "bundle_color_int"
        const val BUNDLE_KEY_COLOR_HEX = "bundle_color_hex"
        private const val ARG_INITIAL_COLOR = "arg_initial_color"
        private const val ARG_REQUEST_KEY = "arg_request_key"

        val PRESET_RED = Color.parseColor("#EF4444")
        val PRESET_BLACK = Color.parseColor("#1E293B")
        val PRESET_BLUE = Color.parseColor("#2563EB")
        val PRESET_ORANGE = Color.parseColor("#F59E0B")
        val PRESET_GREEN = Color.parseColor("#10B981")
        val PRESET_WHITE = Color.parseColor("#FFFFFF")

        fun show(
            fragmentManager: FragmentManager,
            initialColor: Int,
            requestKey: String = REQ_KEY_TEXT_COLOR
        ) {
            val sheet = StampTextColorBottomSheet().apply {
                arguments = bundleOf(
                    ARG_INITIAL_COLOR to initialColor,
                    ARG_REQUEST_KEY to requestKey
                )
            }
            sheet.show(fragmentManager, requestKey)
        }
    }

    private var currentColor: Int = PRESET_RED
    private var requestKey: String = REQ_KEY_TEXT_COLOR
    private var isUpdatingFromCode = false

    override fun inflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): BottomSheetStampTextColorBinding {
        return BottomSheetStampTextColorBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        currentColor = arguments?.getInt(ARG_INITIAL_COLOR, PRESET_RED) ?: PRESET_RED
        requestKey = arguments?.getString(ARG_REQUEST_KEY) ?: REQ_KEY_TEXT_COLOR

        setupSwatches()
        setupHexInput()
        setupButtons()
        updateUI()
    }

    private fun setupSwatches() {
        binding.cardColorRed.setOnClickListener { selectColor(PRESET_RED) }
        binding.cardColorBlack.setOnClickListener { selectColor(PRESET_BLACK) }
        binding.cardColorBlue.setOnClickListener { selectColor(PRESET_BLUE) }
        binding.cardColorOrange.setOnClickListener { selectColor(PRESET_ORANGE) }
        binding.cardColorGreen.setOnClickListener { selectColor(PRESET_GREEN) }
        binding.cardColorWhite.setOnClickListener { selectColor(PRESET_WHITE) }
    }

    private fun selectColor(color: Int) {
        currentColor = color
        updateUI()
    }

    private fun setupHexInput() {
        binding.etHexCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingFromCode) return
                val raw = s?.toString()?.trim().orEmpty()
                if (raw.isBlank()) return
                val formatted = if (raw.startsWith("#")) raw else "#$raw"
                if (formatted.length == 7) {
                    try {
                        val parsed = Color.parseColor(formatted)
                        currentColor = parsed
                        updateSwatchesOnly()
                        updatePreviewAndBorder()
                    } catch (e: Exception) {
                        // Invalid hex, ignore until valid
                    }
                }
            }
        })
    }

    private fun setupButtons() {
        binding.btnClose.setOnClickListener {
            dismiss()
        }

        binding.btnSaveTextColor.setOnClickListener {
            setFragmentResult(
                requestKey,
                bundleOf(
                    BUNDLE_KEY_COLOR_INT to currentColor,
                    BUNDLE_KEY_COLOR_HEX to toHex(currentColor)
                )
            )
            dismiss()
        }
    }

    private fun updateUI() {
        isUpdatingFromCode = true
        binding.etHexCode.setText(toHex(currentColor))
        isUpdatingFromCode = false

        updateSwatchesOnly()
        updatePreviewAndBorder()
    }

    private fun updateSwatchesOnly() {
        val density = resources.displayMetrics.density

        binding.viewColorRed.background = createCircleDrawable(PRESET_RED, currentColor == PRESET_RED, density)
        binding.viewColorBlack.background = createCircleDrawable(PRESET_BLACK, currentColor == PRESET_BLACK, density)
        binding.viewColorBlue.background = createCircleDrawable(PRESET_BLUE, currentColor == PRESET_BLUE, density)
        binding.viewColorOrange.background = createCircleDrawable(PRESET_ORANGE, currentColor == PRESET_ORANGE, density)
        binding.viewColorGreen.background = createCircleDrawable(PRESET_GREEN, currentColor == PRESET_GREEN, density)
        binding.viewColorWhite.background = createCircleDrawable(PRESET_WHITE, currentColor == PRESET_WHITE, density, isWhite = true)
    }

    private fun updatePreviewAndBorder() {
        val density = resources.displayMetrics.density

        // Swatch view
        binding.viewActiveColorPreview.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(currentColor)
            if (currentColor == Color.WHITE) {
                setStroke((1f * density).toInt(), Color.parseColor("#E2E8F0"))
            }
        }

        // Hex Box border
        binding.layoutHexBox.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(Color.WHITE)
            val strokeColor = if (currentColor == Color.WHITE) Color.parseColor("#CBD5E1") else currentColor
            setStroke((1.5f * density).toInt(), strokeColor)
        }
    }

    private fun createCircleDrawable(
        colorInt: Int,
        isSelected: Boolean,
        density: Float,
        isWhite: Boolean = false
    ): Drawable {
        if (isSelected) {
            val ring = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                val ringColor = if (isWhite) Color.parseColor("#94A3B8") else colorInt
                setStroke((2.2f * density).toInt(), ringColor)
            }
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorInt)
                if (isWhite) {
                    setStroke((1f * density).toInt(), Color.parseColor("#E2E8F0"))
                }
            }
            val insetPx = (5f * density).toInt()
            return LayerDrawable(arrayOf(ring, InsetDrawable(dot, insetPx, insetPx, insetPx, insetPx)))
        } else {
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorInt)
                if (isWhite) {
                    setStroke((1.5f * density).toInt(), Color.parseColor("#E2E8F0"))
                }
            }
        }
    }

    private fun toHex(colorInt: Int): String {
        return String.format(Locale.US, "#%06X", 0xFFFFFF and colorInt)
    }
}
