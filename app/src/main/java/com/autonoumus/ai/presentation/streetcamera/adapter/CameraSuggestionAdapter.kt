package com.autonoumus.ai.presentation.streetcamera.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.CachePolicy
import com.autonoumus.ai.R
import com.autonoumus.ai.databinding.ItemCameraSuggestionBinding
import com.autonoumus.ai.domain.model.StreetCamera

data class CameraSuggestionItem(
    val id: String,
    val title: String,
    val authorOrLocation: String,
    val viewsAndAge: String,
    val durationText: String,
    val imageUrl: String? = null,
    val streetCamera: StreetCamera? = null
)

class CameraSuggestionAdapter(
    private val onSuggestionClick: (CameraSuggestionItem) -> Unit
) : ListAdapter<CameraSuggestionItem, CameraSuggestionAdapter.SuggestionViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val binding = ItemCameraSuggestionBinding.inflate(
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
        private val binding: ItemCameraSuggestionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CameraSuggestionItem) {
            binding.tvSuggestionTitle.text = item.title
            binding.tvSuggestionAuthor.text = item.authorOrLocation
            binding.tvSuggestionViews.text = item.viewsAndAge
           // binding.tvSuggestionDuration.text = item.durationText

            val fallbackRes = R.drawable.img_street_view_mountain
            if (!item.imageUrl.isNullOrBlank()) {
                binding.ivSuggestionThumb.load(item.imageUrl) {
                    crossfade(true)
                    placeholder(fallbackRes)
                    error(fallbackRes)
                    fallback(fallbackRes)
                    memoryCachePolicy(CachePolicy.ENABLED)
                }
            } else {
                binding.ivSuggestionThumb.setImageResource(fallbackRes)
            }

            binding.root.setOnClickListener {
                onSuggestionClick(item)
            }
        }
    }

    private companion object {
        val DiffCallback = object : DiffUtil.ItemCallback<CameraSuggestionItem>() {
            override fun areItemsTheSame(oldItem: CameraSuggestionItem, newItem: CameraSuggestionItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: CameraSuggestionItem, newItem: CameraSuggestionItem): Boolean =
                oldItem == newItem
        }
    }
}
