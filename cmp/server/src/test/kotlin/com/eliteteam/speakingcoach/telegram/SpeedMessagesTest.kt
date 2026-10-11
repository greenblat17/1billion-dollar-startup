package com.eliteteam.speakingcoach.telegram

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeedMessagesTest {
    @Test
    fun commandAndButtonsSelectOnlySupportedSpeeds() {
        assertTrue(isSpeedCommand("/speed"))
        assertTrue(isSpeedCommand("/speed@speaky"))
        assertFalse(isSpeedCommand("/speeding"))
        assertEquals(0.8, parseSpeedCallback("speed:0.8"))
        assertEquals(0.9, parseSpeedCallback("speed:0.9"))
        assertEquals(1.0, parseSpeedCallback("speed:1.0"))
        assertNull(parseSpeedCallback("speed:0.85"))
        val buttons = speedKeyboard(0.9).keyboard.map { row -> row.single() as CallbackDataInlineKeyboardButton }
        assertEquals(listOf("speed:0.8", "speed:0.9", "speed:1.0"), buttons.map { it.callbackData })
        assertTrue(buttons[1].text.startsWith("✓ "))
        assertTrue(speedMessage(0.9).contains("0.9×"))
    }
}
