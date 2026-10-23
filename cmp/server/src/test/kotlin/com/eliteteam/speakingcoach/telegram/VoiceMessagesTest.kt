package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.CharacterResponse
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.message.textsources.BoldTextSource
import dev.inmo.tgbotapi.types.message.textsources.CodeTextSource
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
        assertNull(parseVoiceCallback(VOICE_MENU_CALLBACK))
        assertNull(parseVoiceCallback("voice:unknown"))
    }

    @Test
    fun unavailableKoraHCanStillBeSwitchedBackAndBusyNoticeIsClear() {
        val unavailable = CharacterResponse("kora_h", koraHAvailable = false)
        val buttons = voiceKeyboard(unavailable).keyboard.flatten().map { it as CallbackDataInlineKeyboardButton }
        assertEquals(listOf("voice:speaky"), buttons.map { it.callbackData })
        assertTrue(voiceMessage(unavailable).plain().contains("Change Kora H's speaking speed with /speed"))
        assertTrue(voiceMessage(unavailable.copy(blocked = "active_conversation")).plain().contains("Finish your current call"))
        assertEquals("Finish your current call with End call, then choose a partner again.",
            voiceSelectionNotice(unavailable.copy(blocked = "active_conversation")))
        assertEquals("Kora H is temporarily unavailable. Please try again later.",
            voiceSelectionNotice(unavailable.copy(blocked = "unavailable")))
        assertNull(voiceSelectionNotice(unavailable))
        val available = CharacterResponse("kora_h", koraHAvailable = true)
        assertEquals(2, voiceKeyboard(available).keyboard.size)
        val withH = CharacterResponse("kora_h", koraHAvailable = true)
        assertEquals(listOf("voice:speaky", "voice:kora_h"),
            voiceKeyboard(withH).keyboard.flatten().map { (it as CallbackDataInlineKeyboardButton).callbackData })
        val message = voiceMessage(withH)
        assertTrue(message.any { it is BoldTextSource && it.source == "🎙 Choose your speaking partner" })
        assertTrue(message.any { it is BoldTextSource && it.source == "✨ Kora H ✓" })
        assertTrue(message.any { it is CodeTextSource && it.source == START_CALL_BUTTON })
        assertFalse(message.plain().contains("Selected:"))
        assertFalse(message.plain().contains("This affects free chats"))
        assertFalse(message.plain().contains("Sonnet"))
        assertFalse(voiceMessage(CharacterResponse("speaky")).plain().contains("Kora H"))
        assertTrue(voiceMessage(CharacterResponse("speaky", koraHAvailable = true))
            .any { it is BoldTextSource && it.source == "🌿 Speaky ✓" })
    }

    private fun dev.inmo.tgbotapi.types.message.textsources.TextSourcesList.plain(): String =
        joinToString("") { it.source }
}
