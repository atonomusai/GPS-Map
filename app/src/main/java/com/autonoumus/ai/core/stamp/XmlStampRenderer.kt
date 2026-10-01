package com.autonoumus.ai.core.stamp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.autonoumus.ai.R
import com.autonoumus.ai.databinding.StampTemplateBigBenMinimalBinding
import com.autonoumus.ai.databinding.StampTemplateCathedralLeftMapBinding
import com.autonoumus.ai.databinding.StampTemplateCyberHudBinding
import com.autonoumus.ai.databinding.StampTemplateGpsFullCardBinding
import com.autonoumus.ai.databinding.StampTemplateLondonCathedralBinding
import com.autonoumus.ai.databinding.StampTemplateNotreDameAlarmBinding
import com.autonoumus.ai.databinding.StampTemplateParisGlassBinding
import com.autonoumus.ai.databinding.StampTemplatePisaCapsuleBinding
import com.autonoumus.ai.databinding.StampTemplatePisaPillBinding
import com.autonoumus.ai.databinding.StampTemplatePisaPinCardBinding
import com.autonoumus.ai.databinding.StampTemplateRetroCamBinding
import com.autonoumus.ai.databinding.StampTemplateFrostedBlurBinding
import com.autonoumus.ai.domain.model.MapTypeOption
import com.autonoumus.ai.domain.model.StampData
import com.autonoumus.ai.domain.model.StampPosition
import com.autonoumus.ai.domain.model.StampSettings
import com.autonoumus.ai.domain.model.StampTemplate
import com.autonoumus.ai.domain.model.StampTextAlignment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders XML-based stamp templates into Canvas, enabling full visual styling in Android XML
 * with proportional resolution scaling across high-res captured photos, live camera overlays, videos, and previews.
 */
