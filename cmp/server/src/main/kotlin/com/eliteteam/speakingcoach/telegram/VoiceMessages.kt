package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.CharacterResponse
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.message.textsources.TextSourcesList
import dev.inmo.tgbotapi.utils.bold
import dev.inmo.tgbotapi.utils.buildEntities
import dev.inmo.tgbotapi.utils.code
import dev.inmo.tgbotapi.utils.regular
import dev.inmo.tgbotapi.utils.row

internal fun parseVoiceCallback(data: String): String? = when (data) {
    "voice:speaky" -> "speaky"
    "voice:kora_h" -> "kora_h"
    else -> null
}

internal fun voiceMessage(state: CharacterResponse): TextSourcesList = buildEntities {
    when (state.blocked) {
        "active_conversation" -> regular("⏳ Finish your current call and wait for its reply before switching.\n\n")
        "unavailable" -> regular("⚠️ Kora H is temporarily unavailable.\n\n")
    }
    bold(if (state.koraHAvailable) "🎙 Choose your speaking partner" else "🎙 Your speaking partner")
    regular("\n\n")
    bold("🌿 Speaky${if (state.character == "speaky") " ✓" else ""}")
    regular("\nA calm chat to practise English.\n")
    if (state.koraHAvailable) {
        regular("\n")
        bold("✨ Kora H${if (state.character == "kora_h") " ✓" else ""}")
        regular("\nA chat with more energy. Kora shares ideas and sometimes sees things another way.\n")
        regular("\nTap a name below, then tap ")
    } else {
        regular("\nTap ")
    }
    code(START_CALL_BUTTON)
    regular(" or send a voice message.")
    if (state.character == "kora_h") regular("\n\nChange Kora H's speaking speed with /speed.")
}

internal fun voiceKeyboard(state: CharacterResponse): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton("${if (state.character == "speaky") "✓ " else ""}Speaky", "voice:speaky") }
    if (state.koraHAvailable) {
        row { dataButton("${if (state.character == "kora_h") "✓ " else ""}Kora H", "voice:kora_h") }
    }
}
