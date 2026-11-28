package com.tennisshadowcoach.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandParserTest {
    @Test fun `calibration commands in several word forms`() {
        assertEquals(VoiceCommand.START_CALIBRATION, VoiceCommandParser.parse("Старт калибровки"))
        assertEquals(VoiceCommand.START_CALIBRATION, VoiceCommandParser.parse("начать калибровку"))
        assertEquals(VoiceCommand.FINISH_CALIBRATION, VoiceCommandParser.parse("Заверши калибровку"))
        assertEquals(VoiceCommand.FINISH_CALIBRATION, VoiceCommandParser.parse("завершить калибровку"))
        assertEquals(VoiceCommand.FINISH_CALIBRATION, VoiceCommandParser.parse("калибровка готова"))
        assertEquals(VoiceCommand.FINISH_CALIBRATION, VoiceCommandParser.parse("стоп калибровка"))
    }

    @Test fun `training start and stop`() {
        assertEquals(VoiceCommand.START_TRAINING, VoiceCommandParser.parse("Старт"))
        assertEquals(VoiceCommand.START_TRAINING, VoiceCommandParser.parse("старт тренировки"))
        assertEquals(VoiceCommand.START_TRAINING, VoiceCommandParser.parse("поехали"))
        assertEquals(VoiceCommand.STOP_TRAINING, VoiceCommandParser.parse("Стоп"))
        assertEquals(VoiceCommand.STOP_TRAINING, VoiceCommandParser.parse("стоп тренировка"))
        assertEquals(VoiceCommand.STOP_TRAINING, VoiceCommandParser.parse("хватит"))
    }

    @Test fun `english commands`() {
        assertEquals(VoiceCommand.START_CALIBRATION, VoiceCommandParser.parse("Start calibration"))
        assertEquals(VoiceCommand.START_CALIBRATION, VoiceCommandParser.parse("begin calibrating"))
        assertEquals(VoiceCommand.FINISH_CALIBRATION, VoiceCommandParser.parse("Finish calibration"))
        assertEquals(VoiceCommand.FINISH_CALIBRATION, VoiceCommandParser.parse("calibration done"))
        assertEquals(VoiceCommand.FINISH_CALIBRATION, VoiceCommandParser.parse("stop calibration"))
        assertEquals(VoiceCommand.START_TRAINING, VoiceCommandParser.parse("Start"))
        assertEquals(VoiceCommand.START_TRAINING, VoiceCommandParser.parse("let's go"))
        assertEquals(VoiceCommand.STOP_TRAINING, VoiceCommandParser.parse("Stop"))
        assertEquals(VoiceCommand.STOP_TRAINING, VoiceCommandParser.parse("that's enough"))
        assertNull(VoiceCommandParser.parse("good morning"))
        // Frequent mishearings of "calibration" and the short "ready".
        assertEquals(VoiceCommand.START_CALIBRATION, VoiceCommandParser.parse("Start Liberation"))
        assertEquals(VoiceCommand.START_CALIBRATION, VoiceCommandParser.parse("start celebration"))
        assertEquals(VoiceCommand.FINISH_CALIBRATION, VoiceCommandParser.parse("finish liberation"))
        assertEquals(VoiceCommand.FINISH_CALIBRATION, VoiceCommandParser.parse("Ready"))
        assertEquals(VoiceCommand.FINISH_CALIBRATION, VoiceCommandParser.parse("I'm ready"))
        assertNull(VoiceCommandParser.parse("calibration"))
    }

    @Test fun `unrelated speech is ignored`() {
        assertNull(VoiceCommandParser.parse(""))
        assertNull(VoiceCommandParser.parse("привет как дела"))
        assertNull(VoiceCommandParser.parse("калибровка"))
    }

    @Test fun `the first matching recogniser alternative wins`() {
        assertEquals(
            VoiceCommand.START_CALIBRATION,
            VoiceCommandParser.parse(listOf("старый калибр", "старт калибровки", "стоп"))
        )
    }
}
