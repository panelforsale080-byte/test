package com.example.humanoiddetector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A transparent, non-touchable overlay that draws bounding boxes around every
 * detected person. It only draws — it never receives or injects touches.
 */
class OverlayView(context: Context) : View(context) {

    private val detections = CopyOnWriteArrayList<DetectionResult>()

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * resources.displayMetrics.density
        color = Color.rgb(0, 200, 255)
        isAntiAlias = true
    }

    private val emphasizePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * resources.displayMetrics.density
        color = Color.rgb(255, 80, 80)
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 14f * resources.displayMetrics.density
        isAntiAlias = true
    }

    fun postDetections(list: List<DetectionResult>) {
        detections.clear()
        detections.addAll(list)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (d in detections) {
            val paint = if (d.emphasized) emphasizePaint else boxPaint
            canvas.drawRect(d.left, d.top, d.right, d.bottom, paint)
            val label = "${d.label} ${(d.score * 100).toInt()}%"
            canvas.drawText(label, d.left, (d.top - 8f).coerceAtLeast(0f), labelPaint)
        }
    }
}
