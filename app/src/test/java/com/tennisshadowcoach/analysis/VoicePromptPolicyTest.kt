package com.tennisshadowcoach.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VoicePromptPolicyTest {
    private val wrong = listOf(SwingCorrection.NO_SHOULDER_TURN)

    @Test fun `a wrong swing is said at once and a clean swing is silent`() {
        val policy = VoicePromptPolicy()
        assertNull(policy.onSwing(1_000, emptyList()))
        assertEquals("Нет замаха. Разверни плечи.", policy.onSwing(1_100, wrong))
    }

    @Test fun `in a fast series at most one swing phrase per gap`() {
        val policy = VoicePromptPolicy(minGapMs = 2_500)
        assertNotNull(policy.onSwing(0, wrong))
        assertNull(policy.onSwing(1_600, wrong))
        assertNotNull(policy.onSwing(3_200, wrong))
    }

    @Test fun `a framing problem is said only after it lasts and is not repeated too often`() {
        val policy = VoicePromptPolicy(minGapMs = 2_500, framingDelayMs = 2_000, framingRepeatMs = 5_000)
        val phrase = FramingIssue.FEET_CUT.spoken
        assertNull(policy.onFrame(0, phrase))
        assertNull(policy.onFrame(1_900, phrase))
        assertEquals(phrase, policy.onFrame(2_000, phrase))
        assertNull(policy.onFrame(6_000, phrase))
        assertEquals(phrase, policy.onFrame(7_000, phrase))
    }

    @Test fun `a short framing problem while walking into place is never said`() {
        val policy = VoicePromptPolicy(framingDelayMs = 2_000)
        assertNull(policy.onFrame(0, FramingIssue.LEGS_CUT.spoken))
        assertNull(policy.onFrame(1_500, FramingIssue.LEGS_CUT.spoken))
        assertNull(policy.onFrame(1_600, null))
        assertNull(policy.onFrame(3_000, FramingIssue.LEGS_CUT.spoken))
    }

    @Test fun `a framing prompt waits for the gap after a swing correction`() {
        val policy = VoicePromptPolicy(minGapMs = 2_500, framingDelayMs = 2_000)
        assertNotNull(policy.onSwing(1_000, wrong))
        assertNull(policy.onFrame(0, "Отойди дальше"))
        assertNull(policy.onFrame(2_500, "Отойди дальше"))
        assertEquals("Отойди дальше", policy.onFrame(3_600, "Отойди дальше"))
    }

    @Test fun `a swing correction is not blocked by a recent framing prompt`() {
        val policy = VoicePromptPolicy(minGapMs = 2_500, framingDelayMs = 0)
        assertNotNull(policy.onFrame(0, "Подойди ближе"))
        assertNotNull(policy.onSwing(500, wrong))
    }

    @Test fun `framing phrase covers a cut frame and standing away from the calibrated spot`() {
        assertEquals(FramingIssue.FEET_CUT.spoken, VoicePromptPolicy.framingPhrase(FramingIssue.FEET_CUT, calibrated = true, inPosition = true))
        assertEquals("Встань на место калибровки", VoicePromptPolicy.framingPhrase(null, calibrated = true, inPosition = false))
        assertNull(VoicePromptPolicy.framingPhrase(null, calibrated = false, inPosition = false))
        assertNull(VoicePromptPolicy.framingPhrase(null, calibrated = true, inPosition = true))
    }
}
