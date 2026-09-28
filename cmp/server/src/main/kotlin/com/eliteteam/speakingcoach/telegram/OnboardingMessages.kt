package com.eliteteam.speakingcoach.telegram

import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.row

internal const val ONBOARDING_VOICE_HINT = "🎙 Ответь голосовым на английском. Не переживай насчёт ошибок."
internal const val ONBOARDING_BEGIN_HINT = "Нажми «Давай 👋», чтобы начать знакомство."

internal fun onboardingInvitation(firstName: String?): String {
    val name = firstName?.trim().orEmpty()
    val hello = if (name.isEmpty()) "Hey!" else "Hey, $name!"
    return "👋 $hello I’m Speaky, your English practice buddy.\n\n" +
        "Давай немного познакомимся — заодно я пойму, как лучше практиковаться с тобой.\n\n" +
        "Это займёт около минуты."
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
