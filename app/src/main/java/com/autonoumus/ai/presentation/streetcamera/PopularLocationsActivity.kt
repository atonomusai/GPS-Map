package com.autonoumus.ai.presentation.streetcamera

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.databinding.ActivityPopularLocationsBinding
import com.autonoumus.ai.domain.model.StreetCamera
import com.autonoumus.ai.presentation.streetcamera.adapter.FeaturedLocationAdapter
import com.autonoumus.ai.presentation.streetcamera.adapter.PopularCityCameraAdapter
import com.autonoumus.ai.presentation.streetcamera.adapter.PopularLocationFilterChipAdapter
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PopularLocationsActivity : BaseActivity<ActivityPopularLocationsBinding>() {

    private lateinit var featuredAdapter: FeaturedLocationAdapter
    private lateinit var filterChipAdapter: PopularLocationFilterChipAdapter
    private lateinit var cityCameraAdapter: PopularCityCameraAdapter

    private var allCities: List<PopularCityLocationModel> = emptyList()
    private var activeFilter: String = "All"
    private var categoryNameExtra: String? = null
    private var countryNameExtra: String? = null

    companion object {
        private const val EXTRA_CATEGORY_NAME = "extra_category_name"
        private const val EXTRA_COUNTRY_NAME = "extra_country_name"

        fun newIntentForCategory(context: Context, categoryName: String): Intent {
            return Intent(context, PopularLocationsActivity::class.java).apply {
                putExtra(EXTRA_CATEGORY_NAME, categoryName)
            }
        }

        fun newIntentForCountry(context: Context, countryName: String): Intent {
            return Intent(context, PopularLocationsActivity::class.java).apply {
                putExtra(EXTRA_COUNTRY_NAME, countryName)
            }
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityPopularLocationsBinding {
        return ActivityPopularLocationsBinding.inflate(inflater)
    }

    override fun initViews() {
        categoryNameExtra = intent.getStringExtra(EXTRA_CATEGORY_NAME)
        countryNameExtra = intent.getStringExtra(EXTRA_COUNTRY_NAME)

        allCities = StreetCameraDataProvider.getPopularCities(
            countryFilter = countryNameExtra,
            categoryFilter = categoryNameExtra
        )

        setupHeader()
        setupFeaturedCarousel()
        setupFilterChips()
        setupCityList()
    }

    private fun setupHeader() {
        binding.btnLocationsBack.setOnClickListener { finish() }

        val title = when {
            !categoryNameExtra.isNullOrBlank() -> "$categoryNameExtra - Webcams"
            !countryNameExtra.isNullOrBlank() -> "$countryNameExtra - Webcams"
            else -> "Popular Cities & Locations"
        }
        binding.tvLocationsHeaderTitle.text = title
    }

    private fun setupFeaturedCarousel() {
        val featured = StreetCameraDataProvider.getFeaturedLocations()
        featuredAdapter = FeaturedLocationAdapter(featured) { item ->
            val camera = StreetCamera(
                id = item.id,
                title = item.title,
                city = item.subtitle,
                country = "",
                imageUrl = item.imageUrl,
                thumbnailUrl = item.imageUrl
            )
            startActivity(StreetCameraDetailActivity.newIntent(this, camera))
        }

        binding.rvFeaturedLocations.apply {
            layoutManager = LinearLayoutManager(this@PopularLocationsActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = featuredAdapter
        }
    }

    private fun setupFilterChips() {
        // If a specific category was clicked, we already filter directly to that category
        if (!categoryNameExtra.isNullOrBlank()) {
            binding.rvFilterChips.visibility = View.GONE
            return
        }

        binding.rvFilterChips.visibility = View.VISIBLE
        val chipOptions = listOf("All", "Cities", "Landmarks", "Beaches", "Highways")
        filterChipAdapter = PopularLocationFilterChipAdapter(chipOptions, selectedIndex = 0) { selectedChip ->
            activeFilter = selectedChip
            applyFilter()
        }

        binding.rvFilterChips.apply {
            layoutManager = LinearLayoutManager(this@PopularLocationsActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = filterChipAdapter
            scrollToPosition(0)
        }
    }

    private fun setupCityList() {
        cityCameraAdapter = PopularCityCameraAdapter(allCities) { city ->
            val camera = StreetCamera(
                id = city.id,
                title = city.fullDisplayName,
                city = city.name,
                country = city.country,
                imageUrl = city.imageUrl,
                thumbnailUrl = city.imageUrl
            )
            startActivity(StreetCameraDetailActivity.newIntent(this, camera))
        }

        binding.rvCityLocations.apply {
            layoutManager = LinearLayoutManager(this@PopularLocationsActivity)
            adapter = cityCameraAdapter
        }

        applyFilter()
    }

    private fun applyFilter() {
        val filtered = if (activeFilter.equals("All", ignoreCase = true) || !categoryNameExtra.isNullOrBlank()) {
            allCities
        } else {
            allCities.filter { it.categoryType.equals(activeFilter, ignoreCase = true) }
        }

        cityCameraAdapter.updateCities(filtered)

        val noun = when {
            !categoryNameExtra.isNullOrBlank() -> "locations"
            !countryNameExtra.isNullOrBlank() -> "locations in $countryNameExtra"
            activeFilter.equals("Beaches", ignoreCase = true) -> "beaches"
            activeFilter.equals("Landmarks", ignoreCase = true) -> "landmarks"
            activeFilter.equals("Highways", ignoreCase = true) -> "highway views"
            else -> "cities"
        }
        binding.tvResultsCount.text = "Showing ${filtered.size} $noun"
    }

    override fun observeViewModel() {
        // No shared state needed for static/curated browsing
    }
}
