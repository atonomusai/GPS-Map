package com.autonoumus.ai.presentation.camera.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import com.autonoumus.ai.core.stamp.StampRenderer
import com.autonoumus.ai.domain.model.StampData
import com.autonoumus.ai.domain.model.StampSettings

/**
 * Renders the live stamp preview as an ordinary Android view drawn on top of the camera [PreviewView],
 * using this view's own (guaranteed-correct) width/height. Deliberately independent of CameraX's
 * `OverlayEffect` GL pipeline - that pipeline still burns the stamp into saved photos/videos, but no
 * longer has to get the *visible* preview's coordinate mapping right, sidestepping a real, documented
 * CameraX coordinate-space gotcha (the sensor buffer OverlayEffect draws into is cropped/scaled/rotated
 * to fill PreviewView, which is a persistent source of mispositioned overlays). This view is simply
 * what the user sees - no transforms, no per-frame guessing.
 */
class StampOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var stampRenderer: StampRenderer? = null
    var onStampClickListener: (() -> Unit)? = null
    var onEditClickListener: (() -> Unit)? = null
    var onTemplateClickListener: (() -> Unit)? = null

    private var data: StampData? = null
    private var settings: StampSettings = StampSettings()
    private var orientationDegrees: Int = 0
    private var isLoading: Boolean = false

    // Plain Views only redraw when told to - drives the shimmer's animated sweep with a
    // Choreographer-synced tick while a location fix is being fetched, and stops itself once
    // real data arrives so there's no wasted redrawing the rest of the time.
    private val shimmerTick = object : Runnable {
        override fun run() {
            if (!isLoading) return
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        if (isInEditMode) {
            val themedCtx = androidx.appcompat.view.ContextThemeWrapper(context, com.autonoumus.ai.R.style.Theme_GSMCAMER)
            stampRenderer = StampRenderer(themedCtx, com.autonoumus.ai.core.stamp.XmlStampRenderer(themedCtx))
            data = StampData(
                titleLine = "Eiffel Tower, Paris",
                addressLine = "5 Av. Anatole France, Paris",
                coordinatesLine = "48.8584° N, 2.2945° E",
                timestampLine = "24/10/2025 • 03:09 PM",
                gpsStatusLine = "GPS LOCKED",
                dateLine = "24/10/2025",
                timeLine = "03:09 PM",
                timeZoneLine = "UTC+1"
            )
        }
    }

    fun updateStamp(data: StampData?, settings: StampSettings, isLoading: Boolean = false) {
        this.data = data
        this.settings = settings
        val wasLoading = this.isLoading
        this.isLoading = isLoading
        invalidate()
        if (isLoading && !wasLoading) {
            postOnAnimation(shimmerTick)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(shimmerTick)
        super.onDetachedFromWindow()
    }

    fun setOrientation(degrees: Int) {
        if (orientationDegrees != degrees) {
            orientationDegrees = degrees
            invalidate()
        }
    }

    private var customCanvasRect: android.graphics.RectF? = null

    fun setCustomCanvasRect(rect: android.graphics.RectF?) {
        if (customCanvasRect != rect) {
            customCanvasRect = rect
            invalidate()
        }
    }

    private data class TouchStampResult(val isInsideStamp: Boolean, val isOnEditIcon: Boolean)

    private fun checkTouchLocation(x: Float, y: Float): TouchStampResult {
        val currentData = data ?: return TouchStampResult(false, false)
        if (width <= 0 || height <= 0) return TouchStampResult(false, false)
        val renderer = stampRenderer ?: return TouchStampResult(false, false)

        val targetRect = customCanvasRect ?: android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat())
        val cw = targetRect.width().toInt()
        val ch = targetRect.height().toInt()
        if (cw <= 0 || ch <= 0) return TouchStampResult(false, false)

        val localXInTarget = x - targetRect.left
        val localYInTarget = y - targetRect.top

        // Map screen touch coordinates back to local canvas coordinates (lx, ly)
        val (lx, ly, hitBounds) = when (orientationDegrees) {
            270 -> {
                // Landscape Left: canvas was drawn with (w = height, h = width) and translate(width, 0), rotate(90)
                val localX = localYInTarget
                val localY = cw.toFloat() - localXInTarget
                val hit = renderer.computeHitTestBounds(ch, cw, currentData, settings)
                Triple(localX, localY, hit)
            }
            90 -> {
                // Landscape Right: canvas was drawn with (w = height, h = width) and translate(0, height), rotate(-90)
                val localX = ch.toFloat() - localYInTarget
                val localY = localXInTarget
                val hit = renderer.computeHitTestBounds(ch, cw, currentData, settings)
                Triple(localX, localY, hit)
            }
            180 -> {
                // Upside down: canvas was drawn with (w = width, h = height) and translate(width, height), rotate(180)
                val localX = cw.toFloat() - localXInTarget
                val localY = ch.toFloat() - localYInTarget
                val hit = renderer.computeHitTestBounds(cw, ch, currentData, settings)
                Triple(localX, localY, hit)
            }
            else -> {
                // Normal Portrait: canvas was drawn with (w = width, h = height)
                val hit = renderer.computeHitTestBounds(cw, ch, currentData, settings)
                Triple(localXInTarget, localYInTarget, hit)
            }
        }

        val bounds = hitBounds.stampBounds
        val editBounds = hitBounds.editIconBounds
        val density = resources.displayMetrics.density

        // 1. Check if touch is on Edit Icon with generous touch target (+20dp padding around icon)
        if (editBounds != null) {
            val iconTouchPadding = 20f * density
            val editTouchRect = android.graphics.RectF(
                editBounds.left - iconTouchPadding,
                editBounds.top - iconTouchPadding,
                editBounds.right + iconTouchPadding,
                editBounds.bottom + iconTouchPadding
            )
            if (editTouchRect.contains(lx, ly)) {
                return TouchStampResult(isInsideStamp = true, isOnEditIcon = true)
            }
        }

        // 2. Check if touch is anywhere inside the stamp card (+16dp touch padding)
        val cardTouchPadding = 16f * density
        val cardTouchRect = android.graphics.RectF(
            bounds.left - cardTouchPadding,
            bounds.top - cardTouchPadding,
            bounds.right + cardTouchPadding,
            bounds.bottom + cardTouchPadding
        )

        if (cardTouchRect.contains(lx, ly)) {
            val isEditFallback = editBounds == null && lx >= (bounds.right - 48f * density)
            return TouchStampResult(isInsideStamp = true, isOnEditIcon = isEditFallback)
        }

        return TouchStampResult(isInsideStamp = false, isOnEditIcon = false)
    }

    private var isDownInsideStamp = false
    private var isDownOnEditIcon = false

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (data == null) return false
        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                val result = checkTouchLocation(event.x, event.y)
                isDownInsideStamp = result.isInsideStamp
                isDownOnEditIcon = result.isOnEditIcon
                return isDownInsideStamp
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (isDownInsideStamp) {
                    val wasEdit = isDownOnEditIcon
                    isDownInsideStamp = false
                    isDownOnEditIcon = false
                    performClick()
                    if (wasEdit) {
                        onEditClickListener?.invoke() ?: onStampClickListener?.invoke()
                    } else {
                        onTemplateClickListener?.invoke() ?: onStampClickListener?.invoke()
                    }
                    return true
                }
                isDownInsideStamp = false
                isDownOnEditIcon = false
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                isDownInsideStamp = false
                isDownOnEditIcon = false
            }
        }
        return isDownInsideStamp
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // While the very first location fix is still being fetched there may be no real data
        // yet at all - fall back to an empty placeholder just for sizing so the shimmer still
        // renders at the correct template shape/position instead of showing nothing.
        val currentData = data ?: if (isLoading) EMPTY_LOADING_DATA else return
        if (width <= 0 || height <= 0) return

        val targetRect = customCanvasRect ?: android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat())
        val cw = targetRect.width().toInt()
        val ch = targetRect.height().toInt()
        if (cw <= 0 || ch <= 0) return

        canvas.save()
        canvas.translate(targetRect.left, targetRect.top)
        canvas.clipRect(0f, 0f, targetRect.width(), targetRect.height())

        when (orientationDegrees) {
            270 -> {
                // Device rotated 90° CCW (Landscape Left, top is to the left)
                canvas.save()
                canvas.translate(cw.toFloat(), 0f)
                canvas.rotate(90f)
                stampRenderer?.draw(canvas, ch, cw, currentData, settings, isLivePreview = true, isLoading = isLoading)
                canvas.restore()
            }
            90 -> {
                // Device rotated 90° CW (Landscape Right, top is to the right)
                canvas.save()
                canvas.translate(0f, ch.toFloat())
                canvas.rotate(-90f)
                stampRenderer?.draw(canvas, ch, cw, currentData, settings, isLivePreview = true, isLoading = isLoading)
                canvas.restore()
            }
            180 -> {
                // Device upside down
                canvas.save()
                canvas.translate(cw.toFloat(), ch.toFloat())
                canvas.rotate(180f)
                stampRenderer?.draw(canvas, cw, ch, currentData, settings, isLivePreview = true, isLoading = isLoading)
                canvas.restore()
            }
            else -> {
                // Normal portrait
                stampRenderer?.draw(canvas, cw, ch, currentData, settings, isLivePreview = true, isLoading = isLoading)
            }
        }

        canvas.restore()
    }

    companion object {
        private val EMPTY_LOADING_DATA = StampData(
            titleLine = "",
            addressLine = "",
            coordinatesLine = "",
            timestampLine = "",
            gpsStatusLine = ""
        )
    }
}
