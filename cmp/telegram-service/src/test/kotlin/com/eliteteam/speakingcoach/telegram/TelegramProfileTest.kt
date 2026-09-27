package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.ChatProfile
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.Username
import dev.inmo.tgbotapi.types.chat.GroupChatImpl
import dev.inmo.tgbotapi.types.chat.PrivateChatImpl
import dev.inmo.tgbotapi.utils.RiskFeature
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(RiskFeature::class)
class TelegramProfileTest {
    @Test
    fun privateChatGivesUsernameWithoutAtAndFullName() {
        val chat = PrivateChatImpl(
            id = ChatId(RawChatId(42L)),
            username = Username("@alex_g"),
            firstName = "Alex",
            lastName = "Green",
        )
        assertEquals(ChatProfile(username = "alex_g", name = "Alex Green"), telegramProfile(chat))
    }

    @Test
    fun missingUsernameAndLastNameStayOut() {
        val chat = PrivateChatImpl(id = ChatId(RawChatId(42L)), firstName = "Alex")
        assertEquals(ChatProfile(username = null, name = "Alex"), telegramProfile(chat))
    }

    @Test
    fun groupChatHasNoProfile() {
        val chat = GroupChatImpl(id = ChatId(RawChatId(-100L)), title = "Club")
        assertEquals(ChatProfile(username = null, name = null), telegramProfile(chat))
    }
}