@Singleton
class XmlStampRenderer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val themedContext: Context by lazy {
        ContextThemeWrapper(context, R.style.Theme_GSMCAMER)
    }

    private val inflater: LayoutInflater by lazy {
        LayoutInflater.from(themedContext)
    }

    private val dummyParent: FrameLayout by lazy {
        FrameLayout(themedContext)
    }

    // Live preview draws at 30-60fps on the main thread (CameraX video-effect draw callback) -
    // re-inflating a fresh View hierarchy from XML on every single call was a real per-frame
    // performance cost. These caches let live preview reuse one inflated View per template and
    // just re-bind its data each frame.
    private var parisGlassPreviewBinding: StampTemplateParisGlassBinding? = null
    private var londonCathedralPreviewBinding: StampTemplateLondonCathedralBinding? = null
    private var pisaCapsulePreviewBinding: StampTemplatePisaCapsuleBinding? = null
    private var bigBenMinimalPreviewBinding: StampTemplateBigBenMinimalBinding? = null
    private var gpsFullCardPreviewBinding: StampTemplateGpsFullCardBinding? = null
    private var pisaPillPreviewBinding: StampTemplatePisaPillBinding? = null
    private var notreDameAlarmPreviewBinding: StampTemplateNotreDameAlarmBinding? = null
    private var cathedralLeftMapPreviewBinding: StampTemplateCathedralLeftMapBinding? = null
    private var pisaPinCardPreviewBinding: StampTemplatePisaPinCardBinding? = null
    private var cyberHudPreviewBinding: StampTemplateCyberHudBinding? = null
    private var retroCamPreviewBinding: StampTemplateRetroCamBinding? = null
    private var frostedBlurPreviewBinding: StampTemplateFrostedBlurBinding? = null

    /**
     * Caches the result of the (relatively expensive) forceLayout()/measure()/layout() pass so a
     * per-frame draw() call with identical inputs (same view instance, canvas size, data and
     * settings) can skip straight to canvas.draw(). During video recording the stamp data/settings
     * are frozen for the whole clip (the periodic refresh is paused while recording), so without
     * this cache every single recorded frame - commonly 30+ times per second - was paying for a
     * full re-measure/re-layout of a multi-TextView hierarchy for no reason, which was a real
     * contributor to recording stutter.
     */
    private data class LayoutCache(
        val view: View,
        val canvasWidth: Int,
        val canvasHeight: Int,
        val data: StampData,
        val settings: StampSettings,
        val isLoading: Boolean,
        val measuredW: Int,
        val measuredH: Int,
        val drawX: Float,
        val drawY: Float
    )

    // Separate cache slots per call site so the background video-overlay thread and the main-thread
    // live preview never read/write the same field concurrently.
    private var previewLayoutCache: LayoutCache? = null
    private var videoLayoutCache: LayoutCache? = null

    // Dedicated cached bindings for video overlay recording (runs on background VideoOverlayThread).
    // Keeping separate caches guarantees thread safety between the UI live preview and video burning.
    private var parisGlassVideoBinding: StampTemplateParisGlassBinding? = null
    private var londonCathedralVideoBinding: StampTemplateLondonCathedralBinding? = null
    private var pisaCapsuleVideoBinding: StampTemplatePisaCapsuleBinding? = null
    private var bigBenMinimalVideoBinding: StampTemplateBigBenMinimalBinding? = null
    private var gpsFullCardVideoBinding: StampTemplateGpsFullCardBinding? = null
    private var pisaPillVideoBinding: StampTemplatePisaPillBinding? = null
    private var notreDameAlarmVideoBinding: StampTemplateNotreDameAlarmBinding? = null
    private var cathedralLeftMapVideoBinding: StampTemplateCathedralLeftMapBinding? = null
    private var pisaPinCardVideoBinding: StampTemplatePisaPinCardBinding? = null
    private var cyberHudVideoBinding: StampTemplateCyberHudBinding? = null
    private var retroCamVideoBinding: StampTemplateRetroCamBinding? = null
    private var frostedBlurVideoBinding: StampTemplateFrostedBlurBinding? = null

    fun draw(
        canvas: Canvas,
        canvasWidth: Int,
        canvasHeight: Int,
        data: StampData,
        settings: StampSettings,
        isLivePreview: Boolean = false,
        isLoading: Boolean = false,
        isForVideo: Boolean = false
    ) {
        if (canvasWidth <= 0 || canvasHeight <= 0) return

        val view = when (settings.template) {
            StampTemplate.PARIS_GLASS -> bindParisGlass(data, settings, isLivePreview, isForVideo)
            StampTemplate.LONDON_CATHEDRAL -> bindLondonCathedral(data, settings, isLivePreview, isForVideo)
            StampTemplate.PISA_CAPSULE -> bindPisaCapsule(data, settings, isLivePreview, isForVideo)
            StampTemplate.BIG_BEN_MINIMAL -> bindBigBenMinimal(data, settings, isLivePreview, isForVideo)
            StampTemplate.GPS_FULL_CARD -> bindGpsFullCard(data, settings, isLivePreview, isForVideo)
            StampTemplate.PISA_PILL -> bindPisaPill(data, settings, isLivePreview, isForVideo)
            StampTemplate.NOTRE_DAME_ALARM -> bindNotreDameAlarm(data, settings, isLivePreview, isForVideo)
            StampTemplate.CATHEDRAL_LEFT_MAP -> bindCathedralLeftMap(data, settings, isLivePreview, isForVideo)
            StampTemplate.PISA_PIN_CARD -> bindPisaPinCard(data, settings, isLivePreview, isForVideo)
            StampTemplate.CYBER_HUD -> bindCyberHud(data, settings, isLivePreview, isForVideo)
            StampTemplate.RETRO_CAM -> bindRetroCam(data, settings, isLivePreview, isForVideo)
            StampTemplate.FROSTED_BLUR -> bindFrostedBlur(data, settings, isLivePreview, isForVideo)
        }

        val isPortrait = canvasHeight >= (canvasWidth * 0.85f)
        val isFullWidth = settings.template == StampTemplate.GPS_FULL_CARD ||
                          settings.template == StampTemplate.CYBER_HUD ||
                          settings.position == StampPosition.BOTTOM_FULL_WIDTH

        val dm = context.resources.displayMetrics
        val density = dm.density
        val userFontScale = settings.fontSizePercent.coerceIn(50, 200) / 100f

        // Reference viewport width in DP (standard Android device portrait width is 360-390dp)
        val refScreenWidthDp = if (isPortrait) 360f else 640f
        val refScreenWidthPx = refScreenWidthDp * density

        val baseScale = (canvasWidth.toFloat() / refScreenWidthPx).coerceAtLeast(0.2f)
        val totalScale = baseScale * userFontScale

        val virtualCanvasWidth = canvasWidth / totalScale
        val virtualCanvasHeight = canvasHeight / totalScale

        val hMargin = if (isPortrait) 16f * density else 64f * density
        val vMargin = if (isPortrait) 16f * density else 24f * density

        val widthSpec = if (isFullWidth) {
            val fullWidthPx = (virtualCanvasWidth - (hMargin * 2f)).coerceAtLeast(100f).toInt()
            View.MeasureSpec.makeMeasureSpec(fullWidthPx, View.MeasureSpec.EXACTLY)
        } else {
            val maxWidthPx = (virtualCanvasWidth - (hMargin * 2f)).coerceAtLeast(100f).toInt()
            View.MeasureSpec.makeMeasureSpec(maxWidthPx, View.MeasureSpec.AT_MOST)
        }

        val existingCache = if (isForVideo) videoLayoutCache else if (isLivePreview) previewLayoutCache else null
        val cacheHit = existingCache != null &&
            existingCache.view === view &&
            existingCache.canvasWidth == canvasWidth &&
            existingCache.canvasHeight == canvasHeight &&
            existingCache.data == data &&
            existingCache.settings == settings &&
            existingCache.isLoading == isLoading

        val measuredW: Int
        val measuredH: Int
        val drawX: Float
        val drawY: Float

        if (cacheHit) {
            measuredW = existingCache!!.measuredW
            measuredH = existingCache.measuredH
            drawX = existingCache.drawX
            drawY = existingCache.drawY
        } else {
            view.forceLayout()
            view.measure(
                widthSpec,
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            measuredW = view.measuredWidth.coerceAtLeast(1)
            measuredH = view.measuredHeight.coerceAtLeast(1)
            view.layout(0, 0, measuredW, measuredH)

            val isNaturallyCentered = settings.template == StampTemplate.PISA_CAPSULE ||
                                      settings.template == StampTemplate.PISA_PILL

            drawX = if (isFullWidth) {
                ((virtualCanvasWidth - measuredW) / 2f).coerceAtLeast(0f)
            } else {
                when (settings.position) {
                    StampPosition.BOTTOM_LEFT, StampPosition.TOP_LEFT -> {
                        if (isNaturallyCentered) {
                            ((virtualCanvasWidth - measuredW) / 2f).coerceAtLeast(0f)
                        } else {
                            hMargin
                        }
                    }
                    StampPosition.BOTTOM_RIGHT, StampPosition.TOP_RIGHT -> {
                        (virtualCanvasWidth - measuredW - hMargin).coerceAtLeast(0f)
                    }
                    StampPosition.BOTTOM_FULL_WIDTH -> {
                        ((virtualCanvasWidth - measuredW) / 2f).coerceAtLeast(0f)
                    }
                }
            }

            drawY = when (settings.position) {
                StampPosition.TOP_LEFT, StampPosition.TOP_RIGHT -> vMargin
                else -> (virtualCanvasHeight - measuredH - vMargin).coerceAtLeast(0f)
            }

            val newCache = LayoutCache(view, canvasWidth, canvasHeight, data, settings, isLoading, measuredW, measuredH, drawX, drawY)
            if (isForVideo) videoLayoutCache = newCache else if (isLivePreview) previewLayoutCache = newCache
        }

        canvas.save()
        canvas.scale(totalScale, totalScale)
        canvas.translate(drawX, drawY)
        if (isLoading && isLivePreview) {
            com.autonoumus.ai.core.util.ShimmerDrawUtils.drawShimmer(
                canvas,
                measuredW.toFloat(),
                measuredH.toFloat(),
                cornerRadius = 16f
            )
        } else {
            view.draw(canvas)
        }
        canvas.restore()
    }

    data class StampHitTestBounds(
        val stampBounds: RectF,
        val editIconBounds: RectF?
    )

    fun computeHitTestBounds(
        w: Int,
        h: Int,
        data: StampData,
        settings: StampSettings
    ): StampHitTestBounds {
        if (w <= 0 || h <= 0) return StampHitTestBounds(RectF(), null)
        val isPortrait = h >= (w * 0.85f)
        val isFullWidth = settings.template == StampTemplate.GPS_FULL_CARD ||
                          settings.template == StampTemplate.CYBER_HUD ||
                          settings.position == StampPosition.BOTTOM_FULL_WIDTH

        val view = when (settings.template) {
            StampTemplate.PARIS_GLASS -> bindParisGlass(data, settings, isLivePreview = true)
            StampTemplate.LONDON_CATHEDRAL -> bindLondonCathedral(data, settings, isLivePreview = true)
            StampTemplate.PISA_CAPSULE -> bindPisaCapsule(data, settings, isLivePreview = true)
            StampTemplate.BIG_BEN_MINIMAL -> bindBigBenMinimal(data, settings, isLivePreview = true)
            StampTemplate.GPS_FULL_CARD -> bindGpsFullCard(data, settings, isLivePreview = true)
            StampTemplate.PISA_PILL -> bindPisaPill(data, settings, isLivePreview = true)
            StampTemplate.NOTRE_DAME_ALARM -> bindNotreDameAlarm(data, settings, isLivePreview = true)
            StampTemplate.CATHEDRAL_LEFT_MAP -> bindCathedralLeftMap(data, settings, isLivePreview = true)
            StampTemplate.PISA_PIN_CARD -> bindPisaPinCard(data, settings, isLivePreview = true)
            StampTemplate.CYBER_HUD -> bindCyberHud(data, settings, isLivePreview = true)
            StampTemplate.RETRO_CAM -> bindRetroCam(data, settings, isLivePreview = true)
            StampTemplate.FROSTED_BLUR -> bindFrostedBlur(data, settings, isLivePreview = true)
        }

        val dm = context.resources.displayMetrics
        val density = dm.density
        val userFontScale = settings.fontSizePercent.coerceIn(50, 200) / 100f

        val refScreenWidthDp = if (isPortrait) 360f else 640f
        val refScreenWidthPx = refScreenWidthDp * density

        val baseScale = (w.toFloat() / refScreenWidthPx).coerceAtLeast(0.2f)
        val totalScale = baseScale * userFontScale

        val virtualCanvasWidth = w / totalScale
        val virtualCanvasHeight = h / totalScale

        val hMargin = if (isPortrait) 16f * density else 64f * density
        val vMargin = if (isPortrait) 16f * density else 24f * density

        val widthSpec = if (isFullWidth) {
            val fullWidthPx = (virtualCanvasWidth - (hMargin * 2f)).coerceAtLeast(100f).toInt()
            View.MeasureSpec.makeMeasureSpec(fullWidthPx, View.MeasureSpec.EXACTLY)
        } else {
            val maxWidthPx = (virtualCanvasWidth - (hMargin * 2f)).coerceAtLeast(100f).toInt()
            View.MeasureSpec.makeMeasureSpec(maxWidthPx, View.MeasureSpec.AT_MOST)
        }

        view.forceLayout()
        view.measure(
            widthSpec,
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val measuredW = view.measuredWidth.coerceAtLeast(1)
        val measuredH = view.measuredHeight.coerceAtLeast(1)
        view.layout(0, 0, measuredW, measuredH)

        val isNaturallyCentered = settings.template == StampTemplate.PISA_CAPSULE ||
                                  settings.template == StampTemplate.PISA_PILL

        val drawX = if (isFullWidth) {
            ((virtualCanvasWidth - measuredW) / 2f).coerceAtLeast(0f)
        } else {
            when (settings.position) {
                StampPosition.BOTTOM_LEFT, StampPosition.TOP_LEFT -> {
                    if (isNaturallyCentered) {
                        ((virtualCanvasWidth - measuredW) / 2f).coerceAtLeast(0f)
                    } else {
                        hMargin
                    }
                }
                StampPosition.BOTTOM_RIGHT, StampPosition.TOP_RIGHT -> {
                    (virtualCanvasWidth - measuredW - hMargin).coerceAtLeast(0f)
                }
                StampPosition.BOTTOM_FULL_WIDTH -> {
                    ((virtualCanvasWidth - measuredW) / 2f).coerceAtLeast(0f)
                }
            }
        }

        val drawY = when (settings.position) {
            StampPosition.TOP_LEFT, StampPosition.TOP_RIGHT -> vMargin
            else -> (virtualCanvasHeight - measuredH - vMargin).coerceAtLeast(0f)
        }

        val stampRect = RectF(
            drawX * totalScale,
            drawY * totalScale,
            (drawX + measuredW) * totalScale,
            (drawY + measuredH) * totalScale
        )

        var editIconRect: RectF? = null
        val ivEdit = view.findViewById<View>(R.id.ivEditIcon)
        if (ivEdit != null && ivEdit.visibility == View.VISIBLE) {
            val rect = android.graphics.Rect()
            ivEdit.getDrawingRect(rect)
            if (view is android.view.ViewGroup) {
                try {
                    view.offsetDescendantRectToMyCoords(ivEdit, rect)
                } catch (e: Exception) {}
            }
            editIconRect = RectF(
                (drawX + rect.left) * totalScale,
                (drawY + rect.top) * totalScale,
                (drawX + rect.right) * totalScale,
                (drawY + rect.bottom) * totalScale
            )
        }

        return StampHitTestBounds(stampRect, editIconRect)
    }

    fun computeCardBounds(w: Int, h: Int, settings: StampSettings): RectF {
        val sampleData = StampData(
            titleLine = "Islamabad, Pakistan",
            addressLine = "Street 4, Sector F-7/3, Islamabad",
            coordinatesLine = "33.7294° N, 73.0551° E",
            timestampLine = "24/10/2026 • 03:09 PM",
            gpsStatusLine = "GPS LOCKED",
            dateLine = "24/10/2026",
            timeLine = "03:09 PM",
            timeZoneLine = "UTC+1"
        )
        return computeHitTestBounds(w, h, sampleData, settings).stampBounds
    }

    private fun getMapDrawableRes(mapType: MapTypeOption): Int {
        return when (mapType) {
            MapTypeOption.NORMAL -> R.drawable.img_map_normal
            MapTypeOption.HYBRID -> R.drawable.img_map_hybrid
            MapTypeOption.SATELLITE -> R.drawable.img_map_satellite
            MapTypeOption.TERRAIN -> R.drawable.img_map_terrain
        }
    }

    private fun applyTextGravity(alignment: StampTextAlignment, vararg textViews: TextView) {
        val gravity = when (alignment) {
            StampTextAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
            StampTextAlignment.RIGHT -> Gravity.END
            StampTextAlignment.LEFT -> Gravity.START
        }
        textViews.forEach { it.gravity = gravity }
    }

    private fun bindParisGlass(data: StampData, settings: StampSettings, isLivePreview: Boolean = false, isForVideo: Boolean = false): View {
        val binding = if (isLivePreview) {
            parisGlassPreviewBinding ?: StampTemplateParisGlassBinding.inflate(inflater, dummyParent, false).also { parisGlassPreviewBinding = it }
        } else if (isForVideo) {
            parisGlassVideoBinding ?: StampTemplateParisGlassBinding.inflate(inflater, dummyParent, false).also { parisGlassVideoBinding = it }
        } else {
            StampTemplateParisGlassBinding.inflate(inflater, dummyParent, false)
        }

        val alphaInt = (settings.opacityPercent.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        binding.root.background?.mutate()?.alpha = alphaInt

        binding.ivEditIcon.isVisible = isLivePreview && !isForVideo

        val isNoGeotag = data.coordinatesLine.equals("NO GEOTAG", ignoreCase = true) || data.rawLatitude == null

        val datePart = data.dateLine.ifBlank { "24/10/2025" }
        val timePart = if (settings.showTimeZone) data.timeZoneLine.ifBlank { "UTC+1" } else data.timeLine.ifBlank { "03:09 PM" }
        binding.tvHeaderDate.text = "$datePart   $timePart"
        binding.tvHeaderDate.isVisible = settings.showTitleHeader

        val loc = if (isNoGeotag) {
            "Location Disabled"
        } else if (settings.showAddress) {
            data.addressLine.ifBlank { data.titleLine.ifBlank { "Current Location" } }
        } else {
            data.titleLine.ifBlank { data.addressLine.ifBlank { "Current Location" } }
        }
        binding.tvLocation.text = loc

        if (settings.textColor != Color.WHITE) {
            binding.tvHeaderDate.setTextColor(settings.textColor)
        }

        applyTextGravity(settings.textAlignment, binding.tvHeaderDate, binding.tvLocation)

        binding.ivMapBadge.setImageResource(getMapDrawableRes(settings.mapType))
        binding.ivMapBadge.isVisible = settings.showMapBadge && !isNoGeotag
        (binding.layoutTexts.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            val density = context.resources.displayMetrics.density
            lp.marginStart = if (settings.showMapBadge && !isNoGeotag) (12 * density).toInt() else 0
            binding.layoutTexts.layoutParams = lp
        }

        return binding.root
    }

    private fun bindLondonCathedral(data: StampData, settings: StampSettings, isLivePreview: Boolean = false, isForVideo: Boolean = false): View {
        val binding = if (isLivePreview) {
            londonCathedralPreviewBinding ?: StampTemplateLondonCathedralBinding.inflate(inflater, dummyParent, false).also { londonCathedralPreviewBinding = it }
        } else if (isForVideo) {
            londonCathedralVideoBinding ?: StampTemplateLondonCathedralBinding.inflate(inflater, dummyParent, false).also { londonCathedralVideoBinding = it }
        } else {
            StampTemplateLondonCathedralBinding.inflate(inflater, dummyParent, false)
        }

        val alphaInt = (settings.opacityPercent.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        binding.root.background?.mutate()?.alpha = alphaInt

        binding.ivEditIcon.isVisible = isLivePreview && !isForVideo
        val isNoGeotag = data.coordinatesLine.equals("NO GEOTAG", ignoreCase = true) || data.rawLatitude == null

        val loc = if (isNoGeotag) {
            "Location Disabled"
        } else if (settings.showAddress) {
            data.addressLine.ifBlank { data.titleLine.ifBlank { "Current Location" } }
        } else {
            data.titleLine.ifBlank { data.addressLine.ifBlank { "Current Location" } }
        }
        binding.tvLocation.text = loc
        binding.tvLocation.isVisible = settings.showTitleHeader

        val datePart = data.dateLine.ifBlank { "24/10/2025" }
        binding.tvDate.text = if (settings.showTimeZone && data.timeZoneLine.isNotBlank()) {
            "📅 $datePart • ${data.timeZoneLine}"
        } else {
            "📅 $datePart"
        }

        if (settings.textColor != Color.WHITE) {
            binding.tvDate.setTextColor(settings.textColor)
        }

        applyTextGravity(settings.textAlignment, binding.tvLocation, binding.tvDate)

        binding.ivMapBadge.setImageResource(getMapDrawableRes(settings.mapType))
        binding.ivMapBadge.isVisible = settings.showMapBadge && !isNoGeotag
        (binding.layoutTexts.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            val density = context.resources.displayMetrics.density
            lp.marginStart = if (settings.showMapBadge && !isNoGeotag) (10 * density).toInt() else 0
            binding.layoutTexts.layoutParams = lp
        }

        return binding.root
    }

    private fun bindPisaCapsule(data: StampData, settings: StampSettings, isLivePreview: Boolean = false, isForVideo: Boolean = false): View {
        val binding = if (isLivePreview) {
            pisaCapsulePreviewBinding ?: StampTemplatePisaCapsuleBinding.inflate(inflater, dummyParent, false).also { pisaCapsulePreviewBinding = it }
        } else if (isForVideo) {
            pisaCapsuleVideoBinding ?: StampTemplatePisaCapsuleBinding.inflate(inflater, dummyParent, false).also { pisaCapsuleVideoBinding = it }
        } else {
            StampTemplatePisaCapsuleBinding.inflate(inflater, dummyParent, false)
        }

        val alphaInt = (settings.opacityPercent.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        binding.cardCapsule.background?.mutate()?.alpha = alphaInt

        binding.ivEditIcon.isVisible = isLivePreview && !isForVideo
        val isNoGeotag = data.coordinatesLine.equals("NO GEOTAG", ignoreCase = true) || data.rawLatitude == null

        val timeStr = data.timeLine.ifBlank { "4:23 PM" }
        val timePart = if (settings.showTimeZone && data.timeZoneLine.isNotBlank()) {
            "⏰ $timeStr  ${data.timeZoneLine}"
        } else {
            "⏰ $timeStr"
        }
        binding.tvTimeHeader.text = timePart
        binding.tvTimeHeader.isVisible = settings.showTitleHeader

        val loc = if (isNoGeotag) {
            "Location Disabled"
        } else if (settings.showAddress) {
            data.addressLine.ifBlank { data.titleLine.ifBlank { "Current Location" } }
        } else {
            data.titleLine.ifBlank { data.addressLine.ifBlank { "Current Location" } }
        }
        binding.tvLocation.text = loc

        if (settings.showCoordinates) {
            val coords = if (isNoGeotag) {
                "NO GEOTAG"
            } else if (data.rawLongitude != null) {
                String.format(Locale.US, "Lat: %.4f°    |    Long: %.4f°", data.rawLatitude, data.rawLongitude)
            } else {
                data.coordinatesLine.ifBlank { "Lat: --    |    Long: --" }
            }
            binding.tvCoordinates.text = coords
            binding.tvCoordinates.isVisible = true
        } else {
            binding.tvCoordinates.isVisible = false
        }

        if (settings.textColor != Color.WHITE) {
            binding.tvTimeHeader.setTextColor(settings.textColor)
        }

        applyTextGravity(settings.textAlignment, binding.tvTimeHeader, binding.tvLocation, binding.tvCoordinates)

        binding.ivMapBadge.setImageResource(getMapDrawableRes(settings.mapType))
        binding.layoutMapBadge.isVisible = settings.showMapBadge && !isNoGeotag

        return binding.root
    }

    private fun bindBigBenMinimal(data: StampData, settings: StampSettings, isLivePreview: Boolean = false, isForVideo: Boolean = false): View {
        val binding = if (isLivePreview) {
            bigBenMinimalPreviewBinding ?: StampTemplateBigBenMinimalBinding.inflate(inflater, dummyParent, false).also { bigBenMinimalPreviewBinding = it }
        } else if (isForVideo) {
            bigBenMinimalVideoBinding ?: StampTemplateBigBenMinimalBinding.inflate(inflater, dummyParent, false).also { bigBenMinimalVideoBinding = it }
        } else {
            StampTemplateBigBenMinimalBinding.inflate(inflater, dummyParent, false)
        }

        val alphaInt = (settings.opacityPercent.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        binding.root.background?.mutate()?.alpha = alphaInt

        binding.ivEditIcon.isVisible = isLivePreview && !isForVideo
        val isNoGeotag = data.coordinatesLine.equals("NO GEOTAG", ignoreCase = true) || data.rawLatitude == null

        val timePart = if (settings.showTimeZone && data.timeZoneLine.isNotBlank()) {
            "${data.timeLine.ifBlank { "3:45 PM" }} ${data.timeZoneLine}"
        } else {
            data.timeLine.ifBlank { "3:45 PM" }
        }
        binding.tvTime.text = timePart
        binding.tvTime.isVisible = settings.showTitleHeader

        val loc = if (isNoGeotag) {
            "Location Disabled"
        } else if (settings.showAddress) {
            data.addressLine.ifBlank { data.titleLine.ifBlank { "Current Location" } }
        } else {
            data.titleLine.ifBlank { data.addressLine.ifBlank { "Current Location" } }
        }
        binding.tvLocation.text = loc

        if (settings.textColor != Color.WHITE) {
            binding.tvTime.setTextColor(settings.textColor)
        }

        applyTextGravity(settings.textAlignment, binding.tvTime, binding.tvLocation)

        binding.ivMapBadge.setImageResource(getMapDrawableRes(settings.mapType))
        binding.ivMapBadge.isVisible = settings.showMapBadge && !isNoGeotag
        (binding.layoutTexts.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            val density = context.resources.displayMetrics.density
            lp.marginStart = if (settings.showMapBadge && !isNoGeotag) (10 * density).toInt() else 0
            binding.layoutTexts.layoutParams = lp
        }

        return binding.root
    }

    private fun bindGpsFullCard(data: StampData, settings: StampSettings, isLivePreview: Boolean = false, isForVideo: Boolean = false): View {
        val binding = if (isLivePreview) {
            gpsFullCardPreviewBinding ?: StampTemplateGpsFullCardBinding.inflate(inflater, dummyParent, false).also { gpsFullCardPreviewBinding = it }
        } else if (isForVideo) {
            gpsFullCardVideoBinding ?: StampTemplateGpsFullCardBinding.inflate(inflater, dummyParent, false).also { gpsFullCardVideoBinding = it }
        } else {
            StampTemplateGpsFullCardBinding.inflate(inflater, dummyParent, false)
        }

        val alphaInt = (settings.opacityPercent.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        binding.root.background?.mutate()?.alpha = alphaInt

        binding.ivEditIcon.isVisible = isLivePreview && !isForVideo

        val isNoGeotag = data.coordinatesLine.equals("NO GEOTAG", ignoreCase = true) ||
                         data.titleLine.equals("Location Disabled", ignoreCase = true) ||
                         data.rawLatitude == null

        if (isNoGeotag) {
            binding.tvGpsStatus.text = "GEOSNAP"
            binding.tvGpsStatus.setTextColor(Color.WHITE)
            binding.tvGpsStatus.textSize = 12f

            binding.tvTopRightBadge.text = "NO GEOTAG"
            binding.tvTopRightBadge.setTextColor(Color.parseColor("#FFAA00"))
            binding.tvTopRightBadge.isVisible = true

            binding.viewDivider.isVisible = true

            binding.ivMapBadge.isVisible = false
            (binding.layoutTexts.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.marginStart = 0
                binding.layoutTexts.layoutParams = lp
            }

            binding.tvTitle.isVisible = false

            val timePart = data.timestampLine.ifBlank {
                "${data.dateLine} • ${data.timeLine}"
            }
            binding.tvAddress.text = timePart
            binding.tvAddress.setTextColor(Color.WHITE)
            binding.tvAddress.textSize = 12f
            binding.tvAddress.typeface = android.graphics.Typeface.DEFAULT_BOLD
            binding.tvAddress.isVisible = true

            binding.tvCoordinates.text = "Location Disabled"
            binding.tvCoordinates.setTextColor(Color.parseColor("#94A3B8"))
            binding.tvCoordinates.textSize = 10.5f
            binding.tvCoordinates.typeface = android.graphics.Typeface.DEFAULT
            binding.tvCoordinates.isVisible = true

            binding.tvTimestamp.isVisible = false
        } else {
            binding.tvGpsStatus.text = if (data.gpsStatusLine.isNotBlank()) "●  ${data.gpsStatusLine}" else "●  GPS"
            binding.tvGpsStatus.setTextColor(Color.parseColor("#10B981"))
            binding.tvGpsStatus.textSize = 10.5f

            binding.tvTopRightBadge.isVisible = false
            binding.viewDivider.isVisible = false

            binding.tvTitle.text = data.titleLine.ifBlank { "Current Location" }
            binding.tvTitle.setTextColor(if (settings.textColor != Color.WHITE) settings.textColor else Color.WHITE)
            binding.tvTitle.textSize = 12.5f
            binding.tvTitle.isVisible = settings.showTitleHeader

            binding.ivMapBadge.setImageResource(getMapDrawableRes(settings.mapType))
            binding.ivMapBadge.isVisible = settings.showMapBadge
            (binding.layoutTexts.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                val density = context.resources.displayMetrics.density
                lp.marginStart = if (settings.showMapBadge) (10 * density).toInt() else 0
                binding.layoutTexts.layoutParams = lp
            }

            binding.tvAddress.text = data.addressLine.ifBlank { data.titleLine.ifBlank { "Current Location" } }
            binding.tvAddress.setTextColor(Color.parseColor("#CBD5E1"))
            binding.tvAddress.textSize = 10f
            binding.tvAddress.typeface = android.graphics.Typeface.DEFAULT
            binding.tvAddress.isVisible = settings.showAddress

            binding.tvCoordinates.text = data.coordinatesLine.ifBlank { "GPS Coordinates" }
            binding.tvCoordinates.setTextColor(Color.parseColor("#38BDF8"))
            binding.tvCoordinates.textSize = 10f
            binding.tvCoordinates.typeface = android.graphics.Typeface.DEFAULT
            binding.tvCoordinates.isVisible = settings.showCoordinates

            binding.tvTimestamp.text = data.timestampLine
            binding.tvTimestamp.setTextColor(Color.parseColor("#94A3B8"))
            binding.tvTimestamp.textSize = 9.5f
            binding.tvTimestamp.isVisible = true
        }

        applyTextGravity(settings.textAlignment, binding.tvTitle, binding.tvAddress, binding.tvCoordinates, binding.tvTimestamp)

        return binding.root
    }

    private fun bindPisaPill(data: StampData, settings: StampSettings, isLivePreview: Boolean = false, isForVideo: Boolean = false): View {
        val binding = if (isLivePreview) {
            pisaPillPreviewBinding ?: StampTemplatePisaPillBinding.inflate(inflater, dummyParent, false).also { pisaPillPreviewBinding = it }
        } else if (isForVideo) {
            pisaPillVideoBinding ?: StampTemplatePisaPillBinding.inflate(inflater, dummyParent, false).also { pisaPillVideoBinding = it }
        } else {
            StampTemplatePisaPillBinding.inflate(inflater, dummyParent, false)
        }

        val alphaInt = (settings.opacityPercent.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        binding.root.background?.mutate()?.alpha = alphaInt

        binding.ivEditIcon.isVisible = isLivePreview && !isForVideo

        val timeStr = data.timeLine.ifBlank { "4:23 PM" }
        val timePart = if (settings.showTimeZone && data.timeZoneLine.isNotBlank()) {
            "⏰ $timeStr  ${data.timeZoneLine}"
        } else {
            "⏰ $timeStr"
        }
        binding.tvTime.text = timePart
        binding.tvTime.isVisible = settings.showTitleHeader

        val isNoGeotag = data.coordinatesLine.equals("NO GEOTAG", ignoreCase = true) || data.rawLatitude == null
        val loc = if (isNoGeotag) {
            "Location Disabled"
        } else if (settings.showAddress) {
            data.addressLine.ifBlank { data.titleLine.ifBlank { "Current Location" } }
        } else {
            data.titleLine.ifBlank { data.addressLine.ifBlank { "Current Location" } }
        }
        binding.tvLocation.text = loc

        if (settings.showCoordinates) {
            val coords = if (isNoGeotag) {
                "NO GEOTAG"
            } else if (data.rawLongitude != null) {
                String.format(Locale.US, "Lat: %.4f°    |    Long: %.4f°", data.rawLatitude, data.rawLongitude)
            } else {
                data.coordinatesLine.ifBlank { "Lat: --    |    Long: --" }
            }
            binding.tvCoordinates.text = coords
            binding.tvCoordinates.isVisible = true
        } else {
            binding.tvCoordinates.isVisible = false
        }

        if (settings.textColor != Color.WHITE) {
            binding.tvTime.setTextColor(settings.textColor)
        }

        applyTextGravity(settings.textAlignment, binding.tvTime, binding.tvLocation, binding.tvCoordinates)

        return binding.root
    }

    private fun bindNotreDameAlarm(data: StampData, settings: StampSettings, isLivePreview: Boolean = false, isForVideo: Boolean = false): View {
        val binding = if (isLivePreview) {
            notreDameAlarmPreviewBinding ?: StampTemplateNotreDameAlarmBinding.inflate(inflater, dummyParent, false).also { notreDameAlarmPreviewBinding = it }
        } else if (isForVideo) {
            notreDameAlarmVideoBinding ?: StampTemplateNotreDameAlarmBinding.inflate(inflater, dummyParent, false).also { notreDameAlarmVideoBinding = it }
        } else {
            StampTemplateNotreDameAlarmBinding.inflate(inflater, dummyParent, false)
        }

        val alphaInt = (settings.opacityPercent.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        binding.root.background?.mutate()?.alpha = alphaInt

        binding.ivEditIcon.isVisible = isLivePreview && !isForVideo

        val timeStr = data.timeLine.ifBlank { "4:23 PM" }
        val timePart = if (settings.showTimeZone && data.timeZoneLine.isNotBlank()) {
            "⏰ $timeStr  ${data.timeZoneLine}"
        } else {
            "⏰ $timeStr"
        }
        binding.tvTime.text = timePart
        binding.tvTime.isVisible = settings.showTitleHeader

        val isNoGeotag = data.coordinatesLine.equals("NO GEOTAG", ignoreCase = true) || data.rawLatitude == null
        val loc = if (isNoGeotag) {
            "Location Disabled"
        } else if (settings.showAddress) {
            data.addressLine.ifBlank { data.titleLine.ifBlank { "Current Location" } }
        } else {
            data.titleLine.ifBlank { data.addressLine.ifBlank { "Current Location" } }
        }
        binding.tvLocation.text = loc

        if (settings.textColor != Color.WHITE) {
            binding.tvTime.setTextColor(settings.textColor)
        }

        applyTextGravity(settings.textAlignment, binding.tvTime, binding.tvLocation)

        binding.ivMapBadge.setImageResource(getMapDrawableRes(settings.mapType))
        binding.ivMapBadge.isVisible = settings.showMapBadge && !isNoGeotag
        (binding.layoutTexts.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            val density = context.resources.displayMetrics.density
            lp.marginStart = if (settings.showMapBadge && !isNoGeotag) (10 * density).toInt() else 0
            binding.layoutTexts.layoutParams = lp
        }

        return binding.root
    }

    private fun bindCathedralLeftMap(data: StampData, settings: StampSettings, isLivePreview: Boolean = false, isForVideo: Boolean = false): View {
        val binding = if (isLivePreview) {
            cathedralLeftMapPreviewBinding ?: StampTemplateCathedralLeftMapBinding.inflate(inflater, dummyParent, false).also { cathedralLeftMapPreviewBinding = it }
        } else if (isForVideo) {
            cathedralLeftMapVideoBinding ?: StampTemplateCathedralLeftMapBinding.inflate(inflater, dummyParent, false).also { cathedralLeftMapVideoBinding = it }
        } else {
            StampTemplateCathedralLeftMapBinding.inflate(inflater, dummyParent, false)
        }

        val alphaInt = (settings.opacityPercent.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        binding.root.background?.mutate()?.alpha = alphaInt

        binding.ivEditIcon.isVisible = isLivePreview && !isForVideo

        val isNoGeotag = data.coordinatesLine.equals("NO GEOTAG", ignoreCase = true) || data.rawLatitude == null
        val loc = if (isNoGeotag) {
            "Location Disabled"
        } else if (settings.showAddress) {
            data.addressLine.ifBlank { data.titleLine.ifBlank { "Current Location" } }
        } else {
            data.titleLine.ifBlank { data.addressLine.ifBlank { "Current Location" } }
        }
        binding.tvLocation.text = loc
        binding.tvLocation.isVisible = settings.showTitleHeader

        val datePart = data.dateLine.ifBlank { "24/10/2025" }
        binding.tvDate.text = if (settings.showTimeZone && data.timeZoneLine.isNotBlank()) {
            "📅 $datePart • ${data.timeZoneLine}"
        } else {
            "📅 $datePart"
        }

        if (settings.textColor != Color.WHITE) {
            binding.tvDate.setTextColor(settings.textColor)
        }

        applyTextGravity(settings.textAlignment, binding.tvLocation, binding.tvDate)

        binding.ivMapBadge.setImageResource(getMapDrawableRes(settings.mapType))
        binding.ivMapBadge.isVisible = settings.showMapBadge && !isNoGeotag
        (binding.layoutTexts.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            val density = context.resources.displayMetrics.density
            lp.marginStart = if (settings.showMapBadge && !isNoGeotag) (10 * density).toInt() else 0
            binding.layoutTexts.layoutParams = lp
        }

        return binding.root
    }

    private fun bindPisaPinCard(data: StampData, settings: StampSettings, isLivePreview: Boolean = false, isForVideo: Boolean = false): View {
        val binding = if (isLivePreview) {
            pisaPinCardPreviewBinding ?: StampTemplatePisaPinCardBinding.inflate(inflater, dummyParent, false).also { pisaPinCardPreviewBinding = it }
        } else if (isForVideo) {
            pisaPinCardVideoBinding ?: StampTemplatePisaPinCardBinding.inflate(inflater, dummyParent, false).also { pisaPinCardVideoBinding = it }
        } else {
            StampTemplatePisaPinCardBinding.inflate(inflater, dummyParent, false)
        }

        val alphaInt = (settings.opacityPercent.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        binding.root.background?.mutate()?.alpha = alphaInt

        binding.ivEditIcon.isVisible = isLivePreview && !isForVideo

        val isNoGeotag = data.coordinatesLine.equals("NO GEOTAG", ignoreCase = true) || data.rawLatitude == null
        val loc = if (isNoGeotag) {
            "Location Disabled"
        } else if (settings.showAddress) {
            data.addressLine.ifBlank { data.titleLine.ifBlank { "Current Location" } }
        } else {
            data.titleLine.ifBlank { data.addressLine.ifBlank { "Current Location" } }
        }
        binding.tvLocation.text = loc

        val timeStr = data.timeLine.ifBlank { "4:23 PM" }
        val timePart = if (settings.showTimeZone && data.timeZoneLine.isNotBlank()) {
            "⏰ $timeStr  ${data.timeZoneLine}"
        } else {
            "⏰ $timeStr"
        }
        binding.tvTime.text = timePart
        binding.tvTime.isVisible = settings.showTitleHeader

        if (settings.textColor != Color.WHITE) {
            binding.tvTime.setTextColor(settings.textColor)
        }

        applyTextGravity(settings.textAlignment, binding.tvLocation, binding.tvTime)

        binding.ivMapBadge.setImageResource(getMapDrawableRes(settings.mapType))
        binding.layoutMapBadge.isVisible = settings.showMapBadge && !isNoGeotag
        (binding.layoutTexts.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            val density = context.resources.displayMetrics.density
            lp.marginStart = if (settings.showMapBadge && !isNoGeotag) (10 * density).toInt() else 0
            binding.layoutTexts.layoutParams = lp
        }

        return binding.root
    }

    private fun bindCyberHud(data: StampData, settings: StampSettings, isLivePreview: Boolean = false, isForVideo: Boolean = false): View {
        val binding = if (isLivePreview) {
            cyberHudPreviewBinding ?: StampTemplateCyberHudBinding.inflate(inflater, dummyParent, false).also { cyberHudPreviewBinding = it }
        } else if (isForVideo) {
            cyberHudVideoBinding ?: StampTemplateCyberHudBinding.inflate(inflater, dummyParent, false).also { cyberHudVideoBinding = it }
        } else {
            StampTemplateCyberHudBinding.inflate(inflater, dummyParent, false)
        }

        val alphaInt = (settings.opacityPercent.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        binding.root.background?.mutate()?.alpha = alphaInt

        binding.ivEditIcon.isVisible = isLivePreview && !isForVideo
        val isNoGeotag = data.coordinatesLine.equals("NO GEOTAG", ignoreCase = true) || data.rawLatitude == null

        val altStr = data.rawAltitudeMeters?.toInt()?.let { "${it}M" } ?: "--"
        binding.tvGpsLive.text = if (isNoGeotag) {
            "●  NO GEOTAG  •  LOCATION DISABLED"
        } else if (data.gpsStatusLine.isNotBlank()) {
            "●  ${data.gpsStatusLine.uppercase(Locale.US)}  •  ALT: $altStr"
        } else {
            "●  GPS LIVE  •  ALT: $altStr"
        }

        val loc = if (isNoGeotag) {
            "Location Disabled"
        } else if (settings.showAddress) {
            data.addressLine.ifBlank { data.titleLine.ifBlank { "Current Location" } }
        } else {
            data.titleLine.ifBlank { data.addressLine.ifBlank { "Current Location" } }
        }
        binding.tvLocation.text = loc
        binding.tvLocation.isVisible = settings.showTitleHeader

        binding.tvCoordinates.text = data.coordinatesLine.ifBlank { "NO GEOTAG" }
        binding.tvCoordinates.isVisible = settings.showCoordinates

        binding.tvTimestamp.text = data.timestampLine.ifBlank { "" }

        if (settings.textColor != Color.WHITE) {
            binding.tvLocation.setTextColor(settings.textColor)
        }

        applyTextGravity(settings.textAlignment, binding.tvLocation, binding.tvCoordinates, binding.tvTimestamp)

        binding.ivMapBadge.setImageResource(getMapDrawableRes(settings.mapType))
        binding.ivMapBadge.isVisible = settings.showMapBadge

        return binding.root
    }

    private fun bindRetroCam(data: StampData, settings: StampSettings, isLivePreview: Boolean = false, isForVideo: Boolean = false): View {
        val binding = if (isLivePreview) {
            retroCamPreviewBinding ?: StampTemplateRetroCamBinding.inflate(inflater, dummyParent, false).also { retroCamPreviewBinding = it }
        } else if (isForVideo) {
            retroCamVideoBinding ?: StampTemplateRetroCamBinding.inflate(inflater, dummyParent, false).also { retroCamVideoBinding = it }
        } else {
            StampTemplateRetroCamBinding.inflate(inflater, dummyParent, false)
        }

        val alphaInt = (settings.opacityPercent.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        binding.root.background?.mutate()?.alpha = alphaInt

        binding.ivEditIcon.isVisible = isLivePreview && !isForVideo

        val dateText = if (data.dateLine.isNotBlank() && data.timeLine.isNotBlank()) {
            val parts = data.dateLine.split("/", "-", ".", " ")
            val formattedDate = if (parts.size >= 3) {
                val yr = parts[2].takeLast(2)
                val mo = parts[1].padStart(2, '0')
                val dy = parts[0].padStart(2, '0')
                "'$yr $mo $dy"
            } else {
                data.dateLine
            }
            "$formattedDate   ${data.timeLine}"
        } else {
            data.timestampLine.ifBlank { "" }
        }
        binding.tvDigitalDate.text = dateText

        val isNoGeotag = data.coordinatesLine.equals("NO GEOTAG", ignoreCase = true) || data.rawLatitude == null
        val loc = if (isNoGeotag) {
            "Location Disabled"
        } else if (settings.showAddress) {
            data.addressLine.ifBlank { data.titleLine.ifBlank { "Current Location" } }
        } else {
            data.titleLine.ifBlank { data.addressLine.ifBlank { "Current Location" } }
        }
        val fullLoc = if (settings.showCoordinates && data.coordinatesLine.isNotBlank() && !isNoGeotag) {
            "📍 $loc  •  ${data.coordinatesLine}"
        } else {
            "📍 $loc"
        }
        binding.tvLocation.text = fullLoc

        if (settings.textColor != Color.WHITE) {
            binding.tvDigitalDate.setTextColor(settings.textColor)
        }

        applyTextGravity(settings.textAlignment, binding.tvDigitalDate, binding.tvLocation)

        return binding.root
    }

    private fun bindFrostedBlur(data: StampData, settings: StampSettings, isLivePreview: Boolean = false, isForVideo: Boolean = false): View {
        val binding = if (isLivePreview) {
            frostedBlurPreviewBinding ?: StampTemplateFrostedBlurBinding.inflate(inflater, dummyParent, false).also { frostedBlurPreviewBinding = it }
        } else if (isForVideo) {
            frostedBlurVideoBinding ?: StampTemplateFrostedBlurBinding.inflate(inflater, dummyParent, false).also { frostedBlurVideoBinding = it }
        } else {
            StampTemplateFrostedBlurBinding.inflate(inflater, dummyParent, false)
        }

        val alphaInt = (settings.opacityPercent.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        binding.root.background?.mutate()?.alpha = alphaInt

        binding.ivEditIcon.isVisible = isLivePreview && !isForVideo

        val isNoGeotag = data.coordinatesLine.equals("NO GEOTAG", ignoreCase = true) || data.rawLatitude == null

        val loc = if (isNoGeotag) {
            "Location Disabled"
        } else if (settings.showAddress) {
            data.addressLine.ifBlank { data.titleLine.ifBlank { "Current Location" } }
        } else {
            data.titleLine.ifBlank { data.addressLine.ifBlank { "Current Location" } }
        }
        binding.tvLocation.text = loc
        binding.tvLocation.isVisible = settings.showTitleHeader

        val coordsText = if (isNoGeotag) {
            "NO GEOTAG"
        } else if (data.rawLongitude != null) {
            com.autonoumus.ai.core.util.OsmGeocodingHelper.formatLatitude(data.rawLatitude) + ", " +
                com.autonoumus.ai.core.util.OsmGeocodingHelper.formatLongitude(data.rawLongitude)
        } else {
            data.coordinatesLine.ifBlank { "NO GEOTAG" }
        }
        binding.tvCoordinates.text = coordsText
        binding.tvCoordinates.isVisible = settings.showCoordinates

        val dateText = data.dateLine.ifBlank { "14 DEC 2024" }.uppercase(Locale.US)
        binding.tvDate.text = dateText
        binding.tvDate.isVisible = dateText.isNotBlank()

        val timeText = if (settings.showTimeZone && data.timeZoneLine.isNotBlank()) {
            "${data.timeLine.ifBlank { "09:42 AM" }} ${data.timeZoneLine}"
        } else {
            data.timeLine.ifBlank { "09:42 AM" }
        }
        binding.tvTime.text = timeText
        binding.tvTime.isVisible = timeText.isNotBlank()

        if (settings.textColor != Color.WHITE) {
            binding.tvLocation.setTextColor(settings.textColor)
            binding.tvDate.setTextColor(settings.textColor)
        }

        applyTextGravity(settings.textAlignment, binding.tvLocation, binding.tvCoordinates)

        binding.ivMapBadge.setImageResource(getMapDrawableRes(settings.mapType))
        binding.layoutMapFrame.isVisible = settings.showMapBadge && !isNoGeotag
        (binding.layoutTexts.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            val density = context.resources.displayMetrics.density
            lp.marginStart = if (settings.showMapBadge && !isNoGeotag) (12 * density).toInt() else 0
            binding.layoutTexts.layoutParams = lp
        }

        return binding.root
    }
}
