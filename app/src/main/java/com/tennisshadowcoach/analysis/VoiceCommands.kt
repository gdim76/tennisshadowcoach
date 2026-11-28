package com.tennisshadowcoach.analysis

/** Spoken commands of the mirror (M4): the phone stands 2.5–3 m away, the buttons are out of reach. */
enum class VoiceCommand { START_CALIBRATION, FINISH_CALIBRATION, START_TRAINING, STOP_TRAINING }

/**
 * Maps recognised phrases to commands.  The phone's on-device recogniser has English installed, so
 * the commands are English ("start calibration", "finish calibration", "start", "stop"); Russian
 * word forms are understood too in case a Russian model is added later.  Pure Kotlin: recognisers return several
 * alternatives with varying word forms, so matching is by word stems and the first alternative that
 * matches wins.  Calibration phrases are checked before the bare "старт"/"стоп".
 */
object VoiceCommandParser {
    // "Liberation" / "celebration": how the English recogniser often hears "calibration".
    private val calibration = listOf("калибр", "calibrat", "liberat", "celebrat")
    /** Said alone, finishes the calibration: shorter and harder to mishear than "finish calibration". */
    private val readyAlone = listOf("ready", "готово")
    private val begin = listOf("старт", "начать", "начни", "начинай", "начало", "поехали", "запуск", "start", "begin", "go")
    private val finish = listOf("заверш", "законч", "конец", "готов", "хватит", "стоп", "finish", "done", "end", "complete", "stop", "ready")
    private val stop = listOf("стоп", "закончить", "заканчив", "хватит", "конец", "финиш", "останов", "stop", "finish", "end", "enough", "pause")

    fun parse(alternatives: List<String>): VoiceCommand? =
        alternatives.asSequence().mapNotNull(::parse).firstOrNull()

    fun parse(phrase: String): VoiceCommand? {
        val words = phrase.lowercase().replace('ё', 'е').split(Regex("[^а-яa-z0-9]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        // Stems match word beginnings; very short ones ("go") only whole words, or "good" would start a set.
        fun has(stems: List<String>) = words.any { word -> stems.any { if (it.length <= 2) word == it else word.startsWith(it) } }
        if (words.size <= 3 && has(readyAlone) && !has(calibration)) return VoiceCommand.FINISH_CALIBRATION
        if (has(calibration)) {
            return when {
                has(finish) -> VoiceCommand.FINISH_CALIBRATION
                has(begin) -> VoiceCommand.START_CALIBRATION
                else -> null
            }
        }
        return when {
            has(stop) -> VoiceCommand.STOP_TRAINING
            has(begin) -> VoiceCommand.START_TRAINING
            else -> null
        }
    }
}
