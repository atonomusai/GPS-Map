package com.autonoumus.ai.presentation.templates.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.autonoumus.ai.databinding.ItemSavedLocationTemplateBinding
import com.autonoumus.ai.domain.model.SavedLocationTemplate

class SavedLocationsAdapter(
    private val onItemClick: (SavedLocationTemplate) -> Unit,
    private val onDeleteClick: (SavedLocationTemplate) -> Unit
) : ListAdapter<SavedLocationTemplate, SavedLocationsAdapter.SavedLocationViewHolder>(DiffCallback) {

    inner class SavedLocationViewHolder(
        private val binding: ItemSavedLocationTemplateBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SavedLocationTemplate) {
            val titleText = if (item.country.isNotBlank()) item.country else item.name.ifBlank { "Custom Location" }
            binding.tvSavedCountry.text = titleText
            binding.tvSavedAddress.text = item.address.ifBlank { "No address specified" }

            val coordsText = if (item.latitude.isNotBlank() && item.longitude.isNotBlank()) {
                "${item.latitude}, ${item.longitude}"
            } else {
                "Custom Coordinates"
            }
            binding.tvSavedCoordinates.text = coordsText

            binding.cardSavedLocation.setOnClickListener {
                onItemClick(item)
            }

            binding.btnDeleteTemplate.setOnClickListener {
                onDeleteClick(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SavedLocationViewHolder {
        val binding = ItemSavedLocationTemplateBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SavedLocationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SavedLocationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object DiffCallback : DiffUtil.ItemCallback<SavedLocationTemplate>() {
        override fun areItemsTheSame(
            oldItem: SavedLocationTemplate,
            newItem: SavedLocationTemplate
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: SavedLocationTemplate,
            newItem: SavedLocationTemplate
        ): Boolean = oldItem == newItem
    }
}
