package com.tennisshadowcoach.coach

import com.tennisshadowcoach.analysis.MetricSummary
import com.tennisshadowcoach.analysis.MotionAnalysis
import com.tennisshadowcoach.analysis.SwingMetrics

enum class FocusMetric(val label: String) {
    LEADING_KNEE("Колено ведущей стороны"),
    LEADING_ELBOW("Локоть ведущей руки"),
    TRUNK_LEAN("Наклон корпуса")
}

enum class SwingPhase(val label: String) {
    PEAK_SPEED("момент максимальной скорости"),
    MIN_IN_SWING("минимум за замах"),
    MAX_IN_SWING("максимум за замах")
}

data class FocusRule(
    val metric: FocusMetric,
    val phase: SwingPhase,
    val minimum: Float? = null,
    val maximum: Float? = null,
    val hint: String = ""
)

data class WeeklyFocus(
    val id: String,
    val title: String,
    val active: Boolean,
    val rule: FocusRule?
)

data class CoachSessionDraft(
    val dateIso: String,
    val coachNote: String = "",
    val focuses: List<WeeklyFocus> = emptyList()
)

enum class RuleStatus(val label: String) { IN_RANGE("в диапазоне"), OUT_OF_RANGE("вне диапазона"), NOT_MEASURED("не измерено") }

data class SwingRuleResult(val swingNumber: Int, val value: Float?, val status: RuleStatus)

/** Pure Kotlin M2 rule evaluator. It only compares user-entered ranges; it never grades technique. */
object FocusRuleEvaluator {
    fun evaluate(analysis: MotionAnalysis, rule: FocusRule): List<SwingRuleResult> =
        analysis.swingMetrics.mapIndexed { index, swing ->
            val summary = swing.summary(rule.metric)
            val value = summary.valueAt(rule.phase)
            val status = when {
                !summary.available || !summary.reliable || value == null -> RuleStatus.NOT_MEASURED
                (rule.minimum != null && value < rule.minimum) || (rule.maximum != null && value > rule.maximum) -> RuleStatus.OUT_OF_RANGE
                else -> RuleStatus.IN_RANGE
            }
            SwingRuleResult(index + 1, value.takeIf { status != RuleStatus.NOT_MEASURED }, status)
        }

    private fun SwingMetrics.summary(metric: FocusMetric): MetricSummary = when (metric) {
        FocusMetric.LEADING_KNEE -> leadingKnee
        FocusMetric.LEADING_ELBOW -> leadingElbow
        FocusMetric.TRUNK_LEAN -> trunkLean
    }

    private fun MetricSummary.valueAt(phase: SwingPhase): Float? = when (phase) {
        SwingPhase.PEAK_SPEED -> atPeak
        SwingPhase.MIN_IN_SWING -> min
        SwingPhase.MAX_IN_SWING -> max
    }
}
