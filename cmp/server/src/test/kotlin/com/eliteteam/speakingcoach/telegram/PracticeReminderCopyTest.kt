package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.PersonalizedReminderResponse
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
        assertEquals(PracticeReminderCopy("General", "general", "How are you?"),
            practiceReminderCopy(chosen, day, "General", "How are you?") { null })
        assertEquals(PracticeReminderCopy("A personal question?", "personalized", "What book did you read?"),
            practiceReminderCopy(chosen, day, "General", "How are you?") {
                PersonalizedReminderResponse("A personal question?", "What book did you read?")
            })
        assertEquals(PracticeReminderCopy("General", "general", "How are you?"),
            practiceReminderCopy(chosen, day, "General", "How are you?") {
                PersonalizedReminderResponse("A personal question?", null)
            })
    }

    @Test
    fun everyGeneralPracticeInvitationHasOneSpokenQuestion() {
        val ids = (REMINDER_TEMPLATES + STREAK_REMINDER_TEMPLATES).map { it.id } +
            FollowupCatalog.variants("practice").map { it.id } + FollowupCatalog.finalMessage().id
        ids.forEach { id ->
            val question = practiceOpeningQuestion(id)
            assertTrue(question.length <= 220 && question.count { it == '?' } == 1, id)
        }
    }
}
