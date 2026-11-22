package com.eliteteam.speakingcoach.telegram

import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.row
import java.util.UUID

internal fun followupKeyboard(action: String, runId: String, stage: String? = null,
                              reminderToken: UUID? = null): InlineKeyboardMarkup {
    return inlineKeyboard {
        when (action) {
            "practice" -> row { dataButton("🎙 Start call",
                reminderToken?.let { "fu:practice:$it" } ?: "fu:practice") }
        }
    }
}

internal fun reminderPracticeToken(data: String): UUID? =
    data.takeIf { it.startsWith("fu:practice:") }
        ?.removePrefix("fu:practice:")
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
