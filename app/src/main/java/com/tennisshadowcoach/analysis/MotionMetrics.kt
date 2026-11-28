package com.tennisshadowcoach.analysis

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Every metric is computed for every recording.  The view the user films from is not chosen in
 * the app; it is measured from the pose ([ViewEstimate]) and shown next to the numbers, so the
 * accuracy of each metric per view can be checked instead of hiding metrics up front.
 */
enum class MetricId(val label: String) {
    LEFT_KNEE("Левое колено"),
    RIGHT_KNEE("Правое колено"),
    LEADING_ELBOW("Локоть ведущей руки"),
    TRUNK_LEAN("Наклон корпуса (3D, от вертикали, без знака)"),
    SHOULDER_HIP_ROTATION("Разворот плеч относительно таза"),
    STANCE_WIDTH("Ширина стойки"),
    WRIST_SPEED("Скорость ведущего запястья")
}

/** Which body side faces the camera, by the depth of the shoulders. */
enum class CameraSide(val wireValue: String, val label: String) {
    LEADING("leading", "со стороны ведущей руки"),
    TRAILING("trailing", "со стороны свободной руки"),
    UNKNOWN("unknown", "не определено")
}

/** Coarse body orientation to the camera, measured at rest between swings. */
enum class MeasuredView(val wireValue: String, val label: String) {
    FACING("facing", "корпус к камере"),
    HALF_TURNED("half_turned", "вполоборота"),
    PROFILE("profile", "профиль"),
    UNKNOWN("unknown", "не определено")
}

/**
 * Measured, not declared.  Yaw is the angle of the hip line out of the image plane in MediaPipe
 * world coordinates (0° = hips parallel to the image, 90° = profile), median over in-position
 * frames between swings.  World depth is the noisiest MediaPipe axis, so the numbers are a
 * diagnostic estimate; the category boundaries (20°/55°) are not validated against a reference.
 */
data class ViewEstimate(
    val hipYawDegrees: Float?,
    val shoulderYawDegrees: Float?,
    /** Image shoulder width / image torso length in pixels; it shrinks towards a profile view. */
    val imageShoulderWidthToTorso: Float?,
    val cameraSide: CameraSide,
    val view: MeasuredView,
    val restFrames: Int
) {
    companion object {
        val UNKNOWN = ViewEstimate(null, null, null, CameraSide.UNKNOWN, MeasuredView.UNKNOWN, 0)
    }
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
    val trailingPeakExclusionSeconds: Float = 0.3f,
    /**
     * Setup gate: a frame counts only while the image torso length is within this relative
     * tolerance of the recording median, i.e. the person stands at the working distance.
     * It removes walking to/from the phone at the start and end of selfie recordings.
     */
    val inPositionTorsoTolerance: Float = 0.25f,
    /** Shorter in-position runs are treated as setup (passing frames while walking). */
    val minInPositionSeconds: Float = 1.0f,
    /**
     * A cycle counts as a swing only if its maximum rise speed along the swing axis reaches
     * this fraction of the median over candidate cycles.  Slowly raising the racket into the
     * ready position is a cycle of position, but not a swing.
     */
    val minSwingSpeedFraction: Float = 0.3f,
    /**
     * Live counting: a swing is counted once the coordinate has fallen this fraction of the
     * calibrated amplitude below its running peak, instead of waiting for the return to rest
     * (median 1.0 s, up to 1.9 s after the peak).  With 0.05 the delay on the six reference
     * recordings is median 0.10 s, at most 0.33 s; with 0.1 it was up to 0.6 s.  It must not
     * exceed front_frac - back_frac (0.1 at the edge of the tested matrix), so every window
     * reaches it before it closes.
     */
    val liveCountDropFraction: Float = 0.05f,
    /**
     * A swing without a shoulder turn is still counted, but gets the [SwingCorrection.NO_SHOULDER_TURN]
     * correction.  A full swing turns the shoulders at least this much (range of the shoulder-line yaw from
     * [shoulderTurnBeforePeakSeconds] before the coordinate peak to [shoulderTurnAfterPeakSeconds]
     * after it).  Arm-only movements ("a hit without a backswing", a light wave of the arm) stay
     * below: 30–36° against 45–130° for full swings.  Same result for 37–42° and spans 1.0–1.4 s.
     */
    val minShoulderTurnDegrees: Float = 40f,
    val shoulderTurnBeforePeakSeconds: Float = 1.2f,
    val shoulderTurnAfterPeakSeconds: Float = 0.1f
) {
    val shoulderTurnBeforeFrames: Int get() = (shoulderTurnBeforePeakSeconds * segmentationFps).toInt()
    val shoulderTurnAfterFrames: Int get() = (shoulderTurnAfterPeakSeconds * segmentationFps).toInt()
}

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
    /** Maximum positive derivative of the swing coordinate before its coordinate peak. */
    val impactCoordinateSpeed: Float?,
    val impactFrame: Int?,
    val durationMs: Long,
    val truncated: Boolean = false,
    /** Excluded because the rise was much slower than in the other swings of the recording. */
    val tooSlow: Boolean = false,
    /** Range of the shoulder turn around the peak, degrees; see [MetricsConfig.minShoulderTurnDegrees]. */
    val shoulderTurnDegrees: Float? = null,
    /** What to correct in this swing.  A swing with corrections is still counted. */
    val corrections: List<SwingCorrection> = emptyList()
) {
    val counted: Boolean get() = !truncated && !tooSlow
}

