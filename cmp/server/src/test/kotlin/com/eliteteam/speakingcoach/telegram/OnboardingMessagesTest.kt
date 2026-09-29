package com.eliteteam.speakingcoach.telegram

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingMessagesTest {
    @Test
    fun buttonsCarryTheAttemptAndFitTelegramLimit() {
        val run = "a".repeat(32)
        for (action in listOf("begin", "retry", "continue")) {
            val button = onboardingKeyboard(action, run).keyboard.single().single() as CallbackDataInlineKeyboardButton
            assertEquals(OnboardingCallback(action, run), parseOnboardingCallback(button.callbackData))
            assertTrue(button.callbackData.encodeToByteArray().size <= 64)
        }
        assertNull(parseOnboardingCallback("ob:reset:$run"))
        assertNull(parseOnboardingCallback("ob:begin:another:chat"))
        assertNull(parseOnboardingCallback("ob:begin:"))
        assertNull(parseOnboardingCallback(SPOKEN_TEXT_CALLBACK))
        val spoken = spokenTextKeyboard().keyboard.single().single() as CallbackDataInlineKeyboardButton
        assertEquals(SPOKEN_TEXT_BUTTON, spoken.text)
        assertEquals(SPOKEN_TEXT_CALLBACK, spoken.callbackData)
    }

    @Test
    fun progressButtonsCountSpeechUpToTwoMinutes() {
        assertEquals("🎙 0:00 / 2:00", progressLabels(0.0).single())
        assertEquals("🎙 0:38 / 2:00", progressLabels(38.0).single())
        assertEquals("🎙 1:17 / 2:00", progressLabels(77.0).single())
        assertEquals("🎙 2:00+", progressLabels(120.0).single())
        assertEquals("🎙 2:00+", progressLabels(150.0).single())
        val run = "a".repeat(32)
        val withRetry = onboardingProgressKeyboard(38.0) + onboardingKeyboard("retry", run)
        val labels = withRetry.keyboard.map { row ->
            (row.single() as CallbackDataInlineKeyboardButton).text
        }
        assertEquals("Повторить", labels.last())
    }

    private fun progressLabels(seconds: Double): List<String> =
        onboardingProgressKeyboard(seconds).keyboard.map { row ->
            val button = row.single() as CallbackDataInlineKeyboardButton
            assertEquals(ONBOARDING_PROGRESS_CALLBACK, button.callbackData)
            assertNull(parseOnboardingCallback(button.callbackData))
            assertTrue(button.text.length <= 64)
            button.text
        }

    @Test
    fun invitationSupportsMissingNameAndCommandMentions() {
        assertTrue(onboardingInvitation("Alex").startsWith("👋 Hey, Alex!"))
        assertTrue(onboardingInvitation(null).startsWith("👋 Hey!"))
        assertTrue(onboardingInvitation("Alex").contains("Let’s get to know each other a little."))
        assertTrue(onboardingInvitation("Alex").contains("a couple of minutes"))
        val begin = onboardingKeyboard("begin", "a".repeat(32)).keyboard.single().single() as CallbackDataInlineKeyboardButton
        assertEquals("Let’s chat 👋", begin.text)
        assertEquals("Я тебя запомнила. Давай просто говорить.", ONBOARDING_REMEMBERED)
        assertTrue(isOnboardingCommand("/onboarding@speaky"))
        assertTrue(!isOnboardingCommand("/onboarding_extra"))
    }
}
