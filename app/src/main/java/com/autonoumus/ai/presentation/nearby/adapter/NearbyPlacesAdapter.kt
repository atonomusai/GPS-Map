package com.autonoumus.ai.presentation.nearby.adapter

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.autonoumus.ai.databinding.ItemNearbyPlaceBinding
import com.autonoumus.ai.domain.model.NearbyCategory
import com.autonoumus.ai.domain.model.NearbyPlace

class NearbyPlacesAdapter(
    private val onPlaceClick: (NearbyPlace) -> Unit,
    private val onDirectionsClick: (NearbyPlace) -> Unit
) : ListAdapter<NearbyPlace, NearbyPlacesAdapter.PlaceViewHolder>(PlaceDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
        val binding = ItemNearbyPlaceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlaceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlaceViewHolder(
        private val binding: ItemNearbyPlaceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(place: NearbyPlace) {
            val context = binding.root.context

            // Title
            binding.tvPlaceName.text = place.name

            // Subtitle: Area / Street • Distance
            val locText = when {
                place.street.isNotBlank() -> place.street
                place.city.isNotBlank() -> place.city
                else -> place.address.split(",").firstOrNull()?.trim() ?: "Near you"
            }
            binding.tvPlaceSubtitle.text = "$locText • ${place.getFormattedDistance()} away"

            // Category Icon Badge & Color Tint
            binding.layoutCategoryIcon.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor(place.categoryType.badgeColor))
            binding.tvCategoryEmoji.text = place.getCategoryEmoji()

            // Status line: only what the map data actually tells us
            val (statusText, statusColor) = when {
                place.isOpen == true -> {
                    val label = if (place.openingHours.equals("24/7", ignoreCase = true)) "Open 24/7" else "Open now"
                    "• ${place.categoryName} · 🟢 $label" to "#059669"
                }
                place.isOpen == false -> "• ${place.categoryName} · 🔴 Closed" to "#DC2626"
                place.categoryType == NearbyCategory.HOSPITALS && place.tags["emergency"] == "yes" ->
                    "• ${place.categoryName} · 🔴 Emergency" to "#DC2626"
                else -> "• ${place.categoryName}" to "#4F46E5"
            }
            binding.tvPlaceStatus.text = statusText
            binding.tvPlaceStatus.setTextColor(Color.parseColor(statusColor))

            // Directions Button
            binding.btnDirections.setOnClickListener {
                onDirectionsClick(place)
            }

            // Share Button
            binding.btnShare.setOnClickListener {
                sharePlace(context, place)
            }

            // Entire Card Click
            binding.cardPlaceRoot.setOnClickListener {
                onPlaceClick(place)
            }
        }

        private fun sharePlace(context: Context, place: NearbyPlace) {
            val shareText = "📍 ${place.name}\n" +
                    "Type: ${place.categoryName}\n" +
                    "Address: ${place.address}\n" +
                    "Location: https://maps.google.com/?q=${place.latitude},${place.longitude}"

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, place.name)
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            context.startActivity(Intent.createChooser(intent, "Share Place"))
        }
    }

    class PlaceDiffCallback : DiffUtil.ItemCallback<NearbyPlace>() {
        override fun areItemsTheSame(oldItem: NearbyPlace, newItem: NearbyPlace): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: NearbyPlace, newItem: NearbyPlace): Boolean {
            return oldItem == newItem
        }
    }
}
