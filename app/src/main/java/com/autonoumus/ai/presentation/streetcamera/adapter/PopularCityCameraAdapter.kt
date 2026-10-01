package com.autonoumus.ai.presentation.streetcamera.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.autonoumus.ai.R
import com.autonoumus.ai.databinding.ItemCityCameraRowBinding
import com.autonoumus.ai.presentation.streetcamera.PopularCityLocationModel

class PopularCityCameraAdapter(
    private var cities: List<PopularCityLocationModel>,
    private val onCityClick: (PopularCityLocationModel) -> Unit
) : RecyclerView.Adapter<PopularCityCameraAdapter.CityViewHolder>() {

    fun updateCities(newCities: List<PopularCityLocationModel>) {
        this.cities = newCities
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CityViewHolder {
        val binding = ItemCityCameraRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CityViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CityViewHolder, position: Int) {
        holder.bind(cities[position])
    }

    override fun getItemCount(): Int = cities.size

    inner class CityViewHolder(
        private val binding: ItemCityCameraRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PopularCityLocationModel) {
            binding.tvCityName.text = item.name
            binding.tvCityState.text = if (item.stateOrRegion.isNotBlank()) ", ${item.stateOrRegion}" else ""
            binding.tvCameraCount.text = item.cameraCountLabel

            binding.ivCityThumb.load(item.imageUrl) {
                crossfade(true)
                placeholder(R.drawable.img_street_view_mountain)
                error(R.drawable.img_street_view_mountain)
            }

            binding.root.setOnClickListener {
                onCityClick(item)
            }
        }
    }
}
