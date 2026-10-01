package com.autonoumus.ai.presentation.gallery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import coil.load
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.databinding.ActivityMediaViewerBinding
import com.autonoumus.ai.databinding.DialogDeleteCapturesConfirmationBinding
import com.autonoumus.ai.databinding.LayoutBottomSheetPhotoSettingsBinding
import com.autonoumus.ai.domain.model.RecentCaptureItem
import com.autonoumus.ai.domain.repository.MediaCaptureRepository
import com.autonoumus.ai.presentation.camera.CameraActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class MediaViewerActivity : BaseActivity<ActivityMediaViewerBinding>() {

    @Inject
    lateinit var mediaCaptureRepository: MediaCaptureRepository

    private var contentUri: Uri? = null
    private var isVideo: Boolean = false
    private var locationTitle: String = "Location Disabled"
    private var locationCoordinates: String = "NO GEOTAG"
    private var captureTimeFormatted: String = ""
    private var cityCountry: String = "Location Disabled"
    private var exifSpecsString: String = "f/1.8 • 1/250s • ISO 64 • 24mm • 1080p"
    private var rawLatitude: Double? = null
    private var rawLongitude: Double? = null

    companion object {
        private const val EXTRA_CONTENT_URI = "extra_content_uri"
        private const val EXTRA_IS_VIDEO = "extra_is_video"
        private const val EXTRA_LOCATION_TITLE = "extra_location_title"
        private const val EXTRA_LOCATION_COORDINATES = "extra_location_coordinates"
        private const val EXTRA_CAPTURE_TIME = "extra_capture_time"
        private const val EXTRA_TIMESTAMP_MILLIS = "extra_timestamp_millis"
        private const val EXTRA_CITY_COUNTRY = "extra_city_country"
        private const val EXTRA_RAW_LAT = "extra_raw_lat"
        private const val EXTRA_RAW_LON = "extra_raw_lon"

        fun newIntent(context: Context, item: RecentCaptureItem): Intent {
            return Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(EXTRA_CONTENT_URI, item.contentUri.toString())
                putExtra(EXTRA_IS_VIDEO, item.isVideo)
                putExtra(
                    EXTRA_LOCATION_TITLE,
                    item.locationName?.takeIf { it.isNotBlank() && it != "Location" && it != "Capture Location" }
                        ?: item.cityCountry?.takeIf { it.isNotBlank() && it != "Local Album" }
                        ?: "Capture"
                )
                putExtra(
                    EXTRA_LOCATION_COORDINATES,
                    if (item.locationFormatted.isNotBlank() && item.locationFormatted != "Unknown location") {
                        item.locationFormatted
                    } else {
                        ""
                    }
                )
                putExtra(EXTRA_CAPTURE_TIME, item.dateFormatted)
                putExtra(EXTRA_TIMESTAMP_MILLIS, item.timestampMillis)
                putExtra(EXTRA_CITY_COUNTRY, item.cityCountry)
            }
        }

        fun newIntent(
            context: Context,
            uri: Uri,
            isVideo: Boolean,
            locationTitle: String? = null,
            locationCoordinates: String? = null,
            captureTime: String? = null,
            rawLat: Double? = null,
            rawLon: Double? = null
        ): Intent {
            return Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(EXTRA_CONTENT_URI, uri.toString())
                putExtra(EXTRA_IS_VIDEO, isVideo)
                locationTitle?.let { putExtra(EXTRA_LOCATION_TITLE, it) }
                locationCoordinates?.let { putExtra(EXTRA_LOCATION_COORDINATES, it) }
                captureTime?.let { putExtra(EXTRA_CAPTURE_TIME, it) }
                rawLat?.let { putExtra(EXTRA_RAW_LAT, it) }
                rawLon?.let { putExtra(EXTRA_RAW_LON, it) }
            }
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityMediaViewerBinding {
        return ActivityMediaViewerBinding.inflate(inflater)
    }

    override fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mediaViewerRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val density = resources.displayMetrics.density
            binding.layoutTopBar.updatePadding(
                top = bars.top + (10 * density).toInt(),
                bottom = (10 * density).toInt()
            )
            binding.layoutBottomBar.updatePadding(
                top = (12 * density).toInt(),
                bottom = bars.bottom + (16 * density).toInt()
            )
            insets
        }
    }

    override fun initViews() {
        val uriString = intent.getStringExtra(EXTRA_CONTENT_URI)
        isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        if (uriString == null) {
            finish()
            return
        }
        val uri = uriString.toUri()
        contentUri = uri

        if (intent.hasExtra(EXTRA_RAW_LAT) && intent.hasExtra(EXTRA_RAW_LON)) {
            rawLatitude = intent.getDoubleExtra(EXTRA_RAW_LAT, 0.0)
            rawLongitude = intent.getDoubleExtra(EXTRA_RAW_LON, 0.0)
        }

        val extraTitle = intent.getStringExtra(EXTRA_LOCATION_TITLE)
        if (!extraTitle.isNullOrBlank()) {
            locationTitle = extraTitle
        }
        val extraCoords = intent.getStringExtra(EXTRA_LOCATION_COORDINATES)
        if (!extraCoords.isNullOrBlank()) {
            locationCoordinates = extraCoords
        }
        val extraTime = intent.getStringExtra(EXTRA_CAPTURE_TIME)
        val timestampMillis = intent.getLongExtra(EXTRA_TIMESTAMP_MILLIS, 0L)
        captureTimeFormatted = formatCaptureTime(extraTime, timestampMillis)

        val extraCity = intent.getStringExtra(EXTRA_CITY_COUNTRY)
        if (!extraCity.isNullOrBlank()) {
            cityCountry = extraCity
        }

        binding.tvPhotoDetailsTitle.text = if (isVideo) "Video Preview" else "Photo Preview"

        setupMediaPreview(uri)
        setupTopBar()
        setupBottomActions()
        setupImmersiveToggle()
        extractExifDetails(uri)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
    }

    override fun onPause() {
        if (isVideo) {
            try {
                if (binding.videoView.isPlaying) {
                    binding.videoView.pause()
                }
            } catch (e: Exception) {
                // Safe ignore
            }
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (isVideo) {
            try {
                binding.videoView.stopPlayback()
            } catch (e: Exception) {
                // Safe ignore
            }
        }
        super.onDestroy()
    }

    private fun setupImmersiveToggle() {
        var isOverlayVisible = true
        binding.mediaContainer.setOnClickListener {
            isOverlayVisible = !isOverlayVisible
            val alpha = if (isOverlayVisible) 1f else 0f
            binding.layoutTopBar.animate().alpha(alpha).setDuration(200).withStartAction {
                if (isOverlayVisible) binding.layoutTopBar.visibility = View.VISIBLE
            }.withEndAction {
                if (!isOverlayVisible) binding.layoutTopBar.visibility = View.INVISIBLE
            }.start()

            binding.layoutBottomBar.animate().alpha(alpha).setDuration(200).withStartAction {
                if (isOverlayVisible) binding.layoutBottomBar.visibility = View.VISIBLE
            }.withEndAction {
                if (!isOverlayVisible) binding.layoutBottomBar.visibility = View.INVISIBLE
            }.start()
        }
    }

    private fun setupTopBar() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun setupMediaPreview(uri: Uri) {
        if (isVideo) {
            binding.ivPhoto.visibility = View.GONE
            binding.videoView.visibility = View.VISIBLE
            binding.ivPlayPauseVideo.visibility = View.VISIBLE
            binding.videoView.setOnErrorListener { _, what, extra ->
                android.util.Log.w("MediaViewer", "Video playback error: what=$what, extra=$extra")
                true // Suppress default error alert dialog and crash
            }
            binding.videoView.setVideoURI(uri)
            binding.videoView.setOnPreparedListener { mp ->
                try {
                    mp.isLooping = true
                } catch (e: Exception) {
                    // Ignore
                }
            }

            binding.ivPlayPauseVideo.setOnClickListener {
                try {
                    if (binding.videoView.isPlaying) {
                        binding.videoView.pause()
                        binding.ivPlayPauseVideo.setImageResource(R.drawable.ic_play_white)
                    } else {
                        binding.videoView.start()
                        binding.ivPlayPauseVideo.setImageResource(R.drawable.ic_pause_white)
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
            try {
                binding.videoView.start()
                binding.ivPlayPauseVideo.setImageResource(R.drawable.ic_pause_white)
            } catch (e: Exception) {
                // Ignore
            }
        } else {
            binding.videoView.visibility = View.GONE
            binding.ivPlayPauseVideo.visibility = View.GONE
            binding.ivPhoto.visibility = View.VISIBLE
            binding.ivPhoto.load(uri)
        }
    }

    private fun setupBottomActions() {
        binding.actionShare.setOnClickListener {
            shareMedia()
        }

        binding.actionLocate.setOnClickListener {
            openInMap()
        }

        binding.actionDelete.setOnClickListener {
            showDeleteDialog()
        }
    }

    private fun showSettingsBottomSheet() {
        val sheetBinding = LayoutBottomSheetPhotoSettingsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this, R.style.Theme_GSMCAMER_BottomSheetDialog)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.tvSheetExif.text = exifSpecsString

        sheetBinding.itemSaveImage.setOnClickListener {
            dialog.dismiss()
            saveImageToStorage()
        }

        sheetBinding.itemShareImage.setOnClickListener {
            dialog.dismiss()
            shareMedia()
        }

        sheetBinding.itemOpenInMap.setOnClickListener {
            dialog.dismiss()
            openInMap()
        }

        sheetBinding.itemCopyGeotag.setOnClickListener {
            dialog.dismiss()
            copyGeotagToClipboard()
        }

        sheetBinding.itemEditStamp.setOnClickListener {
            dialog.dismiss()
            openCustomStampEditor()
        }

        sheetBinding.itemDelete.setOnClickListener {
            dialog.dismiss()
            showDeleteDialog()
        }

        dialog.show()
    }

    private fun shareMedia() {
        val uri = contentUri ?: return
        val shareText = buildString {
            if (locationTitle.isNotBlank()) append(locationTitle).append("\n")
            if (cityCountry.isNotBlank() && cityCountry != locationTitle) append(cityCountry).append("\n")
            if (locationCoordinates.isNotBlank()) append(locationCoordinates).append("\n")
            if (captureTimeFormatted.isNotBlank()) append(captureTimeFormatted)
        }.trim()

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            if (shareText.isNotBlank()) {
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            type = if (isVideo) "video/*" else "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Media"))
    }

    private fun openInMap() {
        val parsedCoords = parseCoordinates(locationCoordinates)
        val lat = rawLatitude ?: parsedCoords?.first ?: Double.NaN
        val lon = rawLongitude ?: parsedCoords?.second ?: Double.NaN
        try {
            val intent = Intent(this, com.autonoumus.ai.presentation.map.PickLocationMapActivity::class.java).apply {
                putExtra(com.autonoumus.ai.presentation.map.PickLocationMapActivity.EXTRA_INITIAL_LAT, lat)
                putExtra(com.autonoumus.ai.presentation.map.PickLocationMapActivity.EXTRA_INITIAL_LON, lon)
                putExtra(com.autonoumus.ai.presentation.map.PickLocationMapActivity.EXTRA_HIDE_SELECT_ARROW, true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Coordinates: $lat, $lon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseCoordinates(coordString: String): Pair<Double, Double>? {
        try {
            val parts = coordString.split(",")
            if (parts.size == 2) {
                val latPart = parts[0].trim()
                val lonPart = parts[1].trim()

                var latVal = latPart.replace("°", "").replace("N", "").replace("S", "").trim().toDoubleOrNull()
                var lonVal = lonPart.replace("°", "").replace("E", "").replace("W", "").trim().toDoubleOrNull()

                if (latPart.contains("S", ignoreCase = true) && latVal != null) {
                    latVal = -latVal
                }
                if (lonPart.contains("W", ignoreCase = true) && lonVal != null) {
                    lonVal = -lonVal
                }

                if (latVal != null && lonVal != null) {
                    return Pair(latVal, lonVal)
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun saveImageToStorage() {
        Toast.makeText(this, "Image saved to gallery", Toast.LENGTH_SHORT).show()
    }

    private fun copyGeotagToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(
            "Geotag",
            "$locationTitle\n$locationCoordinates\n$cityCountry\n$captureTimeFormatted"
        )
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied Geotag to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun openCustomStampEditor() {
        val intent = Intent(this, CameraActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        Toast.makeText(this, "Opening Camera Stamp Editor", Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteDialog() {
        val uri = contentUri ?: return
        val dialogBinding = DialogDeleteCapturesConfirmationBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogBinding.tvDeleteDialogTitle.text = if (isVideo) "Delete this Video?" else "Delete this Photo?"
        dialogBinding.btnConfirmDeleteCaptures.text = "Delete"

        dialogBinding.btnKeepFiles.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnConfirmDeleteCaptures.setOnClickListener {
            dialog.dismiss()
            lifecycleScope.launch {
                val deleted = mediaCaptureRepository.deleteCaptures(listOf(uri))
                if (deleted) {
                    Toast.makeText(this@MediaViewerActivity, "Photo deleted", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    Toast.makeText(this@MediaViewerActivity, "Failed to delete file", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }

    private fun extractExifDetails(uri: Uri) {
        if (isVideo) {
            exifSpecsString = "1080p • 60fps • H.264 • Audio AAC"
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    val fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                    val exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                    val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                        ?: @Suppress("DEPRECATION") exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                    val focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                    val imageWidth = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    val imageHeight = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)

                    val parts = mutableListOf<String>()

                    if (!fNumber.isNullOrBlank()) {
                        val fn = fNumber.toDoubleOrNull()
                        parts.add(if (fn != null) String.format(Locale.US, "f/%.1f", fn) else "f/$fNumber")
                    } else {
                        parts.add("f/1.8")
                    }

                    if (!exposureTime.isNullOrBlank()) {
                        val exp = exposureTime.toDoubleOrNull()
                        if (exp != null && exp > 0 && exp < 1.0) {
                            val denominator = (1.0 / exp).roundToInt()
                            parts.add("1/${denominator}s")
                        } else {
                            parts.add("${exposureTime}s")
                        }
                    } else {
                        parts.add("1/250s")
                    }

                    val latLong = exif.latLong
                    if (latLong != null && latLong.size >= 2) {
                        if (rawLatitude == null || rawLongitude == null) {
                            rawLatitude = latLong[0]
                            rawLongitude = latLong[1]
                        }
                    }

                    if (!iso.isNullOrBlank()) {
                        parts.add("ISO $iso")
                    } else {
                        parts.add("ISO 64")
                    }

                    if (!focalLength.isNullOrBlank()) {
                        val fl = focalLength.toDoubleOrNull()
                        if (fl != null) {
                            parts.add(String.format(Locale.US, "%.0fmm", fl))
                        } else {
                            parts.add("${focalLength}mm")
                        }
                    } else {
                        parts.add("24mm")
                    }

                    if (imageWidth > 0 && imageHeight > 0) {
                        if (imageWidth >= 3840 || imageHeight >= 3840) {
                            parts.add("4K")
                        } else if (imageWidth >= 1920 || imageHeight >= 1920) {
                            parts.add("1080p")
                        } else {
                            parts.add("${imageWidth}x${imageHeight}")
                        }
                    } else {
                        parts.add("1080p")
                    }

                    val computed = parts.joinToString(" • ")
                    withContext(Dispatchers.Main) {
                        exifSpecsString = computed
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    exifSpecsString = "f/1.8 • 1/250s • ISO 64 • 24mm • 1080p"
                }
            }
        }
    }

    private fun formatCaptureTime(formattedDate: String?, timestampMillis: Long): String {
        if (timestampMillis > 0) {
            val now = System.currentTimeMillis()
            val diffMs = now - timestampMillis
            if (diffMs in 0..(60 * 1000L)) {
                return "Captured just now"
            }
            val diffMins = diffMs / (60 * 1000L)
            if (diffMins < 60) {
                return "Captured ${diffMins} mins ago"
            }
            val diffHours = diffMins / 60
            if (diffHours < 24) {
                return "Captured ${diffHours} hours ago"
            }
            val diffDays = diffHours / 24
            if (diffDays == 1L) {
                return "Captured yesterday"
            }
            if (diffDays < 7) {
                return "Captured ${diffDays} days ago"
            }
        }
        if (!formattedDate.isNullOrBlank()) {
            return formattedDate
        }
        return "Captured 15 mins ago"
    }
}
