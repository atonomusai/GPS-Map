package com.autonoumus.ai.presentation.gallery

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.autonoumus.ai.R
import com.autonoumus.ai.core.util.VideoThumbnailModel
import com.autonoumus.ai.databinding.ItemGalleryMediaCardBinding
import com.autonoumus.ai.databinding.ItemGallerySectionHeaderBinding
import com.autonoumus.ai.domain.model.RecentCaptureItem

sealed class GalleryListItem {
    abstract val uniqueId: String

    data class Header(
        val sectionKey: String,
        val dateTitle: String,
        val locationBadge: String,
        val itemCount: Int
    ) : GalleryListItem() {
        override val uniqueId: String = "header_$sectionKey"
    }

    data class Media(
        val capture: RecentCaptureItem,
        val isSelected: Boolean = false,
        val isSelectionMode: Boolean = false
    ) : GalleryListItem() {
        override val uniqueId: String = capture.id
    }
}

class GalleryGroupedAdapter(
    private val onItemClick: (RecentCaptureItem) -> Unit,
    private val onItemLongClick: (RecentCaptureItem) -> Unit,
    private val onItemSelectToggle: (RecentCaptureItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_MEDIA = 1
        private const val SELECTED_STROKE_COLOR = "#5865F2"
    }

    private var items: List<GalleryListItem> = emptyList()

    fun submitList(newItems: List<GalleryListItem>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition].uniqueId == newItems[newItemPosition].uniqueId
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition] == newItems[newItemPosition]
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is GalleryListItem.Header -> VIEW_TYPE_HEADER
            is GalleryListItem.Media -> VIEW_TYPE_MEDIA
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val binding = ItemGallerySectionHeaderBinding.inflate(inflater, parent, false)
                HeaderViewHolder(binding)
            }
            else -> {
                val binding = ItemGalleryMediaCardBinding.inflate(inflater, parent, false)
                MediaViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is GalleryListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is GalleryListItem.Media -> (holder as MediaViewHolder).bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class HeaderViewHolder(
        private val binding: ItemGallerySectionHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(header: GalleryListItem.Header) {
            binding.tvSectionDate.text = header.dateTitle
            binding.tvSectionLocationBadge.text = header.locationBadge
            binding.layoutLocationBadge.visibility = if (header.locationBadge.isNotBlank()) View.VISIBLE else View.GONE
        }
    }

    inner class MediaViewHolder(
        private val binding: ItemGalleryMediaCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(media: GalleryListItem.Media) {
            val item = media.capture
            val imageRequest: Any = if (item.isVideo) VideoThumbnailModel(item.contentUri) else item.contentUri

            binding.ivThumbnail.load(imageRequest) {
                crossfade(true)
                placeholder(R.drawable.bg_tool_icon)
                error(R.drawable.bg_tool_icon)
            }

            // Location tag at bottom
            val loc = item.locationName?.takeIf { it.isNotBlank() } ?: item.locationFormatted
            binding.tvLocationName.text = loc

            // Video duration badge on top-right
            if (item.isVideo) {
                binding.layoutVideoDurationBadge.visibility = View.VISIBLE
                binding.tvVideoDuration.text = item.durationFormatted ?: "0:00"
            } else {
                binding.layoutVideoDurationBadge.visibility = View.GONE
            }

            // Selection visual state: vibrant blue stroke outline on selected card
            val density = binding.root.resources.displayMetrics.density
            if (media.isSelected) {
                binding.cardMediaRoot.strokeWidth = (2.5f * density).toInt()
                binding.cardMediaRoot.strokeColor = Color.parseColor(SELECTED_STROKE_COLOR)
            } else {
                binding.cardMediaRoot.strokeWidth = 0
            }

            // Multi-selection checkmark
            if (media.isSelectionMode) {
                binding.ivSelectionCheck.visibility = View.VISIBLE
                val checkRes = if (media.isSelected) {
                    R.drawable.ic_checkbox_circle_checked
                } else {
                    R.drawable.ic_checkbox_circle_unchecked
                }
                binding.ivSelectionCheck.setImageResource(checkRes)
            } else {
                binding.ivSelectionCheck.visibility = View.GONE
            }

            // Interaction
            binding.cardMediaRoot.setOnClickListener {
                if (media.isSelectionMode) {
                    onItemSelectToggle(item)
                } else {
                    onItemClick(item)
                }
            }

            binding.cardMediaRoot.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }
    }
}
