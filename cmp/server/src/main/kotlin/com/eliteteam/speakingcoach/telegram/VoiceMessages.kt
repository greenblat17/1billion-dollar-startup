package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.CharacterResponse
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.row

internal fun parseVoiceCallback(data: String): String? = when (data) {
    "voice:speaky" -> "speaky"
    "voice:kora" -> "kora"
    "voice:kora_h" -> "kora_h"
    else -> null
}

internal fun voiceMessage(state: CharacterResponse): String {
    val name = when (state.character) {
        "kora" -> "Kora"
        "kora_h" -> "Kora H"
        else -> "Speaky"
    }
    val notice = when (state.blocked) {
        "active_conversation" -> "Finish your current call and wait for its reply before switching.\n\n"
        "unavailable" -> "Kora is temporarily unavailable.\n\n"
        else -> ""
    }
    return notice + "Your conversation partner: $name\n\n" +
        "Speaky — your familiar speaking partner.\n" +
        "Kora — warm, curious chats in simple English.\n" +
        "Kora H — another view when useful; Sonnet's exact words in Hume's voice.\n\n" +
        "Choose a partner, then tap Start call and send a voice message. " +
        "This choice applies to free conversations. Lessons and situations keep their usual voice." +
        if (state.character == "kora" || state.character == "kora_h") "\n/speed also changes Kora's voice." else ""
}

internal fun voiceKeyboard(state: CharacterResponse): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton("${if (state.character == "speaky") "✓ " else ""}Speaky", "voice:speaky") }
    if (state.koraAvailable) {
        row { dataButton("${if (state.character == "kora") "✓ " else ""}Kora", "voice:kora") }
    }
    if (state.koraHAvailable) {
        row { dataButton("${if (state.character == "kora_h") "✓ " else ""}Kora H", "voice:kora_h") }
    }
}
