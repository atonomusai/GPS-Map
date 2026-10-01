package com.autonoumus.ai.presentation.speedometer.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import com.autonoumus.ai.R

/**
 * Custom Donut Progress Chart for Speed Distribution:
 * - 3 Segment Arcs: Safe (Blue #4F65F6), Warning (Amber #F59E0B), Over Speed (Red #EF4444)
 * - Center score number + "SCORE" label
 * - Smooth animated sweep progression
 */
class SpeedDistributionDonutView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var safePercent: Float = 62f
    private var warningPercent: Float = 28f
    private var overSpeedPercent: Float = 10f
    private var scoreValue: Int = 85

    private var animationFraction: Float = 1f
    private var animator: ValueAnimator? = null

    private val colorSafe = Color.parseColor("#4F65F6")       // Blue
    private val colorWarning = Color.parseColor("#F59E0B")    // Amber / Orange
    private val colorOverSpeed = Color.parseColor("#EF4444")  // Red
    private val colorTrackBg = Color.parseColor("#F1F5F9")    // Light grey track

    private val colorTextDark = Color.parseColor("#0F172A")
    private val colorTextGray = Color.parseColor("#64748B")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = colorTrackBg
    }

    private val safePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = colorSafe
    }

    private val warningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = colorWarning
    }

    private val overSpeedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = colorOverSpeed
    }

    private val scoreTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = colorTextDark
        try {
            val tf = ResourcesCompat.getFont(context, R.font.instrument_sans_bold)
            typeface = tf ?: Typeface.DEFAULT_BOLD
        } catch (e: Exception) {
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = colorTextGray
        try {
            val tf = ResourcesCompat.getFont(context, R.font.instrument_sans_bold)
            typeface = tf ?: Typeface.DEFAULT_BOLD
        } catch (e: Exception) {
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private val arcBounds = RectF()

    fun setDistribution(
        safe: Float,
        warning: Float,
        overSpeed: Float,
        score: Int,
        animated: Boolean = true
    ) {
        val total = (safe + warning + overSpeed).coerceAtLeast(1f)
        safePercent = (safe / total) * 100f
        warningPercent = (warning / total) * 100f
        overSpeedPercent = (overSpeed / total) * 100f
        scoreValue = score

        if (animated) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 800
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    animationFraction = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            animationFraction = 1f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val density = resources.displayMetrics.density
        val cx = w / 2f
        val cy = h / 2f

        val strokeW = 11f * density
        trackPaint.strokeWidth = strokeW
        safePaint.strokeWidth = strokeW
        warningPaint.strokeWidth = strokeW
        overSpeedPaint.strokeWidth = strokeW

        val radius = (minOf(w, h) / 2f) - (strokeW / 2f) - (2f * density)
        if (radius <= 0) return

        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // 1. Draw Background Track
        canvas.drawCircle(cx, cy, radius, trackPaint)

        // 2. Draw 3 Colored Arcs starting at top (-90 degrees)
        var startAngle = -90f
        val totalSweep = 360f * animationFraction

        val safeSweep = (safePercent / 100f) * totalSweep
        val warningSweep = (warningPercent / 100f) * totalSweep
        val overSpeedSweep = (overSpeedPercent / 100f) * totalSweep

        if (safeSweep > 0) {
            canvas.drawArc(arcBounds, startAngle, safeSweep, false, safePaint)
            startAngle += safeSweep
        }

        if (warningSweep > 0) {
            canvas.drawArc(arcBounds, startAngle, warningSweep, false, warningPaint)
            startAngle += warningSweep
        }

        if (overSpeedSweep > 0) {
            canvas.drawArc(arcBounds, startAngle, overSpeedSweep, false, overSpeedPaint)
        }

        // 3. Draw Center Score Text (e.g. "85" + "SCORE")
        scoreTextPaint.textSize = 21f * density
        labelTextPaint.textSize = 8.5f * density

        val scoreStr = scoreValue.toString()
        val textY = cy + (3f * density)
        canvas.drawText(scoreStr, cx, textY, scoreTextPaint)

        val labelY = textY + (13f * density)
        canvas.drawText("SCORE", cx, labelY, labelTextPaint)
    }
}
