package com.tennisshadowcoach.analysis

/**
 * Android-free data boundary for M1.  These types are deliberately kept free
 * of CameraX, MediaPipe and Compose so the analysis can be unit-tested from a
 * CSV track on a regular JVM.
 */
enum class LeadingHand { RIGHT, LEFT }

data class ImageLandmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float?
)

data class WorldLandmark(val x: Float, val y: Float, val z: Float)

/** A null landmark means that the pose detector did not produce that point. */
data class PoseFrame(
    val index: Int,
    val timestampUs: Long,
    val landmarks: List<ImageLandmark?>,
    val worldLandmarks: List<WorldLandmark?>
)
