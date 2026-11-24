package com.tennisshadowcoach.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MotionMetricsEngineTest {
    @Test fun `computes 3d knee angle from synthetic landmarks`() {
        val frame = frame(0, wristX = 0f).withWorld(23, WorldLandmark(0f, 1f, 0f))
            .withWorld(25, WorldLandmark(0f, 0f, 0f)).withWorld(27, WorldLandmark(1f, 0f, 0f))
        val result = MotionMetricsEngine.analyze(listOf(frame), LeadingHand.RIGHT, RecordingAngle.SIDE)
        assertEquals(90f, result.frames.single().readings.getValue(MetricId.LEFT_KNEE).world3d!!, 0.1f)
    }

    @Test fun `segments wrist movement relative to hips`() {
        val frames = listOf(0f, 0f, 1f, 2f, 2f).mapIndexed { index, wristX -> frame(index, wristX) }
        val result = MotionMetricsEngine.analyze(
            frames, LeadingHand.RIGHT, RecordingAngle.SIDE,
            MetricsConfig(
                swingSpeedThreshold = 0.5f,
                minSwingDurationMs = 10,
                speedSmoothingWindowFrames = 1,
                maxLandmarkJumpPerSecond = 100f
            )
        )
        assertEquals(1, result.swings.size)
        assertNotNull(result.frames[2].readings[MetricId.WRIST_SPEED]?.world3d)
    }

    private fun frame(index: Int, wristX: Float): PoseFrame {
        val image = List(33) { ImageLandmark(0f, 0f, 0f, 1f) }
        val world = List(33) { WorldLandmark(0f, 0f, 0f) }.toMutableList()
        world[11] = WorldLandmark(-.5f, 1f, 0f); world[12] = WorldLandmark(.5f, 1f, 0f)
        world[23] = WorldLandmark(-.5f, 0f, 0f); world[24] = WorldLandmark(.5f, 0f, 0f)
        world[16] = WorldLandmark(wristX, 1f, 0f)
        return PoseFrame(index, index * 100_000L, image, world)
    }
    private fun PoseFrame.withWorld(index: Int, point: WorldLandmark): PoseFrame = copy(worldLandmarks = worldLandmarks.toMutableList().also { it[index] = point })
}
