package com.autonoumus.ai.core.stamp.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import androidx.appcompat.widget.AppCompatImageView
import com.autonoumus.ai.R

/**
 * An ImageView that guarantees rounded rect or circular clipping on all Canvas rendering pipelines
 * using direct BitmapShader geometry rendering. Safe against superclass constructor callbacks.
 */
class RoundedImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val isInitialized: Boolean

    var cornerRadiusDp: Float = 14f
        set(value) {
            field = value
            invalidateOutline()
            invalidate()
        }

    var isCircle: Boolean = false
        set(value) {
            field = value
            invalidateOutline()
            invalidate()
        }

    var strokeWidthDp: Float = 1.0f
        set(value) {
            field = value
            strokePaint.strokeWidth = value * resources.displayMetrics.density
            invalidate()
        }

    var strokeColor: Int = Color.parseColor("#30FFFFFF")
        set(value) {
            field = value
            strokePaint.color = value
            invalidate()
        }

    private val clipPath = Path()
    private val viewRect = RectF()
    private val shaderMatrix = Matrix()

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * resources.displayMetrics.density
        color = Color.parseColor("#30FFFFFF")
    }

    private var bitmap: Bitmap? = null
    private var bitmapShader: BitmapShader? = null

    init {
        isInitialized = true
        scaleType = ScaleType.CENTER_CROP

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.RoundedImageView, defStyleAttr, 0)
            val defaultRadiusPx = 14f * resources.displayMetrics.density
            val radiusPx = a.getDimension(R.styleable.RoundedImageView_riv_cornerRadius, defaultRadiusPx)
            cornerRadiusDp = radiusPx / resources.displayMetrics.density
            isCircle = a.getBoolean(R.styleable.RoundedImageView_riv_isCircle, false)
            val strokePx = a.getDimension(R.styleable.RoundedImageView_riv_strokeWidth, 1f * resources.displayMetrics.density)
            strokeWidthDp = strokePx / resources.displayMetrics.density
            strokeColor = a.getColor(R.styleable.RoundedImageView_riv_strokeColor, Color.parseColor("#30FFFFFF"))
            a.recycle()
        }

        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width <= 0 || view.height <= 0) return
                if (isCircle) {
                    val size = minOf(view.width, view.height)
                    val left = (view.width - size) / 2
                    val top = (view.height - size) / 2
                    outline.setOval(left, top, left + size, top + size)
                } else {
                    val radius = cornerRadiusDp * resources.displayMetrics.density
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }
        clipToOutline = true

        initializeBitmap()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        if (isInitialized) {
            initializeBitmap()
        }
    }

    override fun setImageResource(resId: Int) {
        super.setImageResource(resId)
        if (isInitialized) {
            initializeBitmap()
        }
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        if (isInitialized) {
            initializeBitmap()
        }
    }

    private fun getBitmapFromDrawable(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable) return drawable.bitmap

        return try {
            val bmp = if (drawable is ColorDrawable) {
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            } else {
                val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 160
                val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 160
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        } catch (e: Exception) {
            null
        }
    }

    private fun initializeBitmap() {
        bitmap = getBitmapFromDrawable(drawable)
        setupShader()
    }

    private fun setupShader() {
        val bmp = bitmap ?: return
        bitmapShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        bitmapPaint.shader = bitmapShader
        updateShaderMatrix()
        invalidate()
    }

    private fun updateShaderMatrix() {
        val bmp = bitmap ?: return
        val w = if (width > 0) width.toFloat() else (layoutParams?.width?.toFloat() ?: 0f)
        val h = if (height > 0) height.toFloat() else (layoutParams?.height?.toFloat() ?: 0f)
        if (w <= 0f || h <= 0f) return

        shaderMatrix.set(null)
        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()
        if (bmpW <= 0f || bmpH <= 0f) return

        val scale: Float
        var dx = 0f
        var dy = 0f

        if (bmpW * h > w * bmpH) {
            scale = h / bmpH
            dx = (w - bmpW * scale) * 0.5f
        } else {
            scale = w / bmpW
            dy = (h - bmpH * scale) * 0.5f
        }

        shaderMatrix.setScale(scale, scale)
        shaderMatrix.postTranslate(dx, dy)
        bitmapShader?.setLocalMatrix(shaderMatrix)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShaderMatrix()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            super.onDraw(canvas)
            return
        }

        if (bitmap == null && drawable != null) {
            initializeBitmap()
        }

        val density = resources.displayMetrics.density
        val radiusPx = if (isCircle) (minOf(w, h) / 2f) else (cornerRadiusDp * density)
        val halfStroke = strokePaint.strokeWidth / 2f

        viewRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke)

        if (bitmapPaint.shader != null) {
            updateShaderMatrix()
            if (isCircle) {
                val cx = w / 2f
                val cy = h / 2f
                val r = (minOf(w, h) - strokePaint.strokeWidth) / 2f
                canvas.drawCircle(cx, cy, r, bitmapPaint)
            } else {
                canvas.drawRoundRect(viewRect, radiusPx, radiusPx, bitmapPaint)
            }
        } else {
            clipPath.reset()
            if (isCircle) {
                clipPath.addCircle(w / 2f, h / 2f, (minOf(w, h) - strokePaint.strokeWidth) / 2f, Path.Direction.CW)
            } else {
                clipPath.addRoundRect(viewRect, radiusPx, radiusPx, Path.Direction.CW)
            }
            val saveCount = canvas.save()
            canvas.clipPath(clipPath)
            super.onDraw(canvas)
            canvas.restoreToCount(saveCount)
        }

        // Draw border stroke
        if (strokePaint.strokeWidth > 0f && strokePaint.color != Color.TRANSPARENT) {
            if (isCircle) {
                val cx = w / 2f
                val cy = h / 2f
                val r = (minOf(w, h) - strokePaint.strokeWidth) / 2f
                canvas.drawCircle(cx, cy, r, strokePaint)
            } else {
                canvas.drawRoundRect(viewRect, radiusPx, radiusPx, strokePaint)
            }
        }
    }
}
