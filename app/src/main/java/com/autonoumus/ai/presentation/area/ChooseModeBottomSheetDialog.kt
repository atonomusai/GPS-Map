package com.autonoumus.ai.presentation.area

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.autonoumus.ai.R
import com.autonoumus.ai.databinding.BottomSheetChooseModeBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ChooseModeBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetChooseModeBinding? = null
    private val binding get() = _binding!!

    var onManualMeasuringSelected: (() -> Unit)? = null
    var onEnterCoordinatesSelected: (() -> Unit)? = null
    var onMeasureTypeChanged: ((MeasureType) -> Unit)? = null

    private var currentMeasureType: MeasureType = MeasureType.AREA

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetChooseModeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClickListener {
            dismiss()
        }

        binding.cardManualMeasuring.setOnClickListener {
            dismiss()
            onManualMeasuringSelected?.invoke()
        }

        binding.cardEnterCoordinates.setOnClickListener {
            dismiss()
            onEnterCoordinatesSelected?.invoke()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ChooseModeBottomSheet"
        fun newInstance(): ChooseModeBottomSheetDialog = ChooseModeBottomSheetDialog()
    }
}

enum class MeasureType {
    AREA,
    DISTANCE,
    PINPOINT
}
