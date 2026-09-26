package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.StreakProfileResponse
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreakMessagesTest {
    @Test
    fun kickoffOpensTheDayAndShowsTheWeek() {
        val text = streakKickoffText(
            current = 3,
            last7 = listOf(true, true, false, true, true, true, false),
            today = LocalDate.of(2026, 9, 26),
        )
        assertEquals(
            "Day 3. Glad you're here. Let's talk.\nMo 🟩  Tu ⬜  We 🟩  Th 🟩\nFr 🟩  Sa ⬜  Su ⬜",
            text,
        )
    }

    @Test
    fun profileStartsTheWeekOnMonday() {
        val text = streakProfileText(
            StreakProfileResponse(current = 3, last7 = listOf(true, true, false, true, true, true, false)),
            today = LocalDate.of(2026, 9, 26),
        )
        assertEquals(
            "🔥 Current streak: 3 days\nMo 🟩  Tu ⬜  We 🟩  Th 🟩\nFr 🟩  Sa ⬜  Su ⬜",
            text,
        )
    }

    @Test
    fun emptyStreakStillShowsTheWeek() {
        val text = streakProfileText(
            StreakProfileResponse(current = 0, last7 = listOf(false, false)),
            today = LocalDate.of(2026, 9, 26),
        )
        assertTrue(text.startsWith("No streak yet."))
        assertFalse("🔥" in text)
        assertTrue(text.endsWith("Mo ⬜  Tu ⬜  We ⬜  Th ⬜\nFr ⬜  Sa ⬜  Su ⬜"))
    }

    @Test
    fun streakCommandIsRecognizedWithABotSuffix() {
        assertTrue(isStreakCommand("/streak"))
        assertTrue(isStreakCommand("/streak@speaky_english_buddy_bot"))
        assertFalse(isStreakCommand("/start"))
        assertFalse(isStreakCommand("/streaks"))
    }

}
