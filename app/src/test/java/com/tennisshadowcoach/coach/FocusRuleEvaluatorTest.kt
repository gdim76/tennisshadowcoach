package com.tennisshadowcoach.coach

import com.tennisshadowcoach.analysis.MetricSummary
import com.tennisshadowcoach.analysis.MotionAnalysis
import com.tennisshadowcoach.analysis.SwingDebugData
import com.tennisshadowcoach.analysis.SwingMetrics
import com.tennisshadowcoach.analysis.SwingSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusRuleEvaluatorTest {
    @Test fun `compares peak knee angle against user range`() {
        val rule = FocusRule(FocusMetric.LEADING_KNEE, SwingPhase.PEAK_SPEED, maximum = 150f)
        val results = FocusRuleEvaluator.evaluate(analysis(kneeAtPeak = 155f), rule)
        assertEquals(RuleStatus.OUT_OF_RANGE, results.single().status)
        assertEquals(155f, results.single().value)
    }

    @Test fun `does not evaluate an unreliable metric`() {
        val rule = FocusRule(FocusMetric.LEADING_ELBOW, SwingPhase.PEAK_SPEED, minimum = 90f)
        val results = FocusRuleEvaluator.evaluate(analysis(elbowReliable = false), rule)
        assertEquals(RuleStatus.NOT_MEASURED, results.single().status)
        assertEquals(null, results.single().value)
    }

    private fun analysis(kneeAtPeak: Float = 140f, elbowReliable: Boolean = true): MotionAnalysis {
        val segment = SwingSegment(0, 1, 2, 0L, 3f, 1, 1000L)
        fun metric(label: String, value: Float, reliable: Boolean = true) = MetricSummary(label, value, value - 5f, value + 5f, if (reliable) 1f else .5f, reliable, true)
        return MotionAnalysis(
            frames = emptyList(),
            swings = listOf(segment),
            swingMetrics = listOf(SwingMetrics(segment, metric("Колено", kneeAtPeak), metric("Локоть", 110f, elbowReliable), metric("Корпус", 5f))),
            validFractions = emptyMap(),
            debug = SwingDebugData(emptyList(), emptyList(), null, null, emptyList())
        )
    }
}
