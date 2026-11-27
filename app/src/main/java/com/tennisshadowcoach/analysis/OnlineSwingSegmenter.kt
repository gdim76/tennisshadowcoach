package com.tennisshadowcoach.analysis

/**
 * Online half of the cyclic method.  A caller feeds the already projected swing coordinate.
 * The first two or three deliberately performed swings are retained for calibration; after
 * [completeCalibration] the same hysteresis used offline runs frame by frame.  It has no
 * Android dependency and deliberately produces counts only, never coaching prompts.
 */
class OnlineSwingSegmenter(
    private val config: MetricsConfig = MetricsConfig()
) {
    data class DetectedSwing(val startFrame: Int, val peakFrame: Int, val endFrame: Int)
    data class Update(
        val calibrated: Boolean,
        val completedCount: Int,
        val completedSwing: DetectedSwing? = null
    )

    private data class Sample(val frame: Int, val timestampUs: Long, val coordinate: Float?)

    private val samples = mutableListOf<Sample>()
    private val heldCoordinates = mutableListOf<Float>()
    private val detected = mutableListOf<DetectedSwing>()
    private var calibrated = false
    private var lower = 0f
    private var upper = 0f
    private var state = REST
    private var start = 0
    private var peak = 0
    private var lastHeld = 0f

    /** Call after the user has completed two or three calibration swings. */
    fun completeCalibration(): Update {
        val good = samples.mapNotNull { it.coordinate }
        require(good.size >= 3) { "Для калибровки нужны видимые точки запястья." }
        val lo = percentile(good, .05f)
        val hi = percentile(good, .95f)
        require(hi - lo > EPSILON) { "Для калибровки нужны движения с заметным размахом." }
        lower = lo + config.backFrac * (hi - lo)
        upper = lo + config.frontFrac * (hi - lo)
        calibrated = true
        resetDetector()
        samples.indices.forEach { index ->
            heldCoordinates += samples[index].coordinate ?: lastHeld
            lastHeld = heldCoordinates.last()
            process(index)
        }
        return Update(true, detected.size)
    }

    fun add(frame: Int, timestampUs: Long, coordinate: Float?): Update {
        samples += Sample(frame, timestampUs, coordinate)
        if (!calibrated) return Update(false, detected.size)
        heldCoordinates += coordinate ?: lastHeld
        lastHeld = heldCoordinates.last()
        val completed = process(samples.lastIndex)
        return Update(true, detected.size, completed)
    }

    fun detectedSwings(): List<DetectedSwing> = detected.toList()

    /**
     * Called when a live session stops.  It mirrors the offline trailing-peak rule: an open
     * window is countable only when its peak is at least 0.3 seconds before the final frame.
     */
    fun finish(): Update {
        if (!calibrated || state != SWING || samples.isEmpty()) return Update(calibrated, detected.size)
        val endIndex = samples.lastIndex
        val peakAgeSeconds = (samples[endIndex].timestampUs - samples[peak].timestampUs) / 1_000_000f
        if (peakAgeSeconds < config.trailingPeakExclusionSeconds) return Update(true, detected.size)
        state = REST
        val durationSeconds = (samples[endIndex].timestampUs - samples[start].timestampUs) / 1_000_000f
        val completed = if (durationSeconds >= config.minSwingDurationSeconds) {
            DetectedSwing(samples[start].frame, samples[peak].frame, samples[endIndex].frame).also(detected::add)
        } else null
        return Update(true, detected.size, completed)
    }

    private fun resetDetector() {
        detected.clear()
        state = REST
        start = 0
        peak = 0
        lastHeld = lower
        heldCoordinates.clear()
    }

    private fun process(index: Int): DetectedSwing? {
        val value = heldCoordinates[index]
        if (state == REST) {
            if (value > upper) {
                var foundStart = index
                while (foundStart > 0 && heldCoordinates[foundStart] > lower) foundStart--
                start = foundStart
                peak = index
                state = SWING
            }
            return null
        }
        if (value > heldCoordinates[peak]) peak = index
        if (value < lower) {
            state = REST
            val durationSeconds = (samples[index].timestampUs - samples[start].timestampUs) / 1_000_000f
            if (durationSeconds >= config.minSwingDurationSeconds) {
                return DetectedSwing(samples[start].frame, samples[peak].frame, samples[index].frame)
                    .also(detected::add)
            }
        }
        return null
    }

    private fun percentile(values: List<Float>, fraction: Float): Float {
        val sorted = values.sorted()
        val position = fraction.coerceIn(0f, 1f) * (sorted.size - 1)
        val lowerIndex = position.toInt()
        val upperIndex = (lowerIndex + 1).coerceAtMost(sorted.lastIndex)
        return sorted[lowerIndex] + (sorted[upperIndex] - sorted[lowerIndex]) * (position - lowerIndex)
    }

    private companion object {
        const val REST = 0
        const val SWING = 1
        const val EPSILON = 1e-6f
    }
}
