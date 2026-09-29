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
    fun progressCountsSpeechUpToTwoMinutes() {
        assertEquals(
            "Знакомство\n0:00 из 2:00\n░░░░░░░░░░░░░░░░\nМожно короткими фразами",
            onboardingProgress(0.0),
        )
        assertEquals(
            "Знакомство\n0:38 из 2:00\n▰▰▰▰▰░░░░░░░░░░░\nМожно короткими фразами",
            onboardingProgress(38.0),
        )
        assertEquals(
            "Знакомство\n2:00 из 2:00\n▰▰▰▰▰▰▰▰▰▰▰▰▰▰▰▰",
            onboardingProgress(150.0),
        )
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
