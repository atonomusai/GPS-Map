package com.autonoumus.ai.presentation.streetcamera.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.autonoumus.ai.R
import com.autonoumus.ai.databinding.ItemLocationFilterChipBinding

class PopularLocationFilterChipAdapter(
    private val chips: List<String>,
    private var selectedIndex: Int = 0,
    private val onChipSelected: (String) -> Unit
) : RecyclerView.Adapter<PopularLocationFilterChipAdapter.ChipViewHolder>() {

    fun setSelectedIndex(index: Int) {
        val prevIndex = selectedIndex
        selectedIndex = index
        notifyItemChanged(prevIndex)
        notifyItemChanged(selectedIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
        val binding = ItemLocationFilterChipBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChipViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
        holder.bind(chips[position], position == selectedIndex)
    }

    override fun getItemCount(): Int = chips.size

    inner class ChipViewHolder(
        private val binding: ItemLocationFilterChipBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(chipTitle: String, isSelected: Boolean) {
            binding.tvChipLabel.text = chipTitle

            if (isSelected) {
                binding.tvChipLabel.setBackgroundResource(R.drawable.bg_chip_selected_blue)
                binding.tvChipLabel.setTextColor(Color.WHITE)
            } else {
                binding.tvChipLabel.setBackgroundResource(R.drawable.bg_chip_unselected_outline)
                binding.tvChipLabel.setTextColor(Color.parseColor("#0F172A"))
            }

            binding.root.setOnClickListener {
                val currentPos = adapterPosition
                if (currentPos != RecyclerView.NO_POSITION && currentPos != selectedIndex) {
                    setSelectedIndex(currentPos)
                    onChipSelected(chipTitle)
                }
            }
        }
    }
}
