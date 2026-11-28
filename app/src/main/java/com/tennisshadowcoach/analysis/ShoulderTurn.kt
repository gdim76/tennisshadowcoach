package com.tennisshadowcoach.analysis

import kotlin.math.atan2

/**
 * A forehand starts with an early turn of the shoulders and trunk side-on, and finishes with the
 * trunk turning back to the net; a swing of the arm alone barely turns the shoulders.  The range of
 * the shoulder-line yaw around the coordinate peak separates the two on the labelled recordings:
 * 30–36° for arm-only movements, 45–130° for full swings (see SPEC, section 5).
 *
 * Yaw is the angle of the left→right shoulder line in the world x–z plane (horizontal), unwrapped so
 * a turn through ±180° is continuous.  Pure Kotlin, shared by the offline and the live analysis.
 */
object ShoulderTurn {
    /** Incremental unwrapping, so the live tracker can extend the series frame by frame. */
    class YawTrack {
        private val values = mutableListOf<Float?>()
        private var lastRaw: Float? = null
        private var offset = 0f

        fun add(world: List<WorldLandmark?>): Float? {
            val raw = rawYaw(world)
            val value = raw?.let { current ->
                lastRaw?.let { previous ->
                    val step = current - previous
                    if (step > 180f) offset -= 360f else if (step < -180f) offset += 360f
                }
                lastRaw = current
                current + offset
            }
            values += value
            return value
        }

        val series: List<Float?> get() = values
    }

    fun yawSeries(frames: List<List<WorldLandmark?>>): List<Float?> =
        YawTrack().also { track -> frames.forEach(track::add) }.series

    /**
     * Robust range (95th minus 5th percentile, so a single tracking jump does not count) of the yaw
     * from [beforeFrames] before the coordinate peak to [afterFrames] after it, using only frames up
     * to [lastAvailable].  The span is tied to the peak, not to the window start: a merged window of
     * two short movements would otherwise cover both and collect extra turn.
     */
    fun turnDegrees(
        yaw: List<Float?>,
        peak: Int,
        beforeFrames: Int,
        afterFrames: Int,
        lastAvailable: Int = yaw.lastIndex
    ): Float? {
        val from = (peak - beforeFrames).coerceAtLeast(0)
        val to = minOf(peak + afterFrames, lastAvailable, yaw.lastIndex)
        if (to < from) return null
        val values = (from..to).mapNotNull { yaw[it] }.sorted()
        if (values.size < 3) return null
        return percentile(values, .95f) - percentile(values, .05f)
    }

    private fun rawYaw(world: List<WorldLandmark?>): Float? {
        val left = world.getOrNull(11) ?: return null
        val right = world.getOrNull(12) ?: return null
        return Math.toDegrees(atan2((left.z - right.z).toDouble(), (left.x - right.x).toDouble())).toFloat()
    }

    private fun percentile(sorted: List<Float>, fraction: Float): Float {
        val position = fraction * (sorted.size - 1)
        val low = position.toInt()
        val high = (low + 1).coerceAtMost(sorted.lastIndex)
        return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
    }
}
