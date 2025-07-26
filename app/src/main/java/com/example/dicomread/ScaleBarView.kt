package com.example.dicomread

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

class ScaleBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }

    // ★★★ 核心数据: 视图中1个单位(归一化后的)对应多少毫米(mm) ★★★
    private var mmPerUnit: Float = 0f
    private var currentScale: Float = 1f

    /**
     * 更新比例尺的参数
     * @param modelPhysicalWidth 模型的实际物理宽度(mm)，用于计算mm/unit
     * @param viewContentScale  视图当前的缩放系数
     */
    fun update(modelPhysicalWidth: Float, viewContentScale: Float) {
        if (modelPhysicalWidth > 0f) {
            // 模型在C++中被归一化到2个单位宽, 所以 mm/unit = 物理宽度 / 2
            this.mmPerUnit = modelPhysicalWidth / 2.0f
            this.currentScale = viewContentScale
            visibility = View.VISIBLE
            invalidate()
        } else {
            visibility = View.GONE
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mmPerUnit <= 0f || width == 0) {
            return
        }

        // 1. 计算当前1mm在屏幕上占多少像素
        val pixelsPerUnit = (width / 2.0f) * currentScale
        val pixelsPerMm = pixelsPerUnit / mmPerUnit

        // 2. 确定一个合适的人类易读的比例尺长度（如 10mm, 20mm, 50mm）
        // 目标是让比例尺的屏幕长度在视图宽度的1/4左右
        val targetScreenLength = width / 4f
        val idealPhysicalLength = targetScreenLength / pixelsPerMm

        // ★★★ 核心算法: 使用健壮的算法计算 "nice number" ★★★
        val exponent = floor(log10(idealPhysicalLength))
        val powerOf10 = 10f.pow(exponent)
        val fraction = idealPhysicalLength / powerOf10

        val niceFraction = when {
            fraction < 1.5f -> 1f
            fraction < 3f -> 2f
            fraction < 7f -> 5f
            else -> 10f
        }

        val finalPhysicalLength = niceFraction * powerOf10
        val finalScreenLength = finalPhysicalLength * pixelsPerMm

        // 3. 绘制比例尺
        val y = height - 25f // 距离底部25px
        val startX = 15f
        val endX = startX + finalScreenLength

        // 绘制主横线
        canvas.drawLine(startX, y, endX, y, linePaint)
        // 绘制两端的竖线
        canvas.drawLine(startX, y - 10, startX, y + 10, linePaint)
        canvas.drawLine(endX, y - 10, endX, y + 10, linePaint)

        // 4. 绘制文本
        val text = if (finalPhysicalLength < 10) {
            "${"%.1f".format(finalPhysicalLength)} mm"
        } else {
            "${finalPhysicalLength.roundToInt()} mm"
        }
        canvas.drawText(text, startX + finalScreenLength / 2, y - 20, textPaint)
    }
}