/**
 * A technique correction for a counted swing: the app is a practice coach, so a wrong swing is
 * counted and told what to fix instead of being dropped.
 */
enum class SwingCorrection(val title: String, val advice: String, val spoken: String) {
    NO_SHOULDER_TURN(
        "Нет разворота плеч",
        "Начни замах с разворота плеч и корпуса боком к сетке, а не только рукой.",
        "Нет замаха. Разверни плечи."
    )
}

data class SwingMetrics(
    val segment: SwingSegment,
    val leadingKnee: MetricSummary,
    val leadingElbow: MetricSummary,
    val trunkLean: MetricSummary
) {
    /** Names of metrics deliberately withheld because too few raw frames were valid. */
    val unreliableMetricLabels: List<String>
        get() = listOf(leadingKnee, leadingElbow, trunkLean)
            .filter { it.available && !it.reliable }
            .map { it.label }
}

/** Values are in degrees.  A value is omitted rather than guessed when raw evidence is weak. */
data class MetricSummary(
    val label: String,
    val atImpact: Float?,
    val atCoordinatePeak: Float?,
    val min: Float?,
    val max: Float?,
    val validFrameFraction: Float,
    val reliable: Boolean,
    val available: Boolean
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
    val debug: SwingDebugData,
    val view: ViewEstimate = ViewEstimate.UNKNOWN
)

