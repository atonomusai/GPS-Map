package com.autonoumus.ai.presentation.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import com.autonoumus.ai.core.base.BaseBottomSheetDialogFragment
import com.autonoumus.ai.databinding.DialogExitAppBinding

class ExitAppDialog : BaseBottomSheetDialogFragment<DialogExitAppBinding>() {

    var onExitConfirmed: (() -> Unit)? = null
    var onCancelled: (() -> Unit)? = null

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?): DialogExitAppBinding {
        return DialogExitAppBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnConfirmExit.setOnClickListener {
            dismiss()
            onExitConfirmed?.invoke()
        }

        binding.btnCancelExit.setOnClickListener {
            dismiss()
            onCancelled?.invoke()
        }
    }

    companion object {
        const val TAG = "ExitAppDialog"

        fun show(fragmentManager: FragmentManager, onExit: () -> Unit) {
            val existing = fragmentManager.findFragmentByTag(TAG)
            if (existing != null && existing.isAdded) return
            val dialog = ExitAppDialog().apply {
                onExitConfirmed = onExit
            }
            dialog.show(fragmentManager, TAG)
        }
    }
}
