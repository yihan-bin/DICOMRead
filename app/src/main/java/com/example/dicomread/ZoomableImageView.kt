package com.example.dicomread

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.*

// ★★★ 理由: 自实现缩放、平移、旋转，替代PhotoView，减少依赖，并提供更符合需求的交互 ★★★
class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    interface OnSwipeListener { fun onSwipeUp(); fun onSwipeDown() }
    private var swipeListener: OnSwipeListener? = null
    private val imageMatrixInternal = Matrix()
    private var minScale = 1.0f
    private val maxScale = 10.0f
    private var currentScaleValues = FloatArray(9)
    private var viewWidth = 0f
    private var viewHeight = 0f
    private var originalBitmapWidth = 0f
    private var originalBitmapHeight = 0f
    private var scrollAccumulatorY = 0f

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    private var pixelSpacing: DoubleArray? = null
    private val scaleBarPaint = Paint().apply { color = Color.WHITE; strokeWidth = 4f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE; setShadowLayer(5f, 2f, 2f, Color.BLACK) }
    private val scaleBarTextPaint = Paint().apply { color = Color.WHITE; textSize = 28f; isAntiAlias = true; setShadowLayer(5f, 2f, 2f, Color.BLACK) }

    companion object { private const val SCRUB_SENSITIVITY_PIXELS = 30 }

    init {
        super.setScaleType(ScaleType.MATRIX)
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
    }
    private val rotationDetector: RotationGestureDetector by lazy {
        RotationGestureDetector(object : RotationGestureDetector.OnRotationGestureListener {
            override fun onRotate(degrees: Float, focusX: Float, focusY: Float) {
                imageMatrixInternal.postRotate(degrees, focusX, focusY)
                checkAndCorrectBounds()
                imageMatrix = imageMatrixInternal
            }
        })
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = false

        // 首先处理旋转手势（双指）
        rotationDetector.onTouchEvent(event)

        // 然后处理缩放手势
        handled = scaleDetector.onTouchEvent(event) || handled

        // 最后处理普通手势（如果没有在进行缩放）
        if (!scaleDetector.isInProgress) {
            handled = gestureDetector.onTouchEvent(event) || handled
        }

        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            scrollAccumulatorY = 0f
        }

        return handled || super.onTouchEvent(event)
    }


    // ★★★ 理由: 优化边界检查，允许图像在缩放时自由移动，体验更好 ★★★
    private fun checkAndCorrectBounds() {
        val rect = getDrawableRect() ?: return
        var deltaX = 0f; var deltaY = 0f
        val requiredOverlap = 50f

        // 如果图像比视图大，确保至少有50px的重叠部分在屏幕内
        if(rect.width() > viewWidth) {
            if (rect.right < requiredOverlap) deltaX = requiredOverlap - rect.right
            if (rect.left > viewWidth - requiredOverlap) deltaX = (viewWidth - requiredOverlap) - rect.left
        } else { // 如果图像比视图小，则居中
            deltaX = (viewWidth - rect.width()) / 2 - rect.left
        }

        if (rect.height() > viewHeight) {
            if (rect.bottom < requiredOverlap) deltaY = requiredOverlap - rect.bottom
            if (rect.top > viewHeight - requiredOverlap) deltaY = (viewHeight - requiredOverlap) - rect.top
        } else {
            deltaY = (viewHeight - rect.height()) / 2 - rect.top
        }

        imageMatrixInternal.postTranslate(deltaX, deltaY)
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scrollAccumulatorY = 0f; return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            imageMatrixInternal.postTranslate(-distanceX, -distanceY)
            checkAndCorrectBounds()
            imageMatrix = imageMatrixInternal

            if (!isImageZoomed()) {
                scrollAccumulatorY += distanceY
                while (scrollAccumulatorY >= SCRUB_SENSITIVITY_PIXELS) { swipeListener?.onSwipeUp(); scrollAccumulatorY -= SCRUB_SENSITIVITY_PIXELS }
                while (scrollAccumulatorY <= -SCRUB_SENSITIVITY_PIXELS) { swipeListener?.onSwipeDown(); scrollAccumulatorY += SCRUB_SENSITIVITY_PIXELS }
            }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isImageZoomed()) {
                resetZoom()
            } else {
                val newScale = minScale * 2.5f
                imageMatrixInternal.postScale(newScale / getCurrentScale(), newScale / getCurrentScale(), e.x, e.y)
                checkAndCorrectBounds()
                imageMatrix = imageMatrixInternal
            }
            return true
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val currentScale = getCurrentScale()
            var newScale = currentScale * detector.scaleFactor
            newScale = max(minScale, min(newScale, maxScale))
            val actualScale = newScale / currentScale
            imageMatrixInternal.postScale(actualScale, actualScale, detector.focusX, detector.focusY)
            checkAndCorrectBounds()
            imageMatrix = imageMatrixInternal
            return true
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (drawable != null && (pixelSpacing?.size ?: 0) >= 2) {
            drawScaleBar(canvas)
        }
    }

    private fun drawScaleBar(canvas: Canvas) {
        val spacingX = pixelSpacing?.getOrNull(1) ?: return; if (spacingX <= 0) return
        val currentScale = getCurrentScale(); val pixelsPerMm = (1.0 / spacingX) * currentScale
        val targetScreenLength = 120f; val idealPhysicalLength = targetScreenLength / pixelsPerMm
        val magnitude = 10.0.pow(floor(log10(idealPhysicalLength)))
        val residual = idealPhysicalLength / magnitude
        val niceLength = when {
            residual < 1.5 -> 1.0
            residual < 3.5 -> 2.0
            residual < 7.5 -> 5.0
            else -> 10.0
        } * magnitude
        val finalPhysicalLength = niceLength.toInt()
        val finalScreenLength = (finalPhysicalLength * pixelsPerMm).toFloat()
        val startX = 40f; val y = height - 40f; val endX = startX + finalScreenLength
        val text = "$finalPhysicalLength mm"
        canvas.drawText(text, startX, y - 20, scaleBarTextPaint)
        canvas.drawLine(startX, y, endX, y, scaleBarPaint)
        canvas.drawLine(startX, y - 10, startX, y + 10, scaleBarPaint)
        canvas.drawLine(endX, y - 10, endX, y + 10, scaleBarPaint)
    }

    fun setOnSwipeListener(listener: OnSwipeListener) { this.swipeListener = listener }

    fun setImageBitmap(bm: Bitmap?, pixelSpacing: DoubleArray?) {
        super.setImageBitmap(bm)
        this.pixelSpacing = pixelSpacing
        if (bm != null) {
            this.originalBitmapWidth = bm.width.toFloat()
            this.originalBitmapHeight = bm.height.toFloat()
            resetZoom()
        } else {
            imageMatrixInternal.reset(); imageMatrix = imageMatrixInternal
        }
        invalidate()
    }

    private fun isImageZoomed(): Boolean = getCurrentScale() > minScale * 1.01f
    private fun getCurrentScale(): Float { imageMatrixInternal.getValues(currentScaleValues); return currentScaleValues[Matrix.MSCALE_X] }

    fun resetZoom() {
        if (originalBitmapWidth == 0f || viewWidth == 0f) return
        imageMatrixInternal.reset()
        val scaleX = viewWidth / originalBitmapWidth; val scaleY = viewHeight / originalBitmapHeight
        minScale = min(scaleX, scaleY)
        val redundantXSpace = viewWidth - (minScale * originalBitmapWidth)
        val redundantYSpace = viewHeight - (minScale * originalBitmapHeight)
        imageMatrixInternal.postScale(minScale, minScale)
        imageMatrixInternal.postTranslate(redundantXSpace / 2, redundantYSpace / 2)
        imageMatrix = imageMatrixInternal
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            this.viewWidth = w.toFloat(); this.viewHeight = h.toFloat()
            if (drawable != null) resetZoom()
        }
    }

    private fun getDrawableRect(): RectF? {
        val d = drawable ?: return null
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        imageMatrixInternal.mapRect(rect)
        return rect
    }
}