/** Pure Kotlin M1 geometry. It never assigns a quality score to tennis technique. */
object MotionMetricsEngine {
    /**
     * [imageAspect] is image height / width of the frames the model saw (portrait 1280×720 → 16/9);
     * it only affects the image shoulder-width diagnostic.
     */
    fun analyze(
        frames: List<PoseFrame>,
        leadingHand: LeadingHand,
        config: MetricsConfig = MetricsConfig(),
        imageAspect: Float = 16f / 9f
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
                readings = readingsFor(image, world, wristSpeed[index], leadingHand)
            )
        }
        val validFractions = MetricId.entries.associateWith { metric ->
            frames.indices.count { rawMetricIsValid(frames[it], metric, leadingHand, config) }
                .toFloat() / frames.size
        }
        val segmentation = segmentSwings(frames, leadingHand, config)
        val swingMetrics = segmentation.complete.map { segment ->
            SwingMetrics(
                segment = segment,
                leadingKnee = summarizeSwingMetric(
                    frames, frameMetrics, segment,
                    if (leadingHand == LeadingHand.RIGHT) MetricId.RIGHT_KNEE else MetricId.LEFT_KNEE,
                    leadingHand, config
                ),
                leadingElbow = summarizeSwingMetric(
                    frames, frameMetrics, segment, MetricId.LEADING_ELBOW,
                    leadingHand, config
                ),
                trunkLean = summarizeSwingMetric(
                    frames, frameMetrics, segment, MetricId.TRUNK_LEAN,
                    leadingHand, config
                )
            )
        }
        return MotionAnalysis(
            frames = frameMetrics,
            swings = segmentation.complete,
            swingMetrics = swingMetrics,
            validFractions = validFractions,
            debug = SwingDebugData(segmentation.coordinate, wristSpeed, segmentation.lower, segmentation.upper, segmentation.all),
            view = estimateView(frames, segmentation.all, leadingHand, config, imageAspect)
        )
    }

    /** Median body orientation over in-position frames outside every swing window. */
    internal fun estimateView(
        frames: List<PoseFrame>,
        windows: List<SwingSegment>,
        hand: LeadingHand,
        config: MetricsConfig,
        imageAspect: Float
    ): ViewEstimate {
        val inPosition = inPositionMask(frames, config)
        val rest = frames.indices.filter { index ->
            inPosition[index] && windows.none { index in it.startFrame..it.endFrame }
        }
        if (rest.isEmpty()) return ViewEstimate.UNKNOWN
        fun yaw(points: List<WorldLandmark?>, left: Int, right: Int): Float? {
            val l = points.getOrNull(left) ?: return null
            val r = points.getOrNull(right) ?: return null
            val dx = abs(l.x - r.x); val dz = abs(l.z - r.z)
            return if (dx + dz <= EPSILON) null else Math.toDegrees(atan2(dz, dx).toDouble()).toFloat()
        }
        val hipYaw = rest.mapNotNull { yaw(frames[it].worldLandmarks, 23, 24) }.takeIf { it.isNotEmpty() }?.median()
        val shoulderYaw = rest.mapNotNull { yaw(frames[it].worldLandmarks, 11, 12) }.takeIf { it.isNotEmpty() }?.median()
        val widthRatio = rest.mapNotNull { index ->
            val p = frames[index].landmarks
            val ls = p.getOrNull(11) ?: return@mapNotNull null; val rs = p.getOrNull(12) ?: return@mapNotNull null
            val lh = p.getOrNull(23) ?: return@mapNotNull null; val rh = p.getOrNull(24) ?: return@mapNotNull null
            fun px(dx: Float, dy: Float) = sqrt(dx * dx + dy * dy * imageAspect * imageAspect)
            val torso = px((ls.x + rs.x - lh.x - rh.x) / 2f, (ls.y + rs.y - lh.y - rh.y) / 2f)
            if (torso <= EPSILON) null else px(ls.x - rs.x, ls.y - rs.y) / torso
        }.takeIf { it.isNotEmpty() }?.median()
        // MediaPipe world z: smaller is closer to the camera.
        val leadingCloser = rest.mapNotNull { index ->
            val w = frames[index].worldLandmarks
            val leading = w.getOrNull(if (hand == LeadingHand.RIGHT) 12 else 11) ?: return@mapNotNull null
            val trailing = w.getOrNull(if (hand == LeadingHand.RIGHT) 11 else 12) ?: return@mapNotNull null
            trailing.z - leading.z
        }.takeIf { it.isNotEmpty() }?.median()
        val side = when {
            leadingCloser == null || abs(leadingCloser) < CAMERA_SIDE_MIN_DEPTH_METERS -> CameraSide.UNKNOWN
            leadingCloser > 0f -> CameraSide.LEADING
            else -> CameraSide.TRAILING
        }
        val view = when {
            hipYaw == null -> MeasuredView.UNKNOWN
            hipYaw < 20f -> MeasuredView.FACING
            hipYaw < 55f -> MeasuredView.HALF_TURNED
            else -> MeasuredView.PROFILE
        }
        return ViewEstimate(hipYaw, shoulderYaw, widthRatio, side, view, rest.size)
    }

    private fun summarizeSwingMetric(
        rawFrames: List<PoseFrame>,
        smoothedFrames: List<FrameMetrics>,
        segment: SwingSegment,
        metric: MetricId,
        hand: LeadingHand,
        config: MetricsConfig
    ): MetricSummary {
        val range = segment.startFrame..segment.endFrame
        val validFraction = range.count { index ->
            rawMetricIsValid(rawFrames[index], metric, hand, config)
        }.toFloat() / range.count()
        val reliable = validFraction >= config.reliableFrameFraction
        val values = range.mapNotNull { index -> smoothedFrames[index].readings[metric]?.world3d }
        val atImpact = when (metric) {
            MetricId.LEADING_ELBOW -> averageAroundImpact(smoothedFrames, segment, metric)
            else -> segment.impactFrame?.let { smoothedFrames.getOrNull(it)?.readings?.get(metric)?.world3d }
        }
        val atCoordinatePeak = smoothedFrames.getOrNull(segment.peakFrame)?.readings?.get(metric)?.world3d
        return MetricSummary(
            label = metric.label,
            atImpact = atImpact.takeIf { reliable },
            atCoordinatePeak = atCoordinatePeak.takeIf { reliable },
            min = values.minOrNull().takeIf { reliable },
            max = values.maxOrNull().takeIf { reliable },
            validFrameFraction = validFraction,
            reliable = reliable,
            available = true
        )
    }

    /** The elbow is averaged over five frames around impact to suppress a single unstable frame. */
    private fun averageAroundImpact(frames: List<FrameMetrics>, segment: SwingSegment, metric: MetricId): Float? {
        val impact = segment.impactFrame ?: return null
        val values = ((impact - 2)..(impact + 2))
            .filter { it in segment.startFrame..segment.endFrame }
            .mapNotNull { frames.getOrNull(it)?.readings?.get(metric)?.world3d }
        return values.takeIf { it.size >= 3 }?.average()?.toFloat()
    }

    private fun rawMetricIsValid(
        frame: PoseFrame,
        metric: MetricId,
        hand: LeadingHand,
        config: MetricsConfig
    ): Boolean {
        val required = when (metric) {
            MetricId.LEFT_KNEE -> listOf(23, 25, 27)
            MetricId.RIGHT_KNEE -> listOf(24, 26, 28)
            MetricId.LEADING_ELBOW -> if (hand == LeadingHand.RIGHT) listOf(12, 14, 16) else listOf(11, 13, 15)
            MetricId.TRUNK_LEAN -> listOf(11, 12, 23, 24)
            MetricId.SHOULDER_HIP_ROTATION -> listOf(11, 12, 23, 24)
            MetricId.STANCE_WIDTH -> listOf(11, 12, 27, 28)
            MetricId.WRIST_SPEED -> if (hand == LeadingHand.RIGHT) listOf(16, 23, 24, 11, 12) else listOf(15, 23, 24, 11, 12)
        }
        return required.all { landmark ->
            frame.landmarks.getOrNull(landmark)?.visibility?.let { it >= config.visibilityThreshold } == true &&
                frame.worldLandmarks.getOrNull(landmark) != null
        }
    }

    private fun readingsFor(
        image: List<ImageLandmark?>,
        world: List<WorldLandmark?>,
        wristSpeed: Float?,
        hand: LeadingHand
    ): Map<MetricId, MetricReading> {
        fun reading(worldValue: Float?, imageValue: Float?) = MetricReading(worldValue, imageValue)
        val leadingShoulder = if (hand == LeadingHand.RIGHT) 12 else 11
        val leadingElbow = if (hand == LeadingHand.RIGHT) 14 else 13
        val leadingWrist = if (hand == LeadingHand.RIGHT) 16 else 15
        return mapOf(
            MetricId.LEFT_KNEE to reading(angle(world[23], world[25], world[27]), angle(image[23], image[25], image[27])),
            MetricId.RIGHT_KNEE to reading(angle(world[24], world[26], world[28]), angle(image[24], image[26], image[28])),
            MetricId.LEADING_ELBOW to reading(
                angle(world[leadingShoulder], world[leadingElbow], world[leadingWrist]),
                angle(image[leadingShoulder], image[leadingElbow], image[leadingWrist])
            ),
            // The reported metric is world-3D.  The image-plane value is debug-only.
            MetricId.TRUNK_LEAN to reading(worldTrunkLean(world), imageTrunkLean(image)),
            MetricId.SHOULDER_HIP_ROTATION to reading(shoulderHipRotation(world), null),
            MetricId.STANCE_WIDTH to reading(worldStanceWidth(world), imageStanceWidth(image)),
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
        return savitzkyGolayWithGaps(raw, config.speedSmoothingWindowFrames)
    }

    private data class Segmentation(
        val complete: List<SwingSegment>,
        val all: List<SwingSegment>,
        val coordinate: List<Float?>,
        val lower: Float?,
        val upper: Float?
    )

    private data class SwingCoordinates(
        val segmentation: List<Float?>,
        /** Savitzky–Golay derivative, matching impact_moment() in the Python reference. */
        val impactDerivative: List<Float?>
    )

    /** Kotlin port of swing_segmentation_reference.py in the repository root. */
    private fun segmentSwings(frames: List<PoseFrame>, hand: LeadingHand, config: MetricsConfig): Segmentation {
        val wrist = if (hand == LeadingHand.RIGHT) 16 else 15
        val torso = medianTorsoLength(frames.map { it.worldLandmarks }) ?: return Segmentation(emptyList(), emptyList(), List(frames.size) { null }, null, null)
        // MediaPipe world coordinates are hip-centred in the usual case; subtracting the
        // measured hip midpoint also makes the invariant explicit for imported tracks.
        val raw = frames.map { frame ->
            relativeToHips(frame.worldLandmarks.getOrNull(wrist), frame.worldLandmarks)?.div(torso)
        }
        val inPosition = inPositionMask(frames, config)
        val visible = frames.indices.map { index ->
            inPosition[index] && (frames[index].landmarks.getOrNull(wrist)?.visibility ?: 0f) >= config.visibilityThreshold
        }
        val points = cleanReferenceTrack(raw, visible, config)
        val trailingSide = trailingSideDirection(frames.indices.filter { visible[it] }.map { frames[it].worldLandmarks }, hand)
        val coordinates = swingCoordinates(points, config, trailingSide)
        val coordinate = coordinates.segmentation
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
                        all += makeSegment(frames, coordinates.impactDerivative, start, peak, index, truncated = false)
                    }
                    state = 0
                }
            }
        }
        if (state == 1 && (frames.lastIndex - start) / config.segmentationFps >= config.minSwingDurationSeconds) {
            all += makeSegment(frames, coordinates.impactDerivative, start, peak, frames.lastIndex, truncated = false)
        }
        // The usable recording runs from the first to the last frame with a coordinate: setup
        // frames before and after are outside it, exactly like frames outside the video.
        val firstValid = coordinate.indexOfFirst { it != null }
        val lastValid = coordinate.indexOfLast { it != null }
        val tailFrames = (config.trailingPeakExclusionSeconds * config.segmentationFps).toInt()
        val yaw = ShoulderTurn.yawSeries(frames.map { it.worldLandmarks })
        val bounded = all.map { segment ->
            val turn = ShoulderTurn.turnDegrees(yaw, segment.peakFrame, config.shoulderTurnBeforeFrames, config.shoulderTurnAfterFrames)
            segment.copy(
                truncated = segment.startFrame <= firstValid || segment.peakFrame >= lastValid - tailFrames,
                shoulderTurnDegrees = turn,
                corrections = listOfNotNull(
                    SwingCorrection.NO_SHOULDER_TURN.takeIf { turn != null && turn < config.minShoulderTurnDegrees }
                )
            )
        }
        val speeds = bounded.filterNot { it.truncated }.mapNotNull { it.impactCoordinateSpeed }
        val speedFloor = speeds.takeIf { it.isNotEmpty() }?.median()?.times(config.minSwingSpeedFraction)
        val tagged = bounded.map { segment ->
            if (segment.truncated || speedFloor == null) segment
            else segment.copy(tooSlow = (segment.impactCoordinateSpeed ?: 0f) < speedFloor)
        }
        return Segmentation(tagged.filter { it.counted }, tagged, coordinate, back, front)
    }

    /**
     * True where the person is at the working distance: the image torso length is within
     * [MetricsConfig.inPositionTorsoTolerance] of the recording median, for a continuous run of
     * at least [MetricsConfig.minInPositionSeconds].
     */
    internal fun inPositionMask(frames: List<PoseFrame>, config: MetricsConfig): List<Boolean> {
        val torso = frames.map { imageTorsoLength2d(it.landmarks) }
        val median = torso.filterNotNull().takeIf { it.isNotEmpty() }?.median() ?: return List(frames.size) { true }
        if (median <= EPSILON) return List(frames.size) { true }
        val upper = 1f + config.inPositionTorsoTolerance
        val raw = torso.map { length -> length != null && length / median in (1f / upper)..upper }
        val minRun = (config.minInPositionSeconds * config.segmentationFps).toInt()
        val mask = raw.toMutableList()
        var start = 0
        while (start < mask.size) {
            if (!raw[start]) { start++; continue }
            var end = start
            while (end < mask.size && raw[end]) end++
            if (end - start < minRun) for (index in start until end) mask[index] = false
            start = end
        }
        return mask
    }

    /** Shoulder-midpoint to hip-midpoint distance in normalised image x/y. */
    internal fun imageTorsoLength2d(points: List<ImageLandmark?>): Float? {
        val ls = points.getOrNull(11) ?: return null; val rs = points.getOrNull(12) ?: return null
        val lh = points.getOrNull(23) ?: return null; val rh = points.getOrNull(24) ?: return null
        val dx = (ls.x + rs.x - lh.x - rh.x) / 2f
        val dy = (ls.y + rs.y - lh.y - rh.y) / 2f
        return sqrt(dx * dx + dy * dy)
    }

    private fun makeSegment(
        frames: List<PoseFrame>, impactDerivative: List<Float?>,
        start: Int, peak: Int, end: Int, truncated: Boolean
    ): SwingSegment {
        val impact = maximumCoordinateDerivative(frames, impactDerivative, start, peak)
        return SwingSegment(
            startFrame = start, peakFrame = peak, endFrame = end,
            startTimestampUs = frames[start].timestampUs,
            impactCoordinateSpeed = impact?.second, impactFrame = impact?.first,
            durationMs = (frames[end].timestampUs - frames[start].timestampUs) / 1_000L,
            truncated = truncated
        )
    }

    /** Same choice as impact_moment(): maximum SG derivative from start through coordinate peak. */
    private fun maximumCoordinateDerivative(
        frames: List<PoseFrame>, derivative: List<Float?>, start: Int, peak: Int
    ): Pair<Int, Float>? = (start..peak)
        .mapNotNull { index -> derivative.getOrNull(index)?.let { index to it } }
        .maxByOrNull { it.second }

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

    /** Savitzky-Golay smoothing is applied per contiguous track; long gaps remain absent. */
    private fun smoothImageTracks(points: List<List<ImageLandmark?>>, window: Int): List<List<ImageLandmark?>> =
        (0 until 33).map { landmark ->
            val x = savitzkyGolayWithGaps(points.map { it[landmark]?.x }, window)
            val y = savitzkyGolayWithGaps(points.map { it[landmark]?.y }, window)
            val z = savitzkyGolayWithGaps(points.map { it[landmark]?.z }, window)
            points.indices.map { index ->
                if (x[index] == null || y[index] == null || z[index] == null) null
                else ImageLandmark(x[index]!!, y[index]!!, z[index]!!, points[index][landmark]?.visibility)
            }
        }.transpose()

    private fun smoothWorldTracks(points: List<List<WorldLandmark?>>, window: Int): List<List<WorldLandmark?>> =
        (0 until 33).map { landmark ->
            val x = savitzkyGolayWithGaps(points.map { it[landmark]?.x }, window)
            val y = savitzkyGolayWithGaps(points.map { it[landmark]?.y }, window)
            val z = savitzkyGolayWithGaps(points.map { it[landmark]?.z }, window)
            points.indices.map { index ->
                if (x[index] == null || y[index] == null || z[index] == null) null
                else WorldLandmark(x[index]!!, y[index]!!, z[index]!!)
            }
        }.transpose()

    private fun angle(a: ImageLandmark?, b: ImageLandmark?, c: ImageLandmark?) = angle(a?.toVec(), b?.toVec(), c?.toVec())
    private fun angle(a: WorldLandmark?, b: WorldLandmark?, c: WorldLandmark?) = angle(a?.toVec(), b?.toVec(), c?.toVec())
    private fun angle(a: Vec3?, b: Vec3?, c: Vec3?): Float? {
        if (a == null || b == null || c == null) return null
        val first = a - b; val second = c - b
        val denominator = first.length() * second.length()
        return if (denominator <= EPSILON) null else Math.toDegrees(acos((first.dot(second) / denominator).coerceIn(-1f, 1f)).toDouble()).toFloat()
    }

    private fun imageTrunkLean(points: List<ImageLandmark?>): Float? = trunkLean(points[11]?.toVec(), points[12]?.toVec(), points[23]?.toVec(), points[24]?.toVec())
    private fun worldTrunkLean(points: List<WorldLandmark?>): Float? = trunkLean3d(points[11]?.toVec(), points[12]?.toVec(), points[23]?.toVec(), points[24]?.toVec())
    private fun trunkLean(ls: Vec3?, rs: Vec3?, lh: Vec3?, rh: Vec3?): Float? {
        val shoulder = midpoint(ls, rs) ?: return null; val hip = midpoint(lh, rh) ?: return null
        val delta = shoulder - hip
        return Math.toDegrees(atan2(abs(delta.x).toDouble(), abs(delta.y).toDouble())).toFloat()
    }
    /** Reference trunk_lean_3d: unsigned angle of hips-to-shoulders against world vertical y. */
    private fun trunkLean3d(ls: Vec3?, rs: Vec3?, lh: Vec3?, rh: Vec3?): Float? {
        val shoulder = midpoint(ls, rs) ?: return null; val hip = midpoint(lh, rh) ?: return null
        val delta = shoulder - hip
        val length = delta.length()
        if (length <= EPSILON) return null
        return Math.toDegrees(acos((abs(delta.y) / length).coerceIn(0f, 1f).toDouble())).toFloat()
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

    internal data class Vec3(val x: Float, val y: Float, val z: Float) {
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

    /**
     * The segmentation coordinate uses its configured long window.  Impact timing uses the
     * identical projection, but a separate 5–7 frame Savitzky–Golay derivative.
     */
    private fun swingCoordinates(points: List<Vec3?>, config: MetricsConfig, trailingSide: Vec3?): SwingCoordinates {
        val valid = points.filterNotNull()
        if (valid.size < 3) return SwingCoordinates(List(points.size) { null }, List(points.size) { null })
        val mean = meanVec(valid)
        val axis = principalAxis(valid.map { it - mean })
            ?: return SwingCoordinates(List(points.size) { null }, List(points.size) { null })
        val raw = points.map { point -> point?.minus(mean)?.dot(axis) }
        val filled = interpolateAll(raw) ?: return SwingCoordinates(raw, raw)
        val segmentationSmoothed = savitzkyGolayReference(filled, config.swingCoordinateSmoothingWindowFrames)
        val impactDerivative = savitzkyGolayDerivativeReference(
            filled,
            config.speedSmoothingWindowFrames.coerceIn(5, 7),
            config.segmentationFps
        )
        val segmentation = raw.indices.map { index -> segmentationSmoothed[index].takeIf { raw[index] != null } }
        val impact = raw.indices.map { index -> impactDerivative[index].takeIf { raw[index] != null } }
        return if (shouldFlip(axis, trailingSide, segmentation.filterNotNull())) {
            SwingCoordinates(segmentation.map { it?.unaryMinus() }, impact.map { it?.unaryMinus() })
        } else SwingCoordinates(segmentation, impact)
    }

    /**
     * Orientation of the swing coordinate.  The coordinate peak is the follow-through, which for a
     * forehand ends towards the trailing (non-leading) side of the body, so the axis is oriented
     * towards that side.  This holds on every labelled recording (cosine 0.61–0.79) and, unlike
     * the skew of the distribution, also on a 2–3 swing calibration.  When the axis is nearly
     * perpendicular to the body side (or hips are missing) the skew rule is the fallback.
     */
    private fun shouldFlip(axis: Vec3, trailingSide: Vec3?, coordinate: List<Float>): Boolean {
        val alignment = trailingSide?.let { axis.dot(it) }
        return if (alignment != null && abs(alignment) >= SIDE_ALIGNMENT_MIN) alignment < 0f
        else skew(coordinate) < 0f
    }

    /** Mean unit vector from the leading hip to the trailing hip in world coordinates. */
    internal fun trailingSideDirection(frames: List<List<WorldLandmark?>>, hand: LeadingHand): Vec3? {
        val directions = frames.mapNotNull { points ->
            val left = points.getOrNull(23)?.toVec() ?: return@mapNotNull null
            val right = points.getOrNull(24)?.toVec() ?: return@mapNotNull null
            val towardLeft = left - right
            val length = towardLeft.length()
            if (length <= EPSILON) null else (if (hand == LeadingHand.RIGHT) towardLeft else right - left) / length
        }
        if (directions.isEmpty()) return null
        val mean = meanVec(directions)
        return mean.length().takeIf { it > EPSILON }?.let { mean / it }
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

    /** scipy.signal.savgol_filter(values, window, 2, deriv=1, delta=1/fps) for this small fixed degree. */
    private fun savitzkyGolayDerivativeReference(values: List<Float>, requestedWindow: Int, fps: Float): List<Float> {
        val window = requestedWindow.coerceAtLeast(3).let { if (it % 2 == 0) it + 1 else it }
            .coerceAtMost(values.size.let { if (it % 2 == 0) it - 1 else it })
        if (window < 3 || fps <= 0f) return List(values.size) { 0f }
        val radius = window / 2
        return values.indices.map { index ->
            val range = when {
                index < radius -> 0 until window
                index >= values.size - radius -> values.size - window until values.size
                else -> index - radius..index + radius
            }
            polynomialDerivativeAt(range.map { it - index to values[it] }, 2) * fps
        }
    }

    private fun savitzkyGolayWithGaps(values: List<Float?>, window: Int): List<Float?> {
        val output = MutableList<Float?>(values.size) { null }
        var start = 0
        while (start < values.size) {
            while (start < values.size && values[start] == null) start++
            if (start >= values.size) break
            var end = start
            while (end < values.size && values[end] != null) end++
            val smoothed = savitzkyGolayReference(values.subList(start, end).map { it!! }, window)
            smoothed.forEachIndexed { offset, value -> output[start + offset] = value }
            start = end + 1
        }
        return output
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

    private fun polynomialDerivativeAt(samples: List<Pair<Int, Float>>, degree: Int): Float {
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
            if (abs(matrix[best][pivot]) <= EPSILON) return 0f
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
        return rhs.getOrElse(1) { 0f }
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
    private const val SIDE_ALIGNMENT_MIN = 0.3f
    /** Shoulder depth difference below which the camera side is reported as unknown. */
    private const val CAMERA_SIDE_MIN_DEPTH_METERS = 0.03f
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
