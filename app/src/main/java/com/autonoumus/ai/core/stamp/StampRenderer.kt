package com.autonoumus.ai.core.stamp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.autonoumus.ai.domain.model.MapTypeOption
import com.autonoumus.ai.domain.model.StampData
import com.autonoumus.ai.domain.model.StampFontStyle
import com.autonoumus.ai.domain.model.StampPosition
import com.autonoumus.ai.domain.model.StampSettings
import com.autonoumus.ai.domain.model.StampTemplate
import com.autonoumus.ai.domain.model.StampTextAlignment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StampRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val xmlStampRenderer: XmlStampRenderer
) {

    var useXmlRenderer: Boolean = true
    private val typefaceCache = mutableMapOf<Int, Typeface>()

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
        if (useXmlRenderer) {
            try {
                xmlStampRenderer.draw(canvas, canvasWidth, canvasHeight, data, settings, isLivePreview, isLoading, isForVideo)
                return
            } catch (e: Exception) {
                android.util.Log.e("StampRenderer", "XML Stamp rendering failed with error: ${e.message}", e)
                // Fallback to direct canvas rendering if XML inflation encounters an issue
                // (legacy path below doesn't support the loading shimmer - shows real/placeholder
                // content instead, a harmless degradation for this rare exception-only fallback)
            }
        }
        when (settings.template) {
            StampTemplate.PARIS_GLASS -> drawParisGlass(canvas, canvasWidth, canvasHeight, data, settings)
            StampTemplate.LONDON_CATHEDRAL,
            StampTemplate.CATHEDRAL_LEFT_MAP -> drawLondonCathedral(canvas, canvasWidth, canvasHeight, data, settings)
            StampTemplate.PISA_CAPSULE,
            StampTemplate.PISA_PILL,
            StampTemplate.PISA_PIN_CARD -> drawPisaCapsule(canvas, canvasWidth, canvasHeight, data, settings)
            StampTemplate.BIG_BEN_MINIMAL,
            StampTemplate.NOTRE_DAME_ALARM,
            StampTemplate.RETRO_CAM -> drawMonumentMinimal(canvas, canvasWidth, canvasHeight, data, settings)
            StampTemplate.GPS_FULL_CARD,
            StampTemplate.CYBER_HUD -> drawGpsFullCard(canvas, canvasWidth, canvasHeight, data, settings)
            StampTemplate.FROSTED_BLUR -> drawParisGlass(canvas, canvasWidth, canvasHeight, data, settings)
        }
    }

    // =========================================================================
    // 1. PARIS GLASS CARD (Rounded glass banner with left map + Date UTC on top + Location below)
    // =========================================================================
    private fun drawParisGlass(canvas: Canvas, w: Int, h: Int, data: StampData, settings: StampSettings) {
        val isPortrait = h >= (w * 0.90f)
        val cardWidth = if (isPortrait) w * 0.88f else w * 0.58f
        val cardHeight = (if (isPortrait) w * 0.20f else h * 0.18f).coerceAtMost(h * 0.30f)
        val padding = cardHeight * 0.13f

        val rect = computeCardRect(w, h, cardWidth, cardHeight, settings.position, isPortrait)
        drawCardBackground(canvas, rect, cardHeight * 0.28f, settings)

        // Left Map Badge
        val textLeft: Float
        val maxTextWidth: Float
        if (settings.showMapBadge) {
            val badgeSize = cardHeight - padding * 2f
            val badgeRect = RectF(
                rect.left + padding * 1.15f,
                rect.top + padding,
                rect.left + padding * 1.15f + badgeSize,
                rect.top + padding + badgeSize
            )
            drawMapThumbnail(canvas, badgeRect, settings.mapType)
            textLeft = badgeRect.right + padding * 1.35f
            maxTextWidth = (rect.right - padding * 1.2f - textLeft).coerceAtLeast(10f)
        } else {
            textLeft = rect.left + padding * 1.5f
            maxTextWidth = (rect.right - padding * 1.5f - textLeft).coerceAtLeast(10f)
        }

        // Top line: Date & Time/TimeZone (e.g. "24/10/2025   UTC+1")
        val datePart = data.dateLine.ifBlank { "24/10/2025" }
        val timeZonePart = data.timeZoneLine.ifBlank { "UTC+1" }
        val timePart = data.timeLine.ifBlank { "03:09 PM" }
        val topHeaderText = when {
            settings.showTimeZone -> "$datePart   $timeZonePart"
            else -> "$datePart   $timePart"
        }

        // Bottom line: Location (e.g. "Eiffel Tower, 5 Av. Anatole, Paris")
        val locationText = if (settings.showAddress) {
            data.addressLine.ifBlank { data.titleLine.ifBlank { "Eiffel Tower, 5 Av. Anatole, Paris" } }
        } else {
            data.titleLine.ifBlank { data.addressLine.ifBlank { "Eiffel Tower, 5 Av. Anatole, Paris" } }
        }

        val headerPaint = createTextPaint(settings, cardHeight * 0.245f, bold = true).apply {
            color = ColorUtils.setAlphaComponent(settings.textColor, applyOpacity(255, settings))
        }
        val locationPaint = createTextPaint(settings, cardHeight * 0.165f, bold = false).apply {
            color = ColorUtils.setAlphaComponent(Color.parseColor("#E5E7EB"), applyOpacity(235, settings))
        }

        applyAlignment(headerPaint, settings.textAlignment)
        applyAlignment(locationPaint, settings.textAlignment)

        val drawX = computeTextDrawX(textLeft, maxTextWidth, settings.textAlignment)

        if (settings.showTitleHeader) {
            val line1Y = rect.top + cardHeight * 0.44f
            val line2Y = rect.top + cardHeight * 0.78f
            canvas.drawText(truncateText(headerPaint, topHeaderText, maxTextWidth), drawX, line1Y, headerPaint)
            canvas.drawText(truncateText(locationPaint, locationText, maxTextWidth), drawX, line2Y, locationPaint)
        } else {
            val lineY = rect.top + cardHeight * 0.58f
            canvas.drawText(truncateText(headerPaint, locationText, maxTextWidth), drawX, lineY, headerPaint)
        }
    }

    // =========================================================================
    // 2. LONDON CATHEDRAL CARD (Location on top + Calendar Date below + optional right map)
    // =========================================================================
    private fun drawLondonCathedral(canvas: Canvas, w: Int, h: Int, data: StampData, settings: StampSettings) {
        val isPortrait = h >= (w * 0.90f)
        val cardWidth = if (isPortrait) w * 0.88f else w * 0.58f
        val cardHeight = (if (isPortrait) w * 0.24f else h * 0.22f).coerceAtMost(h * 0.30f)
        val padding = cardHeight * 0.12f

        val rect = computeCardRect(w, h, cardWidth, cardHeight, settings.position, isPortrait)
        drawCardBackground(canvas, rect, cardHeight * 0.20f, settings)

        // Right Map Badge
        val textLeft = rect.left + padding * 1.3f
        val maxTextWidth: Float
        if (settings.showMapBadge) {
            val badgeSize = cardHeight - padding * 2
            val badgeRect = RectF(
                rect.right - padding * 1.2f - badgeSize,
                rect.top + padding,
                rect.right - padding * 1.2f,
                rect.top + padding + badgeSize
            )
            drawMapThumbnail(canvas, badgeRect, settings.mapType)
            maxTextWidth = (badgeRect.left - padding * 1.1f - textLeft).coerceAtLeast(10f)
        } else {
            maxTextWidth = (rect.right - padding * 1.3f - textLeft).coerceAtLeast(10f)
        }

        val locationText = if (settings.showAddress) {
            data.addressLine.ifBlank { data.titleLine.ifBlank { "Location Disabled" } }
        } else {
            data.titleLine.ifBlank { "Location Disabled" }
        }

        val datePart = data.dateLine.ifBlank { "24/10/2026" }
        val dateText = if (settings.showTimeZone) {
            "📅 $datePart • ${data.timeZoneLine.ifBlank { "UTC" }}"
        } else {
            "📅 $datePart • ${data.timeLine.ifBlank { "03:09 PM" }}"
        }

        val addrPaint = createTextPaint(settings, cardHeight * 0.135f, bold = false).apply {
            color = ColorUtils.setAlphaComponent(Color.parseColor("#E5E7EB"), applyOpacity(220, settings))
        }
        val datePaint = createTextPaint(settings, cardHeight * 0.19f, bold = true).apply {
            color = ColorUtils.setAlphaComponent(settings.textColor, applyOpacity(255, settings))
        }

        applyAlignment(addrPaint, settings.textAlignment)
        applyAlignment(datePaint, settings.textAlignment)

        val drawX = computeTextDrawX(textLeft, maxTextWidth, settings.textAlignment)

        if (settings.showTitleHeader) {
            val textTop = rect.top + cardHeight * 0.38f
            canvas.drawText(truncateText(addrPaint, locationText, maxTextWidth), drawX, textTop, addrPaint)
            val secondLineTop = textTop + cardHeight * 0.28f
            canvas.drawText(truncateText(datePaint, dateText, maxTextWidth), drawX, secondLineTop, datePaint)

            if (settings.showCoordinates && data.coordinatesLine.isNotBlank()) {
                val coordsPaint = createTextPaint(settings, cardHeight * 0.10f, bold = false).apply {
                    color = ColorUtils.setAlphaComponent(Color.parseColor("#38BDF8"), applyOpacity(210, settings))
                }
                applyAlignment(coordsPaint, settings.textAlignment)
                canvas.drawText(truncateText(coordsPaint, data.coordinatesLine, maxTextWidth), drawX, secondLineTop + cardHeight * 0.20f, coordsPaint)
            }
        } else {
            // Header hidden: show location and coords
            val prominentPaint = createTextPaint(settings, cardHeight * 0.17f, bold = true).apply {
                color = ColorUtils.setAlphaComponent(settings.textColor, applyOpacity(255, settings))
            }
            applyAlignment(prominentPaint, settings.textAlignment)
            val textTop = rect.top + cardHeight * 0.48f
            canvas.drawText(truncateText(prominentPaint, locationText, maxTextWidth), drawX, textTop, prominentPaint)

            if (settings.showCoordinates && data.coordinatesLine.isNotBlank()) {
                val coordsPaint = createTextPaint(settings, cardHeight * 0.11f, bold = false).apply {
                    color = ColorUtils.setAlphaComponent(Color.parseColor("#38BDF8"), applyOpacity(210, settings))
                }
                applyAlignment(coordsPaint, settings.textAlignment)
                canvas.drawText(truncateText(coordsPaint, data.coordinatesLine, maxTextWidth), drawX, textTop + cardHeight * 0.24f, coordsPaint)
            }
        }
    }

    // =========================================================================
    // 3. PISA CAPSULE CARD (Center top map pin badge + Clock Time + Location + Lat/Long)
    // =========================================================================
    private fun drawPisaCapsule(canvas: Canvas, w: Int, h: Int, data: StampData, settings: StampSettings) {
        val isPortrait = h >= (w * 0.90f)
        val cardWidth = if (isPortrait) w * 0.88f else w * 0.58f
        val cardHeight = (if (isPortrait) w * 0.26f else h * 0.24f).coerceAtMost(h * 0.30f)
        val padding = cardHeight * 0.12f

        val rect = computeCardRect(w, h, cardWidth, cardHeight, settings.position, isPortrait)
        drawCardBackground(canvas, rect, cardHeight / 2f, settings)

        // Top center circular map badge popping above capsule
        if (settings.showMapBadge) {
            val pinBadgeRadius = cardHeight * 0.24f
            val pinBadgeCenterX = rect.centerX()
            val pinBadgeCenterY = rect.top
            val pinBadgeRect = RectF(
                pinBadgeCenterX - pinBadgeRadius,
                pinBadgeCenterY - pinBadgeRadius,
                pinBadgeCenterX + pinBadgeRadius,
                pinBadgeCenterY + pinBadgeRadius
            )
            drawCircularMapBadge(canvas, pinBadgeRect, settings.mapType)
        }

        // Dynamic strings
        val timeStr = data.timeLine.ifBlank { "4:23 PM" }
        val timeString = if (settings.showTimeZone && data.timeZoneLine.isNotBlank()) {
            "⏰ $timeStr  ${data.timeZoneLine}"
        } else {
            "⏰ $timeStr"
        }

        val locationText = if (settings.showAddress) {
            data.addressLine.ifBlank { data.titleLine }
        } else {
            data.titleLine.ifBlank { data.addressLine }
        }

        val coordsText = if (data.rawLatitude != null && data.rawLongitude != null) {
            String.format(Locale.US, "Lat: %.4f°    |    Long: %.4f°", data.rawLatitude, data.rawLongitude)
        } else {
            data.coordinatesLine
        }

        val timePaint = createTextPaint(settings, cardHeight * 0.17f, bold = true).apply {
            color = ColorUtils.setAlphaComponent(settings.textColor, applyOpacity(255, settings))
            textAlign = Paint.Align.CENTER
        }
        val addrPaint = createTextPaint(settings, cardHeight * 0.12f, bold = false).apply {
            color = ColorUtils.setAlphaComponent(Color.parseColor("#E5E7EB"), applyOpacity(225, settings))
            textAlign = Paint.Align.CENTER
        }
        val coordsPaint = createTextPaint(settings, cardHeight * 0.115f, bold = false).apply {
            color = ColorUtils.setAlphaComponent(Color.parseColor("#CBD5E1"), applyOpacity(230, settings))
            textAlign = Paint.Align.CENTER
        }

        val cx = rect.centerX()
        val maxTextWidth = cardWidth - padding * 2.2f

        val startY = if (settings.showMapBadge) rect.top + cardHeight * 0.38f else rect.top + cardHeight * 0.28f
        var y = startY

        if (settings.showTitleHeader) {
            canvas.drawText(truncateText(timePaint, timeString, maxTextWidth), cx, y, timePaint)
            y += cardHeight * 0.23f
        }

        canvas.drawText(truncateText(addrPaint, locationText, maxTextWidth), cx, y, addrPaint)

        if (settings.showCoordinates) {
            y += cardHeight * 0.22f
            canvas.drawText(truncateText(coordsPaint, coordsText, maxTextWidth), cx, y, coordsPaint)
        }
    }

    // =========================================================================
    // 4. MONUMENT MINIMAL CARD (Prominent Time + Location + Right Map Badge)
    // =========================================================================
    private fun drawMonumentMinimal(canvas: Canvas, w: Int, h: Int, data: StampData, settings: StampSettings) {
        val isPortrait = h >= (w * 0.90f)
        val cardWidth = if (isPortrait) w * 0.88f else w * 0.58f
        val cardHeight = (if (isPortrait) w * 0.22f else h * 0.20f).coerceAtMost(h * 0.30f)
        val padding = cardHeight * 0.12f

        val rect = computeCardRect(w, h, cardWidth, cardHeight, settings.position, isPortrait)
        drawCardBackground(canvas, rect, cardHeight * 0.20f, settings)

        // Right map badge
        val textLeft = rect.left + padding * 1.3f
        val maxTextWidth: Float
        if (settings.showMapBadge) {
            val badgeSize = cardHeight - padding * 2
            val badgeRect = RectF(
                rect.right - padding * 1.2f - badgeSize,
                rect.top + padding,
                rect.right - padding * 1.2f,
                rect.top + padding + badgeSize
            )
            drawMapThumbnail(canvas, badgeRect, settings.mapType)
            maxTextWidth = (badgeRect.left - padding * 1.1f - textLeft).coerceAtLeast(10f)
        } else {
            maxTextWidth = (rect.right - padding * 1.3f - textLeft).coerceAtLeast(10f)
        }

        val timeString = if (settings.showTimeZone) {
            "${data.timeLine.ifBlank { "03:09 PM" }} ${data.timeZoneLine.ifBlank { "PKT" }}"
        } else {
            data.timeLine.ifBlank { "03:09 PM" }
        }

        val locationText = if (settings.showAddress) {
            data.addressLine.ifBlank { data.titleLine.ifBlank { "Location Disabled" } }
        } else {
            data.titleLine.ifBlank { "Location Disabled" }
        }

        val timePaint = createTextPaint(settings, cardHeight * 0.22f, bold = true).apply {
            color = ColorUtils.setAlphaComponent(settings.textColor, applyOpacity(255, settings))
        }
        val addrPaint = createTextPaint(settings, cardHeight * 0.13f, bold = false).apply {
            color = ColorUtils.setAlphaComponent(Color.parseColor("#E5E7EB"), applyOpacity(220, settings))
        }

        applyAlignment(timePaint, settings.textAlignment)
        applyAlignment(addrPaint, settings.textAlignment)

        val drawX = computeTextDrawX(textLeft, maxTextWidth, settings.textAlignment)

        if (settings.showTitleHeader) {
            val textTop = rect.top + cardHeight * 0.40f
            canvas.drawText(truncateText(timePaint, timeString, maxTextWidth), drawX, textTop, timePaint)
            val secondLineTop = textTop + cardHeight * 0.28f
            canvas.drawText(truncateText(addrPaint, locationText, maxTextWidth), drawX, secondLineTop, addrPaint)

            if (settings.showCoordinates && data.coordinatesLine.isNotBlank()) {
                val coordsPaint = createTextPaint(settings, cardHeight * 0.10f, bold = false).apply {
                    color = ColorUtils.setAlphaComponent(Color.parseColor("#38BDF8"), applyOpacity(210, settings))
                }
                applyAlignment(coordsPaint, settings.textAlignment)
                canvas.drawText(truncateText(coordsPaint, data.coordinatesLine, maxTextWidth), drawX, secondLineTop + cardHeight * 0.20f, coordsPaint)
            }
        } else {
            // Header hidden: location prominent
            val prominentPaint = createTextPaint(settings, cardHeight * 0.18f, bold = true).apply {
                color = ColorUtils.setAlphaComponent(settings.textColor, applyOpacity(255, settings))
            }
            applyAlignment(prominentPaint, settings.textAlignment)
            val textTop = rect.top + cardHeight * 0.46f
            canvas.drawText(truncateText(prominentPaint, locationText, maxTextWidth), drawX, textTop, prominentPaint)

            if (settings.showCoordinates && data.coordinatesLine.isNotBlank()) {
                val coordsPaint = createTextPaint(settings, cardHeight * 0.11f, bold = false).apply {
                    color = ColorUtils.setAlphaComponent(Color.parseColor("#38BDF8"), applyOpacity(210, settings))
                }
                applyAlignment(coordsPaint, settings.textAlignment)
                canvas.drawText(truncateText(coordsPaint, data.coordinatesLine, maxTextWidth), drawX, textTop + cardHeight * 0.26f, coordsPaint)
            }
        }
    }

    // =========================================================================
    // 5. GPS FULL CARD (Primary GSM Camera screen stamp)
    // =========================================================================
    private fun drawGpsFullCard(canvas: Canvas, w: Int, h: Int, data: StampData, settings: StampSettings) {
        val isPortrait = h >= (w * 0.90f)
        val cardWidth = if (isPortrait) w * 0.88f else w * 0.58f
        val cardHeight = (if (isPortrait) w * 0.28f else h * 0.24f).coerceAtMost(h * 0.32f)
        val padding = cardHeight * 0.09f
        val accentGreen = Color.parseColor("#10B981")
        val accentCyan = Color.parseColor("#38BDF8")

        val rect = computeCardRect(w, h, cardWidth, cardHeight, settings.position, isPortrait)
        drawCardBackground(canvas, rect, cardHeight * 0.14f, settings)

        // Top Status Line & Edit Button
        val statusText = if (data.gpsStatusLine.isNotBlank()) "●  ${data.gpsStatusLine}" else "●  GPS"
        val statusPaint = createTextPaint(settings, cardHeight * 0.105f, bold = true).apply {
            color = ColorUtils.setAlphaComponent(accentGreen, applyOpacity(255, settings))
        }
        val topStatusY = rect.top + padding + statusPaint.textSize * 0.95f
        canvas.drawText(statusText, rect.left + padding * 1.3f, topStatusY, statusPaint)

        // Edit pencil circle button on top right
        val editCircleRadius = cardHeight * 0.095f
        val editCenterX = rect.right - padding * 1.3f - editCircleRadius
        val editCenterY = topStatusY - editCircleRadius * 0.3f
        val editBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(Color.WHITE, applyOpacity(35, settings))
        }
        canvas.drawCircle(editCenterX, editCenterY, editCircleRadius, editBgPaint)
        val pencilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(Color.WHITE, applyOpacity(255, settings))
            textSize = editCircleRadius * 1.15f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("✎", editCenterX, editCenterY + editCircleRadius * 0.38f, pencilPaint)

        val bodyTop = topStatusY + cardHeight * 0.08f

        // Body: Map Badge on Left
        val textLeft: Float
        val maxTextWidth: Float
        if (settings.showMapBadge) {
            val badgeSize = (rect.bottom - padding - bodyTop).coerceAtMost(cardHeight * 0.58f).coerceAtLeast(cardHeight * 0.40f)
            val badgeRect = RectF(
                rect.left + padding * 1.3f,
                bodyTop,
                rect.left + padding * 1.3f + badgeSize,
                bodyTop + badgeSize
            )
            drawMapThumbnail(canvas, badgeRect, settings.mapType)
            textLeft = badgeRect.right + padding * 1.2f
            maxTextWidth = (rect.right - padding * 1.2f - textLeft).coerceAtLeast(10f)
        } else {
            textLeft = rect.left + padding * 1.3f
            maxTextWidth = (rect.right - padding * 1.3f - textLeft).coerceAtLeast(10f)
        }

        // Dynamic texts
        val titleText = data.titleLine.ifBlank { "Location Disabled" }
        val addressText = data.addressLine.ifBlank { "Location Disabled" }
        val coordsText = data.coordinatesLine.ifBlank { "NO GEOTAG" }
        val timestampText = data.timestampLine.ifBlank { "" }

        val titlePaint = createTextPaint(settings, cardHeight * 0.13f, bold = true).apply {
            color = ColorUtils.setAlphaComponent(settings.textColor, applyOpacity(255, settings))
        }
        val addressPaint = createTextPaint(settings, cardHeight * 0.10f, bold = false).apply {
            color = ColorUtils.setAlphaComponent(Color.parseColor("#CBD5E1"), applyOpacity(190, settings))
        }
        val coordsPaint = createTextPaint(settings, cardHeight * 0.10f, bold = false).apply {
            color = ColorUtils.setAlphaComponent(accentCyan, applyOpacity(255, settings))
        }
        val timestampPaint = createTextPaint(settings, cardHeight * 0.10f, bold = false).apply {
            color = ColorUtils.setAlphaComponent(Color.parseColor("#94A3B8"), applyOpacity(210, settings))
        }

        applyAlignment(titlePaint, settings.textAlignment)
        applyAlignment(addressPaint, settings.textAlignment)
        applyAlignment(coordsPaint, settings.textAlignment)
        applyAlignment(timestampPaint, settings.textAlignment)

        val drawX = computeTextDrawX(textLeft, maxTextWidth, settings.textAlignment)

        val lines = mutableListOf<Pair<String, Paint>>()
        if (settings.showTitleHeader) {
            lines.add(titleText to titlePaint)
        }
        if (settings.showAddress) {
            lines.add(addressText to addressPaint)
        }
        if (settings.showCoordinates) {
            lines.add(coordsText to coordsPaint)
        }
        lines.add(timestampText to timestampPaint)

        val availableHeight = rect.bottom - padding - bodyTop
        val lineSpacing = (availableHeight / (lines.size + 0.5f)).coerceAtLeast(titlePaint.textSize * 1.15f)

        var lineY = bodyTop + titlePaint.textSize * 0.90f
        for ((text, paint) in lines) {
            canvas.drawText(truncateText(paint, text, maxTextWidth), drawX, lineY, paint)
            lineY += lineSpacing
        }
    }

    // =========================================================================
    // 6. DIGITAL HUD OVERLAY
    // =========================================================================
    private fun drawDigitalHud(canvas: Canvas, w: Int, h: Int, data: StampData, settings: StampSettings) {
        val isPortrait = h >= w
        val cardWidth = if (isPortrait) w * 0.88f else w * 0.65f
        val cardHeight = if (isPortrait) w * 0.28f else h * 0.28f
        val padding = cardHeight * 0.12f
        val accent = Color.parseColor("#3DE68C")

        val rect = computeCardRect(w, h, cardWidth, cardHeight, settings.position, isPortrait)

        val bgPaint = backgroundPaint(Color.parseColor("#0A0F0C"), baseAlpha = 180, settings)
        canvas.drawRect(rect, bgPaint)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = ColorUtils.setAlphaComponent(accent, applyOpacity(220, settings))
        }
        canvas.drawRect(rect, borderPaint)

        val lines = mutableListOf<String>()
        if (settings.showTitleHeader) {
            lines.add("SYS: ${data.gpsStatusLine.ifBlank { "NO GEOTAG" }}")
        }
        val locStr = if (settings.showAddress) {
            data.addressLine.ifBlank { data.titleLine.ifBlank { "LOCATION DISABLED" } }
        } else {
            data.titleLine.ifBlank { "LOCATION DISABLED" }
        }
        lines.add("LOC: ${locStr.uppercase()}")

        if (settings.showCoordinates) {
            lines.add("POS: ${data.coordinatesLine.ifBlank { "NO GEOTAG" }.uppercase()}")
        }
        lines.add("TIME: ${data.timestampLine.ifBlank { "" }.uppercase()}")

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = cardHeight * 0.135f * (settings.fontSizePercent / 100f)
            color = ColorUtils.setAlphaComponent(settings.textColor, applyOpacity(230, settings))
        }
        val valuePaint = Paint(labelPaint).apply {
            color = ColorUtils.setAlphaComponent(accent, applyOpacity(255, settings))
        }

        val availableHeight = rect.height() - padding * 2
        val lineSpacing = availableHeight / lines.size.coerceAtLeast(1)

        var textY = rect.top + padding + labelPaint.textSize * 0.9f
        val maxTextWidth = rect.width() - padding * 2.4f

        for (i in lines.indices) {
            val paint = if (i == 0 || i == lines.lastIndex) valuePaint else labelPaint
            canvas.drawText(truncateText(paint, lines[i], maxTextWidth), rect.left + padding * 1.2f, textY, paint)
            textY += lineSpacing
        }
    }

    // =========================================================================
    // POSITIONING & DRAWING HELPERS
    // =========================================================================

    fun computeHitTestBounds(
        w: Int,
        h: Int,
        data: StampData,
        settings: StampSettings
    ): XmlStampRenderer.StampHitTestBounds {
        if (useXmlRenderer) {
            try {
                return xmlStampRenderer.computeHitTestBounds(w, h, data, settings)
            } catch (e: Exception) {
                // Fallback
            }
        }
        val cardBounds = computeCardBounds(w, h, settings)
        val density = context.resources.displayMetrics.density
        val editBounds = RectF(
            cardBounds.right - 52f * density,
            cardBounds.top,
            cardBounds.right,
            cardBounds.bottom
        )
        return XmlStampRenderer.StampHitTestBounds(cardBounds, editBounds)
    }

    fun computeCardBounds(w: Int, h: Int, settings: StampSettings): RectF {
        if (w <= 0 || h <= 0) return RectF()
        if (useXmlRenderer) {
            try {
                return xmlStampRenderer.computeCardBounds(w, h, settings)
            } catch (e: Exception) {
                // Fallback to manual computation
            }
        }
        val isPortrait = h >= (w * 0.90f)
        val cardWidth = if (isPortrait) {
            when (settings.template) {
                StampTemplate.PARIS_GLASS -> w * 0.88f
                else -> w * 0.88f
            }
        } else {
            w * 0.58f
        }
        val cardHeight = if (isPortrait) {
            when (settings.template) {
                StampTemplate.GPS_FULL_CARD -> (w * 0.28f).coerceAtMost(h * 0.32f)
                StampTemplate.PARIS_GLASS -> (w * 0.20f).coerceAtMost(h * 0.30f)
                StampTemplate.PISA_CAPSULE -> (w * 0.26f).coerceAtMost(h * 0.30f)
                StampTemplate.BIG_BEN_MINIMAL -> (w * 0.22f).coerceAtMost(h * 0.30f)
                else -> (w * 0.24f).coerceAtMost(h * 0.30f)
            }
        } else {
            when (settings.template) {
                StampTemplate.PARIS_GLASS -> (h * 0.18f).coerceAtMost(h * 0.30f)
                StampTemplate.BIG_BEN_MINIMAL -> (h * 0.20f).coerceAtMost(h * 0.30f)
                else -> (h * 0.24f).coerceAtMost(h * 0.30f)
            }
        }
        return computeCardRect(w, h, cardWidth, cardHeight, settings.position, isPortrait)
    }

    private fun computeCardRect(
        w: Int,
        h: Int,
        cardWidth: Float,
        cardHeight: Float,
        position: StampPosition,
        isPortrait: Boolean
    ): RectF {
        val hMargin = if (isPortrait) (w * 0.04f).coerceAtLeast(16f) else (w * 0.10f).coerceAtLeast(56f)
        val vMargin = if (isPortrait) (h * 0.035f).coerceAtLeast(16f) else (h * 0.06f).coerceAtLeast(24f)

        val actualWidth = if (position == StampPosition.BOTTOM_FULL_WIDTH || isPortrait) {
            (w * 0.88f).coerceAtMost(w - 16f)
        } else {
            cardWidth.coerceAtMost(w - hMargin * 2)
        }

        val left = if (position == StampPosition.BOTTOM_FULL_WIDTH || isPortrait) {
            (w - actualWidth) / 2f
        } else {
            when (position) {
                StampPosition.BOTTOM_LEFT, StampPosition.TOP_LEFT -> hMargin
                StampPosition.BOTTOM_RIGHT, StampPosition.TOP_RIGHT -> (w - actualWidth - hMargin).coerceAtLeast(0f)
                else -> (w - actualWidth) / 2f
            }
        }

        val top = when (position) {
            StampPosition.TOP_LEFT, StampPosition.TOP_RIGHT -> vMargin
            else -> (h - cardHeight - vMargin).coerceAtLeast(0f)
        }

        return RectF(left, top, left + actualWidth, top + cardHeight)
    }

    private fun applyAlignment(paint: Paint, alignment: StampTextAlignment) {
        paint.textAlign = when (alignment) {
            StampTextAlignment.LEFT -> Paint.Align.LEFT
            StampTextAlignment.CENTER -> Paint.Align.CENTER
            StampTextAlignment.RIGHT -> Paint.Align.RIGHT
        }
    }

    private fun computeTextDrawX(textLeft: Float, maxTextWidth: Float, alignment: StampTextAlignment): Float {
        return when (alignment) {
            StampTextAlignment.LEFT -> textLeft
            StampTextAlignment.CENTER -> textLeft + maxTextWidth / 2f
            StampTextAlignment.RIGHT -> textLeft + maxTextWidth
        }
    }

    private fun truncateText(paint: Paint, text: String, maxWidth: Float): String {
        if (maxWidth <= 0 || paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisWidth = paint.measureText(ellipsis)
        val availableWidth = maxWidth - ellipsisWidth
        if (availableWidth <= 0) return text
        val count = paint.breakText(text, true, availableWidth, null)
        return text.take(count) + ellipsis
    }

    private fun drawCardBackground(canvas: Canvas, rect: RectF, radius: Float, settings: StampSettings) {
        val bgPaint = backgroundPaint(Color.parseColor("#171A21"), baseAlpha = 232, settings)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.0f
            color = ColorUtils.setAlphaComponent(Color.WHITE, (255 * 0.12f * (settings.opacityPercent / 100f)).toInt())
        }
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
    }

    // =========================================================================
    // MAP THUMBNAIL RENDERERS (NORMAL, HYBRID, SATELLITE, TERRAIN)
    // =========================================================================

    private fun drawMapThumbnail(canvas: Canvas, badgeRect: RectF, mapType: MapTypeOption) {
        when (mapType) {
            MapTypeOption.NORMAL -> drawNormalMapThumbnail(canvas, badgeRect)
            MapTypeOption.HYBRID -> drawHybridMapThumbnail(canvas, badgeRect)
            MapTypeOption.SATELLITE -> drawSatelliteMapThumbnail(canvas, badgeRect)
            MapTypeOption.TERRAIN -> drawTerrainMapThumbnail(canvas, badgeRect)
        }
    }

    private fun drawNormalMapThumbnail(canvas: Canvas, badgeRect: RectF) {
        val cornerRadius = badgeRect.width() * 0.20f

        // Base Land (Warm yellowish-green)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6EDAC")
        }
        canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, bgPaint)

        // Save canvas to clip within badge bounds
        canvas.save()
        val clipPath = Path().apply {
            addRoundRect(badgeRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)

        // Urban Pink/Salmon Zone in Center-Left
        val urbanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FCA5A5")
            style = Paint.Style.FILL
        }
        val urbanPath = Path().apply {
            moveTo(badgeRect.left, badgeRect.top + badgeRect.height() * 0.2f)
            quadTo(badgeRect.centerX(), badgeRect.top + badgeRect.height() * 0.15f, badgeRect.centerX() + badgeRect.width() * 0.1f, badgeRect.centerY() + badgeRect.height() * 0.1f)
            quadTo(badgeRect.centerX() - badgeRect.width() * 0.1f, badgeRect.bottom - badgeRect.height() * 0.15f, badgeRect.left, badgeRect.bottom - badgeRect.height() * 0.1f)
            close()
        }
        canvas.drawPath(urbanPath, urbanPaint)

        // Water Bay on the right side
        val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#BAE6FD")
            style = Paint.Style.FILL
        }
        val waterPath = Path().apply {
            moveTo(badgeRect.right, badgeRect.top + badgeRect.height() * 0.3f)
            quadTo(badgeRect.centerX() + badgeRect.width() * 0.15f, badgeRect.centerY() + badgeRect.height() * 0.1f, badgeRect.right, badgeRect.bottom)
            lineTo(badgeRect.right, badgeRect.top + badgeRect.height() * 0.3f)
            close()
        }
        canvas.drawPath(waterPath, waterPaint)

        // Secondary roads / Grid lines (red and orange)
        val redStreetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EF4444")
            strokeWidth = 1.4f
            style = Paint.Style.STROKE
        }
        val yellowStreetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F59E0B")
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
        }
        val w = badgeRect.width()
        val h = badgeRect.height()

        canvas.drawLine(badgeRect.left + w * 0.1f, badgeRect.top + h * 0.25f, badgeRect.left + w * 0.65f, badgeRect.bottom - h * 0.2f, redStreetPaint)
        canvas.drawLine(badgeRect.left + w * 0.05f, badgeRect.bottom - h * 0.35f, badgeRect.left + w * 0.6f, badgeRect.top + h * 0.15f, redStreetPaint)
        canvas.drawLine(badgeRect.left + w * 0.25f, badgeRect.top + h * 0.1f, badgeRect.left + w * 0.45f, badgeRect.bottom - h * 0.1f, redStreetPaint)
        canvas.drawLine(badgeRect.left + w * 0.1f, badgeRect.centerY(), badgeRect.right - w * 0.15f, badgeRect.centerY() - h * 0.1f, yellowStreetPaint)

        // Major Arterial Road (dark orange/red line)
        val majorHighwayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#DC2626")
            strokeWidth = 2.2f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val hwyPath = Path().apply {
            moveTo(badgeRect.left + w * 0.2f, badgeRect.bottom)
            quadTo(badgeRect.centerX() - w * 0.05f, badgeRect.centerY(), badgeRect.left + w * 0.35f, badgeRect.top)
        }
        canvas.drawPath(hwyPath, majorHighwayPaint)

        canvas.restore()

        // Pin glyph
        drawPinGlyph(canvas, badgeRect, Color.parseColor("#EF4444"))

        // Badge border stroke
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#25FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 1.0f
        }
        canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, strokePaint)
    }

    private fun drawHybridMapThumbnail(canvas: Canvas, badgeRect: RectF) {
        val cornerRadius = badgeRect.width() * 0.20f
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#081120")
        }
        canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, bgPaint)

        canvas.save()
        val clipPath = Path().apply {
            addRoundRect(badgeRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A365D")
            strokeWidth = 1.8f
            style = Paint.Style.STROKE
        }
        val cx = badgeRect.centerX()
        val cy = badgeRect.centerY()
        val w = badgeRect.width()
        val h = badgeRect.height()

        canvas.drawLine(badgeRect.left, cy, badgeRect.right, cy, gridPaint)
        canvas.drawLine(cx, badgeRect.top, cx, badgeRect.bottom, gridPaint)
        canvas.drawLine(badgeRect.left + w * 0.25f, badgeRect.top, badgeRect.left + w * 0.25f, badgeRect.bottom, gridPaint)
        canvas.drawLine(badgeRect.left + w * 0.75f, badgeRect.top, badgeRect.left + w * 0.75f, badgeRect.bottom, gridPaint)

        // Vibrant cyan major road
        val majorRoadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0284C7")
            strokeWidth = 2.4f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(badgeRect.left + 4f, badgeRect.bottom - 6f, badgeRect.right - 4f, badgeRect.top + 6f, majorRoadPaint)
        canvas.drawLine(badgeRect.left + 6f, badgeRect.top + h * 0.4f, badgeRect.right - 6f, badgeRect.bottom - h * 0.3f, majorRoadPaint)

        canvas.restore()

        drawPinGlyph(canvas, badgeRect, Color.parseColor("#F59E0B"))

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#25FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 1.0f
        }
        canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, strokePaint)
    }

    private fun drawSatelliteMapThumbnail(canvas: Canvas, badgeRect: RectF) {
        val cornerRadius = badgeRect.width() * 0.20f
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#16281E")
        }
        canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, bgPaint)

        canvas.save()
        val clipPath = Path().apply {
            addRoundRect(badgeRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)

        // Forest / terrain patches
        val patchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#283D28")
        }
        canvas.drawCircle(badgeRect.left + badgeRect.width() * 0.3f, badgeRect.top + badgeRect.height() * 0.4f, badgeRect.width() * 0.25f, patchPaint)
        canvas.drawCircle(badgeRect.right - badgeRect.width() * 0.3f, badgeRect.bottom - badgeRect.height() * 0.3f, badgeRect.width() * 0.28f, patchPaint)

        // Satellite road vectors
        val streetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            strokeWidth = 1.6f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(badgeRect.left + 4f, badgeRect.centerY() + 3f, badgeRect.right - 4f, badgeRect.centerY() - 3f, streetPaint)

        val yellowHwy = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FACC15")
            strokeWidth = 2.2f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(badgeRect.left + badgeRect.width() * 0.15f, badgeRect.bottom - 2f, badgeRect.right - badgeRect.width() * 0.15f, badgeRect.top + 2f, yellowHwy)

        canvas.restore()

        drawPinGlyph(canvas, badgeRect, Color.parseColor("#EF4444"))

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#25FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 1.0f
        }
        canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, strokePaint)
    }

    private fun drawTerrainMapThumbnail(canvas: Canvas, badgeRect: RectF) {
        val cornerRadius = badgeRect.width() * 0.20f
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EDE3D2")
        }
        canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, bgPaint)

        canvas.save()
        val clipPath = Path().apply {
            addRoundRect(badgeRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)

        val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C2A68C")
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
        }
        val cx = badgeRect.centerX()
        val cy = badgeRect.centerY()
        canvas.drawCircle(cx, cy, badgeRect.width() * 0.32f, contourPaint)
        canvas.drawCircle(cx, cy, badgeRect.width() * 0.20f, contourPaint)

        val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7DD3FC")
            strokeWidth = 2.2f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(badgeRect.left, badgeRect.bottom - 4f, badgeRect.right - 6f, badgeRect.top + 6f, waterPaint)

        canvas.restore()

        drawPinGlyph(canvas, badgeRect, Color.parseColor("#EF4444"))

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#25FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 1.0f
        }
        canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, strokePaint)
    }

    private fun drawCircularMapBadge(canvas: Canvas, badgeRect: RectF, mapType: MapTypeOption) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when (mapType) {
                MapTypeOption.NORMAL -> Color.parseColor("#D4EAB4")
                MapTypeOption.HYBRID -> Color.parseColor("#081120")
                MapTypeOption.SATELLITE -> Color.parseColor("#16281E")
                MapTypeOption.TERRAIN -> Color.parseColor("#EDE3D2")
            }
        }
        canvas.drawOval(badgeRect, bgPaint)

        val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (mapType == MapTypeOption.HYBRID) Color.parseColor("#0284C7") else Color.WHITE
            strokeWidth = badgeRect.width() * 0.10f
            style = Paint.Style.STROKE
        }
        val cx = badgeRect.centerX()
        val cy = badgeRect.centerY()
        canvas.drawLine(badgeRect.left + 4f, cy + 3f, badgeRect.right - 4f, cy - 3f, roadPaint)
        canvas.drawLine(cx, badgeRect.top + 2f, cx, badgeRect.bottom - 2f, roadPaint)

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawOval(badgeRect, strokePaint)

        val pinColor = if (mapType == MapTypeOption.HYBRID) Color.parseColor("#F59E0B") else Color.parseColor("#EF4444")
        drawPinGlyph(canvas, badgeRect, pinColor)
    }

    private fun drawPinGlyph(canvas: Canvas, badgeRect: RectF, pinColor: Int) {
        val cx = badgeRect.centerX()
        val cy = badgeRect.centerY()
        val r = badgeRect.width() * 0.20f

        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pinColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy - r * 0.35f, r, pinPaint)

        val path = Path().apply {
            moveTo(cx - r, cy - r * 0.2f)
            lineTo(cx + r, cy - r * 0.2f)
            lineTo(cx, cy + r * 1.55f)
            close()
        }
        canvas.drawPath(path, pinPaint)

        // Center dot in pin
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#081120")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy - r * 0.35f, r * 0.40f, dotPaint)
    }

    private fun createTextPaint(settings: StampSettings, size: Float, bold: Boolean): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = loadTypeface(settings.fontStyle, bold)
            textSize = size * (settings.fontSizePercent / 100f)
            color = ColorUtils.setAlphaComponent(settings.textColor, applyOpacity(255, settings))
            textAlign = Paint.Align.LEFT
        }
    }

    private fun loadTypeface(fontStyle: StampFontStyle, forceBold: Boolean): Typeface {
        val fontRes = if (forceBold) com.autonoumus.ai.R.font.instrument_sans_bold else fontStyle.fontRes
        return typefaceCache.getOrPut(fontRes) {
            try {
                ResourcesCompat.getFont(context, fontRes) ?: Typeface.DEFAULT
            } catch (e: Exception) {
                Typeface.DEFAULT
            }
        }
    }

    private fun applyOpacity(baseAlpha: Int, settings: StampSettings): Int {
        return (baseAlpha * (settings.opacityPercent.coerceIn(0, 100) / 100f)).toInt().coerceIn(0, 255)
    }

    private fun backgroundPaint(baseColor: Int, baseAlpha: Int, settings: StampSettings): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(baseColor, applyOpacity(baseAlpha, settings))
            style = Paint.Style.FILL
        }
    }
}
