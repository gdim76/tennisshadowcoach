package com.tennisshadowcoach.analysis

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

enum class LeadingHand { RIGHT, LEFT }
enum class RecordingAngle { SIDE, FRONT_OR_45 }

enum class MetricId(val label: String, val requiredAngle: RecordingAngle?) {
    LEFT_KNEE("Левое колено", RecordingAngle.SIDE),
    RIGHT_KNEE("Правое колено", RecordingAngle.SIDE),
    LEADING_ELBOW("Локоть ведущей руки", RecordingAngle.SIDE),
    TRUNK_LEAN("Наклон корпуса", RecordingAngle.SIDE),
    SHOULDER_HIP_ROTATION("Разворот плеч относительно таза", RecordingAngle.FRONT_OR_45),
    STANCE_WIDTH("Ширина стойки", RecordingAngle.FRONT_OR_45),
    WRIST_SPEED("Скорость ведущего запястья", null)
}

data class MetricsConfig(
    val visibilityThreshold: Float = 0.5f,
    val maxLandmarkJumpPerSecond: Float = 6f,
    val maxInterpolationGapFrames: Int = 5,
    val speedSmoothingWindowFrames: Int = 5,
    val angleSmoothingWindowFrames: Int = 9,
    val reliableFrameFraction: Float = 0.7f,
    val swingSpeedThreshold: Float = 1.1f,
    val minSwingDurationMs: Long = 120L,
    val minGapBetweenSwingsMs: Long = 550L
)

data class MetricReading(
    val world3d: Float?,
    val image2d: Float?,
    val preferred: Float? = world3d
)

data class FrameMetrics(
    val frameIndex: Int,
    val timestampUs: Long,
    val readings: Map<MetricId, MetricReading>
)

data class SwingSegment(
    val startFrame: Int,
    val peakFrame: Int,
    val endFrame: Int,
    val startTimestampUs: Long,
    val peakSpeed: Float,
    val durationMs: Long
)

data class MotionAnalysis(
    val frames: List<FrameMetrics>,
    val swings: List<SwingSegment>,
    val validFractions: Map<MetricId, Float>
)

/** Pure Kotlin M1 geometry. It never assigns a quality score to tennis technique. */
object MotionMetricsEngine {
    fun analyze(
        frames: List<PoseFrame>,
        leadingHand: LeadingHand,
        recordingAngle: RecordingAngle,
        config: MetricsConfig = MetricsConfig()
    ): MotionAnalysis {
        if (frames.isEmpty()) return MotionAnalysis(emptyList(), emptyList(), emptyMap())
        val cleanImage = cleanImageTracks(frames, config)
        val cleanWorld = cleanWorldTracks(frames, cleanImage, config)
        val angleImage = smoothImageTracks(cleanImage, config.angleSmoothingWindowFrames)
        val angleWorld = smoothWorldTracks(cleanWorld, config.angleSmoothingWindowFrames)
        val wristSpeed = smoothedWristSpeed(frames, cleanWorld, leadingHand, config)

        val frameMetrics = frames.indices.map { index ->
            val image = angleImage[index]
            val world = angleWorld[index]
            FrameMetrics(
                frameIndex = frames[index].index,
                timestampUs = frames[index].timestampUs,
                readings = readingsFor(image, world, wristSpeed[index], leadingHand, recordingAngle)
            )
        }
        val validFractions = MetricId.entries.associateWith { metric ->
            frameMetrics.count { it.readings[metric]?.preferred != null }.toFloat() / frameMetrics.size
        }
        return MotionAnalysis(
            frames = frameMetrics,
            swings = segmentSwings(frames, wristSpeed, config),
            validFractions = validFractions
        )
    }

