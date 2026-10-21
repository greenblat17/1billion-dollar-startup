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
        assertEquals("speaky", parseVoiceCallback("voice:speaky"))
        assertNull(parseVoiceCallback("voice:unknown"))
    }

    @Test
    fun unavailableKoraCanStillBeSwitchedBackAndBusyNoticeIsClear() {
        val unavailable = CharacterResponse("kora", false)
        val buttons = voiceKeyboard(unavailable).keyboard.flatten().map { it as CallbackDataInlineKeyboardButton }
        assertEquals(listOf("voice:speaky"), buttons.map { it.callbackData })
        assertTrue(voiceMessage(unavailable).contains("/speed applies to Speaky"))
        assertTrue(voiceMessage(unavailable.copy(blocked = "active_conversation")).contains("Finish your current call"))
        val available = CharacterResponse("kora", true)
        assertEquals(2, voiceKeyboard(available).keyboard.size)
    }
}
