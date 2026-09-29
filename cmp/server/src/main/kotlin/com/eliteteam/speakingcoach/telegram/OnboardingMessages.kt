package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.OnboardingFluency
import com.eliteteam.speakingcoach.ai.OnboardingReview
import com.eliteteam.speakingcoach.ai.OnboardingSkill
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.message.textsources.TextSourcesList
import dev.inmo.tgbotapi.utils.bold
import dev.inmo.tgbotapi.utils.buildEntities
import dev.inmo.tgbotapi.utils.regular
import dev.inmo.tgbotapi.utils.regularln
import dev.inmo.tgbotapi.utils.row

internal const val ONBOARDING_VOICE_HINT =
    "🎙 Reply with a voice message in English\n" +
        "You can talk about your work, studies, hobbies — anything you like."
internal const val LEVEL_TITLE = "Your English level"
internal const val LEVEL_UNKNOWN = "I don't have a clear level from this chat yet."
internal const val NO_GRAMMAR_PATTERNS = "No clear grammar patterns stood out in this chat."
internal const val NO_VOCABULARY_PATTERNS = "No clear vocabulary patterns stood out in this chat."
internal const val PRACTICE_ASK =
    "This is your starting point. 🚀\n" +
        "A little practice every day can make a big difference.\n" +
        "How much time do you want to practice each day?"
internal const val SEE_YOU_TOMORROW = "See you tomorrow. I'll be here when you're ready."
internal const val ONBOARDING_BEGIN_HINT = "Нажми «Let’s chat 👋», чтобы начать знакомство."
internal const val ONBOARDING_PROGRESS_SECONDS = 120.0
internal const val ONBOARDING_PROGRESS_CALLBACK = "ob:progress"
internal const val SPOKEN_TEXT_CALLBACK = "said"
internal const val SPOKEN_TEXT_BUTTON = "Субтитры"
internal const val ONBOARDING_PROGRESS_HINT =
    "🎙 This tracks how much English you've spoken. Around 2 minutes is usually enough for me to get to know you a little."

internal fun onboardingProgressLabel(seconds: Double): String {
    val elapsed = if (seconds.isFinite() && seconds > 0.0) seconds else 0.0
    if (elapsed >= ONBOARDING_PROGRESS_SECONDS) return "2:00+"
    val whole = elapsed.toInt()
    val clock = "%d:%02d".format(whole / 60, whole % 60)
    return "$clock / 2:00"
}

internal fun onboardingProgressKeyboard(seconds: Double): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton(onboardingProgressLabel(seconds), ONBOARDING_PROGRESS_CALLBACK) }
}

internal fun spokenTextKeyboard(): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton(SPOKEN_TEXT_BUTTON, SPOKEN_TEXT_CALLBACK) }
}

internal fun withSpokenText(keyboard: InlineKeyboardMarkup?, spoken: Boolean): InlineKeyboardMarkup? {
    if (!spoken) return keyboard
    if (keyboard == null || keyboard.keyboard.isEmpty()) return spokenTextKeyboard()
    return inlineKeyboard {
        keyboard.keyboard.forEachIndexed { index, buttons ->
            row {
                buttons.forEach { add(it) }
                if (index == keyboard.keyboard.lastIndex) {
                    dataButton(SPOKEN_TEXT_BUTTON, SPOKEN_TEXT_CALLBACK)
                }
            }
        }
    }
}

internal val noInlineKeyboard: InlineKeyboardMarkup = inlineKeyboard { }

internal fun onboardingInvitation(firstName: String?): String {
    val name = firstName?.trim().orEmpty()
    val hello = if (name.isEmpty()) "Hey!" else "Hey, $name!"
    return "👋 $hello I’m Speaky, your English practice buddy.\n\n" +
        "Let’s get to know each other a little. We’ll chat in English for a couple of minutes, and I’ll get to know you along the way."
}

internal data class OnboardingCallback(val action: String, val runId: String)

private val onboardingActions = setOf(
    "begin", "retry", "continue", "level", "results", "vocab", "fluency", "finish", "talk", "bye", "m5", "m10", "m15",
)

