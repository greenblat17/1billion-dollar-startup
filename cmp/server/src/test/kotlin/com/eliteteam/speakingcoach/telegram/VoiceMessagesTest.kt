package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.CharacterResponse
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceMessagesTest {
    @Test
    fun commandSupportsBotSuffixAndOnlyKnownCharacters() {
        assertTrue(isVoiceCommand("/voice"))
        assertTrue(isVoiceCommand("/voice@speaky"))
        assertFalse(isVoiceCommand("/voices"))
        assertEquals("kora", parseVoiceCallback("voice:kora"))
        assertEquals("kora_h", parseVoiceCallback("voice:kora_h"))
        assertEquals("speaky", parseVoiceCallback("voice:speaky"))
        assertNull(parseVoiceCallback("voice:unknown"))
    }

    @Test
    fun unavailableKoraCanStillBeSwitchedBackAndBusyNoticeIsClear() {
        val unavailable = CharacterResponse("kora", false)
        val buttons = voiceKeyboard(unavailable).keyboard.flatten().map { it as CallbackDataInlineKeyboardButton }
        assertEquals(listOf("voice:speaky"), buttons.map { it.callbackData })
        assertTrue(voiceMessage(unavailable).contains("/speed also changes Kora's voice"))
        assertTrue(voiceMessage(unavailable.copy(blocked = "active_conversation")).contains("Finish your current call"))
        val available = CharacterResponse("kora", true)
        assertEquals(2, voiceKeyboard(available).keyboard.size)
        val withH = CharacterResponse("kora_h", true, koraHAvailable = true)
        assertEquals(listOf("voice:speaky", "voice:kora", "voice:kora_h"),
            voiceKeyboard(withH).keyboard.flatten().map { (it as CallbackDataInlineKeyboardButton).callbackData })
        assertTrue(voiceMessage(withH).contains("Your conversation partner: Kora H"))
    }
}
