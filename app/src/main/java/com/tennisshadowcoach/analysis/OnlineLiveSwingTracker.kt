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
    data class State(val calibrated: Boolean, val completedCount: Int, val justCompleted: Boolean)

    private data class Sample(val frame: Int, val timestampUs: Long, val point: Vec?)
    private val calibration = mutableListOf<Sample>()
    private var mean: Vec? = null
    private var axis: Vec? = null
    private var sign = 1f
    private var segmenter: OnlineSwingSegmenter? = null

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
        val active = segmenter
        if (active == null) {
            calibration += Sample(frame, timestampUs, point)
            return State(false, 0, false)
        }
        val coordinate = point?.let { toCoordinate(it) }
        val update = active.add(frame, timestampUs, coordinate)
        return State(true, update.completedCount, update.completedSwing != null)
    }

    fun completeCalibration(): State {
        val points = calibration.mapNotNull { it.point }
        require(points.size >= 3) { "Для калибровки не хватает видимых точек запястья." }
        val centre = Vec(
            points.map { it.x }.average().toFloat(),
            points.map { it.y }.average().toFloat(),
            points.map { it.z }.average().toFloat()
        )
        val mainAxis = principalAxis(points.map { it - centre })
            ?: error("Для калибровки нужен заметный размах движения.")
        mean = centre
        axis = mainAxis
        val raw = calibration.map { it.point?.let(::toCoordinateUnflipped) }
        if (skew(raw.filterNotNull()) < 0f) sign = -1f
        val online = OnlineSwingSegmenter(config)
        calibration.forEachIndexed { index, sample ->
            online.add(sample.frame, sample.timestampUs, raw[index]?.times(sign))
        }
        val update = online.completeCalibration()
        segmenter = online
        return State(true, update.completedCount, false)
    }

    fun finish(): State {
        val update = segmenter?.finish() ?: return State(false, 0, false)
        return State(true, update.completedCount, update.completedSwing != null)
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

    private companion object { const val EPSILON = 1e-6f }
}
