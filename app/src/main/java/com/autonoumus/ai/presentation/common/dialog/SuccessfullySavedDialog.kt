package com.autonoumus.ai.presentation.common.dialog

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.autonoumus.ai.databinding.DialogSuccessfullySavedBinding

class SuccessfullySavedDialog : DialogFragment() {

    companion object {
        const val TAG = "SuccessfullySavedDialog"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_SUBTITLE = "arg_subtitle"

        fun newInstance(
            title: String = "Successfully Saved!",
            subtitle: String = "Your template has been saved\nsuccessfully.",
            onDone: () -> Unit = {}
        ): SuccessfullySavedDialog {
            return SuccessfullySavedDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_SUBTITLE, subtitle)
                }
                this.onDoneClickListener = onDone
            }
        }

        fun show(
            fragmentManager: FragmentManager,
            title: String = "Successfully Saved!",
            subtitle: String = "Your template has been saved\nsuccessfully.",
            onDone: () -> Unit = {}
        ): SuccessfullySavedDialog {
            val existing = fragmentManager.findFragmentByTag(TAG)
            if (existing != null && existing.isAdded) {
                return existing as SuccessfullySavedDialog
            }
            val dialog = newInstance(title, subtitle, onDone)
            dialog.show(fragmentManager, TAG)
            return dialog
        }
    }

    var onDoneClickListener: (() -> Unit)? = null

    private var _binding: DialogSuccessfullySavedBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dialog?.window?.apply {
            requestFeature(Window.FEATURE_NO_TITLE)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        _binding = DialogSuccessfullySavedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isCancelable = false

        val title = arguments?.getString(ARG_TITLE) ?: "Successfully Saved!"
        val subtitle = arguments?.getString(ARG_SUBTITLE) ?: "Your template has been saved\nsuccessfully."

        binding.tvSavedDialogTitle.text = title
        binding.tvSavedDialogSubtitle.text = subtitle

        binding.btnDone.setOnClickListener {
            dismissAllowingStateLoss()
            onDoneClickListener?.invoke()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
