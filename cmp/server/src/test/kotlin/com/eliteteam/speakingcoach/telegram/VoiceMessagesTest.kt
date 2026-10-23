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
        assertNull(parseVoiceCallback("voice:kora"))
        assertEquals("kora_h", parseVoiceCallback("voice:kora_h"))
        assertEquals("speaky", parseVoiceCallback("voice:speaky"))
        assertNull(parseVoiceCallback("voice:unknown"))
    }

    @Test
    fun unavailableKoraHCanStillBeSwitchedBackAndBusyNoticeIsClear() {
        val unavailable = CharacterResponse("kora_h", koraHAvailable = false)
        val buttons = voiceKeyboard(unavailable).keyboard.flatten().map { it as CallbackDataInlineKeyboardButton }
        assertEquals(listOf("voice:speaky"), buttons.map { it.callbackData })
        assertTrue(voiceMessage(unavailable).contains("Change Kora H's speaking speed with /speed"))
        assertTrue(voiceMessage(unavailable.copy(blocked = "active_conversation")).contains("Finish your current call"))
        val available = CharacterResponse("kora_h", koraHAvailable = true)
        assertEquals(2, voiceKeyboard(available).keyboard.size)
        val withH = CharacterResponse("kora_h", koraHAvailable = true)
        assertEquals(listOf("voice:speaky", "voice:kora_h"),
            voiceKeyboard(withH).keyboard.flatten().map { (it as CallbackDataInlineKeyboardButton).callbackData })
        assertTrue(voiceMessage(withH).contains("Selected: Kora H"))
        assertTrue(voiceMessage(withH).contains("Start call or send a voice message"))
        assertFalse(voiceMessage(withH).contains("Sonnet"))
        assertFalse(voiceMessage(CharacterResponse("speaky")).contains("Kora H"))
    }
}
