package com.autonoumus.ai.presentation.speedometer

import android.content.Intent
import android.graphics.Color
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.databinding.ActivityTripHistoryBinding
import com.autonoumus.ai.domain.model.TripFilter
import com.autonoumus.ai.domain.model.TripRecord
import com.autonoumus.ai.domain.repository.TripHistoryRepository
import com.autonoumus.ai.presentation.speedometer.adapter.TripHistoryAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class TripHistoryActivity : BaseActivity<ActivityTripHistoryBinding>() {

    @Inject
    lateinit var tripHistoryRepository: TripHistoryRepository

    @Inject
    lateinit var preferenceManager: PreferenceManager

    private lateinit var adapter: TripHistoryAdapter
    private lateinit var gestureDetector: GestureDetector
    private var selectedSource = TripRecord.TRIP_SOURCE_SPEEDOMETER
    private var currentFilter = TripFilter.ALL
    private var filterJob: Job? = null
    private var currentLoadedTrips: List<TripRecord> = emptyList()

    private val filtersList = listOf(
        TripFilter.ALL,
        TripFilter.TODAY,
        TripFilter.THIS_WEEK,
        TripFilter.THIS_MONTH
    )

    override fun inflateBinding(inflater: LayoutInflater): ActivityTripHistoryBinding {
        return ActivityTripHistoryBinding.inflate(inflater)
    }

    override fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.tripHistoryRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            binding.layoutTopBar.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                topMargin = bars.top
            }
            binding.layoutExportBottom.updatePadding(bottom = bars.bottom + (8 * density).toInt())
            binding.rvTripHistory.updatePadding(bottom = bars.bottom + (80 * density).toInt())
            insets
        }
    }

    override fun initViews() {
        setupTopBar()
        setupSourceTabs()
        setupFilterPills()
        setupRecyclerView()
        setupExportButton()
        setupSwipeGesture()
        loadTrips()
    }

    override fun onResume() {
        super.onResume()
        val isKmh = preferenceManager.isSpeedUnitKmh
        adapter.setUnit(isKmh)
        // No loadTrips() here: the collector started in initViews uses repeatOnLifecycle(STARTED),
        // so it already re-collects fresh trips every time the screen comes back.
    }

    private fun setupTopBar() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private var lastSpeedometerFilter = TripFilter.ALL
    private var lastRouteFinderFilter = TripFilter.ALL

    private fun setupSourceTabs() {
        binding.tvSourceSpeedometer.setOnClickListener {
            selectSource(TripRecord.TRIP_SOURCE_SPEEDOMETER)
        }

        binding.tvSourceRouteFinder.setOnClickListener {
            selectSource(TripRecord.TRIP_SOURCE_ROUTE_FINDER)
        }
    }

    private fun selectSource(source: String) {
        if (selectedSource == source) return
        selectedSource = source
        currentFilter = if (source == TripRecord.TRIP_SOURCE_SPEEDOMETER) {
            lastSpeedometerFilter
        } else {
            lastRouteFinderFilter
        }
        updateSourceTabsUi()
        updateFilterPillsUi(currentFilter)
        scrollToSelectedFilter(currentFilter)
        loadTrips()
    }

    private fun updateSourceTabsUi() {
        val selectedBg = R.drawable.bg_trip_source_selected
        val unselectedBg = android.R.color.transparent
        val selectedColor = Color.WHITE
        val unselectedColor = Color.parseColor("#64748B")

        if (selectedSource == TripRecord.TRIP_SOURCE_SPEEDOMETER) {
            binding.tvSourceSpeedometer.setBackgroundResource(selectedBg)
            binding.tvSourceSpeedometer.setTextColor(selectedColor)

            binding.tvSourceRouteFinder.setBackgroundResource(unselectedBg)
            binding.tvSourceRouteFinder.setTextColor(unselectedColor)
        } else {
            binding.tvSourceRouteFinder.setBackgroundResource(selectedBg)
            binding.tvSourceRouteFinder.setTextColor(selectedColor)

            binding.tvSourceSpeedometer.setBackgroundResource(unselectedBg)
            binding.tvSourceSpeedometer.setTextColor(unselectedColor)
        }
        updateVisibilityState(currentLoadedTrips.isEmpty())
    }

    private fun setupFilterPills() {
        binding.tvTabAll.setOnClickListener {
            selectFilter(TripFilter.ALL)
        }

        binding.tvTabToday.setOnClickListener {
            selectFilter(TripFilter.TODAY)
        }

        binding.tvTabThisWeek.setOnClickListener {
            selectFilter(TripFilter.THIS_WEEK)
        }

        binding.tvTabThisMonth.setOnClickListener {
            selectFilter(TripFilter.THIS_MONTH)
        }
    }

    private fun selectFilter(filter: TripFilter) {
        if (selectedSource == TripRecord.TRIP_SOURCE_SPEEDOMETER) {
            lastSpeedometerFilter = filter
        } else {
            lastRouteFinderFilter = filter
        }
        if (currentFilter == filter) return
        currentFilter = filter
        updateFilterPillsUi(filter)
        scrollToSelectedFilter(filter)
        loadTrips()
    }

    private fun updateFilterPillsUi(filter: TripFilter) {
        val selectedBg = R.drawable.bg_trip_filter_pill_selected
        val unselectedBg = R.drawable.bg_trip_filter_pill_unselected
        val selectedColor = Color.WHITE
        val unselectedColor = Color.parseColor("#64748B")

        binding.tvTabAll.apply {
            setBackgroundResource(if (filter == TripFilter.ALL) selectedBg else unselectedBg)
            setTextColor(if (filter == TripFilter.ALL) selectedColor else unselectedColor)
        }

        binding.tvTabToday.apply {
            setBackgroundResource(if (filter == TripFilter.TODAY) selectedBg else unselectedBg)
            setTextColor(if (filter == TripFilter.TODAY) selectedColor else unselectedColor)
        }

        binding.tvTabThisWeek.apply {
            setBackgroundResource(if (filter == TripFilter.THIS_WEEK) selectedBg else unselectedBg)
            setTextColor(if (filter == TripFilter.THIS_WEEK) selectedColor else unselectedColor)
        }

        binding.tvTabThisMonth.apply {
            setBackgroundResource(if (filter == TripFilter.THIS_MONTH) selectedBg else unselectedBg)
            setTextColor(if (filter == TripFilter.THIS_MONTH) selectedColor else unselectedColor)
        }
    }

    private fun scrollToSelectedFilter(filter: TripFilter) {
        val targetView = when (filter) {
            TripFilter.ALL -> binding.tvTabAll
            TripFilter.TODAY -> binding.tvTabToday
            TripFilter.THIS_WEEK -> binding.tvTabThisWeek
            TripFilter.THIS_MONTH -> binding.tvTabThisMonth
        }
        binding.hsvFilters.post {
            val scrollX = (targetView.left - (binding.hsvFilters.width - targetView.width) / 2).coerceAtLeast(0)
            binding.hsvFilters.smoothScrollTo(scrollX, 0)
        }
    }

    private var touchStartedInContentArea: Boolean = false

    private fun setupSwipeGesture() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 80
            private val SWIPE_VELOCITY_THRESHOLD = 150

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                // Only handle intentional horizontal swipes (must be significantly more horizontal than vertical)
                if (abs(diffX) > abs(diffY) * 1.4f && abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX < 0) {
                        // Swipe Left: Advance filter (All -> Today -> This Week -> This Month)
                        onSwipeNext()
                        return true
                    } else {
                        // Swipe Right: Step back filter (This Month -> This Week -> Today -> All)
                        onSwipePrevious()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun onSwipeNext() {
        // Only cycles through bottom filter tabs (All -> Today -> This Week -> This Month)
        // Top Speedometer and Route Finder tabs are strictly clickable only.
        val currentIndex = filtersList.indexOf(currentFilter)
        if (currentIndex < filtersList.size - 1) {
            selectFilter(filtersList[currentIndex + 1])
        }
    }

    private fun onSwipePrevious() {
        // Only cycles through bottom filter tabs (This Month -> This Week -> Today -> All)
        // Top Speedometer and Route Finder tabs are strictly clickable only.
        val currentIndex = filtersList.indexOf(currentFilter)
        if (currentIndex > 0) {
            selectFilter(filtersList[currentIndex - 1])
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // Only capture swipe flings when touch begins in the content/list area below the filter tabs
            val filterBottom = binding.hsvFilters.bottom
            touchStartedInContentArea = filterBottom <= 0 || ev.y > filterBottom
        }

        if (touchStartedInContentArea && ::gestureDetector.isInitialized) {
            gestureDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setupRecyclerView() {
        val isKmh = preferenceManager.isSpeedUnitKmh
        adapter = TripHistoryAdapter(isSpeedUnitKmh = isKmh) { item ->
            openTripDetails(item)
        }

        binding.rvTripHistory.apply {
            layoutManager = LinearLayoutManager(this@TripHistoryActivity)
            adapter = this@TripHistoryActivity.adapter
        }
    }

    private fun setupExportButton() {
        binding.btnExportTrips.setOnClickListener {
            exportTrips()
        }
    }

    private fun loadTrips() {
        filterJob?.cancel()
        filterJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tripHistoryRepository.getTripsByFilter(currentFilter).collect { trips ->
                    val filtered = trips.filter {
                        if (selectedSource == TripRecord.TRIP_SOURCE_ROUTE_FINDER) {
                            it.tripSource == TripRecord.TRIP_SOURCE_ROUTE_FINDER
                        } else {
                            it.tripSource != TripRecord.TRIP_SOURCE_ROUTE_FINDER
                        }
                    }
                    currentLoadedTrips = filtered
                    adapter.submitList(filtered)
                    updateSummaryBanner(filtered)
                    updateVisibilityState(filtered.isEmpty())
                }
            }
        }
    }

    private fun updateVisibilityState(isEmpty: Boolean) {
        val isSpeedometer = selectedSource == TripRecord.TRIP_SOURCE_SPEEDOMETER
        if (isEmpty) {
            binding.rvTripHistory.visibility = View.GONE
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.layoutSummaryBanner.visibility = View.GONE
            binding.tvSectionHeader.visibility = View.GONE
            binding.layoutExportBottom.visibility = View.GONE
            if (isSpeedometer) {
                binding.tvEmptyTitle.text = "No Trips Recorded Yet"
                binding.tvEmptySubtitle.text = "Your completed speedometer trips will appear here"
            } else {
                binding.tvEmptyTitle.text = "No Route History Yet"
                binding.tvEmptySubtitle.text = "Your saved and completed routes will appear here"
            }
        } else {
            binding.rvTripHistory.visibility = View.VISIBLE
            binding.layoutEmptyState.visibility = View.GONE
            if (isSpeedometer) {
                binding.layoutSummaryBanner.visibility = View.VISIBLE
                binding.tvSectionHeader.visibility = View.VISIBLE
                binding.layoutExportBottom.visibility = View.VISIBLE
            } else {
                binding.layoutSummaryBanner.visibility = View.GONE
                binding.tvSectionHeader.visibility = View.GONE
                binding.layoutExportBottom.visibility = View.GONE
            }
        }
    }

    private fun updateSummaryBanner(trips: List<TripRecord>) {
        val isKmh = preferenceManager.isSpeedUnitKmh
        val totalTrips = trips.size
        binding.tvSummaryTripsCount.text = totalTrips.toString()

        val totalMeters = trips.sumOf { it.distanceMeters }
        val distanceConverted = if (isKmh) {
            totalMeters / 1000.0
        } else {
            (totalMeters / 1000.0) * 0.621371
        }
        val distUnit = if (isKmh) "km" else "mi"
        binding.tvSummaryDistance.text = String.format(Locale.US, "%.1f %s", distanceConverted, distUnit)

        val avgSpeedSum = trips.map { it.avgSpeedKmh.toDouble() }
        val rawAvgSpeed = if (trips.isNotEmpty()) avgSpeedSum.average() else 0.0
        val avgSpeedConverted = if (isKmh) rawAvgSpeed else rawAvgSpeed * 0.621371
        val speedUnit = if (isKmh) "km/h" else "mph"
        binding.tvSummaryAvgSpeed.text = String.format(Locale.US, "%.1f %s", avgSpeedConverted, speedUnit)
    }

    private fun exportTrips() {
        if (currentLoadedTrips.isEmpty()) {
            Toast.makeText(this, "No trips to export", Toast.LENGTH_SHORT).show()
            return
        }

        val isKmh = preferenceManager.isSpeedUnitKmh
        val distUnit = if (isKmh) "km" else "mi"
        val speedUnit = if (isKmh) "km/h" else "mph"

        val sb = StringBuilder()
        sb.append("=== SPEEDOMETER TRIP HISTORY REPORT ===\n\n")
        sb.append("Total Trips: ${currentLoadedTrips.size}\n")
        sb.append("Total Distance: ${binding.tvSummaryDistance.text}\n")
        sb.append("Average Speed: ${binding.tvSummaryAvgSpeed.text}\n\n")
        sb.append("--------------------------------------\n\n")

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        currentLoadedTrips.forEachIndexed { index, trip ->
            val tripNum = currentLoadedTrips.size - index
            val distance = if (isKmh) trip.distanceMeters / 1000.0 else (trip.distanceMeters / 1000.0) * 0.621371
            val avgSpeed = if (isKmh) trip.avgSpeedKmh else trip.avgSpeedKmh * 0.621371f
            val maxSpeed = if (isKmh) trip.maxSpeedKmh else trip.maxSpeedKmh * 0.621371f

            val hrs = trip.durationSec / 3600
            val mins = (trip.durationSec % 3600) / 60
            val secs = trip.durationSec % 60
            val durStr = String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)

            val locationName = when {
                trip.endAddress.isNotBlank() && trip.endAddress != "Destination" -> trip.endAddress
                trip.startAddress.isNotBlank() && trip.startAddress != "Start Location" -> trip.startAddress
                else -> "Trip"
            }

            sb.append("Trip #$tripNum — $locationName\n")
            sb.append("Date: ${dateFormat.format(Date(trip.timestamp))}\n")
            sb.append("Distance: ${String.format(Locale.US, "%.2f %s", distance, distUnit)}\n")
            sb.append("Duration: $durStr\n")
            sb.append("Avg Speed: ${String.format(Locale.US, "%.1f %s", avgSpeed, speedUnit)}\n")
            sb.append("Max Speed: ${String.format(Locale.US, "%.1f %s", maxSpeed, speedUnit)}\n\n")
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Speedometer Trip History Report")
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(shareIntent, "Export All Trips"))
    }

    private fun openTripDetails(trip: TripRecord) {
        val intent = TripSummaryActivity.newIntent(
            context = this,
            durationSec = trip.durationSec,
            distanceMeters = trip.distanceMeters,
            avgSpeedKmh = trip.avgSpeedKmh,
            maxSpeedKmh = trip.maxSpeedKmh,
            startAddress = trip.startAddress,
            endAddress = trip.endAddress,
            startLat = trip.startLat,
            startLon = trip.startLon,
            endLat = trip.endLat,
            endLon = trip.endLon,
            waypointsJson = downsampleWaypointsJson(trip.waypointsJson),
            isFromHistory = true,
            limitExceededCount = trip.limitExceededCount,
            topSpeedLocation = trip.topSpeedLocation,
            safePercent = trip.safePercent,
            warningPercent = trip.warningPercent,
            overSpeedPercent = trip.overSpeedPercent,
            safetyScore = trip.safetyScore
        )
        startActivity(intent)
    }
}
