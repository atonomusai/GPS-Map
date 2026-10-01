package com.autonoumus.ai.presentation.home.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.autonoumus.ai.databinding.ItemQuickToolBinding
import com.autonoumus.ai.domain.model.QuickToolItem

class QuickToolsAdapter(
    private var items: List<QuickToolItem> = emptyList(),
    private val onItemClick: (QuickToolItem) -> Unit = {}
) : RecyclerView.Adapter<QuickToolsAdapter.ViewHolder>() {

    fun submitList(newItems: List<QuickToolItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemQuickToolBinding.inflate(
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
        private val binding: ItemQuickToolBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: QuickToolItem) {
            binding.ivToolIcon.setImageResource(item.iconRes)
            binding.tvToolName.text = item.title
            binding.root.setOnClickListener { onItemClick(item) }
        }
    }
}
