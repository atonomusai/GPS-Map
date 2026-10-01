package com.autonoumus.ai.presentation.templates.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.autonoumus.ai.core.stamp.StampRenderer
import com.autonoumus.ai.domain.model.StampData
import com.autonoumus.ai.domain.model.StampSettings
import com.autonoumus.ai.domain.model.StampTemplate

/**
 * A small live preview of one [StampTemplate], drawn with the exact same [StampRenderer] used
 * by the real camera capture pipeline - the picker thumbnail is pixel-accurate, not a mockup image.
 */
class StampTemplatePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var stampRenderer: StampRenderer? = null

    init {
        if (isInEditMode) {
            val themedCtx = androidx.appcompat.view.ContextThemeWrapper(context, com.autonoumus.ai.R.style.Theme_GSMCAMER)
            stampRenderer = StampRenderer(themedCtx, com.autonoumus.ai.core.stamp.XmlStampRenderer(themedCtx))
        }
    }

    var template: StampTemplate = StampTemplate.GPS_FULL_CARD
        set(value) {
            if (field == value) return
            field = value
            backgroundBitmap = loadBitmap(value.previewImageRes)
            invalidate()
        }

    var customStampData: StampData? = null
        set(value) {
            field = value
            invalidate()
        }

    var customStampSettings: StampSettings? = null
        set(value) {
            field = value
            invalidate()
        }

    var customBackgroundBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    private val sampleStampData = StampData(
        titleLine = "F-7 Markaz, Jinnah Super, Islamabad",
        addressLine = "Street 4, Sector F-7/3, Islamabad, Pakistan",
        coordinatesLine = "33.7294° N, 73.0551° E • Alt: 540m",
        timestampLine = "Sunday, Aug 16, 2026 • 03:09 PM PKT",
        gpsStatusLine = "GPS LOCKED (±2.5M)",
        dateLine = "24/10/2026",
        timeLine = "03:09 PM",
        timeZoneLine = "PKT",
        rawLatitude = 33.7294,
        rawLongitude = 73.0551,
        rawAltitudeMeters = 540.0
    )

    private var backgroundBitmap: Bitmap? = loadBitmap(template.previewImageRes)

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()

    private fun loadBitmap(@androidx.annotation.DrawableRes resId: Int): Bitmap? {
        val cached = bitmapCache[resId]
        if (cached != null && !cached.isRecycled) {
            return cached
        }
        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeResource(resources, resId, options)
            bitmapCache[resId] = bmp
            bmp
        } catch (e: Exception) {
            null
        }
    }

    private fun getSampleDataForTemplate(t: StampTemplate): StampData {
        return when (t) {
            StampTemplate.PARIS_GLASS -> StampData(
                titleLine = "Eiffel Tower, 5 Av. Anatole, Paris",
                addressLine = "Eiffel Tower, 5 Av. Anatole, Paris",
                coordinatesLine = "",
                timestampLine = "24/10/2025 • UTC+1",
                gpsStatusLine = "GPS LOCKED (±2.5M)",
                dateLine = "24/10/2025",
                timeLine = "03:09 PM",
                timeZoneLine = "UTC+1",
                rawLatitude = null,
                rawLongitude = null,
                rawAltitudeMeters = null
            )
            StampTemplate.LONDON_CATHEDRAL -> StampData(
                titleLine = "Mansion, London SW1A 0AA United Kingdom",
                addressLine = "Mansion, London SW1A 0AA United Kingdom",
                coordinatesLine = "",
                timestampLine = "24/10/2025",
                gpsStatusLine = "GPS LOCKED (±2.5M)",
                dateLine = "24/10/2025",
                timeLine = "",
                timeZoneLine = "",
                rawLatitude = null,
                rawLongitude = null,
                rawAltitudeMeters = null
            )
            StampTemplate.PISA_CAPSULE -> StampData(
                titleLine = "Pisa, 56126 pisa, Province of paisa,Italy",
                addressLine = "Pisa, 56126 pisa, Province of paisa,Italy",
                coordinatesLine = "Lat: 43.7311°    |    Long: 10.3965°",
                timestampLine = "⏰ 4:23 PM",
                gpsStatusLine = "GPS LOCKED (±2.5M)",
                dateLine = "24/10/2025",
                timeLine = "4:23 PM",
                timeZoneLine = "",
                rawLatitude = 43.7311,
                rawLongitude = 10.3965,
                rawAltitudeMeters = 4.0
            )
            StampTemplate.BIG_BEN_MINIMAL -> StampData(
                titleLine = "Big Ben, London SW1A 0AA United Kingdom",
                addressLine = "Big Ben, London SW1A 0AA United Kingdom",
                coordinatesLine = "",
                timestampLine = "3:45 PM",
                gpsStatusLine = "GPS LOCKED (±2.5M)",
                dateLine = "24/10/2025",
                timeLine = "3:45 PM",
                timeZoneLine = "",
                rawLatitude = null,
                rawLongitude = null,
                rawAltitudeMeters = null
            )
            StampTemplate.GPS_FULL_CARD -> sampleStampData
            StampTemplate.PISA_PILL -> StampData(
                titleLine = "Pisa, 56126 pisa, Province of paisa,Italy",
                addressLine = "Pisa, 56126 pisa, Province of paisa,Italy",
                coordinatesLine = "Lat: 43.7311°    |    Long: 10.3965°",
                timestampLine = "⏰ 4:23 PM",
                gpsStatusLine = "GPS LOCKED (±2.5M)",
                dateLine = "24/10/2025",
                timeLine = "4:23 PM",
                timeZoneLine = "",
                rawLatitude = 43.7311,
                rawLongitude = 10.3965,
                rawAltitudeMeters = 4.0
            )
            StampTemplate.NOTRE_DAME_ALARM -> StampData(
                titleLine = "Catheedrale, Notre Dame,Jean Paul II, France",
                addressLine = "Catheedrale, Notre Dame,Jean Paul II, France",
                coordinatesLine = "",
                timestampLine = "⏰ 4:23 PM",
                gpsStatusLine = "GPS LOCKED (±2.5M)",
                dateLine = "24/10/2025",
                timeLine = "4:23 PM",
                timeZoneLine = "",
                rawLatitude = null,
                rawLongitude = null,
                rawAltitudeMeters = null
            )
            StampTemplate.CATHEDRAL_LEFT_MAP -> StampData(
                titleLine = "Catheedrale, Notre Dame,Jean Paul II, France",
                addressLine = "Catheedrale, Notre Dame,Jean Paul II, France",
                coordinatesLine = "",
                timestampLine = "24/10/2025",
                gpsStatusLine = "GPS LOCKED (±2.5M)",
                dateLine = "24/10/2025",
                timeLine = "",
                timeZoneLine = "",
                rawLatitude = null,
                rawLongitude = null,
                rawAltitudeMeters = null
            )
            StampTemplate.PISA_PIN_CARD -> StampData(
                titleLine = "Pisa, 56126 pisa, Province of paisa,Italy",
                addressLine = "Pisa, 56126 pisa, Province of paisa,Italy",
                coordinatesLine = "",
                timestampLine = "⏰ 4:23 PM",
                gpsStatusLine = "GPS LOCKED (±2.5M)",
                dateLine = "24/10/2025",
                timeLine = "4:23 PM",
                timeZoneLine = "",
                rawLatitude = 43.7311,
                rawLongitude = 10.3965,
                rawAltitudeMeters = 4.0
            )
            StampTemplate.CYBER_HUD -> sampleStampData
            StampTemplate.RETRO_CAM -> StampData(
                titleLine = "Islamabad, Pakistan",
                addressLine = "Islamabad, Pakistan",
                coordinatesLine = "33.7294° N, 73.0551° E",
                timestampLine = "'26 10 24   15:09:22",
                gpsStatusLine = "GPS LOCKED (±2.5M)",
                dateLine = "24/10/2026",
                timeLine = "15:09:22",
                timeZoneLine = "",
                rawLatitude = 33.7294,
                rawLongitude = 73.0551,
                rawAltitudeMeters = 540.0
            )
            StampTemplate.FROSTED_BLUR -> StampData(
                titleLine = "F-7 Markaz, Jinnah Super Market, Islamabad",
                addressLine = "F-7 Markaz, Jinnah Super Market, Islamabad",
                coordinatesLine = "33.7294° N, 73.0551° E",
                timestampLine = "14 DEC 2024 • 09:42 AM",
                gpsStatusLine = "GPS LOCKED (±2.5M)",
                dateLine = "14 DEC 2024",
                timeLine = "09:42 AM",
                timeZoneLine = "",
                rawLatitude = 33.7294,
                rawLongitude = 73.0551,
                rawAltitudeMeters = 540.0
            )
        }
    }

    private fun getSampleSettingsForTemplate(t: StampTemplate): StampSettings {
        return when (t) {
            StampTemplate.PARIS_GLASS -> StampSettings(
                template = t,
                showTimeZone = true,
                showCoordinates = false,
                mapType = com.autonoumus.ai.domain.model.MapTypeOption.NORMAL
            )
            StampTemplate.LONDON_CATHEDRAL -> StampSettings(
                template = t,
                showTimeZone = false,
                showCoordinates = false,
                mapType = com.autonoumus.ai.domain.model.MapTypeOption.NORMAL
            )
            StampTemplate.PISA_CAPSULE -> StampSettings(
                template = t,
                showTimeZone = false,
                showCoordinates = true,
                position = com.autonoumus.ai.domain.model.StampPosition.BOTTOM_FULL_WIDTH,
                mapType = com.autonoumus.ai.domain.model.MapTypeOption.NORMAL
            )
            StampTemplate.BIG_BEN_MINIMAL -> StampSettings(
                template = t,
                showTimeZone = false,
                showCoordinates = false,
                mapType = com.autonoumus.ai.domain.model.MapTypeOption.NORMAL
            )
            StampTemplate.GPS_FULL_CARD -> StampSettings(
                template = t,
                showCoordinates = true,
                showAddress = true,
                mapType = com.autonoumus.ai.domain.model.MapTypeOption.NORMAL
            )
            StampTemplate.PISA_PILL -> StampSettings(
                template = t,
                showTimeZone = false,
                showCoordinates = true,
                position = com.autonoumus.ai.domain.model.StampPosition.BOTTOM_FULL_WIDTH,
                mapType = com.autonoumus.ai.domain.model.MapTypeOption.NORMAL
            )
            StampTemplate.NOTRE_DAME_ALARM -> StampSettings(
                template = t,
                showTimeZone = false,
                showCoordinates = false,
                mapType = com.autonoumus.ai.domain.model.MapTypeOption.NORMAL
            )
            StampTemplate.CATHEDRAL_LEFT_MAP -> StampSettings(
                template = t,
                showTimeZone = false,
                showCoordinates = false,
                mapType = com.autonoumus.ai.domain.model.MapTypeOption.NORMAL
            )
            StampTemplate.PISA_PIN_CARD -> StampSettings(
                template = t,
                showTimeZone = false,
                showCoordinates = false,
                mapType = com.autonoumus.ai.domain.model.MapTypeOption.NORMAL
            )
            StampTemplate.CYBER_HUD -> StampSettings(
                template = t,
                showCoordinates = true,
                showAddress = true,
                mapType = com.autonoumus.ai.domain.model.MapTypeOption.NORMAL
            )
            StampTemplate.RETRO_CAM -> StampSettings(
                template = t,
                showCoordinates = true,
                showAddress = true,
                mapType = com.autonoumus.ai.domain.model.MapTypeOption.NORMAL
            )
            StampTemplate.FROSTED_BLUR -> StampSettings(
                template = t,
                showCoordinates = true,
                showAddress = true,
                mapType = com.autonoumus.ai.domain.model.MapTypeOption.NORMAL
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = customBackgroundBitmap ?: backgroundBitmap
        if (bitmap != null) {
            val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val scaledW = bitmap.width * scale
            val scaledH = bitmap.height * scale
            val left = (width - scaledW) / 2f
            val top = (height - scaledH) / 2f
            dstRect.set(left, top, left + scaledW, top + scaledH)
            srcRect.set(0, 0, bitmap.width, bitmap.height)
            canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
        } else {
            canvas.drawColor(Color.DKGRAY)
        }
        val settingsToUse = customStampSettings ?: getSampleSettingsForTemplate(template)
        val dataToUse = customStampData ?: getSampleDataForTemplate(template)
        stampRenderer?.draw(canvas, width, height, dataToUse, settingsToUse)
    }

    private companion object {
        // Shared across all preview instances (only ~6 templates) so the same drawable isn't
        // re-decoded for every RecyclerView row.
        val bitmapCache = mutableMapOf<Int, Bitmap?>()
    }
}
