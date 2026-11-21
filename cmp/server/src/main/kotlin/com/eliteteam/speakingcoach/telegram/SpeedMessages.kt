package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.legacy.LegacySpeechSpeed
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.row

internal fun parseSpeedCallback(data: String): Double? =
    LegacySpeechSpeed.choices.firstOrNull { it.callback == data }?.value

internal fun speedMessage(speed: Double): String =
    "🎙 Voice speed: ${speed}×\nChoose how fast I'll speak in future voice messages."

internal fun speedKeyboard(current: Double): InlineKeyboardMarkup = inlineKeyboard {
    for (speed in LegacySpeechSpeed.choices) {
        val selected = if (current == speed.value) "✓ " else ""
        row { dataButton("$selected${speed.label} · ${speed.value}×", speed.callback) }
    }
}
