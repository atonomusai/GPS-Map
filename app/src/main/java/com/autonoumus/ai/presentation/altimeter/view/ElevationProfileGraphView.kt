package com.autonoumus.ai.presentation.altimeter.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.autonoumus.ai.R
import java.util.Locale

/**
 * Custom line chart view representing the Elevation Profile over the last 60 minutes
 * with crisp polyline segments, horizontal dashed reference line, and a dynamic unit badge
 * positioned smartly above or below the trajectory to prevent line overlap.
 */
class ElevationProfileGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var elevationDataMeters: List<Double> = listOf(510.0, 525.0, 518.0, 555.0, 538.0, 580.0, 560.0)
    private var isFeet: Boolean = false

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#4F46E5")
    }

    private val dashedLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        color = Color.parseColor("#E2E8F0")
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFFFF")
    }

    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = Color.parseColor("#E2E8F0")
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * resources.displayMetrics.scaledDensity
        color = Color.parseColor("#64748B")
        try {
            val tf = ResourcesCompat.getFont(context, R.font.instrument_sans_medium)
            typeface = tf ?: Typeface.DEFAULT
        } catch (e: Exception) {
            typeface = Typeface.DEFAULT
        }
    }

    private val chartPath = Path()
    private val dashedPath = Path()
    private val badgeRect = RectF()

    fun setElevationData(samples: List<Double>, isFeetUnit: Boolean) {
        if (samples.isNotEmpty()) {
            elevationDataMeters = samples
        }
        isFeet = isFeetUnit
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0 || elevationDataMeters.isEmpty()) return

        val density = resources.displayMetrics.density
        val paddingLeft = 14f * density
        val paddingRight = 14f * density
        val paddingTop = 16f * density
        val paddingBottom = 16f * density

        val plotWidth = w - paddingLeft - paddingRight
        val plotHeight = h - paddingTop - paddingBottom
        if (plotWidth <= 0 || plotHeight <= 0) return

        val displayValues = if (isFeet) {
            elevationDataMeters.map { it * 3.28084 }
        } else {
            elevationDataMeters
        }

        val rawMin = displayValues.minOrNull() ?: 0.0
        val rawMax = displayValues.maxOrNull() ?: 100.0
        val range = (rawMax - rawMin).coerceAtLeast(10.0)
        val minVal = rawMin - (range * 0.20)
        val maxVal = rawMax + (range * 0.20)
        val totalRange = (maxVal - minVal).coerceAtLeast(1.0)

        val pointsCount = displayValues.size
        val stepX = plotWidth / (pointsCount - 1).coerceAtLeast(1)

        val points = displayValues.mapIndexed { index, value ->
            val x = paddingLeft + (index * stepX)
            val y = paddingTop + plotHeight - (((value - minVal) / totalRange) * plotHeight).toFloat()
            Pair(x, y.coerceIn(paddingTop, paddingTop + plotHeight))
        }

        // 1. Draw Horizontal Dashed Reference Line (at median / average elevation)
        val avgValue = displayValues.average()
        val dashedY = paddingTop + plotHeight - (((avgValue - minVal) / totalRange) * plotHeight).toFloat()
        dashedPath.reset()
        dashedPath.moveTo(paddingLeft, dashedY)
        dashedPath.lineTo(w - paddingRight, dashedY)
        canvas.drawPath(dashedPath, dashedLinePaint)

        // 2. Draw Polyline (Sharp crisp segments from point to point)
        chartPath.reset()
        if (points.isNotEmpty()) {
            chartPath.moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) {
                chartPath.lineTo(points[i].first, points[i].second)
            }
            canvas.drawPath(chartPath, linePaint)
        }

        // 3. Compute Badge Size & Placement
        val badgeText = if (isFeet) {
            String.format(Locale.US, "%.0f ft", avgValue)
        } else {
            String.format(Locale.US, "%.0f m", avgValue)
        }
        val textWidth = badgeTextPaint.measureText(badgeText)
        val badgeWidth = textWidth + (14f * density)
        val badgeHeight = 18f * density
        val badgeRight = w - paddingRight - (6f * density)
        val badgeLeft = badgeRight - badgeWidth
        val badgeCenterX = (badgeLeft + badgeRight) / 2f

        // Interpolate the exact Y position of the blue line at badgeCenterX
        var lineYAtBadge = dashedY
        if (points.size >= 2) {
            for (i in 0 until points.size - 1) {
                val p0 = points[i]
                val p1 = points[i + 1]
                if (badgeCenterX >= p0.first && badgeCenterX <= p1.first) {
                    val t = if (p1.first != p0.first) (badgeCenterX - p0.first) / (p1.first - p0.first) else 0f
                    lineYAtBadge = p0.second + t * (p1.second - p0.second)
                    break
                }
            }
        }

        // Dynamically place badge ABOVE or BELOW the line/dashed line to avoid any collision
        val badgeTop: Float
        val badgeBottom: Float

        if (lineYAtBadge >= dashedY) {
            // Blue line is passing at or below the dashed line -> Place badge ABOVE the line
            val desiredBottom = minOf(dashedY, lineYAtBadge) - (4f * density)
            badgeBottom = desiredBottom.coerceAtLeast(paddingTop + badgeHeight)
            badgeTop = badgeBottom - badgeHeight
        } else {
            // Blue line is passing above the dashed line -> Place badge BELOW the line
            val desiredTop = maxOf(dashedY, lineYAtBadge) + (4f * density)
            badgeTop = desiredTop.coerceAtMost(h - paddingBottom - badgeHeight)
            badgeBottom = badgeTop + badgeHeight
        }

        badgeRect.set(badgeLeft, badgeTop, badgeRight, badgeBottom)
        val badgeCorner = 4f * density

        // 4. Draw Badge on top with solid background and clean border
        canvas.drawRoundRect(badgeRect, badgeCorner, badgeCorner, badgeBgPaint)
        canvas.drawRoundRect(badgeRect, badgeCorner, badgeCorner, badgeBorderPaint)
        val textBaseline = badgeTop + (badgeHeight / 2f) - ((badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f)
        canvas.drawText(badgeText, badgeLeft + (badgeWidth / 2f), textBaseline, badgeTextPaint)
    }
}
