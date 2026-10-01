package com.autonoumus.ai.presentation.streetcamera

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import coil.request.CachePolicy
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.databinding.ActivityStreetCameraDetailBinding
import com.autonoumus.ai.domain.model.StreetCamera
import com.autonoumus.ai.domain.repository.StreetCameraRepository
import com.autonoumus.ai.presentation.streetcamera.adapter.CameraSuggestionAdapter
import com.autonoumus.ai.presentation.streetcamera.adapter.CameraSuggestionItem
import android.graphics.Color
import androidx.core.view.WindowCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class StreetCameraDetailActivity : BaseActivity<ActivityStreetCameraDetailBinding>() {

    @Inject
    lateinit var streetCameraRepository: StreetCameraRepository

    private var currentCameraId: String = ""
    private var currentTitle: String = ""
    private var currentPlayerUrl: String? = null
    private var currentCity: String = ""
    private var currentCountry: String = ""

    private var refreshJob: Job? = null
    private lateinit var suggestionAdapter: CameraSuggestionAdapter
    private val allSuggestions = mutableListOf<CameraSuggestionItem>()

    companion object {
        private const val EXTRA_ID = "extra_camera_id"
        private const val EXTRA_TITLE = "extra_camera_title"
        private const val EXTRA_CITY = "extra_camera_city"
        private const val EXTRA_COUNTRY = "extra_camera_country"
        private const val EXTRA_IMAGE_URL = "extra_camera_image_url"
        private const val EXTRA_PLAYER_URL = "extra_camera_player_url"

        private const val REFRESH_INTERVAL_MS = 90_000L

        fun newIntent(context: Context, camera: StreetCamera): Intent {
            return Intent(context, StreetCameraDetailActivity::class.java).apply {
                putExtra(EXTRA_ID, camera.id)
                putExtra(EXTRA_TITLE, camera.title)
                putExtra(EXTRA_CITY, camera.city)
                putExtra(EXTRA_COUNTRY, camera.country)
                putExtra(EXTRA_IMAGE_URL, camera.imageUrl ?: camera.thumbnailUrl)
                putExtra(EXTRA_PLAYER_URL, camera.playerUrl)
            }
        }

        fun newIntent(context: Context, id: String, title: String, imageUrl: String?): Intent {
            return Intent(context, StreetCameraDetailActivity::class.java).apply {
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_IMAGE_URL, imageUrl)
            }
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityStreetCameraDetailBinding {
        return ActivityStreetCameraDetailBinding.inflate(inflater)
    }

    override fun initViews() {
        configureDarkStatusBar()
        binding.btnDetailBack.setOnClickListener { finish() }
        WindyAttribution.apply(binding.tvDetailAttribution)

        currentCameraId = intent.getStringExtra(EXTRA_ID).orEmpty()
        currentTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Webcam" }
        currentCity = intent.getStringExtra(EXTRA_CITY).orEmpty()
        currentCountry = intent.getStringExtra(EXTRA_COUNTRY).orEmpty()
        currentPlayerUrl = intent.getStringExtra(EXTRA_PLAYER_URL)
        val imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL)

        updateHeaderInfo(currentTitle, currentCity, currentCountry)

        binding.btnWatchTimelapse.setOnClickListener {
            currentPlayerUrl?.let { url ->
                startActivity(TimelapsePlayerActivity.newIntent(this, url, currentTitle))
            }
        }
        updateTimelapseButtonVisibility()

        setupSuggestionsRecyclerView()
        loadInitialCuratedSuggestions()
        fetchLiveSuggestions()

        loadImage(imageUrl, forceRefresh = false)
        startLiveRefreshLoop(currentCameraId)
    }

    private fun configureDarkStatusBar() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.parseColor("#0A0A0C")
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun updateHeaderInfo(title: String, city: String, country: String) {
        binding.tvDetailTitle.text = title
        val subtitle = listOf(city, country).filter { it.isNotBlank() }.joinToString(", ")
        binding.tvDetailSubtitle.text = subtitle.ifBlank { "Webcam" }
    }

    private fun setupSuggestionsRecyclerView() {
        suggestionAdapter = CameraSuggestionAdapter { selectedItem ->
            onSuggestionSelected(selectedItem)
        }
        binding.rvSuggestions.apply {
            layoutManager = LinearLayoutManager(this@StreetCameraDetailActivity)
            adapter = suggestionAdapter
        }
    }

    private fun onSuggestionSelected(suggestion: CameraSuggestionItem) {
        binding.rvSuggestions.scrollToPosition(0)

        currentCameraId = suggestion.id
        currentTitle = suggestion.title
        currentCity = suggestion.streetCamera?.city ?: ""
        currentCountry = suggestion.streetCamera?.country ?: suggestion.authorOrLocation
        currentPlayerUrl = suggestion.streetCamera?.playerUrl

        updateHeaderInfo(currentTitle, currentCity, currentCountry)
        updateTimelapseButtonVisibility()

        val imgUrl = suggestion.imageUrl ?: suggestion.streetCamera?.imageUrl ?: suggestion.streetCamera?.thumbnailUrl
        loadImage(imgUrl, forceRefresh = false)

        binding.tvDetailError.visibility = View.GONE
        binding.tvDetailLastUpdated.visibility = View.GONE

        // Refresh suggestions list to exclude active camera
        updateVisibleSuggestions()

        startLiveRefreshLoop(currentCameraId)
    }

    private fun loadInitialCuratedSuggestions() {
        // Only real webcams from Windy are suggested (fetchLiveSuggestions). The previous
        // hard-coded items were stock photos with invented view counts / authors.
        allSuggestions.clear()
        updateVisibleSuggestions()
    }

    private fun fetchLiveSuggestions() {
        lifecycleScope.launch {
            binding.progressSuggestionsLoading.visibility = View.VISIBLE
            val result = streetCameraRepository.getWebcams(limit = 25)
            binding.progressSuggestionsLoading.visibility = View.GONE

            result.onSuccess { cameras ->
                val liveConverted = cameras.map { camera ->
                    cameraToSuggestion(camera)
                }
                allSuggestions.clear()
                allSuggestions.addAll(liveConverted.distinctBy { it.id })
                updateVisibleSuggestions()
            }
        }
    }

    private fun cameraToSuggestion(camera: StreetCamera): CameraSuggestionItem {
        // Real data only: location and the frame's actual update time (no invented views/durations).
        return CameraSuggestionItem(
            id = camera.id,
            title = camera.title.ifBlank { "Webcam" },
            authorOrLocation = camera.getLocationSubtitle(),
            viewsAndAge = camera.getUpdatedAgoLabel().orEmpty(),
            durationText = "",
            imageUrl = camera.imageUrl ?: camera.thumbnailUrl,
            streetCamera = camera
        )
    }

    private fun updateVisibleSuggestions() {
        val filtered = allSuggestions.filter { it.id != currentCameraId }
        suggestionAdapter.submitList(filtered)
    }

    private fun updateTimelapseButtonVisibility() {
        binding.btnWatchTimelapse.visibility = if (currentPlayerUrl.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun startLiveRefreshLoop(id: String) {
        refreshJob?.cancel()
        if (id.isBlank() || id.startsWith("sug_")) return

        refreshJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    fetchAndShowLatestFrame(id)
                    delay(REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    private suspend fun fetchAndShowLatestFrame(id: String) {
        val result = streetCameraRepository.getWebcamDetail(id)
        result.onSuccess { camera ->
            if (camera.title.isNotBlank()) {
                currentTitle = camera.title
                binding.tvDetailTitle.text = camera.title
            }
            val subtitle = camera.getLocationSubtitle()
            if (subtitle.isNotBlank()) binding.tvDetailSubtitle.text = subtitle
            val freshImage = camera.imageUrl ?: camera.thumbnailUrl
            if (!freshImage.isNullOrBlank()) loadImage(freshImage, forceRefresh = true)

            val updatedLabel = camera.getUpdatedAgoLabel()
            binding.tvDetailLastUpdated.text = updatedLabel
            binding.tvDetailLastUpdated.visibility = if (updatedLabel != null) View.VISIBLE else View.GONE

            if (!camera.playerUrl.isNullOrBlank()) {
                currentPlayerUrl = camera.playerUrl
                updateTimelapseButtonVisibility()
            }
        }.onFailure {
            if (intent.getStringExtra(EXTRA_IMAGE_URL).isNullOrBlank()) {
                binding.tvDetailError.visibility = View.VISIBLE
                binding.tvDetailError.text = getString(R.string.camera_detail_unavailable)
            }
        }
    }

    private fun loadImage(url: String?, forceRefresh: Boolean) {
        val fallbackRes = R.drawable.img_street_view_mountain
        if (!url.isNullOrBlank()) {
            val requestUrl = if (forceRefresh) "$url&_t=${System.currentTimeMillis()}" else url
            if (!forceRefresh) binding.progressDetailLoading.visibility = View.VISIBLE
            binding.ivDetailImage.load(requestUrl) {
                crossfade(true)
                memoryCachePolicy(CachePolicy.DISABLED)
                diskCachePolicy(CachePolicy.DISABLED)
                placeholder(fallbackRes)
                error(fallbackRes)
                fallback(fallbackRes)
                listener(
                    onSuccess = { _, _ -> binding.progressDetailLoading.visibility = View.GONE },
                    onError = { _, _ -> binding.progressDetailLoading.visibility = View.GONE }
                )
            }
        } else {
            binding.ivDetailImage.setImageResource(fallbackRes)
        }
    }
}
