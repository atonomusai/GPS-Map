package com.autonoumus.ai.presentation.map

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.autonoumus.ai.core.util.OsmSearchResult
import com.autonoumus.ai.databinding.ItemMapSearchResultBinding

class MapSearchResultAdapter(
    private val onItemClick: (OsmSearchResult) -> Unit
) : RecyclerView.Adapter<MapSearchResultAdapter.ViewHolder>() {

    private val items = mutableListOf<OsmSearchResult>()
    val currentList: List<OsmSearchResult> get() = items.toList()

    fun submitList(newList: List<OsmSearchResult>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMapSearchResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemMapSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                @Suppress("DEPRECATION")
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemClick(items[pos])
                }
            }
        }

        fun bind(item: OsmSearchResult) {
            binding.tvPlaceTitle.text = item.placeName
            binding.tvPlaceAddress.text = item.fullAddress
            binding.tvCoordTag.text = item.latitudeFormatted
        }
    }
}
