package com.autonoumus.ai.presentation.common.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.autonoumus.ai.R
import com.autonoumus.ai.core.util.VideoThumbnailModel
import com.autonoumus.ai.databinding.ItemRecentCaptureBinding
import com.autonoumus.ai.domain.model.RecentCaptureItem

/** Shared grid adapter for real captured media - used by both Home's Recent Captures and the full Gallery. */
class CaptureGridAdapter(
    private var items: List<RecentCaptureItem> = emptyList(),
    private val onItemClick: (RecentCaptureItem) -> Unit = {}
) : RecyclerView.Adapter<CaptureGridAdapter.ViewHolder>() {

    fun submitList(newItems: List<RecentCaptureItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentCaptureBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemRecentCaptureBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecentCaptureItem) {
            val request: Any = if (item.isVideo) VideoThumbnailModel(item.contentUri) else item.contentUri
            binding.ivCaptureThumbnail.load(request) {
                crossfade(true)
                placeholder(R.drawable.bg_tool_icon)
                error(R.drawable.bg_tool_icon)
            }
            binding.tvCaptureDate.text = item.dateFormatted
            binding.tvCaptureLocation.text = item.locationFormatted

            val iconRes = if (item.isVideo) R.drawable.ic_video_home else R.drawable.ic_camera
            binding.ivMediaType.setImageResource(iconRes)

            binding.root.setOnClickListener { onItemClick(item) }
        }
    }
}
