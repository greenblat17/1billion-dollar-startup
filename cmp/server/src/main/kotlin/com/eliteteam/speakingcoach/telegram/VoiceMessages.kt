package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.CharacterResponse
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.row

internal fun parseVoiceCallback(data: String): String? = when (data) {
    "voice:speaky" -> "speaky"
    "voice:kora" -> "kora"
    else -> null
}

internal fun voiceMessage(state: CharacterResponse): String {
    val name = if (state.character == "kora") "Kora" else "Speaky"
    val notice = when (state.blocked) {
        "active_conversation" -> "Finish your current call and wait for its reply before switching.\n\n"
        "unavailable" -> "Kora is temporarily unavailable.\n\n"
        else -> ""
    }
    return notice + "Your conversation partner: $name\n\n" +
        "Speaky — your familiar speaking partner.\n" +
        "Kora — warm, curious chats in simple English.\n\n" +
        "Choose a partner, then tap Start call and send a voice message. " +
        "This choice applies to free conversations. Lessons and situations keep their usual voice." +
        if (state.character == "kora") "\nKora uses her natural speaking speed; /speed applies to Speaky." else ""
}

internal fun voiceKeyboard(state: CharacterResponse): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton("${if (state.character == "speaky") "✓ " else ""}Speaky", "voice:speaky") }
    if (state.koraAvailable) {
        row { dataButton("${if (state.character == "kora") "✓ " else ""}Kora", "voice:kora") }
    }
}
