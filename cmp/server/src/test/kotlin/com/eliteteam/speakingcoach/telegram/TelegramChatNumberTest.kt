package com.eliteteam.speakingcoach.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TelegramChatNumberTest {
    @Test
    fun extractsPrivateAndGroupChatNumbersFromTelegramIdString() {
        assertEquals(123L, telegramChatNumber("ChatId(chatId=123)"))
        assertEquals(-100L, telegramChatNumber("ChatId(chatId=-100)"))
        assertEquals(123L, telegramChatNumber("123"))
        assertNull(telegramChatNumber("other"))
    }
}
