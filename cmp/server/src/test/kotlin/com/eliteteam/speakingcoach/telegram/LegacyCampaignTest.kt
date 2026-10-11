package com.eliteteam.speakingcoach.telegram

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.message.textsources.BoldTextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyCampaignTest {
    @Test
    fun announcementKeepsParagraphsAndBoldPhraseWithOnboardingButton() {
        val message = legacyCampaignMessage()
        val plain = message.joinToString("") { it.source }
        assertTrue(plain.startsWith("Привет! Это Саша, создатель Speaky 👋\n\n"))
        assertTrue(plain.endsWith("Я читаю каждое сообщение и отвечаю сам 🙌"))
        assertTrue(plain.contains("корректно именно для тебя, очень важно пройти новый onboarding"))
        assertTrue(message.any { it is BoldTextSource && it.source == LEGACY_CAMPAIGN_BOLD })
        val button = legacyCampaignKeyboard().keyboard.single().single() as CallbackDataInlineKeyboardButton
        assertEquals("🎙 Пройти onboarding", button.text)
        assertEquals(LEGACY_ONBOARDING_CALLBACK, button.callbackData)
    }

    @Test
    fun voiceInvitationKeepsAnnouncementAndAddsOptionalVoiceExplanation() {
        val plain = legacyCampaignMessage(afterVoice = true).joinToString("") { it.source }
        assertTrue(plain.startsWith(LEGACY_CAMPAIGN_BEFORE))
        assertTrue(plain.endsWith(LEGACY_VOICE_INVITATION_NOTE))
        assertTrue(plain.contains("просто продолжай отправлять голосовые"))
    }
}