    private fun readingsFor(
        image: List<ImageLandmark?>,
        world: List<WorldLandmark?>,
        wristSpeed: Float?,
        hand: LeadingHand,
        angle: RecordingAngle
    ): Map<MetricId, MetricReading> {
        fun reading(worldValue: Float?, imageValue: Float?, allowed: Boolean) =
            MetricReading(if (allowed) worldValue else null, if (allowed) imageValue else null)
        val leadingShoulder = if (hand == LeadingHand.RIGHT) 12 else 11
        val leadingElbow = if (hand == LeadingHand.RIGHT) 14 else 13
        val leadingWrist = if (hand == LeadingHand.RIGHT) 16 else 15
        val side = angle == RecordingAngle.SIDE
        val front = angle == RecordingAngle.FRONT_OR_45
        return mapOf(
            MetricId.LEFT_KNEE to reading(angle(world[23], world[25], world[27]), angle(image[23], image[25], image[27]), side),
            MetricId.RIGHT_KNEE to reading(angle(world[24], world[26], world[28]), angle(image[24], image[26], image[28]), side),
            MetricId.LEADING_ELBOW to reading(
                angle(world[leadingShoulder], world[leadingElbow], world[leadingWrist]),
                angle(image[leadingShoulder], image[leadingElbow], image[leadingWrist]),
                side
            ),
            MetricId.TRUNK_LEAN to reading(worldTrunkLean(world), imageTrunkLean(image), side),
            MetricId.SHOULDER_HIP_ROTATION to reading(shoulderHipRotation(world), null, front),
            MetricId.STANCE_WIDTH to reading(worldStanceWidth(world), imageStanceWidth(image), front),
            MetricId.WRIST_SPEED to MetricReading(wristSpeed, null)
        )
    }

    private fun smoothedWristSpeed(
        frames: List<PoseFrame>,
        world: List<List<WorldLandmark?>>,
        hand: LeadingHand,
        config: MetricsConfig
    ): List<Float?> {
        val wrist = if (hand == LeadingHand.RIGHT) 16 else 15
        val raw = MutableList<Float?>(frames.size) { null }
        for (index in 1 until frames.size) {
            val now = relativeToHips(world[index][wrist], world[index])
            val before = relativeToHips(world[index - 1][wrist], world[index - 1])
            val torso = torsoLength(world[index])
            val dt = (frames[index].timestampUs - frames[index - 1].timestampUs) / 1_000_000f
            if (now != null && before != null && torso != null && torso > EPSILON && dt > 0f) {
                raw[index] = distance(now, before)!! / torso / dt
            }
        }
        return smoothSeries(raw, config.speedSmoothingWindowFrames)
    }

    private fun segmentSwings(
        frames: List<PoseFrame>, speed: List<Float?>, config: MetricsConfig
    ): List<SwingSegment> {
        val result = mutableListOf<SwingSegment>()
        var start: Int? = null
        var peak = 0
        for (index in speed.indices) {
            val active = (speed[index] ?: 0f) >= config.swingSpeedThreshold
            if (active && start == null) { start = index; peak = index }
            if (active && (speed[index] ?: 0f) > (speed[peak] ?: 0f)) peak = index
            if ((!active || index == speed.lastIndex) && start != null) {
                val end = if (active) index else index - 1
                val durationMs = (frames[end].timestampUs - frames[start].timestampUs) / 1_000L
                if (end > start && durationMs >= config.minSwingDurationMs) {
                    result += SwingSegment(start, peak, end, frames[start].timestampUs, speed[peak] ?: 0f, durationMs)
                }
                start = null
            }
        }
        return result.fold(mutableListOf()) { merged, segment ->
            val previous = merged.lastOrNull()
            if (previous != null && segment.startTimestampUs / 1_000L - (previous.startTimestampUs / 1_000L + previous.durationMs) <= config.minGapBetweenSwingsMs) {
                val peak = if (segment.peakSpeed > previous.peakSpeed) segment else previous
                merged[merged.lastIndex] = SwingSegment(
                    startFrame = previous.startFrame,
                    peakFrame = peak.peakFrame,
                    endFrame = segment.endFrame,
                    startTimestampUs = previous.startTimestampUs,
                    peakSpeed = peak.peakSpeed,
                    durationMs = (frames[segment.endFrame].timestampUs - previous.startTimestampUs) / 1_000L
                )
            } else merged += segment
            merged
        }
    }

