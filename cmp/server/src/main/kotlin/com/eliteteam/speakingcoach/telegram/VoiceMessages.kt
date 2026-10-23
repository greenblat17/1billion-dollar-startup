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
        "active_conversation" -> "⏳ Finish your current call and wait for its reply before switching.\n\n"
        "unavailable" -> "⚠️ Kora H is temporarily unavailable.\n\n"
        else -> ""
    }
    return buildString {
        append(notice)
        append(if (state.koraHAvailable) "🎙 Choose your speaking partner\n\n" else "🎙 Your speaking partner\n\n")
        append("Selected: $name\n\n")
        append("🌿 Speaky\nA calm chat to practise English.\n")
        if (state.koraHAvailable) {
            append("\n✨ Kora H\nA chat with more energy. Kora shares ideas and sometimes sees things another way.\n")
            append("\nTap a name below, then tap 🎙 Start call or send a voice message.\n\n")
            append("This affects free chats only. Lessons and situations keep their usual voice.")
        } else {
            append("\nTap 🎙 Start call or send a voice message.")
        }
        if (state.character == "kora_h") append("\n\nChange Kora H's speaking speed with /speed.")
    }
}

internal fun voiceKeyboard(state: CharacterResponse): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton("${if (state.character == "speaky") "✓ " else ""}Speaky", "voice:speaky") }
    if (state.koraHAvailable) {
        row { dataButton("${if (state.character == "kora_h") "✓ " else ""}Kora H", "voice:kora_h") }
    }
}
