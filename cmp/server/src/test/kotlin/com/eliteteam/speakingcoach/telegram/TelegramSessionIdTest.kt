package com.eliteteam.speakingcoach.telegram

import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramSessionIdTest {

    @Test
    fun prefixesChatId() {
        assertEquals("tg-12345", telegramSessionId(12345L).value)
        assertEquals("tg--100", telegramSessionId(-100).value)
    }

    @Test
    fun startTextUsesFirstNameWhenPresent() {
        assertEquals(
            "Hey, Alexander! 👋 I'm Speaky, your English practice buddy. Let's improve your English in real conversations. Ready? Send a voice message and tell me a bit about yourself. 😊",
            startTextMessage("Alexander"),
        )
        assertEquals(
            "Hey! 👋 I'm Speaky, your English practice buddy. Let's improve your English in real conversations. Ready? Send a voice message and tell me a bit about yourself. 😊",
            startTextMessage(null),
        )
    }
}
