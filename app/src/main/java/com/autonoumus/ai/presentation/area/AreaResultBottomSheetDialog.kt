package com.autonoumus.ai.presentation.area

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.autonoumus.ai.databinding.BottomSheetAreaResultBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class AreaResultBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAreaResultBinding? = null
    private val binding get() = _binding!!

    private var areaText: String = "0.0 m²"
    private var perimeterText: String = "0.0 m"
    private var locationName: String = "Measured Location"
    private var pointsCount: Int = 0

    var onSaveAndShareClicked: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            areaText = it.getString(ARG_AREA, "0.0 m²")
            perimeterText = it.getString(ARG_PERIMETER, "0.0 m")
            locationName = it.getString(ARG_LOCATION, "Measured Area")
            pointsCount = it.getInt(ARG_POINTS_COUNT, 0)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAreaResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvResultAreaValue.text = areaText
        binding.tvResultPerimeterValue.text = perimeterText
        binding.tvResultLocationName.text = locationName

        binding.btnSaveAndShare.setOnClickListener {
            shareResult()
            onSaveAndShareClicked?.invoke()
            dismiss()
        }
    }

    private fun shareResult() {
        val shareBody = """
            📐 Area Measurement Result
            📍 Location: $locationName
            🟩 Area: $areaText
            📏 Perimeter: $perimeterText
            🔢 Vertices: $pointsCount points
            
            Calculated via ${getString(com.autonoumus.ai.R.string.app_name)}.
        """.trimIndent()

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Area Measurement Details")
            putExtra(Intent.EXTRA_TEXT, shareBody)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Area Result"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AreaResultBottomSheet"
        private const val ARG_AREA = "arg_area"
        private const val ARG_PERIMETER = "arg_perimeter"
        private const val ARG_LOCATION = "arg_location"
        private const val ARG_POINTS_COUNT = "arg_points_count"

        fun newInstance(
            areaText: String,
            perimeterText: String,
            locationName: String = "Measured Area",
            pointsCount: Int = 0
        ): AreaResultBottomSheetDialog {
            val fragment = AreaResultBottomSheetDialog()
            val args = Bundle().apply {
                putString(ARG_AREA, areaText)
                putString(ARG_PERIMETER, perimeterText)
                putString(ARG_LOCATION, locationName)
                putInt(ARG_POINTS_COUNT, pointsCount)
            }
            fragment.arguments = args
            return fragment
        }
    }
}
