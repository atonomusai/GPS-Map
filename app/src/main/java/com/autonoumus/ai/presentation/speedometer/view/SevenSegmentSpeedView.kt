package com.autonoumus.ai.presentation.speedometer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Custom 7-Segment Digital Speed Display View matching the reference design:
 * - Geometric polygon segments with beveled 45-degree ends and spacing gaps
 * - High-contrast dark navy color (#0F172A) for active segments
 * - Renders any integer speed value (e.g. "00", "54", "120")
 */
class SevenSegmentSpeedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var speedText: String = "00"
    private var segmentColor: Int = Color.parseColor("#0F172A")
    private var inactiveSegmentColor: Int = Color.TRANSPARENT

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = segmentColor
    }

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = inactiveSegmentColor
    }

    // 7-segment bitmasks for digits 0-9: [a, b, c, d, e, f, g]
    // a=top, b=top-right, c=bottom-right, d=bottom, e=bottom-left, f=top-left, g=middle
    private val digitPatterns = arrayOf(
        booleanArrayOf(true, true, true, true, true, true, false),      // 0
        booleanArrayOf(false, true, true, false, false, false, false),  // 1
        booleanArrayOf(true, true, false, true, true, false, true),     // 2
        booleanArrayOf(true, true, true, true, false, false, true),     // 3
        booleanArrayOf(false, true, true, false, false, true, true),    // 4
        booleanArrayOf(true, false, true, true, false, true, true),     // 5
        booleanArrayOf(true, false, true, true, true, true, true),      // 6
        booleanArrayOf(true, true, true, false, false, false, false),   // 7
        booleanArrayOf(true, true, true, true, true, true, true),       // 8
        booleanArrayOf(true, true, true, true, false, true, true)       // 9
    )

    fun setSpeed(speed: Int) {
        val formatted = when {
            speed < 10 -> String.format("%02d", speed.coerceAtLeast(0))
            else -> speed.toString()
        }
        if (formatted != speedText) {
            speedText = formatted
            invalidate()
            requestLayout()
        }
    }

    fun setSpeedColor(color: Int) {
        segmentColor = color
        activePaint.color = color
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val digitCount = speedText.length.coerceAtLeast(2)

        val desiredHeight = (130 * density).toInt()
        val digitWidth = (desiredHeight * 0.58f)
        val digitSpacing = (desiredHeight * 0.16f)
        val desiredWidth = (digitCount * digitWidth + (digitCount - 1) * digitSpacing + (16 * density)).toInt()

        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0 || speedText.isEmpty()) return

        val digitCount = speedText.length
        val digitH = h * 0.92f
        val digitW = digitH * 0.56f
        val spacing = digitH * 0.16f
        val totalWidth = digitCount * digitW + (digitCount - 1) * spacing

        var startX = (w - totalWidth) / 2f
        val startY = (h - digitH) / 2f

        for (i in 0 until digitCount) {
            val char = speedText[i]
            val digitValue = char.digitToIntOrNull() ?: 0
            val pattern = if (digitValue in 0..9) digitPatterns[digitValue] else digitPatterns[0]

            drawSevenSegmentDigit(canvas, startX, startY, digitW, digitH, pattern)
            startX += digitW + spacing
        }
    }

    private fun drawSevenSegmentDigit(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        segments: BooleanArray
    ) {
        val thick = (w * 0.22f)
        val gap = (thick * 0.16f)
        val halfThick = thick / 2f
        val midY = y + h / 2f

        val path = Path()

        // Segment A: Top horizontal
        drawHorizontalSegment(canvas, x + gap, y, w - 2 * gap, thick, segments[0])

        // Segment B: Top-right vertical
        drawVerticalSegment(canvas, x + w - thick, y + gap, thick, (h / 2f) - 1.5f * gap, segments[1])

        // Segment C: Bottom-right vertical
        drawVerticalSegment(canvas, x + w - thick, midY + gap * 0.5f, thick, (h / 2f) - 1.5f * gap, segments[2])

        // Segment D: Bottom horizontal
        drawHorizontalSegment(canvas, x + gap, y + h - thick, w - 2 * gap, thick, segments[3])

        // Segment E: Bottom-left vertical
        drawVerticalSegment(canvas, x, midY + gap * 0.5f, thick, (h / 2f) - 1.5f * gap, segments[4])

        // Segment F: Top-left vertical
        drawVerticalSegment(canvas, x, y + gap, thick, (h / 2f) - 1.5f * gap, segments[5])

        // Segment G: Middle horizontal
        drawHorizontalSegment(canvas, x + gap, midY - halfThick, w - 2 * gap, thick, segments[6])
    }

    private fun drawHorizontalSegment(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        isActive: Boolean
    ) {
        val paint = if (isActive) activePaint else inactivePaint
        if (paint.color == Color.TRANSPARENT) return

        val halfH = h / 2f
        val path = Path().apply {
            moveTo(x + halfH, y)
            lineTo(x + w - halfH, y)
            lineTo(x + w, y + halfH)
            lineTo(x + w - halfH, y + h)
            lineTo(x + halfH, y + h)
            lineTo(x, y + halfH)
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun drawVerticalSegment(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        isActive: Boolean
    ) {
        val paint = if (isActive) activePaint else inactivePaint
        if (paint.color == Color.TRANSPARENT) return

        val halfW = w / 2f
        val path = Path().apply {
            moveTo(x + halfW, y)
            lineTo(x + w, y + halfW)
            lineTo(x + w, y + h - halfW)
            lineTo(x + halfW, y + h)
            lineTo(x, y + h - halfW)
            lineTo(x, y + halfW)
            close()
        }
        canvas.drawPath(path, paint)
    }
}
