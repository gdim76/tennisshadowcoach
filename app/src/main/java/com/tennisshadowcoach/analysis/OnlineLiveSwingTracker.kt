package com.tennisshadowcoach.analysis

import kotlin.math.sqrt

/**
 * Converts live world landmarks to the same PCA swing coordinate used by the offline method.
 * Calibration is explicit: the user performs 2–3 swings, then calls [completeCalibration].
 * No rule evaluation or cue is present here.
 */
class OnlineLiveSwingTracker(
    private val leadingHand: LeadingHand,
    private val config: MetricsConfig = MetricsConfig()
) {
    data class State(
        val calibrated: Boolean,
        val completedCount: Int,
        val justCompleted: Boolean,
        /** After calibration: the person stands at the working distance and is being counted. */
        val inPosition: Boolean = false,
        /** Corrections of the swing just counted (empty when the swing was fine or none was counted). */
        val corrections: List<SwingCorrection> = emptyList()
    )

    private data class Sample(
        val frame: Int,
        val timestampUs: Long,
        val point: Vec?,
        val imageTorso: Float?,
        val world: List<WorldLandmark?>,
        val wholeBody: Boolean
    )
    private val calibration = mutableListOf<Sample>()
    private var mean: Vec? = null
    private var axis: Vec? = null
    private var sign = 1f
    private var segmenter: OnlineSwingSegmenter? = null
    /**
     * Working-distance image torso length: the median over calibration frames that show the whole
     * body.  Frames close to the phone (opening the mirror, pressing the button) cut off the feet
     * and must not set the reference: with few swings they used to be the majority.
     */
    private var referenceTorso: Float? = null
    private var inPosition = false
    private var positionRun = 0
    /** Shoulder yaw for every sample fed to the segmenter, calibration included (same indices). */
    private val yaw = ShoulderTurn.YawTrack()
    private val shoulderTurn: (Int, Int) -> Float? = { peak, lastAvailable ->
        ShoulderTurn.turnDegrees(yaw.series, peak, config.shoulderTurnBeforeFrames, config.shoulderTurnAfterFrames, lastAvailable)
    }

    fun add(
        frame: Int,
        timestampUs: Long,
        image: List<ImageLandmark?>,
        world: List<WorldLandmark?>
    ): State {
        val wrist = if (leadingHand == LeadingHand.RIGHT) 16 else 15
        val point = if ((image.getOrNull(wrist)?.visibility ?: 0f) >= config.visibilityThreshold) {
            normalizedRelativeWrist(world.getOrNull(wrist), world)
        } else null
        val torso = MotionMetricsEngine.imageTorsoLength2d(image)
        yaw.add(world)
        val active = segmenter
        if (active == null) {
            calibration += Sample(frame, timestampUs, point, torso, world, FramingCheck.wholeBodyVisible(image))
            return State(false, 0, false)
        }
        val positioned = updatePosition(torso)
        val coordinate = point?.takeIf { positioned }?.let { toCoordinate(it) }
        val update = active.add(frame, timestampUs, coordinate)
        return State(true, update.completedCount, update.completedSwing != null, inPosition, update.completedSwing.corrections())
    }

    fun completeCalibration(): State {
        // Setup frames (walking away from the phone) are removed exactly as offline: the whole
        // calibration buffer is known, so the non-causal run-length rule can be used here.
        val wholeBodyTorso = calibration.filter { it.wholeBody }.mapNotNull { it.imageTorso }
        require(wholeBodyTorso.size >= minPositionRun()) {
            "Калибровка: тело целиком в кадре меньше секунды. Встань так, чтобы рамка была зелёной, и сделай 2–3 замаха."
        }
        referenceTorso = wholeBodyTorso.sorted().let { it[it.size / 2] }
        val positioned = calibrationPositionMask()
        val usable = calibration.mapIndexed { index, sample -> sample.point.takeIf { positioned[index] } }
        inPosition = positioned.lastOrNull() ?: false
        positionRun = if (inPosition) minPositionRun() else 0
        val points = usable.filterNotNull()
        require(points.size >= 3) { "Калибровка: запястье ведущей руки почти не видно. Сделай 2–3 замаха лицом к камере." }
        val centre = Vec(
            points.map { it.x }.average().toFloat(),
            points.map { it.y }.average().toFloat(),
            points.map { it.z }.average().toFloat()
        )
        val mainAxis = principalAxis(points.map { it - centre })
            ?: error("Калибровка: не видно размаха. Сделай 2–3 полных замаха.")
        mean = centre
        axis = mainAxis
        val raw = usable.map { it?.let(::toCoordinateUnflipped) }
        // Same orientation rule as offline: towards the trailing side of the body, with the
        // distribution skew only as a fallback.  Skew alone is unreliable on 2–3 swings.
        val side = MotionMetricsEngine.trailingSideDirection(
            calibration.indices.filter { usable[it] != null }.map { calibration[it].world }, leadingHand
        )
        val alignment = side?.let { mainAxis.x * it.x + mainAxis.y * it.y + mainAxis.z * it.z }
        sign = if (alignment != null && kotlin.math.abs(alignment) >= SIDE_ALIGNMENT_MIN) {
            if (alignment < 0f) -1f else 1f
        } else if (skew(raw.filterNotNull()) < 0f) -1f else 1f
        val online = OnlineSwingSegmenter(config, shoulderTurn)
        calibration.forEachIndexed { index, sample ->
            online.add(sample.frame, sample.timestampUs, raw[index]?.times(sign))
        }
        val update = online.completeCalibration()
        segmenter = online
        return State(true, update.completedCount, false)
    }

    fun detectedSwings(): List<OnlineSwingSegmenter.DetectedSwing> = segmenter?.detectedSwings().orEmpty()

    /** A new practice set with the current calibration; no effect before calibration. */
    fun startNewSet() {
        segmenter?.startNewSet()
    }

    fun finish(): State {
        val update = segmenter?.finish() ?: return State(false, 0, false)
        return State(true, update.completedCount, update.completedSwing != null, inPosition)
    }

    private fun OnlineSwingSegmenter.DetectedSwing?.corrections(): List<SwingCorrection> =
        if (this?.noShoulderTurn == true) listOf(SwingCorrection.NO_SHOULDER_TURN) else emptyList()

    private fun withinTolerance(torso: Float?): Boolean {
        val reference = referenceTorso ?: return true
        if (torso == null || reference <= EPSILON) return false
        val upper = 1f + config.inPositionTorsoTolerance
        return torso / reference in (1f / upper)..upper
    }

    private fun minPositionRun(): Int = (config.minInPositionSeconds * config.segmentationFps).toInt().coerceAtLeast(1)

    private fun calibrationPositionMask(): List<Boolean> {
        val raw = calibration.map { withinTolerance(it.imageTorso) }
        val mask = raw.toMutableList()
        var start = 0
        while (start < raw.size) {
            if (!raw[start]) { start++; continue }
            var end = start
            while (end < raw.size && raw[end]) end++
            if (end - start < minPositionRun()) for (index in start until end) mask[index] = false
            start = end
        }
        return mask
    }

    /**
     * Causal version of the offline gate.  The person is "in position" after a continuous run of
     * frames at the working distance and leaves it after an equally long run away from it.
     * Single frames outside the tolerance are always ignored.
     */
    private fun updatePosition(torso: Float?): Boolean {
        val ok = withinTolerance(torso)
        if (ok == inPosition) positionRun = 0 else positionRun++
        if (positionRun >= minPositionRun()) { inPosition = ok; positionRun = 0 }
        return ok && inPosition
    }

    private fun toCoordinate(point: Vec): Float = toCoordinateUnflipped(point) * sign
    private fun toCoordinateUnflipped(point: Vec): Float = (point - requireNotNull(mean)).dot(requireNotNull(axis))

    private fun normalizedRelativeWrist(wrist: WorldLandmark?, points: List<WorldLandmark?>): Vec? {
        val shoulder = midpoint(points.getOrNull(11)?.vec(), points.getOrNull(12)?.vec()) ?: return null
        val hip = midpoint(points.getOrNull(23)?.vec(), points.getOrNull(24)?.vec()) ?: return null
        val torso = (shoulder - hip).length()
        return wrist?.vec()?.minus(hip)?.takeIf { torso > EPSILON }?.div(torso)
    }

    private data class Vec(val x: Float, val y: Float, val z: Float) {
        operator fun minus(other: Vec) = Vec(x - other.x, y - other.y, z - other.z)
        operator fun div(value: Float) = Vec(x / value, y / value, z / value)
        fun times(value: Float) = Vec(x * value, y * value, z * value)
        fun dot(other: Vec) = x * other.x + y * other.y + z * other.z
        fun length() = sqrt(dot(this))
    }

    private fun WorldLandmark.vec() = Vec(x, y, z)
    private fun midpoint(a: Vec?, b: Vec?) = if (a == null || b == null) null else Vec((a.x + b.x) / 2f, (a.y + b.y) / 2f, (a.z + b.z) / 2f)
    private fun principalAxis(points: List<Vec>): Vec? {
        var current = Vec(1f, 1f, 1f)
        repeat(32) {
            val next = points.fold(Vec(0f, 0f, 0f)) { sum, point ->
                val multiplier = point.dot(current)
                Vec(sum.x + point.x * multiplier, sum.y + point.y * multiplier, sum.z + point.z * multiplier)
            }
            if (next.length() <= EPSILON) return null
            current = next / next.length()
        }
        return current
    }

    private fun skew(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val average = values.average().toFloat()
        val second = values.map { (it - average) * (it - average) }.average().toFloat()
        return if (second <= EPSILON) 0f else values.map { (it - average) * (it - average) * (it - average) }
            .average().toFloat() / sqrt(second * second * second)
    }

    private companion object {
        const val EPSILON = 1e-6f
        const val SIDE_ALIGNMENT_MIN = 0.3f
    }
}
