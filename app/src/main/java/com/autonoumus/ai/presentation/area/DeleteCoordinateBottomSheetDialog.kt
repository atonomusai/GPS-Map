package com.autonoumus.ai.presentation.area

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.autonoumus.ai.databinding.BottomSheetDeleteCoordinateBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class DeleteCoordinateBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDeleteCoordinateBinding? = null
    private val binding get() = _binding!!

    private var pointIndex: Int = 0
    private var pointLabel: String = "A"

    var onCoordinateDeleted: ((Int) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            pointIndex = it.getInt(ARG_INDEX, 0)
            pointLabel = it.getString(ARG_LABEL, "A")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDeleteCoordinateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvDeleteMessage.text =
            "Are you sure you want to delete Point $pointLabel from the measurement list?"

        binding.btnCancelDelete.setOnClickListener { dismiss() }

        binding.btnConfirmDelete.setOnClickListener {
            onCoordinateDeleted?.invoke(pointIndex)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DeleteCoordinateBottomSheet"
        private const val ARG_INDEX = "arg_index"
        private const val ARG_LABEL = "arg_label"

        fun newInstance(index: Int, label: String): DeleteCoordinateBottomSheetDialog {
            val fragment = DeleteCoordinateBottomSheetDialog()
            val args = Bundle().apply {
                putInt(ARG_INDEX, index)
                putString(ARG_LABEL, label)
            }
            fragment.arguments = args
            return fragment
        }
    }
}
