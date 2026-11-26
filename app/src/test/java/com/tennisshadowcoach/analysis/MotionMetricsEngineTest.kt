package com.tennisshadowcoach.analysis

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class MotionMetricsEngineTest {
    @Test fun `computes 3d knee angle from synthetic landmarks`() {
        val pose = frame(0, wristX = 0f).withWorld(23, WorldLandmark(0f, 1f, 0f))
            .withWorld(25, WorldLandmark(0f, 0f, 0f)).withWorld(27, WorldLandmark(1f, 0f, 0f))
        val result = MotionMetricsEngine.analyze(List(9) { pose.copy(index = it, timestampUs = it * 33_333L) }, LeadingHand.RIGHT, RecordingAngle.SIDE)
        assertEquals(90f, result.frames[4].readings.getValue(MetricId.LEFT_KNEE).world3d!!, 0.1f)
    }

    @Test fun `masks a one-frame median outlier instead of limiting true speed`() {
        val positions = List(25) { if (it == 12) 8f else it / 24f }
        val analysis = MotionMetricsEngine.analyze(
            positions.mapIndexed(::frame), LeadingHand.RIGHT, RecordingAngle.SIDE,
            MetricsConfig(outlierMedianDeviationTorsoLengths = .35f)
        )
        // The outlier does not survive into the wrist-speed series.
        assertNotNull(analysis.frames[11].readings[MetricId.WRIST_SPEED]?.world3d)
        assertNotNull(analysis.frames[13].readings[MetricId.WRIST_SPEED]?.world3d)
    }

    @Test fun `reference video has eight swings for full M1 configuration matrix`() {
        val video = referenceCsv()
        assumeTrue("Local M1 data is not present", video.isFile)
        val frames = CsvPoseFrames.read(video)
        for (front in listOf(.4f, .5f, .6f, .7f, .8f)) {
            for (back in listOf(.15f, .2f, .25f, .3f)) {
                for (window in listOf(5, 9, 17)) {
                    val result = MotionMetricsEngine.analyze(
                        frames, LeadingHand.RIGHT, RecordingAngle.SIDE,
                        MetricsConfig(frontFrac = front, backFrac = back, swingCoordinateSmoothingWindowFrames = window)
                    )
                    assertEquals("front=$front back=$back window=$window", 8, result.swings.size)
                }
            }
        }
    }

    @Test fun `all other local CSV files parse and segment without crashing`() {
        val data = requireNotNull(referenceCsv().parentFile)
        assumeTrue("Local M1 data is not present", data.isDirectory)
        data.listFiles { file -> file.extension == "csv" && file.name != referenceCsv().name }.orEmpty().forEach { csv ->
            val result = MotionMetricsEngine.analyze(CsvPoseFrames.read(csv), LeadingHand.RIGHT, RecordingAngle.SIDE)
            println("${csv.name}: ${result.swings.size} complete swings")
        }
    }

    private fun referenceCsv() = listOf(
        File("data/landmarks_1795552262931.csv"),
        File("../data/landmarks_1795552262931.csv")
    ).firstOrNull { it.isFile } ?: File("data/landmarks_1795552262931.csv")

    private fun frame(index: Int, wristX: Float): PoseFrame {
        val image = List(33) { ImageLandmark(0f, 0f, 0f, 1f) }
        val world = List(33) { WorldLandmark(0f, 0f, 0f) }.toMutableList()
        world[11] = WorldLandmark(-.5f, 1f, 0f); world[12] = WorldLandmark(.5f, 1f, 0f)
        world[23] = WorldLandmark(-.5f, 0f, 0f); world[24] = WorldLandmark(.5f, 0f, 0f)
        world[16] = WorldLandmark(wristX, 1f, 0f)
        return PoseFrame(index, index * 33_333L, image, world)
    }
    private fun PoseFrame.withWorld(index: Int, point: WorldLandmark): PoseFrame = copy(worldLandmarks = worldLandmarks.toMutableList().also { it[index] = point })
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
