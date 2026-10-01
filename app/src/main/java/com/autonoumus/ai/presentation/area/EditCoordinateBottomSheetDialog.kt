package com.autonoumus.ai.presentation.area

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.autonoumus.ai.R
import com.autonoumus.ai.databinding.BottomSheetEditCoordinateBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.osmdroid.util.GeoPoint

class EditCoordinateBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetEditCoordinateBinding? = null
    private val binding get() = _binding!!

    private var pointIndex: Int = 0
    private var pointLabel: String = "A"
    private var initialLatitude: Double = 0.0
    private var initialLongitude: Double = 0.0

    var onCoordinateUpdated: ((Int, GeoPoint) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            pointIndex = it.getInt(ARG_INDEX, 0)
            pointLabel = it.getString(ARG_LABEL, "A")
            initialLatitude = it.getDouble(ARG_LAT, 0.0)
            initialLongitude = it.getDouble(ARG_LON, 0.0)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetEditCoordinateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvEditDialogTitle.text = "Edit Point $pointLabel"
        binding.etEditLatitude.setText(initialLatitude.toString())
        binding.etEditLongitude.setText(initialLongitude.toString())

        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnCancelEdit.setOnClickListener { dismiss() }

        binding.btnSaveEdit.setOnClickListener {
            val latStr = binding.etEditLatitude.text.toString().trim()
            val lonStr = binding.etEditLongitude.text.toString().trim()

            var isValid = true

            val lat = latStr.toDoubleOrNull()
            if (lat == null || lat < -90.0 || lat > 90.0) {
                binding.tvEditLatError.text = "Enter a valid latitude (-90° to 90°)"
                binding.tvEditLatError.visibility = View.VISIBLE
                binding.etEditLatitude.setBackgroundResource(R.drawable.bg_input_field_error)
                isValid = false
            } else {
                binding.tvEditLatError.visibility = View.GONE
                binding.etEditLatitude.setBackgroundResource(R.drawable.bg_input_field)
            }

            val lon = lonStr.toDoubleOrNull()
            if (lon == null || lon < -180.0 || lon > 180.0) {
                binding.tvEditLonError.text = "Enter a valid longitude (-180° to 180°)"
                binding.tvEditLonError.visibility = View.VISIBLE
                binding.etEditLongitude.setBackgroundResource(R.drawable.bg_input_field_error)
                isValid = false
            } else {
                binding.tvEditLonError.visibility = View.GONE
                binding.etEditLongitude.setBackgroundResource(R.drawable.bg_input_field)
            }

            if (isValid && lat != null && lon != null) {
                onCoordinateUpdated?.invoke(pointIndex, GeoPoint(lat, lon))
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "EditCoordinateBottomSheet"
        private const val ARG_INDEX = "arg_index"
        private const val ARG_LABEL = "arg_label"
        private const val ARG_LAT = "arg_lat"
        private const val ARG_LON = "arg_lon"

        fun newInstance(
            index: Int,
            label: String,
            point: GeoPoint
        ): EditCoordinateBottomSheetDialog {
            val fragment = EditCoordinateBottomSheetDialog()
            val args = Bundle().apply {
                putInt(ARG_INDEX, index)
                putString(ARG_LABEL, label)
                putDouble(ARG_LAT, point.latitude)
                putDouble(ARG_LON, point.longitude)
            }
            fragment.arguments = args
            return fragment
        }
    }
}
