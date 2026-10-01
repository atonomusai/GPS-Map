package com.autonoumus.ai.presentation.gallery

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.databinding.ActivityGalleryBinding
import com.autonoumus.ai.domain.model.RecentCaptureItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlin.math.abs

@AndroidEntryPoint
class GalleryActivity : BaseActivity<ActivityGalleryBinding>() {

    private val viewModel: GalleryViewModel by viewModels()
    private lateinit var galleryAdapter: GalleryGroupedAdapter
    private lateinit var gestureDetector: GestureDetector
    private var touchStartedInContentArea: Boolean = false

    private var boldTypeface: Typeface? = null
    private var mediumTypeface: Typeface? = null

    // READ_MEDIA_IMAGES/VIDEO and READ_EXTERNAL_STORAGE are declared in the manifest but were
    // never actually requested at runtime - the gallery only "worked" via the scoped-storage
    // self-media exemption on API 29+. Pre-API 29 devices need it requested explicitly.
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.loadCaptures()
        } else {
            val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)
            com.autonoumus.ai.core.util.PermissionSettingsDialog.handleDenied(
                context = this,
                shouldShowRationale = shouldShowRationale,
                deniedMessage = "Storage permission is required to show your gallery"
            )
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityGalleryBinding {
        return ActivityGalleryBinding.inflate(inflater)
    }

    override fun initViews() {
        boldTypeface = ResourcesCompat.getFont(this, R.font.instrument_sans_bold)
        mediumTypeface = ResourcesCompat.getFont(this, R.font.instrument_sans_medium)

        setupToolbar()
        setupSegmentTabs()
        setupSwipeGesture()
        setupSearch()
        setupRecyclerView()
        setupSelectionActions()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.uiState.value.isSelectionMode) {
                    viewModel.clearSelection()
                } else if (binding.layoutSearchBar.visibility == View.VISIBLE) {
                    hideSearchBar()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

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
                if (viewModel.uiState.value.isSelectionMode) return false
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                // Horizontal swipe (must be significantly more horizontal than vertical)
                if (abs(diffX) > abs(diffY) * 1.3f && abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX < 0) {
                        // Swipe Left -> Next Tab (ALL -> PHOTOS -> VIDEOS)
                        switchToNextTab()
                        return true
                    } else {
                        // Swipe Right -> Previous Tab (VIDEOS -> PHOTOS -> ALL)
                        switchToPreviousTab()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun switchToNextTab() {
        if (viewModel.uiState.value.isSelectionMode) return
        val currentTab = viewModel.uiState.value.activeTab
        val nextTab = when (currentTab) {
            GalleryFilterTab.ALL -> GalleryFilterTab.PHOTOS
            GalleryFilterTab.PHOTOS -> GalleryFilterTab.VIDEOS
            GalleryFilterTab.VIDEOS -> null
        }
        nextTab?.let { viewModel.setFilterTab(it) }
    }

    private fun switchToPreviousTab() {
        if (viewModel.uiState.value.isSelectionMode) return
        val currentTab = viewModel.uiState.value.activeTab
        val prevTab = when (currentTab) {
            GalleryFilterTab.VIDEOS -> GalleryFilterTab.PHOTOS
            GalleryFilterTab.PHOTOS -> GalleryFilterTab.ALL
            GalleryFilterTab.ALL -> null
        }
        prevTab?.let { viewModel.setFilterTab(it) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val segmentBottom = binding.layoutFilterSegment.bottom
            val selectionTop = if (binding.layoutSelectionBottomBar.visibility == View.VISIBLE) {
                binding.layoutSelectionBottomBar.top
            } else {
                Int.MAX_VALUE
            }
            touchStartedInContentArea = ev.y > segmentBottom && ev.y < selectionTop
        }

        if (touchStartedInContentArea && ::gestureDetector.isInitialized && !viewModel.uiState.value.isSelectionMode) {
            gestureDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            if (viewModel.uiState.value.isSelectionMode) {
                viewModel.clearSelection()
            } else {
                finish()
            }
        }

        binding.btnSelect.setOnClickListener {
            viewModel.toggleSelectionMode()
        }

        binding.btnSearch.setOnClickListener {
            if (binding.layoutSearchBar.visibility == View.VISIBLE) {
                hideSearchBar()
            } else {
                showSearchBar()
            }
        }
    }

    private fun setupSearch() {
        binding.etSearchQuery.doAfterTextChanged { text ->
            viewModel.setSearchQuery(text?.toString().orEmpty())
        }

        binding.btnCloseSearch.setOnClickListener {
            hideSearchBar()
        }
    }

    private fun showSearchBar() {
        binding.layoutSearchBar.visibility = View.VISIBLE
        binding.etSearchQuery.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etSearchQuery, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSearchBar() {
        binding.etSearchQuery.setText("")
        binding.layoutSearchBar.visibility = View.GONE
        viewModel.setSearchQuery("")
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearchQuery.windowToken, 0)
    }

    private fun setupSegmentTabs() {
        binding.tabAllMedia.setOnClickListener {
            if (viewModel.uiState.value.isSelectionMode) return@setOnClickListener
            viewModel.setFilterTab(GalleryFilterTab.ALL)
        }
        binding.tabPhotos.setOnClickListener {
            if (viewModel.uiState.value.isSelectionMode) return@setOnClickListener
            viewModel.setFilterTab(GalleryFilterTab.PHOTOS)
        }
        binding.tabVideos.setOnClickListener {
            if (viewModel.uiState.value.isSelectionMode) return@setOnClickListener
            viewModel.setFilterTab(GalleryFilterTab.VIDEOS)
        }
    }

    private fun setupRecyclerView() {
        galleryAdapter = GalleryGroupedAdapter(
            onItemClick = { item -> openViewer(item) },
            onItemLongClick = { item ->
                viewModel.setSelectionMode(true)
                viewModel.toggleItemSelection(item)
            },
            onItemSelectToggle = { item ->
                viewModel.toggleItemSelection(item)
            }
        )

        val gridLayoutManager = GridLayoutManager(this, 3).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return when (galleryAdapter.getItemViewType(position)) {
                        GalleryGroupedAdapter.VIEW_TYPE_HEADER -> 3
                        else -> 1
                    }
                }
            }
        }

        binding.rvGallery.apply {
            layoutManager = gridLayoutManager
            adapter = galleryAdapter
            itemAnimator = null // Avoid flicker on selection toggles
        }
    }

    private fun setupSelectionActions() {
        binding.btnSelectAll.setOnClickListener {
            viewModel.selectAll()
        }

        binding.btnShareSelection.setOnClickListener {
            shareSelectedCaptures()
        }

        binding.btnDeleteSelection.setOnClickListener {
            showDeleteConfirmationDialog()
        }
    }

    private fun shareSelectedCaptures() {
        val selected = viewModel.uiState.value.selectedCaptures
        if (selected.isEmpty()) {
            Toast.makeText(this, "Select at least 1 item to share", Toast.LENGTH_SHORT).show()
            return
        }

        val uris = ArrayList(selected.map { it.contentUri })
        val intent = Intent().apply {
            if (uris.size == 1) {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, uris[0])
                type = if (selected[0].isVideo) "video/*" else "image/*"
            } else {
                action = Intent.ACTION_SEND_MULTIPLE
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                type = "*/*"
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share captures"))
    }

    private fun showDeleteConfirmationDialog() {
        val selectedCount = viewModel.uiState.value.selectedCount
        if (selectedCount == 0) return

        val dialogBinding = com.autonoumus.ai.databinding.DialogDeleteCapturesConfirmationBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val titleText = if (selectedCount == 1) "Delete Selected Capture?" else "Delete $selectedCount Selected Captures?"
        val btnText = if (selectedCount == 1) "Delete Capture" else "Delete Captures ($selectedCount)"

        dialogBinding.tvDeleteDialogTitle.text = titleText
        dialogBinding.btnConfirmDeleteCaptures.text = btnText

        dialogBinding.btnKeepFiles.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnConfirmDeleteCaptures.setOnClickListener {
            dialog.dismiss()
            viewModel.deleteSelectedCaptures {
                Toast.makeText(this@GalleryActivity, "Captures deleted", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    override fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderUiState(state)
                }
            }
        }
    }

    private fun renderUiState(state: GalleryUiState) {
        // 1. Update tab counts and active highlight
        binding.tabAllMedia.text = "All Media (${state.allCount})"
        binding.tabPhotos.text = "Photos (${state.photosCount})"
        binding.tabVideos.text = "Videos (${state.videosCount})"

        updateTabStyle(binding.tabAllMedia, state.activeTab == GalleryFilterTab.ALL)
        updateTabStyle(binding.tabPhotos, state.activeTab == GalleryFilterTab.PHOTOS)
        updateTabStyle(binding.tabVideos, state.activeTab == GalleryFilterTab.VIDEOS)

        // 2. Submit items to adapter
        galleryAdapter.submitList(state.groupedItems)

        // 3. Selection mode toolbar states
        binding.btnSelect.text = if (state.isSelectionMode) "Done" else "Select"
        binding.layoutSelectionBottomBar.isVisible = state.isSelectionMode
        binding.tvSelectedCount.text = "${state.selectedCount} selected"
        val currentTabItemCount = when (state.activeTab) {
            GalleryFilterTab.ALL -> state.allCount
            GalleryFilterTab.PHOTOS -> state.photosCount
            GalleryFilterTab.VIDEOS -> state.videosCount
        }
        binding.btnSelectAll.text = if (state.selectedCount > 0 && state.selectedCount == currentTabItemCount) {
            "Deselect All"
        } else {
            "Select All"
        }

        val hasSelection = state.selectedCount > 0
        binding.layoutSelectionActions.isVisible = hasSelection
        binding.btnShareSelection.isEnabled = hasSelection
        binding.btnDeleteSelection.isEnabled = hasSelection

        val bottomPadding = if (state.isSelectionMode) {
            (80 * resources.displayMetrics.density).toInt()
        } else {
            (24 * resources.displayMetrics.density).toInt()
        }
        binding.rvGallery.setPadding(
            binding.rvGallery.paddingLeft,
            binding.rvGallery.paddingTop,
            binding.rvGallery.paddingRight,
            bottomPadding
        )

        // 4. Empty state display
        if (state.isEmpty) {
            binding.rvGallery.visibility = View.GONE
            binding.layoutEmptyState.visibility = View.VISIBLE
            updateEmptyStateMessage(state)
        } else {
            binding.rvGallery.visibility = View.VISIBLE
            binding.layoutEmptyState.visibility = View.GONE
        }
    }

    private fun updateTabStyle(tabView: TextView, isSelected: Boolean) {
        if (isSelected) {
            tabView.setBackgroundResource(R.drawable.bg_gallery_segment_active)
            tabView.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            boldTypeface?.let { tabView.typeface = it }
        } else {
            tabView.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
            tabView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            mediumTypeface?.let { tabView.typeface = it }
        }
    }

    private fun updateEmptyStateMessage(state: GalleryUiState) {
        if (state.searchQuery.isNotBlank()) {
            binding.tvEmptyTitle.text = "No Captures Found"
            binding.tvEmptySubtitle.text = "No media matched \"${state.searchQuery}\""
        } else {
            when (state.activeTab) {
                GalleryFilterTab.ALL -> {
                    binding.tvEmptyTitle.text = "No Captures Yet"
                    binding.tvEmptySubtitle.text = "Capture moments with GPS & Timestamp."
                }
                GalleryFilterTab.PHOTOS -> {
                    binding.tvEmptyTitle.text = "No Photos Found"
                    binding.tvEmptySubtitle.text = "Take your first photo using GPS camera."
                }
                GalleryFilterTab.VIDEOS -> {
                    binding.tvEmptyTitle.text = "No Videos Found"
                    binding.tvEmptySubtitle.text = "Record your first video using GPS camera."
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.loadCaptures()
        } else {
            storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun openViewer(item: RecentCaptureItem) {
        startActivity(MediaViewerActivity.newIntent(this, item))
    }
}
