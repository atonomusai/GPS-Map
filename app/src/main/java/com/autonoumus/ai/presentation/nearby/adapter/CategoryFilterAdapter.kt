package com.autonoumus.ai.presentation.nearby.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.autonoumus.ai.R
import com.autonoumus.ai.databinding.ItemCategoryChipBinding
import com.autonoumus.ai.domain.model.NearbyCategory

class CategoryFilterAdapter(
    private val categories: List<NearbyCategory> = NearbyCategory.values().toList(),
    private var selectedCategory: NearbyCategory = NearbyCategory.ALL,
    private val onCategorySelected: (NearbyCategory) -> Unit
) : RecyclerView.Adapter<CategoryFilterAdapter.CategoryViewHolder>() {

    fun setSelectedCategory(category: NearbyCategory) {
        val prevIndex = categories.indexOf(selectedCategory)
        val newIndex = categories.indexOf(category)
        selectedCategory = category
        if (prevIndex != -1) notifyItemChanged(prevIndex)
        if (newIndex != -1) notifyItemChanged(newIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val binding = ItemCategoryChipBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CategoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(categories[position])
    }

    override fun getItemCount(): Int = categories.size

    inner class CategoryViewHolder(
        private val binding: ItemCategoryChipBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(category: NearbyCategory) {
            val isSelected = category == selectedCategory

            binding.tvChipEmoji.text = category.emoji
            binding.tvChipTitle.text = category.title

            if (isSelected) {
                binding.chipRoot.setBackgroundResource(R.drawable.bg_nearby_chip_selected)
                binding.tvChipTitle.setTextColor(Color.WHITE)
            } else {
                binding.chipRoot.setBackgroundResource(R.drawable.bg_nearby_chip_unselected)
                binding.tvChipTitle.setTextColor(Color.parseColor("#334155"))
            }

            binding.chipRoot.setOnClickListener {
                if (category != selectedCategory) {
                    setSelectedCategory(category)
                    onCategorySelected(category)
                }
            }
        }
    }
}
