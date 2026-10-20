package com.eliteteam.speakingcoach.telegram

import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PracticeReminderCopyTest {
    @Test
    fun eachUserAlternatesAndUsersSplitOnSameDay() {
        val day = LocalDate.parse("2026-10-20")
        assertNotEquals(personalCopyDay("tg-1", day), personalCopyDay("tg-1", day.plusDays(1)))
        assertNotEquals(personalCopyDay("tg-1", day), personalCopyDay("tg-2", day))
    }

    @Test
    fun missingPersonalTextFallsBackAndRecordsGeneral() = runTest {
        val day = LocalDate.parse("2026-10-20")
        val chosen = (1..20).map { "tg-$it" }.first { personalCopyDay(it, day) }
        assertEquals(PracticeReminderCopy("General", "general"),
            practiceReminderCopy(chosen, day, "General") { null })
        assertEquals(PracticeReminderCopy("A personal question?", "personalized"),
            practiceReminderCopy(chosen, day, "General") { "A personal question?" })
    }
}
