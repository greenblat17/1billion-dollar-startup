package com.eliteteam.speakingcoach.telegram

import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.row

internal const val ONBOARDING_VOICE_HINT =
    "🎙 Reply with a voice message in English\n" +
        "You can talk about your work, studies, hobbies — anything you like."
internal const val ONBOARDING_REMEMBERED = "Я тебя запомнила. Давай просто говорить."
internal const val ONBOARDING_BEGIN_HINT = "Нажми «Let’s chat 👋», чтобы начать знакомство."
internal const val ONBOARDING_PROGRESS_SECONDS = 120.0
internal const val ONBOARDING_PROGRESS_CALLBACK = "ob:progress"
internal const val SPOKEN_TEXT_CALLBACK = "said"
internal const val SPOKEN_TEXT_BUTTON = "Субтитры"
private const val ONBOARDING_PROGRESS_CELLS = 16

internal fun onboardingProgressLabel(seconds: Double): String {
    val capped = if (seconds.isFinite() && seconds > 0.0) seconds.coerceAtMost(ONBOARDING_PROGRESS_SECONDS) else 0.0
    val whole = capped.toInt()
    val clock = "%d:%02d".format(whole / 60, whole % 60)
    val filled = (capped / ONBOARDING_PROGRESS_SECONDS * ONBOARDING_PROGRESS_CELLS).toInt()
        .coerceIn(0, ONBOARDING_PROGRESS_CELLS)
    val bar = "●".repeat(filled) + "○".repeat(ONBOARDING_PROGRESS_CELLS - filled)
    return "$clock из 2:00 $bar"
}

internal fun onboardingProgressKeyboard(seconds: Double): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton(onboardingProgressLabel(seconds), ONBOARDING_PROGRESS_CALLBACK) }
}

internal fun spokenTextKeyboard(): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton(SPOKEN_TEXT_BUTTON, SPOKEN_TEXT_CALLBACK) }
}

internal fun withSpokenText(keyboard: InlineKeyboardMarkup?, spoken: Boolean): InlineKeyboardMarkup? {
    if (!spoken) return keyboard
    val button = spokenTextKeyboard()
    return if (keyboard == null) button else keyboard + button
}

internal val noInlineKeyboard: InlineKeyboardMarkup = inlineKeyboard { }

internal fun onboardingInvitation(firstName: String?): String {
    val name = firstName?.trim().orEmpty()
    val hello = if (name.isEmpty()) "Hey!" else "Hey, $name!"
    return "👋 $hello I’m Speaky, your English practice buddy.\n\n" +
        "Let’s get to know each other a little. We’ll chat in English for a couple of minutes, and I’ll get to know you along the way."
}

internal data class OnboardingCallback(val action: String, val runId: String)

internal fun parseOnboardingCallback(data: String): OnboardingCallback? {
    val parts = data.split(':')
    if (parts.size != 3 || parts[0] != "ob" || parts[1] !in setOf("begin", "retry", "continue")) return null
    if (!Regex("[a-f0-9]{32}").matches(parts[2])) return null
    return OnboardingCallback(parts[1], parts[2])
}

internal fun onboardingKeyboard(action: String, runId: String): InlineKeyboardMarkup = inlineKeyboard {
    row {
        val label = when (action) {
            "begin" -> "Let’s chat 👋"
            "retry" -> "Повторить"
            else -> "Продолжить разговор →"
        }
        dataButton(label, "ob:$action:$runId")
    }
}
