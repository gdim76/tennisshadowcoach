package com.tennisshadowcoach.analysis

/**
 * Decides what the mirror says aloud and when (M4).  Pure Kotlin, so the timing rules are tested
 * without a device; the Android side only speaks the returned phrase.
 *
 * - A correction to the swing just counted is said at once: it is the point of the feedback.
 *   A clean swing gets the short beep only, no words.
 * - At most one swing phrase per [minGapMs]: in a fast series the beep still marks every swing.
 * - A framing problem is said only after it has lasted [framingDelayMs] (walking into place is
 *   normal), never within [minGapMs] of another phrase and repeated at most every [framingRepeatMs].
 * - Nothing is queued: a phrase that cannot be said now is dropped, not said late.
 */
class VoicePromptPolicy(
    private val minGapMs: Long = 2_500,
    private val framingDelayMs: Long = 2_000,
    private val framingRepeatMs: Long = 10_000
) {
    private var lastSpokenAt = NEVER
    private var lastSwingSpokenAt = NEVER
    private var lastFramingSpokenAt = NEVER
    private var framingPhrase: String? = null
    private var framingSince = NEVER

    fun onSwing(nowMs: Long, corrections: List<SwingCorrection>): String? {
        val correction = corrections.firstOrNull() ?: return null
        if (nowMs - lastSwingSpokenAt < minGapMs) return null
        lastSwingSpokenAt = nowMs
        lastSpokenAt = nowMs
        return correction.spoken
    }

    /** [phrase] is the spoken form of the current framing problem, or null when the frame is fine. */
    fun onFrame(nowMs: Long, phrase: String?): String? {
        if (phrase == null) {
            framingPhrase = null
            return null
        }
        if (phrase != framingPhrase) {
            framingPhrase = phrase
            framingSince = nowMs
        }
        if (nowMs - framingSince < framingDelayMs) return null
        if (nowMs - lastFramingSpokenAt < framingRepeatMs) return null
        if (nowMs - lastSpokenAt < minGapMs) return null
        lastFramingSpokenAt = nowMs
        lastSpokenAt = nowMs
        return phrase
    }

    /** A phrase said outside the policy (command acknowledgement) also blocks framing prompts. */
    fun markSpoken(nowMs: Long) {
        lastSpokenAt = maxOf(lastSpokenAt, nowMs)
    }

    /** The calibration outcome is always said: the user is at the phone or walking back. */
    fun onCalibration(nowMs: Long, success: Boolean): String {
        lastSpokenAt = nowMs
        return if (success) "Калибровка готова" else "Калибровка не получилась. Встань в кадр целиком и сделай два-три замаха."
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE / 4

        /** What to say about the frame: a framing problem, or standing away from the calibrated spot. */
        fun framingPhrase(issue: FramingIssue?, calibrated: Boolean, inPosition: Boolean): String? = when {
            issue != null -> issue.spoken
            calibrated && !inPosition -> "Встань на место калибровки"
            else -> null
        }
    }
}
