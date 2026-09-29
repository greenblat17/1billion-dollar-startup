package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.ProgressAssessment
import com.eliteteam.speakingcoach.ai.ProgressProfileResponse
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
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
    fun finalGoalMessageUsesActualStreakAndAlwaysLinksProfile() {
        assertEquals(
            "10 minutes a day. Deal 🤝\n🔥 Day 4 of your streak\n" +
                "Come back tomorrow for your 10-minute practice.\n" +
                "You can check your progress anytime with /profile.",
            practiceDeal(10, 4),
        )
        assertTrue(practiceDeal(5, 1).contains("Day 1"))
        for (streak in listOf(null, 0)) {
            assertFalse(practiceDeal(15, streak).contains("Day 1"))
            assertTrue(practiceDeal(15, streak).contains("/profile"))
        }
        assertTrue(isProfileCommand("/profile"))
        assertTrue(isProfileCommand("/profile@speaky"))
        assertFalse(isProfileCommand("/profile_extra"))
    }

    private fun TextSourcesList.plain(): String = joinToString("") { it.source }
}
