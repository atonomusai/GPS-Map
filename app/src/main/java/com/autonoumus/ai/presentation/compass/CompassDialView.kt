package com.autonoumus.ai.presentation.compass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.autonoumus.ai.R
import kotlin.math.min

class CompassDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var headingDegree: Float = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.parseColor("#EDF0F5")
    }

    private val tickMajorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#1A1C20")
    }

    private val tickMinorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#9EA5B4")
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12f * resources.displayMetrics.scaledDensity
        color = Color.parseColor("#1A1C20")
        try {
            val tf = ResourcesCompat.getFont(context, R.font.instrument_sans_bold)
            typeface = tf ?: Typeface.DEFAULT_BOLD
        } catch (e: Exception) {
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private val northTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 13f * resources.displayMetrics.scaledDensity
        color = Color.parseColor("#FF3B30")
        try {
            val tf = ResourcesCompat.getFont(context, R.font.instrument_sans_bold)
            typeface = tf ?: Typeface.DEFAULT_BOLD
        } catch (e: Exception) {
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private val textBounds = Rect()

    fun setHeading(degree: Float) {
        this.headingDegree = degree
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - (12f * resources.displayMetrics.density)

        if (radius <= 0) return

        canvas.save()
        // Rotate canvas around center based on current heading
        canvas.rotate(-headingDegree, cx, cy)

        // Draw outer subtle circle
        canvas.drawCircle(cx, cy, radius, ringPaint)

        val density = resources.displayMetrics.density
        val majorTickLen = 10f * density
        val minorTickLen = 5f * density
        val labelMargin = 22f * density

        // Draw 360 degree ticks (every 10 degrees)
        for (deg in 0 until 360 step 10) {
            canvas.save()
            canvas.rotate(deg.toFloat(), cx, cy)

            val isCardinal = deg % 45 == 0
            val isMajor = deg % 30 == 0

            if (isCardinal || isMajor) {
                canvas.drawLine(cx, cy - radius, cx, cy - radius + majorTickLen, tickMajorPaint)
            } else {
                canvas.drawLine(cx, cy - radius, cx, cy - radius + minorTickLen, tickMinorPaint)
            }

            canvas.restore()
        }

        // Draw direction labels: N, NE, E, SE, S, SW, W, NW
        val labels = arrayOf(
            Pair("N", 0),
            Pair("NE", 45),
            Pair("E", 90),
            Pair("SE", 135),
            Pair("S", 180),
            Pair("SW", 225),
            Pair("W", 270),
            Pair("NW", 315)
        )

        for ((label, deg) in labels) {
            canvas.save()
            canvas.rotate(deg.toFloat(), cx, cy)

            val paint = if (label == "N") northTextPaint else textPaint
            paint.getTextBounds(label, 0, label.length, textBounds)
            val yPos = cy - radius + labelMargin + (textBounds.height() / 2f)

            canvas.drawText(label, cx, yPos, paint)
            canvas.restore()
        }

        canvas.restore()
    }
}
