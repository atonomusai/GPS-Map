package com.autonoumus.ai.core.util

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * Draws a simple animated "shimmer" placeholder: a rounded base rect with a light gradient band
 * sweeping across it. Used wherever real content (the camera GPS stamp, the Home location card,
 * or street view camera list) isn't ready yet.
 *
 * Stateless by design - the sweep position is derived from wall-clock time, so callers just need
 * to invalidate/redraw periodically (e.g. via `View.postOnAnimation`) and this always animates
 * smoothly with no separate animator object to manage or leak.
 */
object ShimmerDrawUtils {

    private const val PERIOD_MS = 1300L

    fun drawShimmer(
        canvas: Canvas,
        width: Float,
        height: Float,
        cornerRadius: Float,
        baseColor: Int = Color.parseColor("#33FFFFFF"),
        highlightColor: Int = Color.parseColor("#80FFFFFF"),
        showTextPlaceholder: Boolean = false,
        textPlaceholderColor: Int = Color.parseColor("#CBD5E1"),
        textLines: Int = 1
    ) {
        if (width <= 0f || height <= 0f) return
        val rect = RectF(0f, 0f, width, height)

        // 1. Draw base card background
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = baseColor }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, basePaint)

        // 2. Draw text placeholder pill(s) if enabled (e.g. for card skeletons)
        if (showTextPlaceholder && textLines > 1) {
            // Multi-line skeleton: one bar per text line, evenly spread, first line bolder/wider.
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textPlaceholderColor }
            val slot = height / textLines
            for (i in 0 until textLines) {
                val barH = (slot * if (i == 0) 0.46f else 0.36f).coerceAtMost(14f * (height / 64f).coerceIn(0.8f, 1.6f))
                val barW = width * when (i) { 0 -> 0.42f; 1 -> 0.78f; else -> 0.58f }
                val top = slot * i + (slot - barH) / 2f
                canvas.drawRoundRect(RectF(0f, top, barW, top + barH), barH / 2f, barH / 2f, textPaint)
            }
        } else if (showTextPlaceholder) {
            val textH = 10f * (height / 90f).coerceIn(0.8f, 1.4f)
            val textW = (width * 0.62f).coerceAtLeast(30f)
            val textMarginX = 10f * (width / 140f).coerceIn(0.8f, 1.4f)
            val textMarginBottom = 10f * (height / 90f).coerceIn(0.8f, 1.4f)
            val textRect = RectF(
                textMarginX,
                height - textMarginBottom - textH,
                textMarginX + textW,
                height - textMarginBottom
            )
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textPlaceholderColor }
            val pillRadius = textH / 2f
            canvas.drawRoundRect(textRect, pillRadius, pillRadius, textPaint)
        }

        // 3. Compute continuous sweep phase
        val phase = (System.currentTimeMillis() % PERIOD_MS).toFloat() / PERIOD_MS
        val sweepWidth = width * 0.85f
        val sweepX = -sweepWidth + phase * (width + sweepWidth * 1.6f)

        // Clean RGB interpolation with matching alpha channels
        val highR = Color.red(highlightColor)
        val highG = Color.green(highlightColor)
        val highB = Color.blue(highlightColor)
        val highAlpha = Color.alpha(highlightColor)
        val centerAlpha = if (highAlpha == 255) 210 else highAlpha
        val transparentHighlight = Color.argb(0, highR, highG, highB)
        val centerHighlight = Color.argb(centerAlpha, highR, highG, highB)

        val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                sweepX, 0f, sweepX + sweepWidth, 0f,
                intArrayOf(transparentHighlight, centerHighlight, transparentHighlight),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        canvas.save()
        canvas.clipRect(rect)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, shimmerPaint)
        canvas.restore()
    }
}

