package com.tennisshadowcoach.analysis

/**
 * Small moving-average smoother used only by the M0 debug viewer.
 * Raw values always remain untouched in the CSV; M1 will introduce the configurable
 * production smoother used for metric calculations.
 */
fun smoothedLandmarks(
    frames: List<PoseFrame>,
    windowFrames: Int = 5,
    minimumVisibility: Float = 0.5f
): List<List<ImageLandmark?>> {
    require(windowFrames > 0) { "Окно сглаживания должно быть положительным." }
    val radius = windowFrames / 2
    return frames.indices.map { frameIndex ->
        List(33) { landmarkIndex ->
            val samples = ((frameIndex - radius)..(frameIndex + radius))
                .mapNotNull { index -> frames.getOrNull(index)?.landmarks?.getOrNull(landmarkIndex) }
                .filter { it.visibility == null || it.visibility >= minimumVisibility }
            if (samples.isEmpty()) null else ImageLandmark(
                x = samples.map { it.x }.average().toFloat(),
                y = samples.map { it.y }.average().toFloat(),
                z = samples.map { it.z }.average().toFloat(),
                visibility = samples.mapNotNull { it.visibility }.averageOrNull()
            )
        }
    }
}

private fun List<Float>.averageOrNull(): Float? =
    if (isEmpty()) null else average().toFloat()
