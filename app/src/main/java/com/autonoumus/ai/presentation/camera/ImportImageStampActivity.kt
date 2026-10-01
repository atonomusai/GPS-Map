package com.autonoumus.ai.presentation.camera

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import coil.load
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.core.camera.CameraStorageConstants
import com.autonoumus.ai.core.stamp.StampRenderer
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.databinding.ActivityImportImageStampBinding
import com.autonoumus.ai.domain.model.StampData
import com.autonoumus.ai.domain.model.StampSettings
import com.autonoumus.ai.domain.usecase.BuildStampDataUseCase
import com.autonoumus.ai.domain.usecase.GetStampSettingsUseCase
import com.autonoumus.ai.presentation.map.PickLocationMapActivity
import com.autonoumus.ai.presentation.settings.TimestampSettingsActivity
import com.autonoumus.ai.presentation.templates.CustomLocationStampBottomSheetDialog
import com.autonoumus.ai.presentation.templates.TemplatesActivity
import com.autonoumus.ai.presentation.common.dialog.SuccessfullySavedDialog
import com.autonoumus.ai.presentation.home.SelectSourceBottomSheetDialog
import com.autonoumus.ai.presentation.main.MainActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ImportImageStampActivity : BaseActivity<ActivityImportImageStampBinding>() {

    companion object {
        private const val EXTRA_IMAGE_URI = "extra_image_uri"
        private const val EXTRA_MEDIA_URI = "extra_media_uri"
        private const val EXTRA_IS_VIDEO = "extra_is_video"

        fun newIntent(context: Context, mediaUri: Uri, isVideo: Boolean = false): Intent {
            return Intent(context, ImportImageStampActivity::class.java).apply {
                putExtra(EXTRA_MEDIA_URI, mediaUri.toString())
                putExtra(EXTRA_IMAGE_URI, mediaUri.toString()) // Backward compatibility
                putExtra(EXTRA_IS_VIDEO, isVideo)
            }
        }
    }

    @Inject
    lateinit var getStampSettingsUseCase: GetStampSettingsUseCase

    @Inject
    lateinit var buildStampDataUseCase: BuildStampDataUseCase

    @Inject
    lateinit var locationHelper: LocationHelper

    @Inject
    lateinit var stampRenderer: StampRenderer

    private var mediaUri: Uri? = null
    private var isVideo: Boolean = false
    private var currentStampData: StampData? = null
    private var currentStampSettings: StampSettings = StampSettings()

    @androidx.annotation.OptIn(UnstableApi::class)
    private var activeTransformer: Transformer? = null
    private var progressTrackingJob: Job? = null

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            mediaUri = uri
            isVideo = isUriVideo(uri)
            val fileName = getFileName(uri) ?: if (isVideo) "Imported Video" else "Imported Photo"
            binding.tvImageTitle.text = fileName
            setupMediaPreview()
            loadStampDataAndSettings()
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityImportImageStampBinding {
        return ActivityImportImageStampBinding.inflate(inflater)
    }

    override fun initViews() {
        val uriStr = intent.getStringExtra(EXTRA_MEDIA_URI) ?: intent.getStringExtra(EXTRA_IMAGE_URI)
        if (uriStr == null) {
            Toast.makeText(this, "Failed to load media", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        mediaUri = uriStr.toUri()
        isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false) || isUriVideo(mediaUri)

        setupToolbar()
        setupBottomActions()
        setupMediaPreview()
        loadStampDataAndSettings()
    }

    private fun isUriVideo(uri: Uri?): Boolean {
        if (uri == null) return false
        val type = contentResolver.getType(uri) ?: ""
        if (type.startsWith("video/")) return true
        val path = uri.toString()
        if (path.contains("/video/", ignoreCase = true) || path.contains("video%3A", ignoreCase = true)) return true
        return path.endsWith(".mp4", ignoreCase = true) ||
                path.endsWith(".mkv", ignoreCase = true) ||
                path.endsWith(".mov", ignoreCase = true) ||
                path.endsWith(".3gp", ignoreCase = true) ||
                path.endsWith(".webm", ignoreCase = true) ||
                path.endsWith(".avi", ignoreCase = true)
    }

    private fun extractMediaDimensionsAsync(uri: Uri?) {
        if (uri == null) return
        lifecycleScope.launch(Dispatchers.IO) {
            var width = 0
            var height = 0
            try {
                if (isVideo) {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(this@ImportImageStampActivity, uri)
                    val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    val rot = rotationStr?.toIntOrNull() ?: 0
                    val rawW = widthStr?.toIntOrNull() ?: 0
                    val rawH = heightStr?.toIntOrNull() ?: 0
                    if (rot == 90 || rot == 270) {
                        width = rawH
                        height = rawW
                    } else {
                        width = rawW
                        height = rawH
                    }
                    retriever.release()
                } else {
                    var rotationDegrees = 0
                    try {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            val exif = ExifInterface(stream)
                            val orientation = exif.getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL
                            )
                            rotationDegrees = when (orientation) {
                                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                                else -> 0
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }

                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, options)
                    }
                    val rawW = options.outWidth
                    val rawH = options.outHeight
                    if (rotationDegrees == 90 || rotationDegrees == 270) {
                        width = rawH
                        height = rawW
                    } else {
                        width = rawW
                        height = rawH
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ImportImageStamp", "Failed to extract media dimensions", e)
            }

            if (width > 0 && height > 0) {
                withContext(Dispatchers.Main) {
                    mediaIntrinsicWidth = width
                    mediaIntrinsicHeight = height
                    updateStampOverlayBounds()
                }
            }
        }
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnSettings.setOnClickListener {
            val intent = Intent(this, TimestampSettingsActivity::class.java).apply {
                putExtra(TimestampSettingsActivity.EXTRA_SCREEN_MODE, TimestampSettingsActivity.MODE_CAMERA)
            }
            startActivity(intent)
        }

        val fileName = getFileName(mediaUri) ?: if (isVideo) "Imported Video" else "Imported Photo"
        binding.tvImageTitle.text = fileName
    }

    private fun setupBottomActions() {
        binding.btnShare.setOnClickListener {
            if (isVideo) {
                shareStampedVideo()
            } else {
                shareStampedImage()
            }
        }

        binding.btnLocate.setOnClickListener {
            val intent = Intent(this, PickLocationMapActivity::class.java).apply {
                val stamp = currentStampData
                val lat = parseCoordNumber(stamp?.latitudeFormatted)
                val lon = parseCoordNumber(stamp?.longitudeFormatted)
                if (lat != null) putExtra(PickLocationMapActivity.EXTRA_INITIAL_LAT, lat)
                if (lon != null) putExtra(PickLocationMapActivity.EXTRA_INITIAL_LON, lon)
            }
            startActivity(intent)
        }

        binding.btnSave.setOnClickListener {
            if (isVideo) {
                saveStampedVideo()
            } else {
                saveStampedImage()
            }
        }

        binding.btnDelete.setOnClickListener {
            showRetakeMediaSourceDialog()
        }

        binding.stampOverlayView.onEditClickListener = {
            openCustomLocationBottomSheet()
        }
        binding.stampOverlayView.onTemplateClickListener = {
            val intent = Intent(this, TimestampSettingsActivity::class.java).apply {
                putExtra(TimestampSettingsActivity.EXTRA_SCREEN_MODE, TimestampSettingsActivity.MODE_CAMERA)
            }
            startActivity(intent)
        }
    }

    private fun showRetakeMediaSourceDialog() {
        val bottomSheet = SelectSourceBottomSheetDialog.newInstance(
            onTakePhotoVideo = {
                val intent = Intent(this, CameraActivity::class.java).apply {
                    putExtra(CameraActivity.EXTRA_INITIAL_MODE, if (isVideo) "VIDEO" else "PHOTO")
                }
                startActivity(intent)
                finish()
            },
            onChooseGallery = {
                pickMediaLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            }
        )
        bottomSheet.show(supportFragmentManager, SelectSourceBottomSheetDialog.TAG)
    }

    private fun parseCoordNumber(coordStr: String?): Double? {
        if (coordStr.isNullOrBlank()) return null
        val clean = coordStr.replace("°", "").replace("N", "", true).replace("E", "", true).trim()
        val isNegative = coordStr.contains("S", true) || coordStr.contains("W", true)
        val parsed = clean.replace("S", "", true).replace("W", "", true).trim().toDoubleOrNull() ?: return null
        return if (isNegative) -Math.abs(parsed) else parsed
    }

    private fun getStampShareText(): String {
        val stamp = currentStampData ?: return "Shared via GPS Camera"
        val title = stamp.titleLine.takeIf { it.isNotBlank() } ?: "Capture Location"
        val addr = stamp.addressLine.takeIf { it.isNotBlank() } ?: ""
        val coords = "${stamp.latitudeFormatted}, ${stamp.longitudeFormatted}"
        val time = "${stamp.dateLine} ${stamp.timeLine}"
        return listOf(title, addr, coords, time).filter { it.isNotBlank() }.joinToString("\n")
    }

    private var mediaIntrinsicWidth = 0
    private var mediaIntrinsicHeight = 0

    private fun updateStampOverlayBounds() {
        val containerW = binding.layoutPhotoContainer.width.toFloat()
        val containerH = binding.layoutPhotoContainer.height.toFloat()
        if (containerW <= 0f || containerH <= 0f) {
            return
        }

        val dWidth = mediaIntrinsicWidth.toFloat()
        val dHeight = mediaIntrinsicHeight.toFloat()

        if (dWidth > 0f && dHeight > 0f) {
            val scale = minOf(containerW / dWidth, containerH / dHeight)
            val displayedW = dWidth * scale
            val displayedH = dHeight * scale
            val left = (containerW - displayedW) / 2f
            val top = (containerH - displayedH) / 2f
            val imageRect = android.graphics.RectF(left, top, left + displayedW, top + displayedH)
            binding.stampOverlayView.setCustomCanvasRect(imageRect)
        } else {
            binding.stampOverlayView.setCustomCanvasRect(android.graphics.RectF(0f, 0f, containerW, containerH))
        }
    }

    private fun setupMediaPreview() {
        val uri = mediaUri ?: return
        extractMediaDimensionsAsync(uri)

        binding.layoutPhotoContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateStampOverlayBounds()
        }
        binding.layoutPhotoContainer.post {
            updateStampOverlayBounds()
        }

        if (isVideo) {
            binding.ivImportedPhoto.visibility = View.GONE
            binding.videoView.visibility = View.VISIBLE

            binding.videoView.setOnErrorListener { _, what, extra ->
                android.util.Log.w("ImportImageStamp", "Video error: what=$what, extra=$extra")
                true
            }
            binding.videoView.setVideoURI(uri)
            binding.videoView.setOnPreparedListener { mp ->
                try {
                    mp.isLooping = true
                    if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                        mediaIntrinsicWidth = mp.videoWidth
                        mediaIntrinsicHeight = mp.videoHeight
                    }
                    binding.layoutPhotoContainer.post {
                        updateStampOverlayBounds()
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            binding.videoView.setOnClickListener {
                try {
                    if (binding.videoView.isPlaying) {
                        binding.videoView.pause()
                    } else {
                        binding.videoView.start()
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            try {
                binding.videoView.start()
            } catch (e: Exception) {
                // Ignore
            }
        } else {
            binding.videoView.visibility = View.GONE
            binding.ivImportedPhoto.visibility = View.VISIBLE
            loadImage()
        }
    }

    private fun openCustomLocationBottomSheet() {
        val stamp = currentStampData
        val hasValidData = stamp != null && (!stamp.titleLine.isNullOrBlank() || !stamp.addressLine.isNullOrBlank() || stamp.rawLatitude != null)
        val sheet = if (hasValidData) {
            CustomLocationStampBottomSheetDialog.newInstance(
                initialCountry = stamp?.titleLine?.takeIf { it != "NO GEOTAG" },
                initialAddress = stamp?.addressLine,
                initialLat = stamp?.latitudeFormatted,
                initialLon = stamp?.longitudeFormatted
            )
        } else {
            CustomLocationStampBottomSheetDialog.newInstance()
        }
        sheet.onLocationApplied = { _, _, _, _ ->
            loadStampDataAndSettings()
        }
        sheet.show(supportFragmentManager, CustomLocationStampBottomSheetDialog.TAG)
    }

    private fun loadImage() {
        mediaUri?.let { uri ->
            binding.ivImportedPhoto.load(uri) {
                crossfade(false)
                listener(
                    onSuccess = { _, result ->
                        val drawable = result.drawable
                        if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                            if (mediaIntrinsicWidth <= 0 || mediaIntrinsicHeight <= 0) {
                                mediaIntrinsicWidth = drawable.intrinsicWidth
                                mediaIntrinsicHeight = drawable.intrinsicHeight
                            }
                        }
                        binding.layoutPhotoContainer.post {
                            updateStampOverlayBounds()
                        }
                    }
                )
            }
        }
    }

    private fun loadStampDataAndSettings() {
        lifecycleScope.launch {
            val userLocation = locationHelper.getCurrentLocationData()
            val settings = getStampSettingsUseCase()
            val data = buildStampDataUseCase(userLocation, settings)

            currentStampData = data
            currentStampSettings = settings

            binding.stampOverlayView.stampRenderer = stampRenderer
            binding.stampOverlayView.updateStamp(data, settings)
            updateStampOverlayBounds()
        }
    }

    override fun onResume() {
        super.onResume()
        loadStampDataAndSettings()
        if (isVideo) {
            try {
                if (!binding.videoView.isPlaying) {
                    binding.videoView.start()
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    override fun onPause() {
        if (isVideo) {
            try {
                if (binding.videoView.isPlaying) {
                    binding.videoView.pause()
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        super.onPause()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onDestroy() {
        if (isVideo) {
            try {
                binding.videoView.stopPlayback()
            } catch (e: Exception) {
                // Ignore
            }
        }
        progressTrackingJob?.cancel()
        activeTransformer?.cancel()
        activeTransformer = null
        super.onDestroy()
    }

    // =========================================================================
    // IMAGE PROCESSING (SAVE & SHARE)
    // =========================================================================

    private fun saveStampedImage() {
        val uri = mediaUri ?: return
        binding.progressBar.visibility = View.VISIBLE
        binding.btnSave.isEnabled = false

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    processAndSaveStampedBitmap(uri)
                }
                binding.progressBar.visibility = View.GONE
                binding.btnSave.isEnabled = true
                showSuccessfullySavedDialog()
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.btnSave.isEnabled = true
                Toast.makeText(this@ImportImageStampActivity, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSuccessfullySavedDialog() {
        SuccessfullySavedDialog.show(
            fragmentManager = supportFragmentManager,
            title = "Successfully Saved!",
            subtitle = "Your template has been saved\nsuccessfully.",
            onDone = {
                navigateToHome()
            }
        )
    }

    private fun navigateToHome() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun shareStampedImage() {
        val uri = mediaUri ?: return
        binding.progressBar.visibility = View.VISIBLE
        binding.btnShare.isEnabled = false

        lifecycleScope.launch {
            try {
                val shareUri = withContext(Dispatchers.IO) {
                    processAndCreateShareImageFile(uri)
                }
                binding.progressBar.visibility = View.GONE
                binding.btnShare.isEnabled = true

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    putExtra(Intent.EXTRA_TEXT, getStampShareText())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share Photo with Timestamp"))
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.btnShare.isEnabled = true
                Toast.makeText(this@ImportImageStampActivity, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun processAndSaveStampedBitmap(inputUri: Uri): Uri {
        val bitmap = decodeOrientedBitmap(inputUri)
        val data = currentStampData
        val settings = currentStampSettings

        val stampedBitmap = if (data != null) {
            val mutableBitmap = if (bitmap.isMutable) {
                bitmap
            } else {
                val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                if (copy != bitmap) bitmap.recycle()
                copy
            }
            val canvas = Canvas(mutableBitmap)
            stampRenderer.draw(canvas, mutableBitmap.width, mutableBitmap.height, data, settings)
            mutableBitmap
        } else {
            bitmap
        }

        val stampedTempFile = try {
            File.createTempFile("stamped_${System.currentTimeMillis()}", ".jpg", cacheDir)
        } catch (e: Exception) {
            File(cacheDir, "stamped_${System.currentTimeMillis()}.jpg")
        }

        try {
            FileOutputStream(stampedTempFile).use { out ->
                stampedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } finally {
            stampedBitmap.recycle()
        }

        try {
            val outputExif = ExifInterface(stampedTempFile.absolutePath)
            val lat = parseCoordNumber(currentStampData?.latitudeFormatted)
            val lon = parseCoordNumber(currentStampData?.longitudeFormatted)
            if (lat != null && lon != null) {
                val loc = android.location.Location("custom").apply {
                    latitude = lat
                    longitude = lon
                }
                outputExif.setGpsInfo(loc)
            }
            outputExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            val nowFormatted = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            outputExif.setAttribute(ExifInterface.TAG_DATETIME, nowFormatted)
            outputExif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, nowFormatted)
            outputExif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, nowFormatted)
            outputExif.saveAttributes()
        } catch (e: Exception) {
            android.util.Log.w("ImportImageStamp", "Failed to write EXIF attributes to temp file", e)
        }

        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val name = "IMG_${sdf.format(Date())}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/${CameraStorageConstants.APP_FOLDER_NAME}"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val appDir = File(picturesDir, CameraStorageConstants.APP_FOLDER_NAME).apply { mkdirs() }
                val targetFile = File(appDir, name)
                put(MediaStore.MediaColumns.DATA, targetFile.absolutePath)
            }
        }

        val outputUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("Failed to create MediaStore record")

        try {
            contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                stampedTempFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IllegalStateException("Failed to open output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(outputUri, contentValues, null, null)
            }
        } finally {
            stampedTempFile.delete()
        }

        return outputUri
    }

    private fun processAndCreateShareImageFile(inputUri: Uri): Uri {
        val bitmap = decodeOrientedBitmap(inputUri)
        val data = currentStampData
        val settings = currentStampSettings

        val stampedBitmap = if (data != null) {
            val mutableBitmap = if (bitmap.isMutable) {
                bitmap
            } else {
                val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                if (copy != bitmap) bitmap.recycle()
                copy
            }
            val canvas = Canvas(mutableBitmap)
            stampRenderer.draw(canvas, mutableBitmap.width, mutableBitmap.height, data, settings)
            mutableBitmap
        } else {
            bitmap
        }

        val shareDir = File(cacheDir, "images").apply { mkdirs() }
        val shareFile = File(shareDir, "SHARE_STAMP_${System.currentTimeMillis()}.jpg")
        try {
            FileOutputStream(shareFile).use { out ->
                stampedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } finally {
            stampedBitmap.recycle()
        }

        return FileProvider.getUriForFile(this, "${packageName}.fileprovider", shareFile)
    }

    // =========================================================================
    // VIDEO PROCESSING (MEDIA3 TRANSFORMER WITH PERCENTAGE PROGRESS)
    // =========================================================================

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun saveStampedVideo() {
        val uri = mediaUri ?: return

        // Pause playback while exporting
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
        }

        binding.layoutProgressOverlay.visibility = View.VISIBLE
        binding.tvProgressTitle.text = "Saving Video..."
        binding.tvProgressSubtitle.text = "Embedding GPS timestamp into video frames"
        binding.tvProgressPercent.text = "0%"
        binding.progressIndicator.progress = 0
        binding.btnSave.isEnabled = false
        binding.btnShare.isEnabled = false

        val tempOutFile = File(cacheDir, "TRANSFORM_EXPORT_${System.currentTimeMillis()}.mp4")

        // Blocking MediaMetadataRetriever + Canvas work moved off the main thread - this used to
        // run synchronously right here and could freeze the UI right after tapping Save.
        lifecycleScope.launch {
            val (vidWidth, vidHeight) = withContext(Dispatchers.IO) { getVideoDimensions(uri) }
            val stampBitmap = withContext(Dispatchers.IO) { createStampOverlayBitmap(vidWidth, vidHeight) }

            val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(stampBitmap)
            val overlayEffect = OverlayEffect(listOf(bitmapOverlay))

            val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(emptyList(), listOf(overlayEffect)))
                .build()

            val transformer = Transformer.Builder(this@ImportImageStampActivity)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        progressTrackingJob?.cancel()
                        activeTransformer = null
                        stampBitmap.recycle()

                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                copyVideoToMediaStore(tempOutFile, vidWidth, vidHeight)
                                tempOutFile.delete()

                                withContext(Dispatchers.Main) {
                                    binding.layoutProgressOverlay.visibility = View.GONE
                                    binding.btnSave.isEnabled = true
                                    binding.btnShare.isEnabled = true
                                    showSuccessfullySavedDialog()
                                }
                            } catch (e: Exception) {
                                tempOutFile.delete()
                                withContext(Dispatchers.Main) {
                                    binding.layoutProgressOverlay.visibility = View.GONE
                                    binding.btnSave.isEnabled = true
                                    binding.btnShare.isEnabled = true
                                    Toast.makeText(this@ImportImageStampActivity, "Failed to save video: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        progressTrackingJob?.cancel()
                        activeTransformer = null
                        tempOutFile.delete()
                        stampBitmap.recycle()

                        runOnUiThread {
                            binding.layoutProgressOverlay.visibility = View.GONE
                            binding.btnSave.isEnabled = true
                            binding.btnShare.isEnabled = true
                            Toast.makeText(this@ImportImageStampActivity, "Video export failed: ${exportException.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                })
                .build()

            activeTransformer = transformer

            binding.btnCancelProgress.setOnClickListener {
                activeTransformer?.cancel()
                activeTransformer = null
                progressTrackingJob?.cancel()
                tempOutFile.delete()
                stampBitmap.recycle()
                binding.layoutProgressOverlay.visibility = View.GONE
                binding.btnSave.isEnabled = true
                binding.btnShare.isEnabled = true
                Toast.makeText(this@ImportImageStampActivity, "Export cancelled", Toast.LENGTH_SHORT).show()
            }

            try {
                transformer.start(editedMediaItem, tempOutFile.absolutePath)
                startProgressTracking(transformer)
            } catch (e: Exception) {
                tempOutFile.delete()
                stampBitmap.recycle()
                binding.layoutProgressOverlay.visibility = View.GONE
                binding.btnSave.isEnabled = true
                binding.btnShare.isEnabled = true
                Toast.makeText(this@ImportImageStampActivity, "Could not start video processing: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun shareStampedVideo() {
        val uri = mediaUri ?: return

        // Pause playback while exporting
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
        }

        binding.layoutProgressOverlay.visibility = View.VISIBLE
        binding.tvProgressTitle.text = "Preparing Video..."
        binding.tvProgressSubtitle.text = "Embedding GPS timestamp to share"
        binding.tvProgressPercent.text = "0%"
        binding.progressIndicator.progress = 0
        binding.btnSave.isEnabled = false
        binding.btnShare.isEnabled = false

        val shareDir = File(cacheDir, "videos").apply { mkdirs() }
        val shareFile = File(shareDir, "SHARE_VID_STAMP_${System.currentTimeMillis()}.mp4")

        // Blocking MediaMetadataRetriever + Canvas work moved off the main thread - this used to
        // run synchronously right here and could freeze the UI right after tapping Share.
        lifecycleScope.launch {
            val (vidWidth, vidHeight) = withContext(Dispatchers.IO) { getVideoDimensions(uri) }
            val stampBitmap = withContext(Dispatchers.IO) { createStampOverlayBitmap(vidWidth, vidHeight) }

            val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(stampBitmap)
            val overlayEffect = OverlayEffect(listOf(bitmapOverlay))

            val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(emptyList(), listOf(overlayEffect)))
                .build()

            val transformer = Transformer.Builder(this@ImportImageStampActivity)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        progressTrackingJob?.cancel()
                        activeTransformer = null
                        stampBitmap.recycle()

                        runOnUiThread {
                            binding.layoutProgressOverlay.visibility = View.GONE
                            binding.btnSave.isEnabled = true
                            binding.btnShare.isEnabled = true

                            val shareUri = FileProvider.getUriForFile(
                                this@ImportImageStampActivity,
                                "${packageName}.fileprovider",
                                shareFile
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_STREAM, shareUri)
                                putExtra(Intent.EXTRA_TEXT, getStampShareText())
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(intent, "Share Video with Timestamp"))
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        progressTrackingJob?.cancel()
                        activeTransformer = null
                        shareFile.delete()
                        stampBitmap.recycle()

                        runOnUiThread {
                            binding.layoutProgressOverlay.visibility = View.GONE
                            binding.btnSave.isEnabled = true
                            binding.btnShare.isEnabled = true
                            Toast.makeText(this@ImportImageStampActivity, "Video export failed: ${exportException.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                })
                .build()

            activeTransformer = transformer

            binding.btnCancelProgress.setOnClickListener {
                activeTransformer?.cancel()
                activeTransformer = null
                progressTrackingJob?.cancel()
                shareFile.delete()
                stampBitmap.recycle()
                binding.layoutProgressOverlay.visibility = View.GONE
                binding.btnSave.isEnabled = true
                binding.btnShare.isEnabled = true
                Toast.makeText(this@ImportImageStampActivity, "Sharing cancelled", Toast.LENGTH_SHORT).show()
            }

            try {
                transformer.start(editedMediaItem, shareFile.absolutePath)
                startProgressTracking(transformer)
            } catch (e: Exception) {
                shareFile.delete()
                stampBitmap.recycle()
                binding.layoutProgressOverlay.visibility = View.GONE
                binding.btnSave.isEnabled = true
                binding.btnShare.isEnabled = true
                Toast.makeText(this@ImportImageStampActivity, "Could not start video processing: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun startProgressTracking(transformer: Transformer) {
        progressTrackingJob?.cancel()
        progressTrackingJob = lifecycleScope.launch {
            val progressHolder = ProgressHolder()
            while (isActive) {
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val percent = progressHolder.progress.coerceIn(0, 100)
                    binding.progressIndicator.progress = percent
                    binding.tvProgressPercent.text = "$percent%"
                }
                delay(120)
            }
        }
    }

    private fun createStampOverlayBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val data = currentStampData
        val settings = currentStampSettings
        if (data != null) {
            stampRenderer.draw(canvas, width, height, data, settings)
        }
        return bitmap
    }

    private fun getVideoDimensions(videoUri: Uri): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, videoUri)
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val rawWidth = widthStr?.toIntOrNull() ?: 1080
            val rawHeight = heightStr?.toIntOrNull() ?: 1920
            val rotation = rotationStr?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                Pair(rawHeight, rawWidth)
            } else {
                Pair(rawWidth, rawHeight)
            }
        } catch (e: Exception) {
            Pair(1080, 1920)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun copyVideoToMediaStore(sourceFile: File, width: Int, height: Int): Uri {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val name = "VID_${sdf.format(Date())}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.WIDTH, width)
            put(MediaStore.Video.Media.HEIGHT, height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/${CameraStorageConstants.APP_FOLDER_NAME}"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val appDir = File(moviesDir, CameraStorageConstants.APP_FOLDER_NAME).apply { mkdirs() }
                val targetFile = File(appDir, name)
                put(MediaStore.MediaColumns.DATA, targetFile.absolutePath)
            }
        }

        val outputUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("Failed to create MediaStore video record")

        contentResolver.openOutputStream(outputUri)?.use { outputStream ->
            sourceFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: throw IllegalStateException("Failed to open output stream for video")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(outputUri, contentValues, null, null)
        }

        return outputUri
    }

    // =========================================================================
    // BITMAP & FILE HELPERS
    // =========================================================================

    private fun decodeOrientedBitmap(uri: Uri): Bitmap {
        var rotationDegrees = 0f
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                rotationDegrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        } catch (e: Exception) {
            // Ignore exif failure
        }

        // Protect against OutOfMemoryError on ultra-high-resolution images (e.g. 48MP/108MP)
        // by sampling down so the maximum dimension does not exceed 4096px (Full 4K Ultra-HD).
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
        } catch (e: Exception) {
            // Ignore
        }

        var sampleSize = 1
        val maxTargetDimension = 4096
        if (boundsOptions.outWidth > 0 && boundsOptions.outHeight > 0) {
            while (boundsOptions.outWidth / sampleSize > maxTargetDimension ||
                boundsOptions.outHeight / sampleSize > maxTargetDimension
            ) {
                sampleSize *= 2
            }
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inMutable = true
        }

        val originalBitmap = contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: throw IllegalStateException("Failed to decode image from uri")

        val rotated = if (rotationDegrees != 0f) {
            val matrix = Matrix().apply { postRotate(rotationDegrees) }
            val rotatedBmp = Bitmap.createBitmap(
                originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true
            )
            if (rotatedBmp != originalBitmap) originalBitmap.recycle()
            rotatedBmp
        } else {
            originalBitmap
        }

        // Ensure high-resolution output when saving/sharing stamped images:
        // If imported image has small dimensions (e.g. icons, low-res graphics), upscale to minimum 1440px
        // with bilinear/bicubic filtering so the embedded GPS stamp details, text, and map badge render razor-sharp.
        val minTargetDimension = 1440
        val currentMinDimension = minOf(rotated.width, rotated.height)
        if (currentMinDimension in 1 until minTargetDimension) {
            val upScaleFactor = minTargetDimension.toFloat() / currentMinDimension
            val targetW = (rotated.width * upScaleFactor).toInt()
            val targetH = (rotated.height * upScaleFactor).toInt()
            val highResBitmap = Bitmap.createScaledBitmap(rotated, targetW, targetH, true)
            if (highResBitmap != rotated) {
                rotated.recycle()
            }
            return highResBitmap
        }

        return rotated
    }

    private fun getFileName(uri: Uri?): String? {
        if (uri == null) return null
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            name = cursor.getString(index)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        if (name == null) {
            name = uri.path?.let { path ->
                val cut = path.lastIndexOf('/')
                if (cut != -1) path.substring(cut + 1) else path
            }
        }
        return name
    }
}
