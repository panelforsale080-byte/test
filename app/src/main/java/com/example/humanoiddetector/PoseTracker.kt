package com.example.humanoiddetector

import kotlin.math.hypot

/**
 * Cross-frame pose tracker for steady (jitter-free) skeleton overlays.
 *
 * For each MediaPipe detection update we:
 *  1. Greedily match new detections to last-frame tracks by nose-joint distance
 *     (cheapest stable feature: nose is almost always visible when a pose is found).
 *  2. Apply EMA (exponential moving average) smoothing on every joint coordinate
 *     so single-frame jitter does not make the skeleton twitch.
 *  3. Keep each track alive for a grace window after it disappears, fading the
 *     visibility values so the box does not pop in/out instantly.
 *  4. Pre-allocate new tracks for unmatched detections.
 *
 * Alpha = smoothing strength: 0 = frozen on first detection, 1 = no smoothing.
 * 0.4 strikes a good compromise for ~10 fps capture.
 */
class PoseTracker {

    private data class Track(
        val id: Int,
        val joints: FloatArray,
        val visibility: FloatArray,
        var ageFrames: Int,
        var sinceLastSeen: Int,
        var emphasized: Boolean
    )

    companion object {
        private const val EMA_ALPHA = 0.4f
        private const val MAX_MATCH_DIST_FRAC = 0.18f
        private const val MAX_LOST_FRAMES = 6
        private const val STABLE_FRAMES_FOR_EMPHASIS = 3
        private const val LOST_VISIBILITY_DECAY = 0.78f
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 0

    /**
     * @param newPoses raw detections from the current frame
     * @param canvasW  width  of the canvas the overlay will draw on
     * @param canvasH  height of the canvas the overlay will draw on
     * @return smoothed, tracked PoseResult list (joints already in canvas coords)
     */
    fun update(newPoses: List<PoseResult>, canvasW: Int, canvasH: Int): List<PoseResult> {
        val maxDist = MAX_MATCH_DIST_FRAC * maxOf(canvasW, canvasH)
        val used = BooleanArray(newPoses.size)
        val updated = ArrayList<Track>(tracks.size + newPoses.size)

        // 1. match existing tracks in id-order
        for (track in tracks) {
            val cx = track.joints[0]
            val cy = track.joints[1]
            var bestIdx = -1
            var bestDist = Float.MAX_VALUE
            for (i in newPoses.indices) {
                if (used[i]) continue
                val np = newPoses[i]
                val nx = np.joints[0]
                val ny = np.joints[1]
                val d = hypot(cx - nx, cy - ny)
                if (d < bestDist && d < maxDist) {
                    bestDist = d
                    bestIdx = i
                }
            }
            if (bestIdx >= 0) {
                used[bestIdx] = true
                val np = newPoses[bestIdx]
                // EMA on every joint
                for (j in track.joints.indices) {
                    track.joints[j] = EMA_ALPHA * np.joints[j] + (1f - EMA_ALPHA) * track.joints[j]
                }
                for (j in track.visibility.indices) {
                    track.visibility[j] =
                        EMA_ALPHA * np.visibility[j] + (1f - EMA_ALPHA) * track.visibility[j]
                }
                track.sinceLastSeen = 0
                track.ageFrames++
                track.emphasized = track.emphasized or np.emphasized
                updated.add(track)
            } else {
                track.sinceLastSeen++
                if (track.sinceLastSeen <= MAX_LOST_FRAMES) {
                    // Fade out instead of vanishing
                    for (j in track.visibility.indices) {
                        track.visibility[j] *= LOST_VISIBILITY_DECAY
                    }
                    updated.add(track)
                }
                // else: drop the track entirely
            }
        }

        // 2. spawn tracks for unmatched detections
        for (i in newPoses.indices) {
            if (used[i]) continue
            val np = newPoses[i]
            updated.add(
                Track(
                    id = nextId++,
                    joints = np.joints.copyOf(),
                    visibility = np.visibility.copyOf(),
                    ageFrames = 0,
                    sinceLastSeen = 0,
                    emphasized = np.emphasized
                )
            )
        }

        tracks.clear()
        tracks.addAll(updated)

        return tracks.map { t ->
            PoseResult(
                joints = t.joints,
                visibility = t.visibility,
                emphasized = t.emphasized && t.ageFrames >= STABLE_FRAMES_FOR_EMPHASIS
            )
        }
    }

    fun reset() {
        tracks.clear()
        nextId = 0
    }
}
