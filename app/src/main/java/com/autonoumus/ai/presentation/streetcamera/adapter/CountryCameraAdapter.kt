package com.autonoumus.ai.presentation.streetcamera.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.autonoumus.ai.databinding.ItemCountryCardBinding
import com.autonoumus.ai.presentation.streetcamera.CountryCameraModel

class CountryCameraAdapter(
    private var countries: List<CountryCameraModel>,
    private val onCountryClick: (CountryCameraModel) -> Unit
) : RecyclerView.Adapter<CountryCameraAdapter.CountryViewHolder>() {

    fun updateCountries(newCountries: List<CountryCameraModel>) {
        this.countries = newCountries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CountryViewHolder {
        val binding = ItemCountryCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CountryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CountryViewHolder, position: Int) {
        holder.bind(countries[position])
    }

    override fun getItemCount(): Int = countries.size

    inner class CountryViewHolder(
        private val binding: ItemCountryCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CountryCameraModel) {
            binding.tvCountryName.text = item.name
            binding.tvCountryCameraCount.text = item.cameraCountLabel
            binding.ivCountryFlag.setImageResource(item.flagDrawableRes)

            binding.cardCountryRoot.setOnClickListener {
                onCountryClick(item)
            }
        }
    }
}
