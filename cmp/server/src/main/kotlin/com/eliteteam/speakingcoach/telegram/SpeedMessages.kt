package com.eliteteam.speakingcoach.telegram

import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.row

internal fun parseSpeedCallback(data: String): Double? = when (data) {
    "speed:0.8" -> 0.8
    "speed:0.9" -> 0.9
    "speed:1.0" -> 1.0
    else -> null
}

internal fun speedMessage(speed: Double): String =
    "🎙 Voice speed: ${speed}×\nChoose how fast I'll speak in future voice messages."

internal fun speedKeyboard(current: Double): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton("${if (current == 0.8) "✓ " else ""}Slower · 0.8×", "speed:0.8") }
    row { dataButton("${if (current == 0.9) "✓ " else ""}Comfortable · 0.9×", "speed:0.9") }
    row { dataButton("${if (current == 1.0) "✓ " else ""}Normal · 1.0×", "speed:1.0") }
}
