package com.autonoumus.ai.presentation.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.autonoumus.ai.R
import com.autonoumus.ai.databinding.ItemStreetViewCameraBinding
import com.autonoumus.ai.domain.model.StreetViewCameraItem

class StreetViewCameraAdapter(
    private val onItemClick: (StreetViewCameraItem) -> Unit
) : ListAdapter<StreetViewCameraItem, StreetViewCameraAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStreetViewCameraBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemStreetViewCameraBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: StreetViewCameraItem) {
            binding.tvStreetCameraTitle.text = item.title

            val fallbackRes = if (item.imageRes != 0) item.imageRes else R.drawable.img_street_view_mountain
            if (!item.imageUrl.isNullOrBlank()) {
                binding.ivStreetCameraImage.load(item.imageUrl) {
                    crossfade(true)
                    placeholder(fallbackRes)
                    error(fallbackRes)
                    fallback(fallbackRes)
                }
            } else {
                binding.ivStreetCameraImage.setImageResource(fallbackRes)
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<StreetViewCameraItem>() {
            override fun areItemsTheSame(
                oldItem: StreetViewCameraItem,
                newItem: StreetViewCameraItem
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: StreetViewCameraItem,
                newItem: StreetViewCameraItem
            ): Boolean = oldItem == newItem
        }
    }
}
