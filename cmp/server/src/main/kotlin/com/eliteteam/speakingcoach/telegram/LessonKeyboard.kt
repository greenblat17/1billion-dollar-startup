package com.eliteteam.speakingcoach.telegram

import dev.inmo.tgbotapi.extensions.utils.types.buttons.replyKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.simpleButton
import dev.inmo.tgbotapi.types.buttons.ReplyKeyboardMarkup
import dev.inmo.tgbotapi.utils.row

internal fun isEndConversation(text: String): Boolean = text.trim() == END_CONVERSATION_TEXT

internal fun endConversationKeyboard(): ReplyKeyboardMarkup = replyKeyboard(
    resizeKeyboard = true,
    oneTimeKeyboard = false,
) {
    row {
        simpleButton(END_CONVERSATION_TEXT)
    }
}

internal fun readyReplyMarkup(lessonOpen: Boolean): ReplyKeyboardMarkup? =
    if (lessonOpen) endConversationKeyboard() else null
