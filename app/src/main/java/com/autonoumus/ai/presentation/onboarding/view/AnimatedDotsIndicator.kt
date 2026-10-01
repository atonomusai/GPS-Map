package com.autonoumus.ai.presentation.onboarding.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.autonoumus.ai.R

class AnimatedDotsIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var dotsCount = 3
    private var currentPage = 0
    private var pageOffset = 0f

    private val dotRadius = dpToPx(3f)
    private val dotDiameter = dotRadius * 2
    private val activeDotWidth = dpToPx(24f)
    private val dotSpacing = dpToPx(6f)

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary_light)
        style = Paint.Style.FILL
    }

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        style = Paint.Style.FILL
    }

    private val dotRect = RectF()

    fun setCount(count: Int) {
        dotsCount = count
        requestLayout()
        invalidate()
    }

    fun attachToViewPager(viewPager: ViewPager2) {
        dotsCount = viewPager.adapter?.itemCount ?: 3
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
                currentPage = position
                pageOffset = positionOffset
                invalidate()
            }

            override fun onPageSelected(position: Int) {
                currentPage = position
                pageOffset = 0f
                invalidate()
            }
        })
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = if (dotsCount > 0) {
            activeDotWidth + (dotsCount - 1) * (dotDiameter + dotSpacing)
        } else {
            0f
        }
        val desiredWidth = totalWidth.toInt() + paddingLeft + paddingRight
        val desiredHeight = dotDiameter.toInt() + paddingTop + paddingBottom

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dotsCount <= 0) return

        val centerY = (height / 2f)
        var startX = paddingLeft.toFloat()

        for (i in 0 until dotsCount) {
            val isCurrent = (i == currentPage)
            val isNext = (i == currentPage + 1)

            val currentWidth = when {
                isCurrent -> {
                    // Smoothly shrink from activeDotWidth down to dotDiameter
                    activeDotWidth - (activeDotWidth - dotDiameter) * pageOffset
                }
                isNext -> {
                    // Smoothly expand from dotDiameter up to activeDotWidth
                    dotDiameter + (activeDotWidth - dotDiameter) * pageOffset
                }
                else -> dotDiameter
            }

            val paint = when {
                isCurrent -> {
                    if (pageOffset == 0f) activePaint else activePaint
                }
                isNext && pageOffset > 0f -> activePaint
                else -> inactivePaint
            }

            dotRect.set(startX, centerY - dotRadius, startX + currentWidth, centerY + dotRadius)
            canvas.drawRoundRect(dotRect, dotRadius, dotRadius, paint)

            startX += currentWidth + dotSpacing
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
