package com.tennisshadowcoach.analysis

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

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
    /** Reference script: median of centred 7-frame neighbourhood, torso lengths. */
    val outlierMedianWindowFrames: Int = 7,
    val outlierMedianDeviationTorsoLengths: Float = 0.35f,
    val maxInterpolationGapFrames: Int = 6,
    val speedSmoothingWindowFrames: Int = 5,
    val angleSmoothingWindowFrames: Int = 9,
    val swingCoordinateSmoothingWindowFrames: Int = 9,
    val frontFrac: Float = 0.6f,
    val backFrac: Float = 0.25f,
    val minSwingDurationSeconds: Float = 0.25f,
    val segmentationFps: Float = 30f,
    val reliableFrameFraction: Float = 0.7f,
    val trailingPeakExclusionSeconds: Float = 0.3f
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
    val peakSpeed: Float?,
    val peakSpeedFrame: Int?,
    val durationMs: Long,
    val truncated: Boolean = false
)

data class SwingMetrics(
    val segment: SwingSegment,
    val leftKneeAngle3d: Float?,
    val rightKneeAngle3d: Float?,
    val leadingElbowAngle3d: Float?,
    val trunkLean3d: Float?
)

data class SwingDebugData(
    val coordinate: List<Float?>,
    val wristSpeed: List<Float?>,
    val lowerThreshold: Float?,
    val upperThreshold: Float?,
    val allSegments: List<SwingSegment>
)

data class MotionAnalysis(
    val frames: List<FrameMetrics>,
    val swings: List<SwingSegment>,
    val swingMetrics: List<SwingMetrics>,
    val validFractions: Map<MetricId, Float>,
    val debug: SwingDebugData
)

