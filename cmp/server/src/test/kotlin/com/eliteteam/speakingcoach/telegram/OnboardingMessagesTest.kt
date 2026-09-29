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
    }

    @Test
    fun progressButtonsCountSpeechUpToTwoMinutes() {
        assertEquals(
            listOf("Знакомство · 0:00 из 2:00", "░░░░░░░░░░░░░░░░", "Можно короткими фразами"),
            progressLabels(0.0),
        )
        assertEquals(
            listOf("Знакомство · 0:38 из 2:00", "▰▰▰▰▰░░░░░░░░░░░", "Можно короткими фразами"),
            progressLabels(38.0),
        )
        assertEquals(
            listOf("Знакомство · 2:00 из 2:00", "▰▰▰▰▰▰▰▰▰▰▰▰▰▰▰▰"),
            progressLabels(150.0),
        )
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
        assertEquals("Я тебя запомнила. Давай просто говорить.", ONBOARDING_REMEMBERED)
        assertTrue(onboardingInvitation("Alex").contains("около двух минут"))
        assertTrue(isOnboardingCommand("/onboarding@speaky"))
        assertTrue(!isOnboardingCommand("/onboarding_extra"))
    }
}
