package com.example.humanoiddetector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/** A single detected person, in capture-frame coordinates (0..1 scaled later). */
data class DetectionResult(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val label: String,
    val score: Float,
    val emphasized: Boolean
)

/**
 * Wraps MediaPipe ObjectDetector (efficientdet_lite0) and keeps only "person"
 * detections. An optional secondary HSV color filter can highlight people whose
 * center pixel falls inside a chosen hue range — useful to focus on one target.
 */
class Detector(private val context: Context) {

    companion object {
        private const val TAG = "Detector"
        private const val MODEL = "efficientdet_lite0.tflite"
        private const val MIN_SCORE = 0.45f
        private const val PERSON_LABEL = "person"

        /** Optional secondary color filter (disabled by default). */
        @Volatile
        var colorFilterEnabled = false
        @Volatile
        var hueTarget = 0f
        @Volatile
        var hueTolerance = 15f
    }

    private val objectDetector: ObjectDetector? = try {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(MIN_SCORE)
            .build()
        ObjectDetector.createFromOptions(context, options)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create ObjectDetector", e)
        null
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        val detector = objectDetector ?: return emptyList()
        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
        val result = detector.detect(mpImage)
        val results = mutableListOf<DetectionResult>()

        for (detection in result.detections()) {
            val category = detection.categories().firstOrNull() ?: continue
            val label = category.categoryName() ?: continue
            if (label != PERSON_LABEL) continue

            val box = detection.boundingBox()
            val left = box.left
            val top = box.top
            val right = box.right
            val bottom = box.bottom
            val score = category.score()

            val emphasized = colorFilterEnabled &&
                matchesColor(bitmap, left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

            results.add(
                DetectionResult(
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    label = label,
                    score = score,
                    emphasized = emphasized
                )
            )
        }
        return results
    }

    /** Samples the center pixel of the box and checks it against the HSV target. */
    private fun matchesColor(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Boolean {
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)
        val sampleX = (left + w / 2).coerceIn(0, bitmap.width - 1)
        val sampleY = (top + h / 2).coerceIn(0, bitmap.height - 1)
        val pixel = bitmap.getPixel(sampleX, sampleY)

        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val (hue, sat, _) = rgbToHsv(r, g, b)

        if (sat < 0.25f) return false
        var diff = Math.abs(hue - hueTarget)
        if (diff > 180f) diff = 360f - diff
        return diff <= hueTolerance
    }

    private fun rgbToHsv(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val d = max - min
        val v = max
        val s = if (max == 0f) 0f else d / max
        var h = 0f
        if (d != 0f) {
            h = when (max) {
                rf -> ((gf - bf) / d) % 6f
                gf -> (bf - rf) / d + 2f
                else -> (rf - gf) / d + 4f
            }
            h *= 60f
            if (h < 0f) h += 360f
        }
        return Triple(h, s, v)
    }

    fun close() {
        objectDetector?.close()
    }
}
