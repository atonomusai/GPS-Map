package com.autonoumus.ai.presentation.onboarding.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.autonoumus.ai.databinding.ItemOnboardingPageBinding
import com.autonoumus.ai.domain.model.OnboardingItem

class OnboardingPagerAdapter(
    private val items: List<OnboardingItem>
) : RecyclerView.Adapter<OnboardingPagerAdapter.OnboardingViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OnboardingViewHolder {
        val binding = ItemOnboardingPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return OnboardingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OnboardingViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class OnboardingViewHolder(
        private val binding: ItemOnboardingPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: OnboardingItem) {
            binding.ivOnboarding.setImageResource(item.imageRes)
            binding.tvTitle.text = item.title
            binding.tvSubtitle.text = item.subtitle
        }
    }
}
