package com.tennisshadowcoach.analysis

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MotionMetricsEngineTest {
    @Test fun `computes 3d knee angle from synthetic landmarks`() {
        val pose = frame(0, wristX = 0f).withWorld(23, WorldLandmark(0f, 1f, 0f))
            .withWorld(25, WorldLandmark(0f, 0f, 0f)).withWorld(27, WorldLandmark(1f, 0f, 0f))
        val result = MotionMetricsEngine.analyze(List(9) { pose.copy(index = it, timestampUs = it * 33_333L) }, LeadingHand.RIGHT, RecordingAngle.SIDE)
        assertEquals(90f, result.frames[4].readings.getValue(MetricId.LEFT_KNEE).world3d!!, 0.1f)
    }

    @Test fun `computes 3d unsigned trunk lean from world vertical`() {
        val pose = frame(0, wristX = 0f)
            .withWorld(11, WorldLandmark(-.5f, 1f, 1f))
            .withWorld(12, WorldLandmark(.5f, 1f, 1f))
            .withWorld(23, WorldLandmark(-.5f, 0f, 0f))
            .withWorld(24, WorldLandmark(.5f, 0f, 0f))
        val result = MotionMetricsEngine.analyze(
            List(9) { pose.copy(index = it, timestampUs = it * 33_333L) },
            LeadingHand.RIGHT,
            RecordingAngle.SIDE
        )
        assertEquals(45f, result.frames[4].readings.getValue(MetricId.TRUNK_LEAN).world3d!!, .1f)
    }

    @Test fun `masks a one-frame median outlier instead of limiting true speed`() {
        val positions = List(25) { if (it == 12) 8f else it / 24f }
        val analysis = MotionMetricsEngine.analyze(
            positions.mapIndexed(::frame), LeadingHand.RIGHT, RecordingAngle.SIDE,
            MetricsConfig(outlierMedianDeviationTorsoLengths = .35f)
        )
        assertNotNull(analysis.frames[11].readings[MetricId.WRIST_SPEED]?.world3d)
        assertNotNull(analysis.frames[13].readings[MetricId.WRIST_SPEED]?.world3d)
    }

    @Test fun `legacy absolute jump threshold would reject 64 leading wrist frames on the 8 swing recording`() {
        val frames = CsvPoseFrames.read(knownRecordings().first().csv)
        val imageInput = frames.map { frame ->
            frame.landmarks[16]?.takeIf { (it.visibility ?: 0f) >= .5f }?.let { LegacyVec(it.x, it.y, it.z) }
        }
        val imageMasked = legacyMaskJumps(imageInput, frames)
        val imageRejected = imageInput.indices.count { imageInput[it] != null && imageMasked[it] == null }
        val imageAvailableAfterInterpolation = shortGapPresence(imageMasked, maxGap = 5)
        val worldInput = frames.indices.map { index ->
            frames[index].worldLandmarks[16]?.takeIf { imageAvailableAfterInterpolation[index] }?.let { LegacyVec(it.x, it.y, it.z) }
        }
        val worldMasked = legacyMaskJumps(worldInput, frames)
        val worldRejected = worldInput.indices.filter { worldInput[it] != null && worldMasked[it] == null }.toSet()
        val imageRejectedIndices = imageInput.indices.filter { imageInput[it] != null && imageMasked[it] == null }.toSet()
        val uniqueRejected = imageRejectedIndices + worldRejected
        println("legacy max_landmark_jump_per_second=6: image=${imageRejectedIndices.size}, world=${worldRejected.size}, unique=${uniqueRejected.size}")
        assertEquals(60, imageRejected)
        assertEquals(24, worldRejected.size)
        assertEquals(64, uniqueRejected.size)
    }

    @Test fun `known recordings match their manual swing counts with defaults`() {
        knownRecordings().forEach { recording ->
            assertEquals(recording.name, recording.expectedSwings, analyze(recording).swings.size)
        }
    }

    @Test fun `known recordings stay stable across the full M1 configuration matrix`() {
        val mismatches = mutableListOf<String>()
        knownRecordings().forEach { recording ->
            val frames = CsvPoseFrames.read(recording.csv)
            for (front in listOf(.4f, .5f, .6f, .7f, .8f)) {
                for (back in listOf(.15f, .2f, .25f, .3f)) {
                    for (window in listOf(5, 9, 17)) {
                        val analysis = MotionMetricsEngine.analyze(
                            frames, recording.leadingHand, recording.recordingAngle,
                            MetricsConfig(frontFrac = front, backFrac = back, swingCoordinateSmoothingWindowFrames = window)
                        )
                        if (analysis.swings.size != recording.expectedSwings) {
                            mismatches += "${recording.name}: front=$front back=$back window=$window " +
                                "expected=${recording.expectedSwings} actual=${analysis.swings.size}"
                        }
                    }
                }
            }
        }
        assertTrue("Configuration-matrix mismatches:\n${mismatches.joinToString("\n")}", mismatches.isEmpty())
    }

    @Test fun `online replay matches offline swing count after three swing calibration`() {
        knownRecordings().forEach { recording ->
            val frames = CsvPoseFrames.read(recording.csv)
            val offline = analyze(recording)
            val calibrationEnd = offline.swings[2].endFrame
            val online = OnlineSwingSegmenter()
            (0..calibrationEnd).forEach { index ->
                online.add(frames[index].index, frames[index].timestampUs, offline.debug.coordinate[index])
            }
            online.completeCalibration()
            (calibrationEnd + 1..frames.lastIndex).forEach { index ->
                online.add(frames[index].index, frames[index].timestampUs, offline.debug.coordinate[index])
            }
            online.finish()
            println("${recording.name}: offline=${offline.swings.map { it.startFrame to it.endFrame }}, online=${online.detectedSwings().map { it.startFrame to it.endFrame }}")
            assertEquals("online replay ${recording.name}", offline.swings.size, online.detectedSwings().size)
        }
    }

    @Test fun `live landmark replay matches offline count after two or three swing calibration`() {
        val mismatches = mutableListOf<String>()
        for (calibrationSwings in listOf(2, 3)) knownRecordings().forEach { recording ->
            val frames = CsvPoseFrames.read(recording.csv)
            val offline = analyze(recording)
            val calibrationEnd = offline.swings[calibrationSwings - 1].endFrame
            val tracker = OnlineLiveSwingTracker(recording.leadingHand)
            (0..calibrationEnd).forEach { index ->
                tracker.add(frames[index].index, frames[index].timestampUs, frames[index].landmarks, frames[index].worldLandmarks)
            }
            tracker.completeCalibration()
            var state = OnlineLiveSwingTracker.State(true, 0, false)
            (calibrationEnd + 1..frames.lastIndex).forEach { index ->
                state = tracker.add(frames[index].index, frames[index].timestampUs, frames[index].landmarks, frames[index].worldLandmarks)
            }
            state = tracker.finish()
            println("${recording.name} (calibration $calibrationSwings): offline=${offline.swings.map { it.startFrame to it.endFrame }}, live=${tracker.detectedSwings().map { it.startFrame to it.endFrame }}")
            if (state.completedCount != offline.swings.size) {
                mismatches += "${recording.name} (calibration $calibrationSwings): offline=${offline.swings.size} live=${state.completedCount}"
            }
        }
        assertTrue("Live landmark replay mismatches:\n${mismatches.joinToString("\n")}", mismatches.isEmpty())
    }

    @Test fun `known recordings stay stable across setup gate and slow movement parameter ranges`() {
        val mismatches = mutableListOf<String>()
        knownRecordings().forEach { recording ->
            val frames = CsvPoseFrames.read(recording.csv)
            for (tolerance in listOf(.2f, .25f, .3f, .4f)) for (run in listOf(.5f, 1f, 2f)) for (speed in listOf(.2f, .3f, .4f)) {
                val count = MotionMetricsEngine.analyze(
                    frames, recording.leadingHand, recording.recordingAngle,
                    MetricsConfig(inPositionTorsoTolerance = tolerance, minInPositionSeconds = run, minSwingSpeedFraction = speed)
                ).swings.size
                if (count != recording.expectedSwings) {
                    mismatches += "${recording.name}: tolerance=$tolerance run=$run speed=$speed expected=${recording.expectedSwings} actual=$count"
                }
            }
        }
        assertTrue("Gate/speed parameter mismatches:\n${mismatches.joinToString("\n")}", mismatches.isEmpty())
    }

    @Test fun `selfie front recording ignores setup at the phone and the slow racket raise`() {
        val recording = knownRecordings().single { it.name.contains("front") }
        val frames = CsvPoseFrames.read(recording.csv)
        fun seconds(frame: Int) = (frames[frame].timestampUs - frames.first().timestampUs) / 1_000_000f
        val defaults = analyze(recording)
        // 0–4.5 s: pressing record, walking away and into place (video-checked).
        assertTrue(defaults.swings.all { seconds(it.startFrame) > 4.5f })
        assertTrue(defaults.debug.allSegments.none { seconds(it.peakFrame) < 3f })
        // Raising the racket into the ready position (~6 s) crosses a low front threshold,
        // but is far slower than the swings, so it is recorded and not counted.
        val lowFront = MotionMetricsEngine.analyze(frames, recording.leadingHand, recording.recordingAngle, MetricsConfig(frontFrac = .4f))
        val raise = lowFront.debug.allSegments.single { seconds(it.peakFrame) in 5f..7f }
        assertTrue(raise.tooSlow)
        assertFalse(raise.counted)
        assertEquals(recording.expectedSwings, lowFront.swings.size)
    }

    @Test fun `impact is 0 point 1 to 0 point 45 seconds before coordinate peak for all labelled swings`() {
        knownRecordings().forEach { recording ->
            val frames = CsvPoseFrames.read(recording.csv)
            val analysis = MotionMetricsEngine.analyze(frames, recording.leadingHand, recording.recordingAngle)
            assertEquals(recording.name, recording.expectedSwings, analysis.swings.size)
            analysis.swings.forEachIndexed { index, swing ->
                val impact = requireNotNull(swing.impactFrame) { "${recording.name} swing ${index + 1}: impact missing" }
                val secondsBeforePeak = (frames[swing.peakFrame].timestampUs - frames[impact].timestampUs) / 1_000_000f
                println("${recording.name} swing ${index + 1}: impact ${"%.3f".format(secondsBeforePeak)}s before coordinate peak")
                assertTrue(
                    "${recording.name} swing ${index + 1}: $secondsBeforePeak s",
                    secondsBeforePeak in .1f.. .45f
                )
            }
        }
    }

    @Test fun `legacy wrist magnitude peak violates impact timing on the 8 swing recording`() {
        val recording = knownRecordings().first()
        val frames = CsvPoseFrames.read(recording.csv)
        val analysis = MotionMetricsEngine.analyze(frames, LeadingHand.RIGHT, RecordingAngle.SIDE)
        val badLegacyTimings = analysis.swings.count { swing ->
            val legacyFrame = (swing.startFrame..swing.endFrame)
                .filter { analysis.debug.wristSpeed[it] != null }
                .maxByOrNull { analysis.debug.wristSpeed[it]!! }
                ?: return@count false
            val secondsBeforePeak = (frames[swing.peakFrame].timestampUs - frames[legacyFrame].timestampUs) / 1_000_000f
            secondsBeforePeak !in .1f.. .45f
        }
        assertEquals(
            "The old full-window wrist-speed maximum is late for six of eight swings",
            6,
            badLegacyTimings
        )
    }

    @Test fun `eighth swing elbow is withheld on the 8 swing recording`() {
        val analysis = analyze(knownRecordings().first())
        assertEquals(8, analysis.swingMetrics.size)
        val elbow = analysis.swingMetrics[7].leadingElbow
        assertFalse(elbow.reliable)
        assertEquals(null, elbow.atImpact)
    }

    @Test fun `marks swing metric unreliable when fewer than 70 percent of raw frames are valid`() {
        val recording = knownRecordings().first()
        val frames = CsvPoseFrames.read(recording.csv).map { frame ->
            val image = frame.landmarks.toMutableList()
            image[24] = image[24]?.copy(visibility = .1f)
            frame.copy(landmarks = image)
        }
        val analysis = MotionMetricsEngine.analyze(frames, LeadingHand.RIGHT, RecordingAngle.SIDE)
        assertFalse(analysis.swingMetrics.isEmpty())
        analysis.swingMetrics.forEach { swing ->
            assertFalse(swing.leadingKnee.reliable)
            assertEquals(null, swing.leadingKnee.atImpact)
        }
    }

    @Test fun `writes independently inspectable swing exports for known recordings`() {
        val outputDirectory = File("build/m1-exports")
        knownRecordings().forEach { recording ->
            val frames = CsvPoseFrames.read(recording.csv)
            val analysis = MotionMetricsEngine.analyze(frames, recording.leadingHand, recording.recordingAngle)
            val output = File(outputDirectory, "swings_${recording.csv.name}")
            SwingCsvExporter.write(output, analysis, frames)
            val lines = output.readLines()
            assertTrue(output.name, lines.first().contains("ненадёжные_метрики"))
            assertTrue(output.name, lines.size >= recording.expectedSwings + 1)
        }
    }

    @Test fun `other local CSV files parse and segment without correctness claims`() {
        val dataDirectory = File("data").takeIf { it.isDirectory } ?: File("../data")
        assumeTrue("Local unlabelled CSV files are not present", dataDirectory.isDirectory)
        val knownNames = knownRecordings().map { it.csv.name }.toSet()
        dataDirectory.listFiles { file ->
            file.extension == "csv" && file.name.startsWith("landmarks_") && file.name !in knownNames
        }.orEmpty().forEach { csv ->
            val result = MotionMetricsEngine.analyze(CsvPoseFrames.read(csv), LeadingHand.RIGHT, RecordingAngle.SIDE)
            println("${csv.name}: ${result.swings.size} complete swings (manual count unknown)")
        }
    }

    private fun analyze(recording: KnownRecording): MotionAnalysis =
        MotionMetricsEngine.analyze(
            CsvPoseFrames.read(recording.csv),
            recording.leadingHand,
            recording.recordingAngle
        )

    /** Every resource with expected_swings automatically joins the regression matrix. */
    private fun knownRecordings(): List<KnownRecording> {
        val classLoader = requireNotNull(javaClass.classLoader)
        return listOf("m1", "m3").flatMap { resourceDirectory ->
            val directory = File(requireNotNull(classLoader.getResource(resourceDirectory)) {
                "Missing $resourceDirectory resources"
            }.toURI())
            directory.listFiles { file -> file.name.endsWith(".metadata.json") }.orEmpty()
            .mapNotNull { metadata ->
                val metadataText = metadata.readText()
                val expected = EXPECTED_SWINGS.find(metadataText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                val csv = File(directory, metadata.name.removeSuffix(".metadata.json") + ".csv")
                require(csv.isFile) { "Metadata ${metadata.name} has expected_swings but no CSV" }
                val hand = if (metadataText.contains("\"leading_hand\"\\s*:\\s*\"left\"".toRegex())) LeadingHand.LEFT else LeadingHand.RIGHT
                val angle = if (metadataText.contains("\"recording_angle\"\\s*:\\s*\"side\"".toRegex())) {
                    RecordingAngle.SIDE
                } else {
                    RecordingAngle.FRONT_OR_45
                }
                KnownRecording(csv.name, csv, expected, hand, angle)
            }
        }
            .sortedBy { it.name }
    }

    private fun frame(index: Int, wristX: Float): PoseFrame {
        val image = List(33) { ImageLandmark(0f, 0f, 0f, 1f) }
        val world = List(33) { WorldLandmark(0f, 0f, 0f) }.toMutableList()
        world[11] = WorldLandmark(-.5f, 1f, 0f); world[12] = WorldLandmark(.5f, 1f, 0f)
        world[23] = WorldLandmark(-.5f, 0f, 0f); world[24] = WorldLandmark(.5f, 0f, 0f)
        world[16] = WorldLandmark(wristX, 1f, 0f)
        return PoseFrame(index, index * 33_333L, image, world)
    }

    private fun PoseFrame.withWorld(index: Int, point: WorldLandmark): PoseFrame =
        copy(worldLandmarks = worldLandmarks.toMutableList().also { it[index] = point })

    private fun legacyMaskJumps(values: List<LegacyVec?>, frames: List<PoseFrame>): List<LegacyVec?> {
        var previous: LegacyVec? = null
        var previousTime: Long? = null
        return values.mapIndexed { index, point ->
            val elapsedSeconds = (frames[index].timestampUs - (previousTime ?: frames[index].timestampUs)).coerceAtLeast(1L) / 1_000_000f
            val accepted = point != null && (previous == null || previous!!.distanceTo(point) / elapsedSeconds <= 6f)
            if (accepted) {
                previous = point
                previousTime = frames[index].timestampUs
                point
            } else null
        }
    }

    private fun shortGapPresence(values: List<LegacyVec?>, maxGap: Int): List<Boolean> {
        val present = values.map { it != null }.toMutableList()
        var start = 0
        while (start < present.size) {
            if (present[start]) { start++; continue }
            val end = (start until present.size).firstOrNull { present[it] } ?: break
            if (start > 0 && end - start <= maxGap) {
                for (index in start until end) present[index] = true
            }
            start = end + 1
        }
        return present
    }

    private data class KnownRecording(
        val name: String,
        val csv: File,
        val expectedSwings: Int,
        val leadingHand: LeadingHand,
        val recordingAngle: RecordingAngle
    )
    private data class LegacyVec(val x: Float, val y: Float, val z: Float) {
        fun distanceTo(other: LegacyVec): Float = kotlin.math.sqrt(
            (x - other.x) * (x - other.x) + (y - other.y) * (y - other.y) + (z - other.z) * (z - other.z)
        )
    }

    private companion object {
        val EXPECTED_SWINGS = Regex("\\\"expected_swings\\\"\\s*:\\s*(\\d+)")
    }
}

/** JVM-only reader for exported M0 CSV tracks. It intentionally has no Android dependency. */
private object CsvPoseFrames {
    fun read(file: File): List<PoseFrame> {
        val rows = file.useLines { lines -> lines.toList() }
        require(rows.isNotEmpty()) { "Empty CSV: ${file.name}" }
        val columns = rows.first().split(',').withIndex().associate { it.value to it.index }
        fun value(row: List<String>, name: String): Float? {
            val column = columns[name] ?: return null
            return row.getOrNull(column)?.toFloatOrNull()
        }
        fun required(row: List<String>, name: String) = row[columns.getValue(name)]
        return rows.drop(1).filter { it.isNotBlank() }.map { line ->
            val row = line.split(',')
            val image = (0 until 33).map { i ->
                val x = value(row, "image_${i}_x") ?: return@map null
                ImageLandmark(x, value(row, "image_${i}_y") ?: return@map null, value(row, "image_${i}_z") ?: return@map null, value(row, "image_${i}_visibility"))
            }
            val world = (0 until 33).map { i ->
                val x = value(row, "world_${i}_x") ?: return@map null
                WorldLandmark(x, value(row, "world_${i}_y") ?: return@map null, value(row, "world_${i}_z") ?: return@map null)
            }
            PoseFrame(required(row, "frame_index").toInt(), required(row, "timestamp_us").toLong(), image, world)
        }
    }
}
