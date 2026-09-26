package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.StreakProfileResponse
import com.eliteteam.speakingcoach.speaking.TurnStreak
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreakMessagesTest {
    @Test
    fun milestoneBeatsANewRecord() {
        val text = streakMessage(streak(current = 7, best = 7, newRecord = true))
        assertEquals("🔥 One week streak! 7 days in a row.", text)
    }

    @Test
    fun recordIsShownWhenItIsNotAMilestone() {
        assertEquals(
            "🔥 4 days in a row. New personal record!",
            streakMessage(streak(current = 4, best = 4, newRecord = true)),
        )
    }

    @Test
    fun firstDayExplainsTheStreakAndARestartDoesNot() {
        assertEquals(
            "🔥 Day 1! Come back tomorrow to start your streak.",
            streakMessage(streak(current = 1, best = 1, firstEver = true)),
        )
        assertEquals(
            "🔥 Day 1. See you tomorrow!",
            streakMessage(streak(current = 1, best = 5, firstEver = false)),
        )
    }

    @Test
    fun ordinaryDayNamesTheCount() {
        assertEquals(
            "🔥 2 days in a row! See you tomorrow.",
            streakMessage(streak(current = 2, best = 2)),
        )
    }

    @Test
    fun profileShowsSquaresWithTodayLast() {
        val text = streakProfileText(
            StreakProfileResponse(current = 3, last7 = listOf(true, true, false, true, true, true, false)),
        )
        assertTrue(text.startsWith("🔥 Current streak: 3 days"))
        assertTrue(text.endsWith("🟩🟩⬜🟩🟩🟩⬜"))
    }

    @Test
    fun emptyStreakStillShowsTheWeek() {
        val text = streakProfileText(StreakProfileResponse(current = 0, last7 = listOf(false, false)))
        assertTrue(text.startsWith("No streak yet."))
        assertFalse("🔥" in text)
        assertTrue(text.endsWith("⬜⬜"))
    }

    @Test
    fun streakCommandIsRecognizedWithABotSuffix() {
        assertTrue(isStreakCommand("/streak"))
        assertTrue(isStreakCommand("/streak@speaky_english_buddy_bot"))
        assertFalse(isStreakCommand("/start"))
        assertFalse(isStreakCommand("/streaks"))
    }

    private fun streak(
        current: Int,
        best: Int,
        firstEver: Boolean = false,
        newRecord: Boolean = false,
    ) = TurnStreak(
        current = current,
        best = best,
        firstToday = true,
        firstEver = firstEver,
        newRecord = newRecord,
    )
}
