package com.tennisshadowcoach.analysis

import java.io.File
import java.util.Locale

/**
 * Android-free M1 export.  It intentionally contains measurements only, never a technique score.
 */
object SwingCsvExporter {
    fun write(file: File, analysis: MotionAnalysis, frames: List<PoseFrame>) {
        file.parentFile?.mkdirs()
        file.writeText(csv(analysis, frames))
    }

    fun csv(analysis: MotionAnalysis, frames: List<PoseFrame>): String {
        val metricsBySegment = analysis.swingMetrics.associateBy { it.segment.key() }
        return buildList {
            add(HEADER)
            analysis.debug.allSegments.forEachIndexed { index, segment ->
                val metrics = metricsBySegment[segment.key()]
                add(
                    listOf(
                        (index + 1).toString(),
                        seconds(frames, segment.startFrame),
                        seconds(frames, segment.peakFrame),
                        seconds(frames, segment.endFrame),
                        number(segment.durationMs / 1_000f),
                        (!segment.truncated).toString(),
                        number(metrics?.leadingKnee?.atPeak),
                        number(metrics?.leadingKnee?.min),
                        number(metrics?.leadingKnee?.max),
                        number(metrics?.leadingElbow?.atPeak),
                        number(metrics?.leadingElbow?.min),
                        number(metrics?.leadingElbow?.max),
                        number(metrics?.trunkLean?.atPeak),
                        number(metrics?.trunkLean?.max),
                        segment.peakSpeedFrame?.let { seconds(frames, it) }.orEmpty(),
                        metrics?.unreliableMetricLabels?.joinToString(";", transform = ::csvText).orEmpty()
                    ).joinToString(",")
                )
            }
        }.joinToString("\n", postfix = "\n")
    }

    private fun SwingSegment.key() = Triple(startFrame, peakFrame, endFrame)
    private fun seconds(frames: List<PoseFrame>, index: Int): String =
        frames.getOrNull(index)?.timestampUs?.div(1_000_000f)?.let(::number).orEmpty()
    private fun number(value: Float?): String = value?.let { "%.4f".format(Locale.US, it) }.orEmpty()
    private fun csvText(value: String): String = value.replace(',', ';')

    private const val HEADER =
        "номер_замаха,начало_с,пик_координаты_с,конец_с,длительность_с,полный_ли_замах," +
            "колено_в_пик_град,колено_мин_град,колено_макс_град," +
            "локоть_в_пик_град,локоть_мин_град,локоть_макс_град," +
            "наклон_корпуса_в_пик_град,наклон_корпуса_макс_град," +
            "время_максимальной_скорости_запястья_с,ненадёжные_метрики"
}
