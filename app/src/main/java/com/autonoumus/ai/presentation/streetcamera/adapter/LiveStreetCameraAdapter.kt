package com.autonoumus.ai.presentation.streetcamera.adapter

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.CachePolicy
import com.autonoumus.ai.R
import com.autonoumus.ai.databinding.ItemStreetCameraLiveCardBinding
import com.autonoumus.ai.domain.model.StreetCamera

class LiveStreetCameraAdapter(
    private val onCameraClick: (StreetCamera) -> Unit,
    private val onFavoriteToggle: ((StreetCamera, Boolean) -> Unit)? = null
) : ListAdapter<StreetCamera, LiveStreetCameraAdapter.LiveCameraViewHolder>(DiffCallback) {

    private val favoriteIds = mutableSetOf<String>()

    fun setFavorites(ids: Set<String>) {
        favoriteIds.clear()
        favoriteIds.addAll(ids)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LiveCameraViewHolder {
        val binding = ItemStreetCameraLiveCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LiveCameraViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LiveCameraViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LiveCameraViewHolder(
        private val binding: ItemStreetCameraLiveCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(camera: StreetCamera) {
            val locationText = when {
                camera.city.isNotBlank() && camera.country.isNotBlank() -> "${camera.country}, ${camera.city}"
                camera.title.isNotBlank() -> camera.title
                else -> camera.country.ifBlank { "Live Camera" }
            }
            binding.tvLiveLocation.text = locationText

            val imageUrl = camera.imageUrl ?: camera.thumbnailUrl
            val fallbackRes = R.drawable.img_street_view_mountain
            if (!imageUrl.isNullOrBlank()) {
                binding.ivLiveThumbnail.load(imageUrl) {
                    crossfade(true)
                    placeholder(fallbackRes)
                    error(fallbackRes)
                    memoryCachePolicy(CachePolicy.ENABLED)
                }
            } else {
                binding.ivLiveThumbnail.setImageResource(fallbackRes)
            }

            //val isFav = favoriteIds.contains(camera.id)
            //updateFavoriteIcon(isFav)

          /*  binding.sbtnFavorite.setOnClickListener {
                val newFav = !favoriteIds.contains(camera.id)
                if (newFav) favoriteIds.add(camera.id) else favoriteIds.remove(camera.id)
                updateFavoriteIcon(newFav)
                onFavoriteToggle?.invoke(camera, newFav)
            }
*/
            binding.root.setOnClickListener {
                onCameraClick(camera)
            }
        }

      /*  private fun updateFavoriteIcon(isFav: Boolean) {
            if (isFav) {
                binding.ivFavoriteStar.setImageResource(R.drawable.ic_star_filled)
                binding.ivFavoriteStar.setColorFilter(Color.parseColor("#FBBF24"))
            } else {
                binding.ivFavoriteStar.setImageResource(R.drawable.ic_star_empty)
                binding.ivFavoriteStar.setColorFilter(Color.WHITE)
            }
        }*/
    }

    private companion object {
        val DiffCallback = object : DiffUtil.ItemCallback<StreetCamera>() {
            override fun areItemsTheSame(oldItem: StreetCamera, newItem: StreetCamera): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: StreetCamera, newItem: StreetCamera): Boolean =
                oldItem == newItem
        }
    }
}
