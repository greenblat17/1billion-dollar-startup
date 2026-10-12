package com.eliteteam.speakingcoach.telegram

import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DailyReminderTest {

    @Test
    fun parsesOnlyTelegramSessionIds() {
        assertEquals(42L, reminderChatId("tg-42"))
        assertEquals(-100L, reminderChatId("tg--100"))
        assertNull(reminderChatId("app-42"))
        assertNull(reminderChatId("tg-abc"))
        assertEquals(123456L, reminderChatId("tg-ChatId(chatId=123456)"))
        assertEquals(-100L, reminderChatId("tg-ChatId(chatId=-100)"))
        assertNull(reminderChatId("tg-ChatId(chatId=x)"))
    }

    @Test
    fun parsesTheIdTheBotReallyStores() {
        val sessionId = telegramSessionId(ChatId(RawChatId(987654321L))).value
        assertEquals(987654321L, reminderChatId(sessionId))
    }
}
