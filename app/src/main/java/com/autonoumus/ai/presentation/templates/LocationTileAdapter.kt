package com.autonoumus.ai.presentation.templates

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.Filterable
import com.autonoumus.ai.R
import com.autonoumus.ai.core.util.LocationSuggestion
import com.autonoumus.ai.core.util.WorldLocationProvider
import com.autonoumus.ai.databinding.ItemLocationSuggestionTileBinding

/**
 * Custom ArrayAdapter rendering tile-style suggestions for Country and Famous City / Landmark search.
 */
class LocationTileAdapter(
    context: Context,
    val isCityMode: Boolean,
    var countryFilter: String? = null
) : ArrayAdapter<LocationSuggestion>(
    context,
    R.layout.item_location_suggestion_tile,
    mutableListOf()
), Filterable {

    private var displayedItems: List<LocationSuggestion> =
        if (isCityMode) WorldLocationProvider.searchCities("", countryFilter, context)
        else WorldLocationProvider.COUNTRY_SUGGESTIONS

    fun updateCountryFilter(newCountry: String?) {
        countryFilter = newCountry
        if (isCityMode) {
            displayedItems = WorldLocationProvider.searchCities("", countryFilter, context)
            notifyDataSetChanged()
        }
    }

    private val customFilter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val query = constraint?.toString()?.trim().orEmpty()
            val filtered = if (isCityMode) {
                WorldLocationProvider.searchCities(query, countryFilter, context)
            } else {
                WorldLocationProvider.searchCountries(query)
            }

            return FilterResults().apply {
                values = filtered
                count = filtered.size
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            displayedItems = (results?.values as? List<LocationSuggestion>) ?: emptyList()
            if (results != null && results.count > 0) {
                notifyDataSetChanged()
            } else {
                notifyDataSetInvalidated()
            }
        }

        override fun convertResultToString(resultValue: Any?): CharSequence {
            val item = resultValue as? LocationSuggestion
            return if (isCityMode) {
                item?.address ?: item?.primaryTitle.orEmpty()
            } else {
                item?.country ?: item?.primaryTitle.orEmpty()
            }
        }
    }

    override fun getCount(): Int = displayedItems.size

    override fun getItem(position: Int): LocationSuggestion? = displayedItems.getOrNull(position)

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding: ItemLocationSuggestionTileBinding
        val view: View

        if (convertView == null) {
            binding = ItemLocationSuggestionTileBinding.inflate(
                LayoutInflater.from(context),
                parent,
                false
            )
            view = binding.root
            view.tag = binding
        } else {
            view = convertView
            binding = view.tag as ItemLocationSuggestionTileBinding
        }

        val item = getItem(position) ?: return view

        binding.tvSuggestionTitle.text = item.primaryTitle
        binding.tvSuggestionSubtitle.text = item.subtitle

        if (isCityMode) {
            binding.flIconBadge.setBackgroundResource(R.drawable.bg_blue_circle_badge)
            binding.ivSuggestionIcon.setImageResource(R.drawable.ic_location)
            binding.tvSuggestionTag.visibility = View.VISIBLE
            binding.tvSuggestionTag.text = item.latitudeFormatted
        } else {
            binding.flIconBadge.setBackgroundResource(R.drawable.bg_badge_purple)
            binding.ivSuggestionIcon.setImageResource(R.drawable.ic_world_gray)
            if (item.latitudeFormatted.isNotBlank() && item.latitudeFormatted != "0.0000° N") {
                binding.tvSuggestionTag.visibility = View.VISIBLE
                binding.tvSuggestionTag.text = item.latitudeFormatted
            } else {
                binding.tvSuggestionTag.visibility = View.VISIBLE
                binding.tvSuggestionTag.text = "Country"
            }
        }

        return view
    }

    override fun getFilter(): Filter = customFilter
}
