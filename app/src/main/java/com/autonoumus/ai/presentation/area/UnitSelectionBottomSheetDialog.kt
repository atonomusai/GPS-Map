package com.autonoumus.ai.presentation.area

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.autonoumus.ai.R
import com.autonoumus.ai.core.util.AreaUnit
import com.autonoumus.ai.databinding.BottomSheetUnitSelectionBinding
import com.autonoumus.ai.databinding.ItemUnitOptionBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class UnitSelectionBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetUnitSelectionBinding? = null
    private val binding get() = _binding!!

    private var selectedUnit: AreaUnit = AreaUnit.SQ_METERS
    var onUnitSelected: ((AreaUnit) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val unitName = arguments?.getString(ARG_SELECTED_UNIT)
        if (unitName != null) {
            selectedUnit = try {
                AreaUnit.valueOf(unitName)
            } catch (e: Exception) {
                AreaUnit.SQ_METERS
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetUnitSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClickListener {
            dismiss()
        }

        binding.rvUnits.layoutManager = LinearLayoutManager(requireContext())
        val units = AreaUnit.values().toList()
        binding.rvUnits.adapter = UnitAdapter(units, selectedUnit) { unit ->
            selectedUnit = unit
            onUnitSelected?.invoke(unit)
            dismiss()
        }
    }

    private class UnitAdapter(
        private val units: List<AreaUnit>,
        private val currentSelected: AreaUnit,
        private val onSelect: (AreaUnit) -> Unit
    ) : RecyclerView.Adapter<UnitAdapter.UnitViewHolder>() {

        class UnitViewHolder(val binding: ItemUnitOptionBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UnitViewHolder {
            val binding = ItemUnitOptionBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return UnitViewHolder(binding)
        }

        override fun onBindViewHolder(holder: UnitViewHolder, position: Int) {
            val unit = units[position]
            holder.binding.tvUnitName.text = unit.displayName
            val isSelected = unit == currentSelected
            holder.binding.ivUnitRadio.setImageResource(
                if (isSelected) R.drawable.ic_radio_checked else R.drawable.ic_radio_unchecked
            )

            holder.itemView.setOnClickListener {
                onSelect(unit)
            }
        }

        override fun getItemCount(): Int = units.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "UnitSelectionBottomSheet"
        private const val ARG_SELECTED_UNIT = "arg_selected_unit"

        fun newInstance(currentUnit: AreaUnit): UnitSelectionBottomSheetDialog {
            val fragment = UnitSelectionBottomSheetDialog()
            val args = Bundle().apply {
                putString(ARG_SELECTED_UNIT, currentUnit.name)
            }
            fragment.arguments = args
            return fragment
        }
    }
}
