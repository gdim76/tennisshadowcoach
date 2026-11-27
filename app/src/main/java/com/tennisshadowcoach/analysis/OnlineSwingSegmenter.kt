package com.tennisshadowcoach.analysis

/**
 * Online half of the cyclic method.  A caller feeds the already projected swing coordinate.
 * The first two or three deliberately performed swings are retained for calibration; after
 * [completeCalibration] the same hysteresis used offline runs frame by frame.  It has no
 * Android dependency and deliberately produces counts only, never coaching prompts.
 *
 * Two things are tracked: [detectedSwings] are the closed windows (identical to offline), and
 * the live count, which increases as soon as the coordinate turns down after the peak — about
 * the follow-through — instead of when the arm is back at rest up to two seconds later.
 */
class OnlineSwingSegmenter(
    private val config: MetricsConfig = MetricsConfig()
) {
    data class DetectedSwing(
        val startFrame: Int,
        val peakFrame: Int,
        val endFrame: Int,
        /** Maximum rise speed of the coordinate between start and peak, coordinate units per second. */
        val riseSpeed: Float = 0f
    )
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
    private var firstValidIndex = -1
    private var lastValidIndex = -1
    /** Median rise speed of the calibration swings; the reference for the slow-movement rule. */
    private var referenceSpeed: Float? = null
    private var amplitude = 0f
    /** Swings shown to the user; normally equal to the closed windows plus a counted open one. */
    private var liveCount = 0
    /** The open window has already been counted at its turn after the peak. */
    private var openCounted = false
    private var openChecked = false

    /** Call after the user has completed two or three calibration swings. */
    fun completeCalibration(): Update {
        val good = samples.mapNotNull { it.coordinate }
        require(good.size >= 3) { "Для калибровки нужны видимые точки запястья." }
        val lo = percentile(good, .05f)
        val hi = percentile(good, .95f)
        require(hi - lo > EPSILON) { "Для калибровки нужны движения с заметным размахом." }
        lower = lo + config.backFrac * (hi - lo)
        upper = lo + config.frontFrac * (hi - lo)
        amplitude = hi - lo
        calibrated = true
        resetDetector()
        samples.indices.forEach { index ->
            heldCoordinates += samples[index].coordinate ?: lastHeld
            lastHeld = heldCoordinates.last()
            process(index)
        }
        // Same rule as offline: the reference is the median over the candidate cycles, and
        // cycles much slower than it (raising the racket into the ready position) are dropped.
        referenceSpeed = detected.map { it.riseSpeed }.takeIf { it.isNotEmpty() }?.sorted()?.let { speeds ->
            if (speeds.size % 2 == 0) (speeds[speeds.size / 2 - 1] + speeds[speeds.size / 2]) / 2f else speeds[speeds.size / 2]
        }
        detected.retainAll { !isTooSlow(it) }
        openCounted = state == SWING && openCounted && !isTooSlow(candidate(start, peak, samples.lastIndex))
        liveCount = detected.size + if (openCounted) 1 else 0
        return Update(true, liveCount)
    }

    fun add(frame: Int, timestampUs: Long, coordinate: Float?): Update {
        samples += Sample(frame, timestampUs, coordinate)
        if (coordinate != null) {
            if (firstValidIndex < 0) firstValidIndex = samples.lastIndex
            lastValidIndex = samples.lastIndex
        }
        if (!calibrated) return Update(false, 0)
        heldCoordinates += coordinate ?: lastHeld
        lastHeld = heldCoordinates.last()
        val counted = process(samples.lastIndex)
        return Update(true, liveCount, counted)
    }

    fun detectedSwings(): List<DetectedSwing> = detected.toList()

    /**
     * Called when a live session stops.  It mirrors the offline trailing-peak rule: an open
     * window is countable only when its peak is at least 0.3 seconds before the final frame.
     */
    fun finish(): Update {
        if (!calibrated || state != SWING || samples.isEmpty()) return Update(calibrated, liveCount)
        // Trailing setup frames (walking to the phone) carry no coordinate; the recording
        // effectively ends at the last frame that has one, as offline.
        val endIndex = lastValidIndex.takeIf { it >= 0 } ?: samples.lastIndex
        val peakAgeSeconds = (samples[endIndex].timestampUs - samples[peak].timestampUs) / 1_000_000f
        state = REST
        val durationSeconds = (samples[endIndex].timestampUs - samples[start].timestampUs) / 1_000_000f
        val countable = peakAgeSeconds >= config.trailingPeakExclusionSeconds &&
            durationSeconds >= config.minSwingDurationSeconds && start > firstValidIndex
        val window = candidate(start, peak, endIndex).takeIf { countable && !isTooSlow(it) }
        // Offline rules decide at the end: a swing counted live but cut by the end of the
        // session is withdrawn, an uncounted complete one is added.
        if (openCounted && window == null) liveCount--
        if (!openCounted && window != null) liveCount++
        window?.let(detected::add)
        openCounted = false
        return Update(true, liveCount, window)
    }

    private fun resetDetector() {
        detected.clear()
        liveCount = 0
        openCounted = false
        openChecked = false
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
                openCounted = false
                openChecked = false
            }
            return null
        }
        if (value > heldCoordinates[peak]) peak = index
        if (value < lower) {
            state = REST
            val durationSeconds = (samples[index].timestampUs - samples[start].timestampUs) / 1_000_000f
            // A rise that began before the first usable frame was not seen: like offline, skip it.
            // During the calibration replay there is no reference yet, so nothing is "too slow";
            // completeCalibration() filters those candidates once the reference exists.
            val window = candidate(start, peak, index).takeIf {
                durationSeconds >= config.minSwingDurationSeconds && start > firstValidIndex && !isTooSlow(it)
            }
            window?.let(detected::add)
            val newlyCounted = window != null && !openCounted
            if (newlyCounted) liveCount++
            if (window == null && openCounted) liveCount--
            openCounted = false
            return window.takeIf { newlyCounted }
        }
        // Count at the turn after the peak (follow-through), once per window.
        if (!openChecked && value < heldCoordinates[peak] - config.liveCountDropFraction * amplitude) {
            openChecked = true
            if (start > firstValidIndex) {
                val swing = candidate(start, peak, index)
                if (!isTooSlow(swing)) {
                    openCounted = true
                    liveCount++
                    return swing
                }
            }
        }
        return null
    }

    private fun candidate(start: Int, peak: Int, end: Int) =
        DetectedSwing(samples[start].frame, samples[peak].frame, samples[end].frame, riseSpeed(start, peak))

    private fun isTooSlow(swing: DetectedSwing): Boolean =
        referenceSpeed?.let { swing.riseSpeed < config.minSwingSpeedFraction * it } ?: false

    /**
     * Five-frame Savitzky–Golay first derivative (quadratic fit; for a symmetric window it
     * equals the least-squares slope), maximised over start..peak.  All frames it needs are
     * already present when a swing completes, because the return happens after the peak.
     */
    private fun riseSpeed(start: Int, peak: Int): Float {
        var best = 0f
        for (centre in start..peak) {
            var numerator = 0f
            var weights = 0f
            for (offset in -2..2) {
                val index = (centre + offset).coerceIn(0, heldCoordinates.lastIndex)
                numerator += offset * heldCoordinates[index]
                weights += offset * offset
            }
            val previous = samples[(centre - 1).coerceAtLeast(0)].timestampUs
            val next = samples[(centre + 1).coerceAtMost(samples.lastIndex)].timestampUs
            val frameSeconds = ((next - previous) / 2_000_000f).takeIf { it > 0f } ?: (1f / config.segmentationFps)
            best = maxOf(best, numerator / weights / frameSeconds)
        }
        return best
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
