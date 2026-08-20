package com.example.humanoiddetector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/** A single detected pose: 33 joints in capture-frame coordinates (px). */
data class PoseResult(
    val joints: FloatArray,
    val visibility: FloatArray,
    val emphasized: Boolean
) {
    /** Returns the joint at index i as (x, y, visibility). */
    fun joint(i: Int): Triple<Float, Float, Float> =
        Triple(joints[i * 2], joints[i * 2 + 1], visibility[i])
}

/** MediaPipe 33-landmark skeleton (BlazePose).
 *  Pairs are (jointA, jointB) for each drawn line segment. */
val SKELETON_CONNECTIONS: List<Pair<Int, Int>> = listOf(
    // face
    0 to 1, 1 to 2, 2 to 3, 3 to 7,           // nose → left eye → ear
    0 to 4, 4 to 5, 5 to 6, 6 to 8,           // nose → right eye → ear
    // shoulders / arms
    9 to 10,                                  // mouth
    11 to 12,                                 // shoulder bridge
    11 to 13, 13 to 15, 15 to 17, 17 to 19, 19 to 15, 15 to 21,  // left arm + hand
    12 to 14, 14 to 16, 16 to 18, 18 to 20, 20 to 16, 16 to 22,  // right arm + hand
    // torso / hips
    11 to 23, 12 to 24, 23 to 24,             // torso + hip bridge
    // legs
    23 to 25, 25 to 27, 27 to 29, 27 to 31, 29 to 31,  // left leg + foot
    24 to 26, 26 to 28, 28 to 30, 28 to 32, 30 to 32,  // right leg + foot
)

/**
 * PoseLandmarker wrapper. Tries each frame and returns a list of all detected
 * poses (each pose = 33 joints x/y + visibility).
 *
 * Detection strength note: pose models handle small/distant figures MUCH better
 * than the previous object detector's "is there a person-shaped blob" check,
 * because they key on shoulders/hips specifically.
 */
class Detector(private val context: Context) {

    companion object {
        private const val TAG = "Detector"
        private const val MODEL = "pose_landmarker_lite.task"
        private const val MIN_POSE_DETECTION_CONFIDENCE = 0.15f
        private const val MIN_PRESENCE_CONFIDENCE = 0.15f
        private const val MIN_TRACKING_CONFIDENCE = 0.2f

        /** Optional secondary HSV color filter (disabled by default). */
        @Volatile
        var colorFilterEnabled = false
        @Volatile
        var hueTarget = 0f
        @Volatile
        var hueTolerance = 15f
    }

    // Indexed by joint id, body-part name for human-readable labels.
    val jointNames: Array<String> = arrayOf(
        "nose",           // 0
        "l_eye_inner",    // 1
        "l_eye",          // 2
        "l_eye_outer",    // 3
        "r_eye_inner",    // 4
        "r_eye",          // 5
        "r_eye_outer",    // 6
        "l_ear",          // 7
        "r_ear",          // 8
        "mouth_l",        // 9
        "mouth_r",        // 10
        "l_shoulder",     // 11
        "r_shoulder",     // 12
        "l_elbow",        // 13
        "r_elbow",        // 14
        "l_wrist",        // 15
        "r_wrist",        // 16
        "l_pinky",        // 17
        "r_pinky",        // 18
        "l_index",        // 19
        "r_index",        // 20
        "l_thumb",        // 21
        "r_thumb",        // 22
        "l_hip",          // 23
        "r_hip",          // 24
        "l_knee",         // 25
        "r_knee",         // 26
        "l_ankle",        // 27
        "r_ankle",        // 28
        "l_heel",         // 29
        "r_heel",         // 30
        "l_foot_index",   // 31
        "r_foot_index"    // 32
    )

