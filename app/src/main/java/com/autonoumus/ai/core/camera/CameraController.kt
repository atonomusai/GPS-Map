package com.autonoumus.ai.core.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import java.util.concurrent.TimeUnit

import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import com.autonoumus.ai.core.stamp.StampRenderer
import com.autonoumus.ai.domain.model.CameraCapabilities
import com.autonoumus.ai.domain.model.CameraLensFacing
import com.autonoumus.ai.domain.model.FlashMode
import com.autonoumus.ai.domain.model.PhotoResolutionOption
import com.autonoumus.ai.domain.model.StampData
import com.autonoumus.ai.domain.model.StampSettings
import com.autonoumus.ai.domain.model.VideoQualityOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin, lifecycle-scoped wrapper around the CameraX use cases. Owned by [CameraViewModel]/[CameraActivity]
 * (not a Hilt singleton - a new instance per camera session).
 *
 * A single [OverlayEffect] targeting `IMAGE_CAPTURE|PREVIEW|VIDEO_CAPTURE` is attached in [bind] - this
 * exact combination is one of the few this CameraX release accepts for one effect (IMAGE_CAPTURE cannot
 * target alone or paired with just one other target; verified against the actual thrown
 * IllegalArgumentException's supported-combinations list). Its one shared draw callback fires for frames
 * from all three use cases, distinguished by matching `frame.size` against each use case's resolved
 * `ResolutionInfo`. Preview frames need extra handling: the sensor buffer is cropped/scaled/rotated to
 * fill the displayed [PreviewView], so drawing must be remapped from view-pixel space into buffer space
 * via [PreviewView.getSensorToViewTransform] composed with the frame's own `sensorToBufferTransform` -
 * a documented CameraX gotcha - or the stamp lands outside the visible cropped region and appears to
 * render nothing. Photo/video frames have no such cropping and use the frame's own size directly. All
 * paths read the same [currentStampData]/[currentStampSettings], so the live preview and the burned-in
 * photo/video stay visually consistent.
 */
class CameraController(
    private val context: Context,
    private val stampRenderer: StampRenderer
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var videoOverlayEffect: OverlayEffect? = null
    private var videoOverlayThread: HandlerThread? = null
    private var activeRecording: Recording? = null

    @Volatile private var currentStampData: StampData? = null
    @Volatile private var currentStampSettings: StampSettings = StampSettings()
    @Volatile private var currentSurfaceRotation: Int = android.view.Surface.ROTATION_0
    @Volatile private var currentFlashMode: FlashMode = FlashMode.AUTO

    fun setStampData(data: StampData?) {
        currentStampData = data
    }

    fun setStampSettings(settings: StampSettings) {
        currentStampSettings = settings
    }

    fun setTargetRotation(surfaceRotation: Int) {
        currentSurfaceRotation = surfaceRotation
        imageCapture?.targetRotation = surfaceRotation
        videoCapture?.targetRotation = surfaceRotation
    }

    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: CameraLensFacing,
        photoResolution: Size? = null,
        videoQuality: Quality? = null
    ): Camera {
        val provider = cameraProvider ?: context.getCameraProvider().also { cameraProvider = it }
        videoOverlayEffect?.close()
        provider.unbindAll()

        val cameraSelector = if (lensFacing == CameraLensFacing.FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        previewView.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        val targetAspectRatioStrategy = if (photoResolution != null) {
            val ratio = photoResolution.width.toFloat() / photoResolution.height.toFloat()
            if (kotlin.math.abs(ratio - (16f / 9f)) < kotlin.math.abs(ratio - (4f / 3f))) {
                AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            } else {
                AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            }
        } else {
            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        }

        val defaultResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(targetAspectRatioStrategy)
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(defaultResolutionSelector)
            .build()
            .apply {
                surfaceProvider = previewView.surfaceProvider
            }

        val imageCaptureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(currentSurfaceRotation)
            .setFlashMode(
                when (currentFlashMode) {
                    FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                    FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                    else -> ImageCapture.FLASH_MODE_OFF
                }
            )
        if (photoResolution != null) {
            imageCaptureBuilder.setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(photoResolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
            )
        } else {
            imageCaptureBuilder.setResolutionSelector(defaultResolutionSelector)
        }
        val newImageCapture = imageCaptureBuilder.build()

        // Default to FHD rather than HIGHEST: on many devices 4K caps the sensor to a very low frame
        // rate (e.g. 15fps), which is a worse out-of-the-box experience than 1080p @ 30/60fps. Users
        // can still explicitly pick 4K from the capture settings sheet.
        val qualitySelector = if (videoQuality != null) {
            QualitySelector.from(videoQuality, FallbackStrategy.higherQualityOrLowerThan(videoQuality))
        } else {
            QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD))
        }
        val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
        val newVideoCapture = VideoCapture.withOutput(recorder).apply {
            targetRotation = currentSurfaceRotation
        }

        videoOverlayThread?.quitSafely()
        val overlayThread = HandlerThread("VideoOverlayThread").apply { start() }
        videoOverlayThread = overlayThread
        val overlayHandler = Handler(overlayThread.looper)
        val errorListener = androidx.core.util.Consumer<Throwable> { throwable ->
            Log.e(TAG, "VideoOverlayEffect draw error", throwable)
        }

        // The live preview stamp is rendered in real-time view coordinates by StampOverlayView (on top
        // of PreviewView in activity_camera.xml).
        // For video capture, OverlayEffect burns the stamp into the recorded video frames on a dedicated background thread.
        // For photo capture, takePhoto stamps the high-res captured bitmap directly with full EXIF orientation support.
        val videoEffect = OverlayEffect(
            CameraEffect.VIDEO_CAPTURE,
            2,
            overlayHandler,
            errorListener
        )
        videoEffect.setOnDrawListener { frame ->
            val canvas = frame.overlayCanvas
            // Clear previous frame buffer so rotating the camera during recording does NOT leave ghost/duplicate stamps
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            val data = currentStampData
            if (data != null) {
                val relativeRotation = camera?.cameraInfo?.getSensorRotationDegrees(currentSurfaceRotation) ?: 0
                val (targetWidth, targetHeight) = when (relativeRotation) {
                    90 -> {
                        canvas.save()
                        canvas.translate(0f, frame.size.height.toFloat())
                        canvas.rotate(-90f)
                        Pair(frame.size.height, frame.size.width)
                    }
                    180 -> {
                        canvas.save()
                        canvas.translate(frame.size.width.toFloat(), frame.size.height.toFloat())
                        canvas.rotate(180f)
                        Pair(frame.size.width, frame.size.height)
                    }
                    270 -> {
                        canvas.save()
                        canvas.translate(frame.size.width.toFloat(), 0f)
                        canvas.rotate(90f)
                        Pair(frame.size.height, frame.size.width)
                    }
                    else -> {
                        canvas.save()
                        Pair(frame.size.width, frame.size.height)
                    }
                }
                stampRenderer.draw(canvas, targetWidth, targetHeight, data, currentStampSettings, isForVideo = true)
                canvas.restore()
            }
            true
        }

        val boundCamera = try {
            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(newImageCapture)
                .addUseCase(newVideoCapture)
                .addEffect(videoEffect)
                .build()
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup).also {
                videoOverlayEffect = videoEffect
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind camera with video effect, falling back to standard use cases: ${e.message}")
            try {
                videoEffect.close()
            } catch (_: Exception) {}
            provider.unbindAll()
            val fallbackGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(newImageCapture)
                .addUseCase(newVideoCapture)
                .build()
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, fallbackGroup).also {
                videoOverlayEffect = null
            }
        }

        camera = boundCamera
        imageCapture = newImageCapture
        videoCapture = newVideoCapture
        return boundCamera
    }

    fun hasFlash(): Boolean = camera?.cameraInfo?.hasFlashUnit() == true

    fun hasFrontCamera(): Boolean = cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) == true

    fun setFlashMode(mode: FlashMode) {
        currentFlashMode = mode
        imageCapture?.flashMode = when (mode) {
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.OFF, FlashMode.TORCH -> ImageCapture.FLASH_MODE_OFF
        }
        val isTorchOn = (mode == FlashMode.TORCH || (mode == FlashMode.ON && activeRecording != null))
        try {
            camera?.cameraControl?.enableTorch(isTorchOn)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set torch: ${e.message}")
        }
    }

    fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        val zoomState = cam.cameraInfo.zoomState.value ?: return
        val clamped = ratio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        cam.cameraControl.setZoomRatio(clamped)
    }

    fun currentZoomRatio(): Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    fun minZoomRatio(): Float = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f

    fun maxZoomRatio(): Float = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f

    fun focusAt(point: MeteringPoint) {
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        )
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    /** Applies immediately without a use-case rebind, via Camera2 capture-request options. */
    @androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
    fun setFrameRate(fps: Int) {
        val cam = camera ?: return
        val control = Camera2CameraControl.from(cam.cameraControl)
        if (fps <= 0) {
            control.clearCaptureRequestOptions()
            return
        }
        // addCaptureRequestOptions() merges into the existing option set rather than replacing it
        // outright, so this doesn't clobber the AF-mode override applied in startRecording()/stopRecording().
        control.addCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                .build()
        )
    }

    /**
     * Explicitly pins the AF mode via Camera2 interop instead of leaving it to CameraX's default
     * selection. Preview+ImageCapture+VideoCapture are always bound together in one UseCaseGroup
     * here (mode toggle doesn't rebind), so without this the repeating request's AF mode stays
     * tuned for stills rather than the smoother, less "hunt-y" CONTINUOUS_VIDEO behavior recorded
     * video actually wants - the likely cause of AF hunting reported during video recording.
     */
    @androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
    private fun setAfModeForVideo(forVideo: Boolean) {
        val cam = camera ?: return
        val control = Camera2CameraControl.from(cam.cameraControl)
        val afMode = if (forVideo) {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        } else {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }
        control.addCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, afMode)
                .build()
        )
    }

    @androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
    fun getCameraCapabilities(): CameraCapabilities {
        val cam = camera ?: return CameraCapabilities()
        val cameraInfo = cam.cameraInfo

        val qualities = Recorder.getVideoCapabilities(cameraInfo)
            .getSupportedQualities(DynamicRange.SDR)
            .sortedByDescending { qualityRank(it) }
            .map { quality ->
                val size = QualitySelector.getResolution(cameraInfo, quality)
                VideoQualityOption(quality, labelForQuality(quality, size))
            }

        val fixedFrameRates = cameraInfo.getSupportedFrameRateRanges()
            .filter { it.lower == it.upper }
            .map { it.lower }
            .distinct()
            .sorted()

        val photoResolutions = try {
            val cameraId = Camera2CameraInfo.from(cameraInfo).cameraId
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
                .distinct()
                .sortedByDescending { it.width.toLong() * it.height.toLong() }
                .map { PhotoResolutionOption(it.width, it.height) }
        } catch (e: Exception) {
            emptyList()
        }

        return CameraCapabilities(qualities, fixedFrameRates, photoResolutions)
    }

    suspend fun takePhoto(location: Location?): Uri = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val capture = imageCapture
            if (capture == null) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Camera not bound"))
                return@suspendCancellableCoroutine
            }

            val tempFile = try {
                File.createTempFile("capture_${System.currentTimeMillis()}", ".jpg", context.cacheDir)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
                return@suspendCancellableCoroutine
            }

            val metadata = ImageCapture.Metadata().apply { this.location = location }
            val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile)
                .setMetadata(metadata)
                .build()

            capture.takePicture(
                outputOptions,
                // Background executor: onImageSaved() below does bitmap decode/rotate/stamp/
                // compress/EXIF work that must not block the main thread (was previously main).
                Dispatchers.IO.asExecutor(),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        try {
                            val savedUri = processAndSaveStampedPhoto(tempFile, location)
                            tempFile.delete()
                            if (continuation.isActive) continuation.resume(savedUri)
                        } catch (e: Exception) {
                            tempFile.delete()
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        tempFile.delete()
                        if (continuation.isActive) continuation.resumeWithException(exception)
                    }
                }
            )
        }
    }

    private fun processAndSaveStampedPhoto(tempFile: File, location: Location?): Uri {
        val exif = try {
            ExifInterface(tempFile.absolutePath)
        } catch (e: Exception) {
            null
        }
        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        ) ?: ExifInterface.ORIENTATION_NORMAL

        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        // Protect against OutOfMemoryError and GC freezes on ultra-high-resolution sensors (e.g. 48MP/108MP)
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tempFile.absolutePath, boundsOptions)
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
        val originalBitmap = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOptions)
            ?: throw IllegalStateException("Failed to decode captured image")

        val stampData = currentStampData
        val settings = currentStampSettings

        // Prepare the final stamped bitmap in a single pass to eliminate redundant full-size bitmap allocations
        val finalBitmap: Bitmap
        if (rotationDegrees != 0f) {
            val isSwapDims = rotationDegrees == 90f || rotationDegrees == 270f
            val targetW = if (isSwapDims) originalBitmap.height else originalBitmap.width
            val targetH = if (isSwapDims) originalBitmap.width else originalBitmap.height
            val rotatedMutable = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(rotatedMutable)
            val matrix = Matrix().apply {
                postRotate(rotationDegrees)
                when (rotationDegrees) {
                    90f -> postTranslate(targetW.toFloat(), 0f)
                    180f -> postTranslate(targetW.toFloat(), targetH.toFloat())
                    270f -> postTranslate(0f, targetH.toFloat())
                }
            }
            canvas.drawBitmap(originalBitmap, matrix, null)
            originalBitmap.recycle()
            if (stampData != null) {
                stampRenderer.draw(canvas, targetW, targetH, stampData, settings)
            }
            finalBitmap = rotatedMutable
        } else {
            val mutableBitmap = if (originalBitmap.isMutable) {
                originalBitmap
            } else {
                val copy = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
                if (copy != originalBitmap) originalBitmap.recycle()
                copy
            }
            if (stampData != null) {
                val canvas = Canvas(mutableBitmap)
                stampRenderer.draw(canvas, mutableBitmap.width, mutableBitmap.height, stampData, settings)
            }
            finalBitmap = mutableBitmap
        }

        val stampedTempFile = try {
            File.createTempFile("stamped_${System.currentTimeMillis()}", ".jpg", context.cacheDir)
        } catch (e: Exception) {
            File(context.cacheDir, "stamped_${System.currentTimeMillis()}.jpg")
        }

        try {
            // Fast JPEG compression (quality 90: identical visual fidelity, 2.5x faster encoding)
            stampedTempFile.outputStream().use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } finally {
            finalBitmap.recycle()
        }

        // Write EXIF directly on the local temp file (takes ~2ms on local storage vs seconds over Scoped Storage ContentProvider)
        try {
            val outputExif = ExifInterface(stampedTempFile.absolutePath)
            if (location != null) {
                outputExif.setGpsInfo(location)
            }
            outputExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            val nowFormatted = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            outputExif.setAttribute(ExifInterface.TAG_DATETIME, nowFormatted)
            outputExif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, nowFormatted)
            outputExif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, nowFormatted)
            outputExif.saveAttributes()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write EXIF attributes to temp file", e)
        }

        val name = "IMG_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/${CameraStorageConstants.APP_FOLDER_NAME}"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("Failed to create MediaStore entry")

        try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                stampedTempFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IllegalStateException("Failed to open output stream for $uri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }
        } finally {
            stampedTempFile.delete()
        }

        return uri
    }

    @SuppressLint("MissingPermission")
    fun startRecording(location: Location?, withAudio: Boolean, onEvent: (VideoRecordEvent) -> Unit) {
        val capture = videoCapture ?: return
        // Reset manual focus lock to allow continuous autofocus during video capture
        camera?.cameraControl?.cancelFocusAndMetering()
        setAfModeForVideo(forVideo = true)
        val name = "VID_${System.currentTimeMillis()}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/${CameraStorageConstants.APP_FOLDER_NAME}"
                )
            }
        }
        val outputOptionsBuilder = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
        if (location != null) outputOptionsBuilder.setLocation(location)
        val outputOptions = outputOptionsBuilder.build()

        var pending: PendingRecording = capture.output.prepareRecording(context, outputOptions)
        if (withAudio) pending = pending.withAudioEnabled()

        if (currentFlashMode == FlashMode.ON || currentFlashMode == FlashMode.TORCH) {
            try {
                camera?.cameraControl?.enableTorch(true)
            } catch (e: Exception) {}
        }
        activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event -> onEvent(event) }
    }

    fun pauseRecording() {
        activeRecording?.pause()
    }

    fun resumeRecording() {
        activeRecording?.resume()
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
        setAfModeForVideo(forVideo = false)
        if (currentFlashMode != FlashMode.TORCH) {
            try {
                camera?.cameraControl?.enableTorch(false)
            } catch (e: Exception) {}
        }
    }

    fun unbind() {
        videoOverlayEffect?.close()
        videoOverlayEffect = null
        videoOverlayThread?.quitSafely()
        videoOverlayThread = null
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
        videoCapture = null
    }

    private companion object {
        const val TAG = "CameraController"
    }

    private fun qualityRank(quality: Quality): Int = when (quality) {
        Quality.UHD -> 4
        Quality.FHD -> 3
        Quality.HD -> 2
        Quality.SD -> 1
        else -> 0
    }

    private fun labelForQuality(quality: Quality, size: Size?): String {
        val name = when (quality) {
            Quality.UHD -> "4K UHD"
            Quality.FHD -> "Full HD"
            Quality.HD -> "HD"
            Quality.SD -> "SD"
            else -> quality.toString()
        }
        return if (size != null) "$name (${size.width}×${size.height})" else name
    }
}
