package com.autonoumus.ai.core.base

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.viewbinding.ViewBinding
import com.autonoumus.ai.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Base BottomSheetDialogFragment ensuring that all bottom sheets across the application
 * have consistent, smoothly rounded top corners (28dp) and transparent system containers.
 */
abstract class BaseBottomSheetDialogFragment<VB : ViewBinding> : BottomSheetDialogFragment() {

    private var _binding: VB? = null
    protected val binding get() = _binding!!

    override fun getTheme(): Int = R.style.Theme_GSMCAMER_BottomSheetDialog

    abstract fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?): VB

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = inflateBinding(inflater, container)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                sheet.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_bottom_sheet_dialog)
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.findViewById<View>(com.google.android.material.R.id.container)?.setBackgroundColor(Color.TRANSPARENT)
            window.findViewById<View>(com.google.android.material.R.id.coordinator)?.setBackgroundColor(Color.TRANSPARENT)
            window.findViewById<View>(com.google.android.material.R.id.touch_outside)?.setBackgroundColor(Color.TRANSPARENT)
        }
        val bottomSheet = dialog?.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_bottom_sheet_dialog)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
