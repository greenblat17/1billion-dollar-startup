package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.ProgressAssessment
import com.eliteteam.speakingcoach.ai.ProgressProfileResponse
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.message.textsources.BoldTextSource
import dev.inmo.tgbotapi.types.message.textsources.TextSourcesList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileMessagesTest {
    @Test
    fun completedProfileShowsLatestAssessmentGoalAndActualStreak() {
        val profile = ProgressProfileResponse(
            assessment = ProgressAssessment("B1", 52, "B2", 11, 52, 52, 52),
            dailyMinutes = 10,
            currentStreak = 4,
        )
        assertEquals(
            "👤 Alex\n\n🎯 English level\nLatest assessment\nB1 · 52/100\n✨ 11 points to B2\n\n" +
                "✍️ Grammar · 52/100\n📚 Vocabulary · 52/100\n🎙 Fluency · 52/100\n\n" +
                "⏱ Daily goal\n10 min/day\n\n🔥 Current streak\n4 days",
            profileMessage("Alex", profile).plain(),
        )
        val button = profileKeyboard().keyboard.single().single() as CallbackDataInlineKeyboardButton
        assertEquals("🔥 View streak", button.text)
        assertEquals(PROFILE_STREAK_CALLBACK, button.callbackData)
    }

    @Test
    fun missingAssessmentKeepsGoalAndStreakWithoutInventingScores() {
        val text = profileMessage(null, ProgressProfileResponse(dailyMinutes = 5, currentStreak = 1)).plain()
        assertTrue(text.contains("👤 Your profile"))
        assertTrue(text.contains("Complete /onboarding"))
        assertTrue(text.contains("5 min/day"))
        assertTrue(text.endsWith("1 day"))
        assertFalse(text.contains("/100"))
        assertTrue(profileMessage("", ProgressProfileResponse()).plain().contains("Not set yet"))
    }

    @Test
    fun unknownSkillsAndC2DoNotBecomeZeroOrInventNextBand() {
        val unknown = profileMessage("Alex", ProgressProfileResponse(assessment = ProgressAssessment())).plain()
        assertTrue(unknown.contains(LEVEL_UNKNOWN))
        assertTrue(unknown.contains("Grammar · Not enough evidence yet"))
        assertFalse(unknown.contains("/100"))
        val c2 = profileMessage("Alex", ProgressProfileResponse(assessment = ProgressAssessment(cefr = "C2"))).plain()
        assertTrue(c2.contains("C2"))
        assertFalse(c2.contains("points to"))
    }

    @Test
    fun goalMessageIsTheDailyCommitment() {
        val deal = practiceDeal(10, 4)
        assertEquals(
            "10 minutes a day. Deal 🤝\n\n🔥 Day 4 of your streak\n\n" +
                "Come back tomorrow for your 10-minute practice.",
            deal.plain(),
        )
        assertTrue(deal.any { it is BoldTextSource && it.source == "10 minutes a day. Deal 🤝" })
        assertTrue(!deal.plain().contains("/profile"))
        assertTrue(practiceDeal(5, 1).plain().contains("Day 1"))
        for (streak in listOf(null, 0)) {
            val text = practiceDeal(15, streak).plain()
            assertFalse(text.contains("Day 1"))
            assertTrue(text.endsWith("Come back tomorrow for your 15-minute practice."))
        }
        assertTrue(isProfileCommand("/profile"))
        assertTrue(isProfileCommand("/profile@speaky"))
        assertFalse(isProfileCommand("/profile_extra"))
        assertTrue(isRemindCommand("/remind"))
        assertTrue(isRemindCommand("/remind@speaky"))
        assertFalse(isRemindCommand("/reminder"))
        assertEquals(
            "Your reminder is 13:00. Send a new time like 13:00 to change it, or tap Stop reminders.",
            reminderChangePrompt("13:00"),
        )
        assertEquals(REMINDER_STOP_CALLBACK, "remind:stop")
    }

    private fun TextSourcesList.plain(): String = joinToString("") { it.source }
}
