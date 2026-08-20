package com.example.humanoiddetector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Transparent, non-touchable overlay that draws a thin 33-joint skeleton over
 * each detected body. Only draws — never receives or injects touches.
 */
class OverlayView(context: Context) : View(context) {

    private val poses = CopyOnWriteArrayList<PoseResult>()

    /** Visibility threshold before a joint is dropped from the drawing. */
    @Volatile
    var jointMinVisibility: Float = 0.5f

    private val bonePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density   // thin
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(0, 200, 255)
        isAntiAlias = true
    }

    private val boneEmphPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(255, 80, 80)
        isAntiAlias = true
    }

    private val jointPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.rgb(0, 200, 255)
        isAntiAlias = true
    }

    private val jointEmphPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 80, 80)
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 11f * resources.displayMetrics.density
        isAntiAlias = true
    }

    fun postPoses(list: List<PoseResult>) {
        poses.clear()
        poses.addAll(list)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = jointMinVisibility
        for (pose in poses) {
            val bones = if (pose.emphasized) boneEmphPaint else bonePaint
            val tips = if (pose.emphasized) jointEmphPaint else jointPaint

            // draw bones first (under dots)
            for ((a, b) in SKELETON_CONNECTIONS) {
                if (pose.visibility[a] < r || pose.visibility[b] < r) continue
                val ax = pose.joints[a * 2]
                val ay = pose.joints[a * 2 + 1]
                val bx = pose.joints[b * 2]
                val by = pose.joints[b * 2 + 1]
                canvas.drawLine(ax, ay, bx, by, bones)
            }

            // draw joints (small circles) on top
            val radius = 3.5f * resources.displayMetrics.density
            for (i in 0 until 33) {
                if (pose.visibility[i] < r) continue
                canvas.drawCircle(
                    pose.joints[i * 2],
                    pose.joints[i * 2 + 1],
                    radius,
                    tips
                )
            }
        }
    }
}
