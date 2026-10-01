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
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Custom Speedometer Gauge View matching the reference design:
 * - Wide soft background track with rounded caps
 * - Symmetrical 260° arc from 140° (0) to 400° (180), with 100 at 270° (top apex)
 * - Exact speed points: 0, 40, 80, 100, 120, 160, 180
 * - 3 colored background zones:
 *     - Safe (0..80): Royal Blue (#5263FF)
 *     - Warning (80..120): Amber (#F59E0B)
 *     - Over Speed (120..180): Red (#EF4444)
 * - Dynamic active speed highlight arc with smooth animations
 * - Digital HUD large readout & Analog needle gauge
 */
class SpeedometerDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentSpeed: Float = 0f
    private var displayedSpeed: Float = 0f
    private var maxScaleSpeed: Float = 180f
    private var unitText: String = "KM/H"
    private var isAnalogMode: Boolean = false

    // Geometry constants
    private val startAngle = 140f
    private val totalSweep = 260f

    // Color constants matching reference
    private val colorSafe = Color.parseColor("#5263FF")       // Royal Blue
    private val colorWarning = Color.parseColor("#F59E0B")    // Amber / Orange
    private val colorOverSpeed = Color.parseColor("#EF4444")  // Red
    private val colorTrackGrey = Color.parseColor("#E5E9F0")  // Soft light grey track
    private val colorTextDark = Color.parseColor("#0F172A")   // Deep Slate
    private val colorTextGray = Color.parseColor("#64748B")   // Slate 500

    // Key speed scale anchors (Speed to Angle)
    private val speedKeyPoints = listOf(
        Pair(0f, 140f),
        Pair(40f, 188f),
        Pair(80f, 232f),
        Pair(100f, 270f),
        Pair(120f, 308f),
        Pair(160f, 352f),
        Pair(180f, 400f)
    )

    // Paints
    private val greyTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = colorTrackGrey
    }

    private val colorZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val activeProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val scaleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = colorTextGray
        try {
            val tf = ResourcesCompat.getFont(context, R.font.instrument_sans_bold)
            typeface = tf ?: Typeface.DEFAULT_BOLD
        } catch (e: Exception) {
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private val centerSpeedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = colorTextDark
        try {
            val tf = ResourcesCompat.getFont(context, R.font.instrument_sans_bold)
            typeface = tf ?: Typeface.DEFAULT_BOLD
        } catch (e: Exception) {
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private val centerUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = colorTextGray
        try {
            val tf = ResourcesCompat.getFont(context, R.font.instrument_sans_bold)
            typeface = tf ?: Typeface.DEFAULT_BOLD
        } catch (e: Exception) {
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = colorSafe
    }

    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorTextDark
    }

    private val outerArcBounds = RectF()
    private val innerArcBounds = RectF()
    private var speedAnimator: ValueAnimator? = null

    fun setSpeed(speed: Float, animated: Boolean = true) {
        val target = speed.coerceIn(0f, maxScaleSpeed)
        currentSpeed = target
        if (target <= 0f) {
            speedAnimator?.cancel()
            displayedSpeed = 0f
            invalidate()
            return
        }
        if (animated) {
            speedAnimator?.cancel()
            speedAnimator = ValueAnimator.ofFloat(displayedSpeed, target).apply {
                duration = 280
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

    fun setUnit(unit: String) {
        unitText = unit.uppercase(Locale.US)
        invalidate()
    }

    fun setMode(analog: Boolean) {
        isAnalogMode = analog
        invalidate()
    }

    /**
     * Maps any speed [0..180] to its exact angular position along the dial arc.
     */
    private fun getAngleForSpeed(speed: Float): Float {
        val clamped = speed.coerceIn(0f, 180f)
        for (i in 0 until speedKeyPoints.size - 1) {
            val (s1, a1) = speedKeyPoints[i]
            val (s2, a2) = speedKeyPoints[i + 1]
            if (clamped in s1..s2) {
                val fraction = (clamped - s1) / (s2 - s1)
                return a1 + fraction * (a2 - a1)
            }
        }
        return speedKeyPoints.last().second
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val density = resources.displayMetrics.density
        val cx = w / 2f
        val cy = h * 0.58f

        val padding = 20f * density
        val outerRadius = (minOf(w, h) / 2f) - padding
        if (outerRadius <= 0) return

        val innerRadius = outerRadius - (10f * density)

        outerArcBounds.set(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius)
        innerArcBounds.set(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)

        // 1. Draw OUTER Broad Grey Background Track (Halo)
        val outerTrackWidth = 13f * density
        greyTrackPaint.strokeWidth = outerTrackWidth
        canvas.drawArc(outerArcBounds, startAngle, totalSweep, false, greyTrackPaint)

        // 2. Draw OUTER Dynamic Active Speed Progress Arc (Around top/outside)
        val activeAngle = getAngleForSpeed(displayedSpeed)
        val activeSweep = activeAngle - startAngle

        if (activeSweep > 0.5f && displayedSpeed > 0.5f) {
            activeProgressPaint.strokeWidth = outerTrackWidth
            activeProgressPaint.color = when {
                displayedSpeed <= 80f -> colorSafe
                displayedSpeed <= 120f -> colorWarning
                else -> colorOverSpeed
            }
            canvas.drawArc(outerArcBounds, startAngle, activeSweep, false, activeProgressPaint)
        }

        // 3. Draw INNER Concentric 3-Color Thin Zone Guide Line
        val innerStrokeWidth = 2.5f * density
        colorZonePaint.strokeWidth = innerStrokeWidth

        // Safe Blue zone: 140° -> 232° (0 -> 80 km/h)
        val angle80 = 232f
        val sweepBlue = angle80 - startAngle
        colorZonePaint.color = colorSafe
        canvas.drawArc(innerArcBounds, startAngle, sweepBlue, false, colorZonePaint)

        // Warning Amber zone: 232° -> 308° (80 -> 120 km/h)
        val angle120 = 308f
        val sweepAmber = angle120 - angle80
        colorZonePaint.color = colorWarning
        canvas.drawArc(innerArcBounds, angle80, sweepAmber, false, colorZonePaint)

        // Over Speed Red zone: 308° -> 400° (120 -> 180 km/h)
        val angle180 = 400f
        val sweepRed = angle180 - angle120
        colorZonePaint.color = colorOverSpeed
        canvas.drawArc(innerArcBounds, angle120, sweepRed, false, colorZonePaint)

        // 4. Draw Speed Scale Numbers (0, 40, 80, 100, 120, 160, 180) inside inner arc
        scaleTextPaint.textSize = 12f * density

        for ((value, angleDeg) in speedKeyPoints) {
            val angleRad = Math.toRadians(angleDeg.toDouble())

            val textRadius = when (value.toInt()) {
                0 -> innerRadius - (14f * density)
                180 -> innerRadius - (14f * density)
                100 -> innerRadius - (14f * density)
                else -> innerRadius - (16f * density)
            }

            var tx = cx + (textRadius * cos(angleRad)).toFloat()
            var ty = cy + (textRadius * sin(angleRad)).toFloat() + (4.5f * density)

            when (value.toInt()) {
                0 -> {
                    tx += 3f * density
                    ty -= 1f * density
                }
                180 -> {
                    tx -= 3f * density
                    ty -= 1f * density
                }
                100 -> {
                    ty += 2f * density
                }
            }

            canvas.drawText(value.toInt().toString(), tx, ty, scaleTextPaint)
        }

        // 5. Center Speed Readout ("00" or "60" + "KM/H") only in Digital mode
        if (!isAnalogMode) {
            val speedInt = displayedSpeed.toInt()
            val speedString = if (speedInt < 10) String.format(Locale.US, "%02d", speedInt) else speedInt.toString()

            centerSpeedPaint.textSize = 62f * density
            val speedY = cy + (6f * density)
            canvas.drawText(speedString, cx, speedY, centerSpeedPaint)

            centerUnitPaint.textSize = 13f * density
            val unitY = speedY + (24f * density)
            canvas.drawText(unitText, cx, unitY, centerUnitPaint)
        } else {
            // 6. Draw Needle Pointer in Analog Mode (No speed digits)
            val needleAngleDeg = getAngleForSpeed(displayedSpeed)
            val needleAngleRad = Math.toRadians(needleAngleDeg.toDouble())

            val needleLength = innerRadius - (22f * density)
            val nx = cx + (needleLength * cos(needleAngleRad)).toFloat()
            val ny = cy + (needleLength * sin(needleAngleRad)).toFloat()

            needlePaint.strokeWidth = 3.5f * density
            needlePaint.color = when {
                displayedSpeed <= 80f -> colorSafe
                displayedSpeed <= 120f -> colorWarning
                else -> colorOverSpeed
            }

            canvas.drawLine(cx, cy, nx, ny, needlePaint)

            // Center pivot outer circle
            pivotPaint.color = colorTextDark
            canvas.drawCircle(cx, cy, 8f * density, pivotPaint)

            // Center pivot inner dot
            val pivotDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = when {
                    displayedSpeed <= 80f -> colorSafe
                    displayedSpeed <= 120f -> colorWarning
                    else -> colorOverSpeed
                }
            }
            canvas.drawCircle(cx, cy, 3.5f * density, pivotDotPaint)
        }
    }
}
