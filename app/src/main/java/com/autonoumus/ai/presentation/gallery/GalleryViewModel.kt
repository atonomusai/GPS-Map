package com.autonoumus.ai.presentation.gallery

import androidx.lifecycle.viewModelScope
import com.autonoumus.ai.core.base.BaseViewModel
import com.autonoumus.ai.domain.model.RecentCaptureItem
import com.autonoumus.ai.domain.repository.MediaCaptureRepository
import com.autonoumus.ai.domain.usecase.GetAllCapturesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class GalleryFilterTab {
    ALL, PHOTOS, VIDEOS
}

data class GalleryUiState(
    val groupedItems: List<GalleryListItem> = emptyList(),
    val allCount: Int = 0,
    val photosCount: Int = 0,
    val videosCount: Int = 0,
    val activeTab: GalleryFilterTab = GalleryFilterTab.ALL,
    val isSelectionMode: Boolean = false,
    val selectedCount: Int = 0,
    val selectedCaptures: List<RecentCaptureItem> = emptyList(),
    val isEmpty: Boolean = false,
    val searchQuery: String = ""
)

private data class SelectionSets(
    val all: Set<String> = emptySet(),
    val photos: Set<String> = emptySet(),
    val videos: Set<String> = emptySet()
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val getAllCapturesUseCase: GetAllCapturesUseCase,
    private val mediaCaptureRepository: MediaCaptureRepository
) : BaseViewModel() {

    private val _rawCaptures = MutableStateFlow<List<RecentCaptureItem>>(emptyList())
    private val _activeTab = MutableStateFlow(GalleryFilterTab.ALL)
    private val _searchQuery = MutableStateFlow("")
    private val _isSelectionMode = MutableStateFlow(false)

    private val _selectedAllIds = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedPhotosIds = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedVideosIds = MutableStateFlow<Set<String>>(emptySet())

    private val _selectionSets = combine(
        _selectedAllIds,
        _selectedPhotosIds,
        _selectedVideosIds
    ) { all, photos, videos ->
        SelectionSets(all, photos, videos)
    }

    val uiState: StateFlow<GalleryUiState> = combine(
        _rawCaptures,
        _activeTab,
        _searchQuery,
        _isSelectionMode,
        _selectionSets
    ) { captures, tab, query, selectionMode, selections ->
        val photosCount = captures.count { !it.isVideo }
        val videosCount = captures.count { it.isVideo }
        val allCount = captures.size

        val tabFiltered = when (tab) {
            GalleryFilterTab.ALL -> captures
            GalleryFilterTab.PHOTOS -> captures.filter { !it.isVideo }
            GalleryFilterTab.VIDEOS -> captures.filter { it.isVideo }
        }

        val queryFiltered = if (query.isBlank()) {
            tabFiltered
        } else {
            val q = query.trim().lowercase(Locale.ROOT)
            tabFiltered.filter { item ->
                item.locationName?.lowercase(Locale.ROOT)?.contains(q) == true ||
                item.cityCountry?.lowercase(Locale.ROOT)?.contains(q) == true ||
                item.locationFormatted.lowercase(Locale.ROOT).contains(q) ||
                item.dateFormatted.lowercase(Locale.ROOT).contains(q)
            }
        }

        val currentSelectedIds = when (tab) {
            GalleryFilterTab.ALL -> selections.all
            GalleryFilterTab.PHOTOS -> selections.photos
            GalleryFilterTab.VIDEOS -> selections.videos
        }

        val grouped = buildGroupedList(queryFiltered, selectionMode, currentSelectedIds)
        val selectedItems = tabFiltered.filter { currentSelectedIds.contains(it.id) }

        GalleryUiState(
            groupedItems = grouped,
            allCount = allCount,
            photosCount = photosCount,
            videosCount = videosCount,
            activeTab = tab,
            isSelectionMode = selectionMode,
            selectedCount = selectedItems.size,
            selectedCaptures = selectedItems,
            isEmpty = grouped.isEmpty(),
            searchQuery = query
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = GalleryUiState()
    )

    init {
        loadCaptures()
    }

    fun loadCaptures() {
        viewModelScope.launch {
            _rawCaptures.value = getAllCapturesUseCase()
        }
    }

    fun setFilterTab(tab: GalleryFilterTab) {
        if (_isSelectionMode.value) return
        _activeTab.value = tab
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleSelectionMode() {
        val newMode = !_isSelectionMode.value
        _isSelectionMode.value = newMode
        if (!newMode) {
            clearAllSelectedSets()
        }
    }

    fun setSelectionMode(enabled: Boolean) {
        _isSelectionMode.value = enabled
        if (!enabled) {
            clearAllSelectedSets()
        }
    }

    fun toggleItemSelection(capture: RecentCaptureItem) {
        val tab = _activeTab.value
        val flow = when (tab) {
            GalleryFilterTab.ALL -> _selectedAllIds
            GalleryFilterTab.PHOTOS -> _selectedPhotosIds
            GalleryFilterTab.VIDEOS -> _selectedVideosIds
        }
        val current = flow.value.toMutableSet()
        if (current.contains(capture.id)) {
            current.remove(capture.id)
        } else {
            current.add(capture.id)
        }
        flow.value = current
    }

    fun selectAll() {
        val tab = _activeTab.value
        val captures = _rawCaptures.value
        val query = _searchQuery.value

        val tabFiltered = when (tab) {
            GalleryFilterTab.ALL -> captures
            GalleryFilterTab.PHOTOS -> captures.filter { !it.isVideo }
            GalleryFilterTab.VIDEOS -> captures.filter { it.isVideo }
        }

        val visibleItems = if (query.isBlank()) {
            tabFiltered
        } else {
            val q = query.trim().lowercase(Locale.ROOT)
            tabFiltered.filter { item ->
                item.locationName?.lowercase(Locale.ROOT)?.contains(q) == true ||
                item.cityCountry?.lowercase(Locale.ROOT)?.contains(q) == true ||
                item.locationFormatted.lowercase(Locale.ROOT).contains(q) ||
                item.dateFormatted.lowercase(Locale.ROOT).contains(q)
            }
        }

        val visibleIds = visibleItems.map { it.id }.toSet()
        val flow = when (tab) {
            GalleryFilterTab.ALL -> _selectedAllIds
            GalleryFilterTab.PHOTOS -> _selectedPhotosIds
            GalleryFilterTab.VIDEOS -> _selectedVideosIds
        }

        val currentSelected = flow.value
        val selectedVisible = currentSelected.intersect(visibleIds)

        if (selectedVisible.size == visibleIds.size && visibleIds.isNotEmpty()) {
            flow.value = currentSelected - visibleIds
        } else {
            flow.value = currentSelected + visibleIds
        }
    }

    fun clearSelection() {
        clearAllSelectedSets()
        _isSelectionMode.value = false
    }

    private fun clearAllSelectedSets() {
        _selectedAllIds.value = emptySet()
        _selectedPhotosIds.value = emptySet()
        _selectedVideosIds.value = emptySet()
    }

    fun deleteSelectedCaptures(onComplete: () -> Unit) {
        val selected = uiState.value.selectedCaptures
        if (selected.isEmpty()) return

        viewModelScope.launch {
            val uris = selected.map { it.contentUri }
            mediaCaptureRepository.deleteCaptures(uris)
            clearSelection()
            loadCaptures()
            onComplete()
        }
    }

    private fun buildGroupedList(
        items: List<RecentCaptureItem>,
        selectionMode: Boolean,
        selectedIds: Set<String>
    ): List<GalleryListItem> {
        if (items.isEmpty()) return emptyList()

        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val groupedByDay = items.groupBy { item ->
            val timestamp = if (item.timestampMillis > 0) item.timestampMillis else System.currentTimeMillis()
            dayFormat.format(Date(timestamp))
        }

        val result = mutableListOf<GalleryListItem>()
        val todayStr = dayFormat.format(Date())
        val yesterdayCalendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yesterdayStr = dayFormat.format(yesterdayCalendar.time)

        for ((dayKey, dayItems) in groupedByDay) {
            val firstItemTimestamp = dayItems.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
            val dateObj = Date(if (firstItemTimestamp > 0) firstItemTimestamp else System.currentTimeMillis())

            val formattedDateOnly = SimpleDateFormat("MMM dd, yyyy", Locale.US).format(dateObj)
            val dateTitle = when (dayKey) {
                todayStr -> "Today — $formattedDateOnly"
                yesterdayStr -> "Yesterday — $formattedDateOnly"
                else -> {
                    val dayOfWeek = SimpleDateFormat("EEEE", Locale.US).format(dateObj)
                    "$dayOfWeek — $formattedDateOnly"
                }
            }

            // Determine dominant location name for the day's badge
            val topCity = dayItems
                .mapNotNull { it.cityCountry?.takeIf { c -> c.isNotBlank() && c != "Location" && c != "Local Album" } }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }?.key
                ?: dayItems.firstOrNull()?.locationName?.takeIf { it.isNotBlank() && it != "Capture Location" }
                ?: "Captured"

            val locationBadge = "$topCity (${dayItems.size} ${if (dayItems.size == 1) "item" else "items"})"

            result.add(
                GalleryListItem.Header(
                    sectionKey = dayKey,
                    dateTitle = dateTitle,
                    locationBadge = locationBadge,
                    itemCount = dayItems.size
                )
            )

            for (capture in dayItems) {
                result.add(
                    GalleryListItem.Media(
                        capture = capture,
                        isSelected = selectedIds.contains(capture.id),
                        isSelectionMode = selectionMode
                    )
                )
            }
        }

        return result
    }
}
