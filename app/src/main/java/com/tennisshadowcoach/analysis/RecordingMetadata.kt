package com.tennisshadowcoach.analysis

/** Explicit capture facts.  Unknown values stay unknown; they are never inferred from a default. */
enum class CaptureCamera(val wireValue: String, val label: String) {
    UNSPECIFIED("unspecified", "Не выбрано"),
    REAR("rear", "Тыловая"),
    SELFIE("selfie", "Селфи")
}

/** Capture angle is kept more precise than the current M1 metric availability enum. */
enum class CaptureRecordingAngle(val wireValue: String, val label: String) {
    UNSPECIFIED("unspecified", "Не выбрано"),
    SIDE("side", "Сбоку"),
    FRONT("front", "Спереди"),
    ANGLE_45("angle_45", "Под 45°")
}

enum class PhoneSide(val wireValue: String, val label: String) {
    UNSPECIFIED("unspecified", "Не выбрано"),
    LEFT("left", "Слева"),
    RIGHT("right", "Справа"),
    NOT_APPLICABLE("not_applicable", "Не важно")
}

data class RecordingMetadata(
    val camera: CaptureCamera = CaptureCamera.UNSPECIFIED,
    val recordingAngle: CaptureRecordingAngle = CaptureRecordingAngle.UNSPECIFIED,
    val phoneSide: PhoneSide = PhoneSide.UNSPECIFIED,
    val leadingHand: LeadingHand? = null,
    val manualPausedSwings: Int? = null,
    val manualSeriesSwings: Int? = null
) {
    val expectedSwings: Int?
        get() = listOfNotNull(manualPausedSwings, manualSeriesSwings)
            .takeIf { it.isNotEmpty() }
            ?.sum()
}