/** Pure Kotlin M1 geometry. It never assigns a quality score to tennis technique. */
object MotionMetricsEngine {
    fun analyze(
        frames: List<PoseFrame>,
        leadingHand: LeadingHand,
        recordingAngle: RecordingAngle,
        config: MetricsConfig = MetricsConfig()
    ): MotionAnalysis {
        if (frames.isEmpty()) return MotionAnalysis(
            emptyList(), emptyList(), emptyList(), emptyMap(),
            SwingDebugData(emptyList(), emptyList(), null, null, emptyList())
        )
        val cleanImage = cleanImageTracks(frames, config)
        val cleanWorld = cleanWorldTracks(frames, cleanImage, config)
        val angleImage = smoothImageTracks(cleanImage, config.angleSmoothingWindowFrames.coerceAtLeast(9))
        val angleWorld = smoothWorldTracks(cleanWorld, config.angleSmoothingWindowFrames.coerceAtLeast(9))
        val wristSpeed = smoothedWristSpeed(frames, cleanWorld, leadingHand, config.copy(speedSmoothingWindowFrames = config.speedSmoothingWindowFrames.coerceIn(5, 7)))

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
        val segmentation = segmentSwings(frames, leadingHand, wristSpeed, config)
        val swingMetrics = segmentation.complete.map { segment ->
            val readings = segment.peakSpeedFrame?.let { frameMetrics.getOrNull(it)?.readings }
            SwingMetrics(
                segment = segment,
                leftKneeAngle3d = readings?.get(MetricId.LEFT_KNEE)?.world3d,
                rightKneeAngle3d = readings?.get(MetricId.RIGHT_KNEE)?.world3d,
                leadingElbowAngle3d = readings?.get(MetricId.LEADING_ELBOW)?.world3d,
                trunkLean3d = readings?.get(MetricId.TRUNK_LEAN)?.world3d
            )
        }
        return MotionAnalysis(
            frames = frameMetrics,
            swings = segmentation.complete,
            swingMetrics = swingMetrics,
            validFractions = validFractions,
            debug = SwingDebugData(segmentation.coordinate, wristSpeed, segmentation.lower, segmentation.upper, segmentation.all)
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

    private data class Segmentation(
        val complete: List<SwingSegment>,
        val all: List<SwingSegment>,
        val coordinate: List<Float?>,
        val lower: Float?,
        val upper: Float?
    )

    /** Direct Kotlin port of data/swing_segmentation_reference.py. */
    private fun segmentSwings(
        frames: List<PoseFrame>, hand: LeadingHand,
        speed: List<Float?>, config: MetricsConfig
    ): Segmentation {
        val wrist = if (hand == LeadingHand.RIGHT) 16 else 15
        val torso = medianTorsoLength(frames.map { it.worldLandmarks }) ?: return Segmentation(emptyList(), emptyList(), List(frames.size) { null }, null, null)
        val raw = frames.map { frame -> frame.worldLandmarks.getOrNull(wrist)?.toVec()?.div(torso) }
        val visible = frames.map { frame -> (frame.landmarks.getOrNull(wrist)?.visibility ?: 0f) >= config.visibilityThreshold }
        val points = cleanReferenceTrack(raw, visible, config)
        val coordinate = swingCoordinate(points, config)
        val good = coordinate.filterNotNull()
        if (good.isEmpty()) return Segmentation(emptyList(), emptyList(), coordinate, null, null)
        val lo = percentile(good, .05f)
        val hi = percentile(good, .95f)
        val amplitude = hi - lo
        if (amplitude <= EPSILON) return Segmentation(emptyList(), emptyList(), coordinate, null, null)
        val front = lo + config.frontFrac * amplitude
        val back = lo + config.backFrac * amplitude
        val held = holdLast(coordinate, lo)
        val all = mutableListOf<SwingSegment>()
        var state = 0
        var start = 0
        var peak = 0
        held.forEachIndexed { index, value ->
            if (state == 0) {
                if (value > front) {
                    var foundStart = index
                    while (foundStart > 0 && held[foundStart] > back) foundStart--
                    start = foundStart
                    peak = index
                    state = 1
                }
            } else {
                if (value > held[peak]) peak = index
                if (value < back) {
                    if ((index - start) / config.segmentationFps >= config.minSwingDurationSeconds) {
                        all += makeSegment(frames, speed, start, peak, index, truncated = false)
                    }
                    state = 0
                }
            }
        }
        if (state == 1 && (frames.lastIndex - start) / config.segmentationFps >= config.minSwingDurationSeconds) {
            all += makeSegment(frames, speed, start, peak, frames.lastIndex, truncated = false)
        }
        val tailFrames = (config.trailingPeakExclusionSeconds * config.segmentationFps).toInt()
        val tagged = all.map { segment -> segment.copy(truncated = segment.startFrame == 0 || segment.peakFrame >= frames.size - 1 - tailFrames) }
        return Segmentation(tagged.filterNot { it.truncated }, tagged, coordinate, back, front)
    }

    private fun makeSegment(frames: List<PoseFrame>, speed: List<Float?>, start: Int, peak: Int, end: Int, truncated: Boolean): SwingSegment {
        val speedFrame = (start..end).filter { speed[it] != null }.maxByOrNull { speed[it]!! }
        return SwingSegment(
            startFrame = start, peakFrame = peak, endFrame = end,
            startTimestampUs = frames[start].timestampUs,
            peakSpeed = speedFrame?.let { speed[it] }, peakSpeedFrame = speedFrame,
            durationMs = (frames[end].timestampUs - frames[start].timestampUs) / 1_000L,
            truncated = truncated
        )
    }

    private fun cleanImageTracks(frames: List<PoseFrame>, config: MetricsConfig): List<List<ImageLandmark?>> {
        val scale = medianImageTorsoLength(frames)?.takeIf { it > EPSILON } ?: 1f
        return (0 until 33).map { landmark ->
            val track = frames.map { it.landmarks.getOrNull(landmark)?.takeIf { point -> (point.visibility ?: 1f) >= config.visibilityThreshold } }
            interpolate(maskMedianOutliers(track, scale, config) { it.toVec() }, config.maxInterpolationGapFrames) { a, b, ratio ->
                ImageLandmark(a.x.lerp(b.x, ratio), a.y.lerp(b.y, ratio), a.z.lerp(b.z, ratio), a.visibility?.lerp(b.visibility ?: a.visibility ?: 1f, ratio))
            }
        }.transpose()
    }

    private fun cleanWorldTracks(frames: List<PoseFrame>, image: List<List<ImageLandmark?>>, config: MetricsConfig): List<List<WorldLandmark?>> {
        val scale = medianTorsoLength(frames.map { it.worldLandmarks })?.takeIf { it > EPSILON } ?: 1f
        return (0 until 33).map { landmark ->
            val track = frames.indices.map { index -> frames[index].worldLandmarks.getOrNull(landmark)?.takeIf { image[index][landmark] != null } }
            interpolate(maskMedianOutliers(track, scale, config) { it.toVec() }, config.maxInterpolationGapFrames) { a, b, ratio ->
                WorldLandmark(a.x.lerp(b.x, ratio), a.y.lerp(b.y, ratio), a.z.lerp(b.z, ratio))
            }
        }.transpose()
    }

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

    private data class Vec3(val x: Float, val y: Float, val z: Float) {
        operator fun minus(other: Vec3) = Vec3(x-other.x,y-other.y,z-other.z)
        operator fun div(scale: Float) = Vec3(x / scale, y / scale, z / scale)
        fun dot(o: Vec3)=x*o.x+y*o.y+z*o.z
        fun length()=sqrt(dot(this))
    }
    private fun ImageLandmark.toVec() = Vec3(x,y,z); private fun WorldLandmark.toVec() = Vec3(x,y,z)
    private fun midpoint(a: Vec3?, b: Vec3?): Vec3? = if (a == null || b == null) null else Vec3((a.x+b.x)/2,(a.y+b.y)/2,(a.z+b.z)/2)
    private fun distance(a: Vec3?, b: Vec3?): Float? = if (a == null || b == null) null else (a-b).length()
    private fun distance(a: ImageLandmark, b: ImageLandmark) = distance(a.toVec(), b.toVec()) ?: 0f
    private fun distance(a: WorldLandmark, b: WorldLandmark) = distance(a.toVec(), b.toVec()) ?: 0f
    private fun Float.lerp(other: Float, ratio: Float) = this + (other - this) * ratio

    /** Same cleaning order as clean_track() in the Python reference. */
    private fun cleanReferenceTrack(raw: List<Vec3?>, visible: List<Boolean>, config: MetricsConfig): List<Vec3?> {
        val masked = raw.indices.map { index ->
            val point = raw[index] ?: return@map null
            if (!visible[index]) return@map null
            val neighbours = ((index - 3)..(index + 3)).mapNotNull { raw.getOrNull(it) }
            val median = neighbours.takeIf { it.isNotEmpty() }?.let { medianVec(it) }
            point.takeIf { median == null || distance(it, median)!! <= config.outlierMedianDeviationTorsoLengths }
        }
        return interpolate(masked, config.maxInterpolationGapFrames) { a, b, ratio ->
            Vec3(a.x.lerp(b.x, ratio), a.y.lerp(b.y, ratio), a.z.lerp(b.z, ratio))
        }
    }

    private fun swingCoordinate(points: List<Vec3?>, config: MetricsConfig): List<Float?> {
        val valid = points.filterNotNull()
        if (valid.size < 3) return List(points.size) { null }
        val mean = meanVec(valid)
        val axis = principalAxis(valid.map { it - mean }) ?: return List(points.size) { null }
        val raw = points.map { point -> point?.minus(mean)?.dot(axis) }
        val filled = interpolateAll(raw) ?: return raw
        val smoothed = savitzkyGolayReference(filled, config.swingCoordinateSmoothingWindowFrames)
        val restored = raw.indices.map { index -> smoothed[index].takeIf { raw[index] != null } }
        return if (skew(restored.filterNotNull()) < 0f) restored.map { it?.unaryMinus() } else restored
    }

    private fun savitzkyGolayReference(values: List<Float>, requestedWindow: Int): List<Float> {
        val window = requestedWindow.coerceAtLeast(3).let { if (it % 2 == 0) it + 1 else it }.coerceAtMost(values.size.let { if (it % 2 == 0) it - 1 else it })
        if (window < 3) return values
        val radius = window / 2
        return values.indices.map { index ->
            val range = when {
                index < radius -> 0 until window
                index >= values.size - radius -> values.size - window until values.size
                else -> index - radius..index + radius
            }
            polynomialAt(range.map { it - index to values[it] }, 2)
        }
    }

    private fun polynomialAt(samples: List<Pair<Int, Float>>, degree: Int): Float {
        val n = degree + 1
        val matrix = Array(n) { FloatArray(n) }
        val rhs = FloatArray(n)
        samples.forEach { (x, y) ->
            for (row in 0 until n) {
                val xr = x.toFloat().pow(row)
                rhs[row] += xr * y
                for (column in 0 until n) matrix[row][column] += xr * x.toFloat().pow(column)
            }
        }
        for (pivot in 0 until n) {
            var best = pivot
            for (row in pivot + 1 until n) if (abs(matrix[row][pivot]) > abs(matrix[best][pivot])) best = row
            if (abs(matrix[best][pivot]) <= EPSILON) return samples.map { it.second }.average().toFloat()
            val swap = matrix[pivot]; matrix[pivot] = matrix[best]; matrix[best] = swap
            val value = rhs[pivot]; rhs[pivot] = rhs[best]; rhs[best] = value
            val diagonal = matrix[pivot][pivot]
            for (column in pivot until n) matrix[pivot][column] /= diagonal
            rhs[pivot] /= diagonal
            for (row in 0 until n) if (row != pivot) {
                val factor = matrix[row][pivot]
                for (column in pivot until n) matrix[row][column] -= factor * matrix[pivot][column]
                rhs[row] -= factor * rhs[pivot]
            }
        }
        return rhs[0]
    }

    private fun <T> maskMedianOutliers(values: List<T?>, scale: Float, config: MetricsConfig, toVec: (T) -> Vec3): List<T?> {
        val half = config.outlierMedianWindowFrames / 2
        return values.indices.map { index ->
            val point = values[index] ?: return@map null
            val neighbours = ((index - half)..(index + half)).mapNotNull { values.getOrNull(it) }
            val median = neighbours.takeIf { it.isNotEmpty() }?.let { medianVec(it.map(toVec)) }
            point.takeIf { median == null || distance(toVec(it), median)!! / scale <= config.outlierMedianDeviationTorsoLengths }
        }
    }

    private fun medianTorsoLength(frames: List<List<WorldLandmark?>>): Float? = frames.mapNotNull { torsoLength(it) }.takeIf { it.isNotEmpty() }?.median()
    private fun medianImageTorsoLength(frames: List<PoseFrame>): Float? = frames.mapNotNull { distance(midpoint(it.landmarks.getOrNull(11)?.toVec(), it.landmarks.getOrNull(12)?.toVec()), midpoint(it.landmarks.getOrNull(23)?.toVec(), it.landmarks.getOrNull(24)?.toVec())) }.takeIf { it.isNotEmpty() }?.median()
    private fun meanVec(points: List<Vec3>) = Vec3(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat(), points.map { it.z }.average().toFloat())
    private fun medianVec(points: List<Vec3>) = Vec3(points.map { it.x }.median(), points.map { it.y }.median(), points.map { it.z }.median())
    private fun principalAxis(centered: List<Vec3>): Vec3? {
        var axis = Vec3(1f, 1f, 1f)
        repeat(32) {
            val next = centered.fold(Vec3(0f, 0f, 0f)) { sum, point -> Vec3(sum.x + point.x * point.dot(axis), sum.y + point.y * point.dot(axis), sum.z + point.z * point.dot(axis)) }
            if (next.length() <= EPSILON) return null
            axis = next / next.length()
        }
        return axis
    }
    private fun interpolateAll(values: List<Float?>): List<Float>? {
        val first = values.indexOfFirst { it != null }
        if (first < 0) return null
        val out = values.toMutableList(); var previous = first
        for (index in first + 1 until out.size) if (out[index] != null) { for (between in previous + 1 until index) out[between] = out[previous]!!.lerp(out[index]!!, (between - previous).toFloat() / (index - previous)); previous = index }
        for (index in 0 until first) out[index] = out[first]
        for (index in previous + 1 until out.size) out[index] = out[previous]
        return out.map { it!! }
    }
    private fun skew(values: List<Float>): Float { if (values.size < 2) return 0f; val mean = values.average().toFloat(); val second = values.map { (it - mean) * (it - mean) }.average().toFloat(); return if (second <= EPSILON) 0f else values.map { (it - mean) * (it - mean) * (it - mean) }.average().toFloat() / sqrt(second * second * second) }
    private fun holdLast(values: List<Float?>, initial: Float): List<Float> { var last = initial; return values.map { (it ?: last).also { value -> last = value } } }
    private fun List<Float>.median(): Float = sorted().let { if (size % 2 == 0) (it[size / 2 - 1] + it[size / 2]) / 2f else it[size / 2] }
    private fun percentile(values: List<Float>, fraction: Float): Float { val sorted = values.sorted(); val position = fraction.coerceIn(0f, 1f) * (sorted.size - 1); val low = position.toInt(); return sorted[low].lerp(sorted[(low + 1).coerceAtMost(sorted.lastIndex)], position - low) }
    private fun Float.pow(power: Int): Float { var result = 1f; repeat(power) { result *= this }; return result }
    private const val EPSILON = 1e-6f
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
