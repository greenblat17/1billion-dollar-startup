package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.CharacterResponse
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.row

internal fun parseVoiceCallback(data: String): String? = when (data) {
    "voice:speaky" -> "speaky"
    "voice:kora_h" -> "kora_h"
    else -> null
}

internal fun voiceMessage(state: CharacterResponse): String {
    val name = when (state.character) {
        "kora_h" -> "Kora H"
        else -> "Speaky"
    }
    val notice = when (state.blocked) {
        "active_conversation" -> "Finish your current call and wait for its reply before switching.\n\n"
        "unavailable" -> "Kora H is temporarily unavailable.\n\n"
        else -> ""
    }
    return notice + "Your conversation partner: $name\n\n" +
        "Speaky — your familiar speaking partner.\n" +
        (if (state.koraHAvailable) "Kora H — another view when useful; Sonnet's words in Gemini's voice.\n" else "") +
        "\n" +
        (if (state.koraHAvailable) "Choose a partner, then tap Start call and send a voice message. "
         else "Tap Start call and send a voice message. ") +
        "This choice applies to free conversations. Lessons and situations keep their usual voice." +
        if (state.character == "kora_h") "\n/speed also changes Kora H's voice." else ""
}

internal fun voiceKeyboard(state: CharacterResponse): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton("${if (state.character == "speaky") "✓ " else ""}Speaky", "voice:speaky") }
    if (state.koraHAvailable) {
        row { dataButton("${if (state.character == "kora_h") "✓ " else ""}Kora H", "voice:kora_h") }
    }
}
