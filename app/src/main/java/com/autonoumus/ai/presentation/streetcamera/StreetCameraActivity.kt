package com.autonoumus.ai.presentation.streetcamera

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.databinding.ActivityStreetCameraBinding
import com.autonoumus.ai.databinding.LayoutTabStreetCameraCountriesBinding
import com.autonoumus.ai.databinding.LayoutTabStreetCameraLiveBinding
import com.autonoumus.ai.databinding.LayoutTabStreetCameraPlacesBinding
import com.autonoumus.ai.domain.model.StreetCamera
import com.autonoumus.ai.presentation.streetcamera.adapter.CountryCameraAdapter
import com.autonoumus.ai.presentation.streetcamera.adapter.LiveStreetCameraAdapter
import com.autonoumus.ai.presentation.streetcamera.adapter.PlaceCategoryAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class StreetCameraActivity : BaseActivity<ActivityStreetCameraBinding>() {

    private val viewModel: StreetCameraViewModel by viewModels()

    private lateinit var liveAdapter: LiveStreetCameraAdapter
    private lateinit var categoryAdapter: PlaceCategoryAdapter
    private lateinit var countryAdapter: CountryCameraAdapter

    private var liveBinding: LayoutTabStreetCameraLiveBinding? = null
    private var placesBinding: LayoutTabStreetCameraPlacesBinding? = null
    private var countriesBinding: LayoutTabStreetCameraCountriesBinding? = null

    companion object {
        private const val TAB_LIVE = 0
        private const val TAB_PLACES = 1
        private const val TAB_COUNTRIES = 2

        fun newIntent(context: Context): Intent {
            return Intent(context, StreetCameraActivity::class.java)
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityStreetCameraBinding {
        return ActivityStreetCameraBinding.inflate(inflater)
    }

    override fun initViews() {
        setupTopBar()
        setupAdapters()
        setupTabs()
        setupViewPager()
        setupListeners()
    }

    private fun setupTopBar() {
        binding.btnBack.setOnClickListener { finish() }
        WindyAttribution.apply(binding.tvWindyAttribution)
    }

    private fun setupAdapters() {
        liveAdapter = LiveStreetCameraAdapter(
            onCameraClick = { camera -> openDetail(camera) },
            onFavoriteToggle = { camera, isFav -> viewModel.toggleFavorite(camera, isFav) }
        )

        categoryAdapter = PlaceCategoryAdapter(viewModel.uiState.value.placeCategories) { category ->
            startActivity(PopularLocationsActivity.newIntentForCategory(this, category.title))
        }

        countryAdapter = CountryCameraAdapter(viewModel.uiState.value.countries) { country ->
            startActivity(PopularLocationsActivity.newIntentForCountry(this, country.name))
        }
    }

    private fun setupTabs() {
        binding.tabLive.setOnClickListener {
            binding.viewPagerStreetCamera.currentItem = TAB_LIVE
        }
        binding.tabPlaces.setOnClickListener {
            binding.viewPagerStreetCamera.currentItem = TAB_PLACES
        }
        binding.tabCountries.setOnClickListener {
            binding.viewPagerStreetCamera.currentItem = TAB_COUNTRIES
        }
    }

    private fun setupViewPager() {
        val pagerAdapter = StreetCameraPagerAdapter()
        binding.viewPagerStreetCamera.adapter = pagerAdapter
        binding.viewPagerStreetCamera.offscreenPageLimit = 2

        binding.viewPagerStreetCamera.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val targetTab = when (position) {
                    TAB_LIVE -> StreetCameraTab.LIVE
                    TAB_PLACES -> StreetCameraTab.PLACES
                    else -> StreetCameraTab.COUNTRIES
                }
                viewModel.selectTab(targetTab)
                updateTabPillsUI(targetTab)
            }
        })
    }

    private inner class StreetCameraPagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = 3

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TAB_LIVE -> {
                    val b = LayoutTabStreetCameraLiveBinding.inflate(inflater, parent, false)
                    liveBinding = b
                    b.rvLiveCameras.apply {
                        layoutManager = LinearLayoutManager(this@StreetCameraActivity)
                        adapter = liveAdapter
                    }
                    b.btnLiveRetry.setOnClickListener { viewModel.retry() }
                    renderLiveEmptyState(viewModel.uiState.value)
                    TabViewHolder(b.root)
                }
                TAB_PLACES -> {
                    val b = LayoutTabStreetCameraPlacesBinding.inflate(inflater, parent, false)
                    placesBinding = b
                    b.rvPlaceCategories.apply {
                        layoutManager = GridLayoutManager(this@StreetCameraActivity, 2)
                        adapter = categoryAdapter
                    }
                    b.etSearchPlaces.doAfterTextChanged { editable ->
                        val query = editable?.toString().orEmpty()
                        viewModel.updatePlacesSearch(query)
                    }
                    b.btnClearPlacesSearch.setOnClickListener {
                        b.etSearchPlaces.setText("")
                        viewModel.clearPlacesSearch()
                    }
                    b.btnBrowseCategories.setOnClickListener {
                        b.etSearchPlaces.setText("")
                        viewModel.clearPlacesSearch()
                    }
                    TabViewHolder(b.root)
                }
                else -> {
                    val b = LayoutTabStreetCameraCountriesBinding.inflate(inflater, parent, false)
                    countriesBinding = b
                    b.rvCountries.apply {
                        layoutManager = GridLayoutManager(this@StreetCameraActivity, 2)
                        adapter = countryAdapter
                    }
                    TabViewHolder(b.root)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            // Reactive state flow handles data submission
        }

        inner class TabViewHolder(view: View) : RecyclerView.ViewHolder(view)
    }

    private fun setupListeners() {
        binding.tvWindyAttribution.setOnClickListener {
            openWindyWebsite()
        }
    }

    private fun openWindyWebsite() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://windy.com")))
        } catch (e: Exception) {
            // No browser available
        }
    }

    private fun openDetail(camera: StreetCamera) {
        startActivity(StreetCameraDetailActivity.newIntent(this, camera))
    }

    override fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> renderUiState(state) }
            }
        }
    }

    private fun renderUiState(state: StreetCameraUiState) {
        updateTabPillsUI(state.activeTab)

        if (binding.viewPagerStreetCamera.currentItem != state.activeTab.ordinal) {
            binding.viewPagerStreetCamera.currentItem = state.activeTab.ordinal
        }

        // Live Feed
        liveAdapter.setFavorites(state.favoriteCameraIds)
        liveAdapter.submitList(state.liveCameras)
        renderLiveEmptyState(state)

        // Places
        placesBinding?.let { pb ->
            pb.btnClearPlacesSearch.visibility = if (state.placesSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
            if (state.isPlacesSearchEmpty) {
                pb.rvPlaceCategories.visibility = View.GONE
                pb.layoutPlacesEmptyState.visibility = View.VISIBLE
                pb.tvEmptyPlacesDescription.text =
                    "We couldn't find live cameras for \"${state.placesSearchQuery}\". Try searching for a different location or browse categories."
            } else {
                pb.layoutPlacesEmptyState.visibility = View.GONE
                pb.rvPlaceCategories.visibility = View.VISIBLE
                categoryAdapter.updateCategories(state.filteredPlaceCategories)
            }
        }

        // Countries
        countryAdapter.updateCountries(state.countries)

        // Loading
        binding.progressLoading.visibility = if (state.isLoading) View.VISIBLE else View.GONE
    }

    private fun renderLiveEmptyState(state: StreetCameraUiState) {
        liveBinding?.layoutLiveEmptyState?.visibility =
            if (!state.isLoading && state.liveCameras.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateTabPillsUI(activeTab: StreetCameraTab) {
        val selectedColor = Color.parseColor("#4F46E5")
        val unselectedColor = Color.parseColor("#94A3B8")

        // Tab 1: Live
        val isLive = activeTab == StreetCameraTab.LIVE
        binding.pillTabLive.setBackgroundResource(if (isLive) R.drawable.bg_tab_pill_selected else 0)
        binding.ivTabLive.setColorFilter(if (isLive) selectedColor else unselectedColor)
        binding.tvTabLive.setTextColor(if (isLive) selectedColor else unselectedColor)

        // Tab 2: Places
        val isPlaces = activeTab == StreetCameraTab.PLACES
        binding.pillTabPlaces.setBackgroundResource(if (isPlaces) R.drawable.bg_tab_pill_selected else 0)
        binding.ivTabPlaces.setColorFilter(if (isPlaces) selectedColor else unselectedColor)
        binding.tvTabPlaces.setTextColor(if (isPlaces) selectedColor else unselectedColor)

        // Tab 3: Countries
        val isCountries = activeTab == StreetCameraTab.COUNTRIES
        binding.pillTabCountries.setBackgroundResource(if (isCountries) R.drawable.bg_tab_pill_selected else 0)
        binding.ivTabCountries.setColorFilter(if (isCountries) selectedColor else unselectedColor)
        binding.tvTabCountries.setTextColor(if (isCountries) selectedColor else unselectedColor)
    }
}