internal fun parseOnboardingCallback(data: String): OnboardingCallback? {
    val parts = data.split(':')
    if (parts.size != 3 || parts[0] != "ob" || parts[1] !in onboardingActions) return null
    if (!Regex("[a-f0-9]{32}").matches(parts[2])) return null
    return OnboardingCallback(parts[1], parts[2])
}

internal fun onboardingKeyboard(action: String, runId: String): InlineKeyboardMarkup = inlineKeyboard {
    row {
        val label = when (action) {
            "begin" -> "Let’s chat 👋"
            "retry" -> "Повторить"
            "level" -> "See my results"
            "results" -> "Detailed report →"
            "vocab" -> "Vocabulary →"
            "fluency" -> "Fluency →"
            "finish" -> "Finish →"
            "talk" -> "Keep talking 🎙"
            "bye" -> "See you tomorrow"
            "m5" -> PRACTICE_5_LABEL
            "m10" -> PRACTICE_10_LABEL
            "m15" -> PRACTICE_15_LABEL
            else -> "Продолжить разговор →"
        }
        dataButton(label, "ob:$action:$runId")
    }
}

internal const val PRACTICE_5_LABEL = "☕ 5 min/day"
internal const val PRACTICE_10_LABEL = "✨ 10 min/day"
internal const val PRACTICE_15_LABEL = "🔥 15 min/day"

internal fun practiceMinutesKeyboard(runId: String): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton(PRACTICE_5_LABEL, "ob:m5:$runId") }
    row { dataButton(PRACTICE_10_LABEL, "ob:m10:$runId") }
    row { dataButton(PRACTICE_15_LABEL, "ob:m15:$runId") }
}

internal fun practiceDealKeyboard(runId: String): InlineKeyboardMarkup = inlineKeyboard {
    row {
        dataButton("Keep talking 🎙", "ob:talk:$runId")
        dataButton("See you tomorrow", "ob:bye:$runId")
    }
}

internal fun practiceDeal(minutes: Int): String =
    "$minutes minutes a day. Deal 🤝\n" +
        "That's your daily goal from now on.\n\n" +
        "We'll keep working on your grammar, vocabulary and fluency — and you'll be able to see how they change over time."

internal fun cefrBandName(cefr: String?): String? = when (cefr) {
    "A1" -> "Beginner"
    "A2" -> "Elementary"
    "B1" -> "Intermediate"
    "B2" -> "Upper-Intermediate"
    "C1" -> "Advanced"
    "C2" -> "Proficient"
    else -> null
}

internal fun levelSlide(cefr: String?, levelText: String): TextSourcesList = buildEntities {
    bold(LEVEL_TITLE)
    regularln("")
    val band = cefrBandName(cefr)
    if (cefr != null && band != null) {
        regularln(cefr)
        regularln(band)
    } else {
        regularln(LEVEL_UNKNOWN)
    }
    regular(levelText.trim())
}

internal fun grammarSlide(review: OnboardingReview): TextSourcesList = skillSlide("Grammar", review.grammar, NO_GRAMMAR_PATTERNS)

internal fun vocabularySlide(review: OnboardingReview): TextSourcesList =
    skillSlide("Vocabulary", review.vocabulary, NO_VOCABULARY_PATTERNS)

internal fun fluencySlide(review: OnboardingReview): TextSourcesList = buildEntities {
    bold("Fluency")
    regularln("")
    regularln("${review.fluency.score} / 100")
    regularln("")
    regularln(review.fluency.text.trim())
    val lines = fluencyLines(review.fluency)
    if (lines.isNotEmpty()) regular(lines.joinToString("\n"))
}

private fun skillSlide(title: String, skill: OnboardingSkill, empty: String): TextSourcesList = buildEntities {
    bold(title)
    regularln("")
    regularln("${skill.score} / 100")
    regularln("")
    regularln(skill.text.trim())
    if (skill.examples.isEmpty()) {
        regular(empty)
    } else {
        regular(skill.examples.joinToString("\n") { "${it.wrong} → ${it.better}" })
    }
}

internal fun fluencyLines(fluency: OnboardingFluency): List<String> = buildList {
    fluency.paceWpm?.let { add("Speaking pace · $it words/min") }
    fluency.longPauses?.let { add("Long pauses · $it") }
    fluency.fillers?.let { add("Filler words · $it") }
    fluency.longestStretchSec?.let { add("Longest stretch without a long pause · $it sec") }
}
