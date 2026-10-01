package com.autonoumus.ai.presentation.streetcamera.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.autonoumus.ai.databinding.ItemPlaceCategoryCardBinding
import com.autonoumus.ai.presentation.streetcamera.PlaceCategoryModel

class PlaceCategoryAdapter(
    private var categories: List<PlaceCategoryModel>,
    private val onCategoryClick: (PlaceCategoryModel) -> Unit
) : RecyclerView.Adapter<PlaceCategoryAdapter.CategoryViewHolder>() {

    fun updateCategories(newCategories: List<PlaceCategoryModel>) {
        this.categories = newCategories
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val binding = ItemPlaceCategoryCardBinding.inflate(
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
        private val binding: ItemPlaceCategoryCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PlaceCategoryModel) {
            binding.tvCategoryTitle.text = item.title

            try {
                val bgColor = Color.parseColor(item.bgTintHex)
                val iconColor = Color.parseColor(item.iconTintHex)

                binding.frameCategoryIcon.backgroundTintList = ColorStateList.valueOf(bgColor)
                binding.ivCategoryIcon.setImageResource(item.iconRes)
                binding.ivCategoryIcon.setColorFilter(iconColor)
            } catch (e: Exception) {
                binding.ivCategoryIcon.setImageResource(item.iconRes)
            }

            binding.cardCategoryRoot.setOnClickListener {
                onCategoryClick(item)
            }
        }
    }
}
