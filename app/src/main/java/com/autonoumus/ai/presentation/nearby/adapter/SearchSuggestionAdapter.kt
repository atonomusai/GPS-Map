package com.autonoumus.ai.presentation.nearby.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.autonoumus.ai.core.util.OsmSearchResult
import com.autonoumus.ai.databinding.ItemMapSearchResultBinding

class SearchSuggestionAdapter(
    private val onItemClick: (OsmSearchResult) -> Unit
) : ListAdapter<OsmSearchResult, SearchSuggestionAdapter.SuggestViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestViewHolder {
        val binding = ItemMapSearchResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SuggestViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SuggestViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SuggestViewHolder(
        private val binding: ItemMapSearchResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: OsmSearchResult) {
            binding.tvPlaceTitle.text = item.placeName
            binding.tvPlaceAddress.text = item.fullAddress
            binding.tvCoordTag.text = item.country.ifBlank { item.latitudeFormatted }

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<OsmSearchResult>() {
        override fun areItemsTheSame(oldItem: OsmSearchResult, newItem: OsmSearchResult): Boolean {
            return oldItem.latitude == newItem.latitude && oldItem.longitude == newItem.longitude
        }

        override fun areContentsTheSame(oldItem: OsmSearchResult, newItem: OsmSearchResult): Boolean {
            return oldItem == newItem
        }
    }
}
