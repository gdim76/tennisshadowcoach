package com.tennisshadowcoach.analysis

/** Why the current frame is not usable for counting; null when the whole body is visible. */
enum class FramingIssue(val label: String, val spoken: String) {
    NO_PERSON("Нет человека в кадре", "Встань в кадр"),
    HEAD_CUT("Не видно головы — отойди дальше", "Не видно головы, отойди дальше"),
    FEET_CUT("Не видно стоп — отойди дальше или поставь телефон ниже", "Не видно стоп, отойди дальше"),
    LEGS_CUT("Видно только верх — отойди дальше или поставь телефон ниже", "Отойди дальше"),
    TOO_FAR("Слишком далеко — подойди ближе", "Подойди ближе")
}

/**
 * Whole body in frame: nose and both ankles detected inside the image with a small margin, and
 * the body tall enough to measure.  Wrists are not required: the racket arm may leave the frame
 * during a swing.  Pure Kotlin, used by the live mirror and by the live calibration.
 */
object FramingCheck {
    private const val MARGIN = 0.02f
    private const val MIN_VISIBILITY = 0.3f
    /** Nose-to-ankle height below this share of the frame height is too small to measure. */
    private const val MIN_BODY_HEIGHT = 0.35f

    fun evaluate(points: List<ImageLandmark?>): FramingIssue? {
        fun present(index: Int) = points.getOrNull(index)?.takeIf { (it.visibility ?: 1f) >= MIN_VISIBILITY }
        val shoulders = listOf(11, 12).mapNotNull(::present)
        val hips = listOf(23, 24).mapNotNull(::present)
        val nose = present(0)
        if (shoulders.isEmpty() && nose == null) return FramingIssue.NO_PERSON
        // Close to the phone the face and shoulders are visible but the hips are not.
        if (hips.isEmpty() || hips.any { it.y > 1f - MARGIN }) return FramingIssue.LEGS_CUT
        if (nose == null || nose.y < MARGIN) return FramingIssue.HEAD_CUT
        val ankles = listOf(27, 28).map(::present)
        if (ankles.any { it == null || it.y > 1f - MARGIN || it.x < MARGIN || it.x > 1f - MARGIN }) {
            return FramingIssue.FEET_CUT
        }
        val ankleY = ankles.filterNotNull().maxOf { it.y }
        if (ankleY - nose.y < MIN_BODY_HEIGHT) return FramingIssue.TOO_FAR
        return null
    }

    fun wholeBodyVisible(points: List<ImageLandmark?>): Boolean = evaluate(points) == null
}