    private val poseLandmarker: PoseLandmarker? = try {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL)
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setMinPoseDetectionConfidence(MIN_POSE_DETECTION_CONFIDENCE)
            .setMinPosePresenceConfidence(MIN_PRESENCE_CONFIDENCE)
            .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
            .setNumPoses(5)
            .build()
        PoseLandmarker.createFromOptions(context, options)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create PoseLandmarker", e)
        null
    }

    /**
     * Minimum long-edge for reliable distant-figure detection.
     * If the input is smaller than this we upscale it before running MediaPipe
     * so small/distant humanoids are not lost in the model's 256x256 input grid.
     */
    private val MIN_DETECTION_EDGE_PX = 960

    /** Optional upscale factor passed to detect(); 1.0 = no upscale. */
    @Volatile
    var upscaleFactor: Float = 1.0f

    fun detect(bitmap: Bitmap): List<PoseResult> {
        val pl = poseLandmarker ?: return emptyList()
        val toProcess = upscaleIfSmall(bitmap)
        val mpImage: MPImage = BitmapImageBuilder(toProcess).build()
        val result: PoseLandmarkerResult = try {
            pl.detect(mpImage)
        } catch (e: Exception) {
            Log.e(TAG, "pose detect failed", e)
            return emptyList()
        }
        val out = mutableListOf<PoseResult>()
        for (landmarks in result.landmarks()) {
            val joints = FloatArray(landmarks.size * 2)
            val vis = FloatArray(landmarks.size)
            for (i in 0 until landmarks.size) {
                val l = landmarks[i]
                joints[i * 2] = l.x() * toProcess.width
                joints[i * 2 + 1] = l.y() * toProcess.height
                vis[i] = l.visibility().orElse(0f)
            }
            val emphasized = colorFilterEnabled &&
                centerPixelMatches(toProcess, joints)
            out.add(PoseResult(joints, vis, emphasized))
        }
        if (toProcess !== bitmap) toProcess.recycle()
        return out
    }

    private fun upscaleIfSmall(src: Bitmap): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge >= MIN_DETECTION_EDGE_PX || upscaleFactor <= 1.0f) return src
        val targetFactor = (MIN_DETECTION_EDGE_PX.toFloat() / longEdge).coerceAtMost(2.0f)
        val factor = max(upscaleFactor, targetFactor)
        val nw = (src.width * factor).toInt()
        val nh = (src.height * factor).toInt()
        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        Log.i(TAG, "Upscaled ${src.width}x${src.height} -> ${nw}x${nh}")
        return scaled
    }

    /** Center pixel of the torso (avg of shoulders+hips) must match hue target. */
    private fun centerPixelMatches(bitmap: Bitmap, joints: FloatArray): Boolean {
        val idxShoulderL = 11; val idxShoulderR = 12
        val idxHipL = 23; val idxHipR = 24
        val cx = ((joints[idxShoulderL * 2] + joints[idxShoulderR * 2] +
            joints[idxHipL * 2] + joints[idxHipR * 2]) / 4f).toInt()
                .coerceIn(0, bitmap.width - 1)
        val cy = ((joints[idxShoulderL * 2 + 1] + joints[idxShoulderR * 2 + 1] +
            joints[idxHipL * 2 + 1] + joints[idxHipR * 2 + 1]) / 4f).toInt()
                .coerceIn(0, bitmap.height - 1)
        val pixel = bitmap.getPixel(cx, cy)
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val (hue, sat, _) = rgbToHsv(r, g, b)
        if (sat < 0.25f) return false
        var diff = Math.abs(hue - hueTarget); if (diff > 180f) diff = 360f - diff
        return diff <= hueTolerance
    }

    private fun rgbToHsv(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf)
        val v = max
        val s = if (max == 0f) 0f else (max - min) / max
        var h = 0f
        if (max != min) {
            h = when (max) {
                rf -> ((gf - bf) / (max - min)) % 6f
                gf -> ((bf - rf) / (max - min)) + 2f
                else -> ((rf - gf) / (max - min)) + 4f
            }
            h *= 60f
            if (h < 0f) h += 360f
        }
        return Triple(h, s, v)
    }

    fun close() {
        poseLandmarker?.close()
    }
}