    private fun cleanImageTracks(frames: List<PoseFrame>, config: MetricsConfig): List<List<ImageLandmark?>> =
        (0 until 33).map { landmark ->
            val track = frames.map { it.landmarks.getOrNull(landmark)?.takeIf { point -> (point.visibility ?: 1f) >= config.visibilityThreshold } }
            interpolate(track.maskJumps(frames, config) { a, b -> distance(a, b) }, config.maxInterpolationGapFrames) { a, b, ratio ->
                ImageLandmark(a.x.lerp(b.x, ratio), a.y.lerp(b.y, ratio), a.z.lerp(b.z, ratio), a.visibility?.lerp(b.visibility ?: a.visibility ?: 1f, ratio))
            }
        }.transpose()

    private fun cleanWorldTracks(frames: List<PoseFrame>, image: List<List<ImageLandmark?>>, config: MetricsConfig): List<List<WorldLandmark?>> =
        (0 until 33).map { landmark ->
            val track = frames.indices.map { index -> frames[index].worldLandmarks.getOrNull(landmark)?.takeIf { image[index][landmark] != null } }
            interpolate(track.maskJumps(frames, config) { a, b -> distance(a, b) }, config.maxInterpolationGapFrames) { a, b, ratio ->
                WorldLandmark(a.x.lerp(b.x, ratio), a.y.lerp(b.y, ratio), a.z.lerp(b.z, ratio))
            }
        }.transpose()

    private fun smoothImageTracks(points: List<List<ImageLandmark?>>, window: Int): List<List<ImageLandmark?>> =
        (0 until 33).map { landmark -> smoothSeries(points.map { it[landmark] }, window) { values ->
            ImageLandmark(values.map { it.x }.average().toFloat(), values.map { it.y }.average().toFloat(), values.map { it.z }.average().toFloat(), values.mapNotNull { it.visibility }.average().toFloat())
        } }.transpose()

    private fun smoothWorldTracks(points: List<List<WorldLandmark?>>, window: Int): List<List<WorldLandmark?>> =
        (0 until 33).map { landmark -> smoothSeries(points.map { it[landmark] }, window) { values ->
            WorldLandmark(values.map { it.x }.average().toFloat(), values.map { it.y }.average().toFloat(), values.map { it.z }.average().toFloat())
        } }.transpose()

    private fun angle(a: ImageLandmark?, b: ImageLandmark?, c: ImageLandmark?) = angle(a?.toVec(), b?.toVec(), c?.toVec())
    private fun angle(a: WorldLandmark?, b: WorldLandmark?, c: WorldLandmark?) = angle(a?.toVec(), b?.toVec(), c?.toVec())
    private fun angle(a: Vec3?, b: Vec3?, c: Vec3?): Float? {
        if (a == null || b == null || c == null) return null
        val first = a - b; val second = c - b
        val denominator = first.length() * second.length()
        return if (denominator <= EPSILON) null else Math.toDegrees(acos((first.dot(second) / denominator).coerceIn(-1f, 1f)).toDouble()).toFloat()
    }

    private fun imageTrunkLean(points: List<ImageLandmark?>): Float? = trunkLean(points[11]?.toVec(), points[12]?.toVec(), points[23]?.toVec(), points[24]?.toVec())
    private fun worldTrunkLean(points: List<WorldLandmark?>): Float? = trunkLean(points[11]?.toVec(), points[12]?.toVec(), points[23]?.toVec(), points[24]?.toVec())
    private fun trunkLean(ls: Vec3?, rs: Vec3?, lh: Vec3?, rh: Vec3?): Float? {
        val shoulder = midpoint(ls, rs) ?: return null; val hip = midpoint(lh, rh) ?: return null
        val delta = shoulder - hip
        return Math.toDegrees(atan2(abs(delta.x).toDouble(), abs(delta.y).toDouble())).toFloat()
    }
    private fun shoulderHipRotation(p: List<WorldLandmark?>): Float? =
        angleBetweenLines(p[11]?.toVec(), p[12]?.toVec(), p[24]?.toVec(), p[23]?.toVec())
    private fun angleBetweenLines(a: Vec3?, b: Vec3?, c: Vec3?, d: Vec3?): Float? {
        if (a == null || b == null || c == null || d == null) return null
        val first = b - a; val second = d - c
        val denominator = first.length() * second.length()
        return if (denominator <= EPSILON) null else Math.toDegrees(acos((first.dot(second) / denominator).coerceIn(-1f, 1f)).toDouble()).toFloat()
    }
    private fun imageStanceWidth(p: List<ImageLandmark?>): Float? = normalizedDistance(p[27]?.toVec(), p[28]?.toVec(), p[11]?.toVec(), p[12]?.toVec())
    private fun worldStanceWidth(p: List<WorldLandmark?>): Float? = normalizedDistance(p[27]?.toVec(), p[28]?.toVec(), p[11]?.toVec(), p[12]?.toVec())
    private fun normalizedDistance(a: Vec3?, b: Vec3?, c: Vec3?, d: Vec3?): Float? { val scale = distance(c, d); return if (scale == null || scale <= EPSILON) null else distance(a, b)?.div(scale) }
    private fun torsoLength(p: List<WorldLandmark?>) = distance(midpoint(p[11]?.toVec(), p[12]?.toVec()), midpoint(p[23]?.toVec(), p[24]?.toVec()))
    private fun relativeToHips(wrist: WorldLandmark?, p: List<WorldLandmark?>): Vec3? {
        val hip = midpoint(p[23]?.toVec(), p[24]?.toVec()) ?: return null
        return wrist?.toVec()?.minus(hip)
    }

