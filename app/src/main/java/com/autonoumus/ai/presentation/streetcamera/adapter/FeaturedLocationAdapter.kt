package com.autonoumus.ai.presentation.streetcamera.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.autonoumus.ai.R
import com.autonoumus.ai.databinding.ItemFeaturedLocationCardBinding
import com.autonoumus.ai.presentation.streetcamera.FeaturedLocationModel

class FeaturedLocationAdapter(
    private val locations: List<FeaturedLocationModel>,
    private val onFeaturedClick: (FeaturedLocationModel) -> Unit
) : RecyclerView.Adapter<FeaturedLocationAdapter.FeaturedViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeaturedViewHolder {
        val binding = ItemFeaturedLocationCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FeaturedViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FeaturedViewHolder, position: Int) {
        holder.bind(locations[position])
    }

    override fun getItemCount(): Int = locations.size

    inner class FeaturedViewHolder(
        private val binding: ItemFeaturedLocationCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FeaturedLocationModel) {
            binding.tvFeaturedTitle.text = item.title
            binding.tvFeaturedSubtitle.text = item.cameraCountLabel

            binding.ivFeaturedThumb.load(item.imageUrl) {
                crossfade(true)
                placeholder(R.drawable.img_street_view_mountain)
                error(R.drawable.img_street_view_mountain)
            }

            binding.root.setOnClickListener {
                onFeaturedClick(item)
            }
        }
    }
}
