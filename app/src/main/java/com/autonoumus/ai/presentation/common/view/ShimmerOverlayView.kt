package com.autonoumus.ai.presentation.common.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import com.autonoumus.ai.R
import com.autonoumus.ai.core.util.ShimmerDrawUtils

/**
 * A sleek animated loading placeholder for UI components that are fetching async data
 * (e.g. the Home screen's current-location card or street view camera cards).
 * Ticks its own redraw loop only while actually visible and attached, so it costs nothing once hidden.
 */
class ShimmerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var cornerRadiusPx = 12f * resources.displayMetrics.density
    private var baseColor = Color.parseColor("#E2E8F0")
    private var highlightColor = Color.parseColor("#FFFFFF")
    private var showTextPlaceholder = false
    private var textPlaceholderColor = Color.parseColor("#CBD5E1")
    private var textLines = 1

    private var isAnimating = false

    init {
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(it, R.styleable.ShimmerOverlayView, defStyleAttr, 0)
            cornerRadiusPx = typedArray.getDimension(R.styleable.ShimmerOverlayView_shimmerCornerRadius, cornerRadiusPx)
            baseColor = typedArray.getColor(R.styleable.ShimmerOverlayView_shimmerBaseColor, baseColor)
            highlightColor = typedArray.getColor(R.styleable.ShimmerOverlayView_shimmerHighlightColor, highlightColor)
            showTextPlaceholder = typedArray.getBoolean(R.styleable.ShimmerOverlayView_shimmerShowTextPlaceholder, showTextPlaceholder)
            textPlaceholderColor = typedArray.getColor(R.styleable.ShimmerOverlayView_shimmerTextPlaceholderColor, textPlaceholderColor)
            textLines = typedArray.getInt(R.styleable.ShimmerOverlayView_shimmerTextLines, textLines)
            typedArray.recycle()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!isShown || visibility != VISIBLE) {
                isAnimating = false
                return
            }
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun startShimmer() {
        if (!isAnimating && isShown && visibility == VISIBLE) {
            isAnimating = true
            removeCallbacks(tick)
            postOnAnimation(tick)
        }
    }

    private fun stopShimmer() {
        isAnimating = false
        removeCallbacks(tick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startShimmer()
    }

    override fun onDetachedFromWindow() {
        stopShimmer()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isShown) {
            startShimmer()
        } else {
            stopShimmer()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isShown) {
            startShimmer()
        } else {
            stopShimmer()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultWidth = (120 * resources.displayMetrics.density).toInt()
        val defaultHeight = (64 * resources.displayMetrics.density).toInt()

        val width = resolveSize(defaultWidth.coerceAtLeast(suggestedMinimumWidth), widthMeasureSpec)
        val height = resolveSize(defaultHeight.coerceAtLeast(suggestedMinimumHeight), heightMeasureSpec)

        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ShimmerDrawUtils.drawShimmer(
            canvas = canvas,
            width = width.toFloat(),
            height = height.toFloat(),
            cornerRadius = cornerRadiusPx,
            baseColor = baseColor,
            highlightColor = highlightColor,
            showTextPlaceholder = showTextPlaceholder,
            textPlaceholderColor = textPlaceholderColor,
            textLines = textLines
        )
    }
}