    private data class Vec3(val x: Float, val y: Float, val z: Float) { operator fun minus(other: Vec3) = Vec3(x-other.x,y-other.y,z-other.z); fun dot(o: Vec3)=x*o.x+y*o.y+z*o.z; fun length()=sqrt(dot(this)) }
    private fun ImageLandmark.toVec() = Vec3(x,y,z); private fun WorldLandmark.toVec() = Vec3(x,y,z)
    private fun midpoint(a: Vec3?, b: Vec3?): Vec3? = if (a == null || b == null) null else Vec3((a.x+b.x)/2,(a.y+b.y)/2,(a.z+b.z)/2)
    private fun distance(a: Vec3?, b: Vec3?): Float? = if (a == null || b == null) null else (a-b).length()
    private fun distance(a: ImageLandmark, b: ImageLandmark) = distance(a.toVec(), b.toVec()) ?: 0f
    private fun distance(a: WorldLandmark, b: WorldLandmark) = distance(a.toVec(), b.toVec()) ?: 0f
    private fun Float.lerp(other: Float, ratio: Float) = this + (other - this) * ratio
    private const val EPSILON = 1e-6f
}

private fun <T> List<T?>.maskJumps(frames: List<PoseFrame>, config: MetricsConfig, distance: (T, T) -> Float): List<T?> {
    var previous: T? = null; var previousTime: Long? = null
    return mapIndexed { index, point ->
        val valid = point != null && (previous == null || distance(previous!!, point) / ((frames[index].timestampUs - (previousTime ?: frames[index].timestampUs)).coerceAtLeast(1L) / 1_000_000f) <= config.maxLandmarkJumpPerSecond)
        if (valid) { previous = point; previousTime = frames[index].timestampUs; point } else null
    }
}
private fun <T> interpolate(values: List<T?>, maxGap: Int, blend: (T,T,Float)->T): List<T?> {
    val result = values.toMutableList(); var start = 0
    while (start < result.size) { if (result[start] != null) { start++; continue }; val end = (start until result.size).firstOrNull { result[it] != null } ?: break
        if (start > 0 && end - start <= maxGap) { val before = result[start-1]!!; val after = result[end]!!; for (i in start until end) result[i] = blend(before, after, (i-start+1f)/(end-start+1f)) }; start = end + 1
    }; return result
}
private fun <T> smoothSeries(values: List<T?>, window: Int, make: (List<T>)->T): List<T?> { val radius=(window.coerceAtLeast(1)-1)/2; return values.indices.map { i -> val v=((i-radius)..(i+radius)).mapNotNull { values.getOrNull(it) }; if(v.isEmpty()) null else make(v) } }
private fun smoothSeries(values: List<Float?>, window: Int): List<Float?> = smoothSeries(values, window) { it.average().toFloat() }
private fun <T> List<List<T>>.transpose(): List<List<T>> = if (isEmpty()) emptyList() else List(first().size) { column -> map { it[column] } }
