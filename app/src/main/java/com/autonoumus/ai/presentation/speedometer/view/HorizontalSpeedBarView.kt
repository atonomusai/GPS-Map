package com.autonoumus.ai.presentation.speedometer.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Custom horizontal gradient speed progress bar matching Digital HUD reference:
 * - Rounded track background (#F1F5F9)
 * - Multi-color gradient fill (Safe Green #10B981 -> Amber #F59E0B -> Red #EF4444)
 * - Smooth animated progress fill
 */
class HorizontalSpeedBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentSpeed: Float = 0f
    private var displayedSpeed: Float = 0f
    private var maxSpeed: Float = 180f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F1F5F9")
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val trackRect = RectF()
    private val progressRect = RectF()
    private var progressAnimator: ValueAnimator? = null

    // Gradient colors: Green -> Amber -> Red
    private val gradientColors = intArrayOf(
        Color.parseColor("#10B981"), // Emerald Green
        Color.parseColor("#F59E0B"), // Amber
        Color.parseColor("#EF4444")  // Red
    )
    private val gradientPositions = floatArrayOf(0f, 0.5f, 1f)

    fun setSpeed(speed: Float, max: Float = 180f, animated: Boolean = true) {
        maxSpeed = if (max > 0) max else 180f
        val target = speed.coerceIn(0f, maxSpeed)
        currentSpeed = target

        if (animated) {
            progressAnimator?.cancel()
            progressAnimator = ValueAnimator.ofFloat(displayedSpeed, target).apply {
                duration = 320
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    displayedSpeed = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            displayedSpeed = target
            invalidate()
        }
    }

    fun setMaxSpeed(max: Float) {
        if (max > 0) {
            maxSpeed = max
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            progressPaint.shader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                gradientColors,
                gradientPositions,
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val radius = h / 2f
        trackRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        val fraction = (displayedSpeed / maxSpeed).coerceIn(0f, 1f)
        var fillWidth = (w * fraction).coerceAtLeast(0f)
        // A fill narrower than the bar's height can't be drawn as a rounded pill - it collapsed
        // into an invisible sliver at walking speed. Any real movement shows at least a full dot.
        if (displayedSpeed >= 0.5f && fillWidth < h) fillWidth = h

        if (fillWidth > 1f) {
            progressRect.set(0f, 0f, fillWidth, h)
            canvas.drawRoundRect(progressRect, radius, radius, progressPaint)
        }
    }
}
