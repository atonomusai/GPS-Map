package com.autonoumus.ai.presentation.routefinder.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.autonoumus.ai.R
import com.autonoumus.ai.core.util.OsmSearchResult
import com.autonoumus.ai.databinding.ItemRouteSuggestionBinding

class RouteSuggestionAdapter(
    private val onSuggestionClick: (OsmSearchResult) -> Unit
) : ListAdapter<OsmSearchResult, RouteSuggestionAdapter.SuggestionViewHolder>(SuggestionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val binding = ItemRouteSuggestionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SuggestionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SuggestionViewHolder(
        private val binding: ItemRouteSuggestionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: OsmSearchResult) {
            binding.tvSuggestionTitle.text = item.placeName
            binding.tvSuggestionSubtitle.text = item.fullAddress

            val iconRes = if (item.country.contains("•")) {
                R.drawable.ic_map_pin_green
            } else {
                R.drawable.ic_location_pin_blue
            }
            binding.ivSuggestionIcon.setImageResource(iconRes)

            binding.root.setOnClickListener {
                onSuggestionClick(item)
            }
        }
    }

    class SuggestionDiffCallback : DiffUtil.ItemCallback<OsmSearchResult>() {
        override fun areItemsTheSame(oldItem: OsmSearchResult, newItem: OsmSearchResult): Boolean {
            return oldItem.latitude == newItem.latitude && oldItem.longitude == newItem.longitude
        }

        override fun areContentsTheSame(oldItem: OsmSearchResult, newItem: OsmSearchResult): Boolean {
            return oldItem == newItem
        }
    }
}
