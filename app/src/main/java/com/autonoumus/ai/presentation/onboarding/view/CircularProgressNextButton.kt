package com.autonoumus.ai.presentation.onboarding.view

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.autonoumus.ai.R

class CircularProgressNextButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progressPercent = 33f
    private var isLastPage = false

    private val primaryColor = ContextCompat.getColor(context, R.color.primary)
    private val inactiveButtonColor = ContextCompat.getColor(context, R.color.text_hint)
    private val trackColor = ContextCompat.getColor(context, R.color.primary_light)

    private var currentButtonColor = inactiveButtonColor
    private val argbEvaluator = ArgbEvaluator()

    private val strokeWidthPx = dpToPx(3f)
    private val ringGap = dpToPx(4f)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = trackColor
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
        color = primaryColor
    }

    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = inactiveButtonColor
    }

    private val arrowDrawable: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.ic_arrow_white)

    private val ringBounds = RectF()
    private var progressAnimator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
    }

    fun setProgress(percent: Float, isFinal: Boolean, animate: Boolean = true) {
        isLastPage = isFinal
        val targetColor = if (isFinal) primaryColor else inactiveButtonColor

        if (!animate) {
            progressPercent = percent
            currentButtonColor = targetColor
            buttonPaint.color = currentButtonColor
            invalidate()
            return
        }

        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofFloat(progressPercent, percent).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progressPercent = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofObject(argbEvaluator, currentButtonColor, targetColor).apply {
            duration = 350
            addUpdateListener {
                currentButtonColor = it.animatedValue as Int
                buttonPaint.color = currentButtonColor
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dpToPx(62f).toInt()
        val desiredWidth = size + paddingLeft + paddingRight
        val desiredHeight = size + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = (width.coerceAtMost(height)).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (size - strokeWidthPx) / 2f

        // 1. Draw outer progress track
        ringBounds.set(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )
        canvas.drawOval(ringBounds, trackPaint)

        // 2. Draw outer progress arc
        val sweepAngle = (progressPercent / 100f) * 360f
        canvas.drawArc(ringBounds, -90f, sweepAngle, false, progressPaint)

        // 3. Draw inner circle button
        val innerRadius = radius - strokeWidthPx / 2f - ringGap
        canvas.drawCircle(centerX, centerY, innerRadius, buttonPaint)

        // 4. Draw center white arrow
        arrowDrawable?.let { arrow ->
            val arrowSize = dpToPx(20f).toInt()
            val left = (centerX - arrowSize / 2f).toInt()
            val top = (centerY - arrowSize / 2f).toInt()
            arrow.setBounds(left, top, left + arrowSize, top + arrowSize)
            arrow.draw(canvas)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start()
            }
            MotionEvent.ACTION_UP -> {
                animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
            }
        }
        return true
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
