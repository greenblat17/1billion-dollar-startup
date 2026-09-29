package com.eliteteam.speakingcoach.telegram

import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.row

internal const val ONBOARDING_VOICE_HINT = "🎙 Ответь голосовым на английском. Не переживай насчёт ошибок."
internal const val ONBOARDING_REMEMBERED = "Я тебя запомнила. Давай просто говорить."
internal const val ONBOARDING_BEGIN_HINT = "Нажми «Давай 👋», чтобы начать знакомство."
internal const val ONBOARDING_PROGRESS_SECONDS = 120.0
internal const val ONBOARDING_PROGRESS_CALLBACK = "ob:progress"
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

internal val noInlineKeyboard: InlineKeyboardMarkup = inlineKeyboard { }

internal fun onboardingInvitation(firstName: String?): String {
    val name = firstName?.trim().orEmpty()
    val hello = if (name.isEmpty()) "Hey!" else "Hey, $name!"
    return "👋 $hello I’m Speaky, your English practice buddy.\n\n" +
        "Давай немного познакомимся — заодно я пойму, как лучше практиковаться с тобой.\n\n" +
        "Это займёт около двух минут."
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
            "begin" -> "Давай 👋"
            "retry" -> "Повторить"
            else -> "Продолжить разговор →"
        }
        dataButton(label, "ob:$action:$runId")
    }
}
