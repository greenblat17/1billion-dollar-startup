package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.ReviewFluency
import com.eliteteam.speakingcoach.ai.ConversationReview
import com.eliteteam.speakingcoach.ai.ReviewSkill
import com.eliteteam.speakingcoach.speaking.Correction
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.message.textsources.TextSourcesList
import dev.inmo.tgbotapi.utils.bold
import dev.inmo.tgbotapi.utils.buildEntities
import dev.inmo.tgbotapi.utils.italic
import dev.inmo.tgbotapi.utils.regular
import dev.inmo.tgbotapi.utils.regularln
import dev.inmo.tgbotapi.utils.row

internal fun voiceMessagesForbiddenText(): TextSourcesList = buildEntities {
    regular("🎙 Пока не могу прислать тебе голосовое.\n\n")
    regular("Telegram блокирует голосовые от меня. Открой:\n")
    bold("Настройки")
    regular("\n→ ")
    bold("Конфиденциальность")
    regular("\n→ ")
    bold("Голосовые сообщения")
    regular("\nи выбери ")
    bold("Все")
    regular(".\n\nПотом нажми кнопку ниже ещё раз.")
}

internal fun isVoiceMessagesForbidden(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current.message?.contains("VOICE_MESSAGES_FORBIDDEN") == true) return true
        current = current.cause
    }
    return false
}
internal const val LEVEL_TITLE = "Your English level"
internal const val LEVEL_ESTIMATE = "I'll make this more accurate as we talk more."
internal const val LEVEL_UNKNOWN = "I don't have a clear level from this chat yet."
internal const val SKILL_UNKNOWN = "Not enough from this chat yet."
internal const val PRACTICE_ASK_LEAD = "🚀 This is your starting point."
internal const val PRACTICE_ASK_BODY = "A little practice every day can make a big difference."
internal const val PRACTICE_ASK_PROGRESS = "Now let's make progress one conversation at a time."
internal const val PRACTICE_ASK_QUESTION = "How much time do you want to practice each day?"
internal const val SEE_YOU_TOMORROW = "See you tomorrow. I'll be here when you're ready."
internal const val LEGACY_ONBOARDING_CALLBACK = "campaign:onboarding"
internal const val SPOKEN_TEXT_CALLBACK = "said"
internal const val SPOKEN_TEXT_BUTTON = "Subtitles"
internal const val SEE_RESULTS_BUTTON = "🔥 See results"
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
                if (index == 0) {
                    dataButton(SPOKEN_TEXT_BUTTON, SPOKEN_TEXT_CALLBACK)
                }
            }
        }
    }
}

internal val noInlineKeyboard: InlineKeyboardMarkup = inlineKeyboard { }

internal const val PRACTICE_5_LABEL = "☕ 5 min/day"
internal const val PRACTICE_10_LABEL = "✨ 10 min/day"
internal const val PRACTICE_15_LABEL = "🔥 15 min/day"
internal const val PRACTICE_SKIP_LABEL = "No goal for now"

private const val NEXT_CHAT_LEAD = "Whenever you're ready to chat again, tap "
private const val NEXT_CHAT_TAIL = " or just send me a voice message."
internal const val NEXT_CHAT_HINT = "$NEXT_CHAT_LEAD$START_CALL_BUTTON$NEXT_CHAT_TAIL"
internal const val REMINDER_QUESTION = "How about we set a reminder so we don't forget to chat?"
private const val PRACTICE_SKIPPED_LEAD = "No daily goal for now. You can still practice whenever you like."

private fun nextChatHint(): TextSourcesList = buildEntities {
    regular(NEXT_CHAT_LEAD)
    bold(START_CALL_BUTTON)
    regular(NEXT_CHAT_TAIL)
}

internal fun practiceSkipped(askReminder: Boolean = true): TextSourcesList = buildEntities {
    regular("$PRACTICE_SKIPPED_LEAD\n\n")
    addAll(nextChatHint())
    if (askReminder) regular("\n\n$REMINDER_QUESTION")
}

internal fun reminderAskKeyboard(runId: String): InlineKeyboardMarkup = inlineKeyboard {
    row {
        dataButton("🔔 Set reminder", "ob:remind:$runId")
        dataButton("Not now", "ob:later:$runId")
    }
}

internal const val REMINDER_TIME_PROMPT = "When should I remind you?\n\nSend a time like 13:00."
internal const val REMINDER_INVALID_TIME_PROMPT = "That time doesn't look right. Send a time like 13:00."
internal const val REMINDER_OFFER = "Want me to remind you tomorrow? Tap /remind"
internal const val REMIND_COMMAND_RUN = "cmd"

internal const val REMINDER_STOP_CALLBACK = "remind:stop"
internal const val REMINDER_STOPPED = "Okay. I won't remind you anymore. /remind turns it back on."

internal fun reminderChangePrompt(time: String): String =
    "Your reminder is $time. Send a new time like 13:00 to change it, or tap Stop reminders."

internal fun reminderStopKeyboard(): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton("Stop reminders", REMINDER_STOP_CALLBACK) }
}
internal const val PROFILE_ANYTIME = "You can check your progress anytime with /profile."

private val reminderClock = Regex("""(\d{1,2}):(\d{2})""")

internal fun parseReminderClock(text: String): String? {
    val match = reminderClock.matchEntire(text.trim()) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    if (hour > 23 || minute > 59) return null
    return "%02d:%02d".format(hour, minute)
}

internal fun reminderSaved(time: String): String =
    "Perfect. I'll remind you every day at $time 🔔\n\n$PROFILE_ANYTIME"

internal const val REMINDER_SKIPPED = "Sounds good. You can set a reminder anytime with /remind."

internal fun practiceDeal(minutes: Int, currentStreak: Int? = null, askReminder: Boolean = true): TextSourcesList = buildEntities {
    bold("$minutes minutes a day. Deal 🤝")
    if (currentStreak != null && currentStreak > 0) {
        regularln("")
        regularln("")
        regularln("🔥 Day $currentStreak of your streak")
        regularln("")
    } else {
        regularln("")
        regularln("")
    }
    addAll(nextChatHint())
    if (askReminder) {
        regular("\n\n")
        regular(REMINDER_QUESTION)
    }
}

internal fun cefrBandName(cefr: String?): String? = when (cefr) {
    "A1" -> "Beginner"
    "A2" -> "Elementary"
    "B1" -> "Intermediate"
    "B2" -> "Upper-Intermediate"
    "C1" -> "Advanced"
    "C2" -> "Proficient"
    else -> null
}

internal fun levelSlide(
    cefr: String?,
    levelText: String,
    overallScore: Int? = null,
    nextBand: String? = null,
    pointsToNext: Int? = null,
    preliminary: Boolean = false,
    showExplanation: Boolean = true,
): TextSourcesList = buildEntities {
    bold("🎯 $LEVEL_TITLE")
    if (preliminary) {
        regularln("")
        regularln("Preliminary estimate")
    }
    regularln("")
    regularln("")
    val band = cefrBandName(cefr)
    if (cefr != null && band != null) {
        bold(cefr)
        regularln("")
        regularln(band)
    } else {
        regularln(LEVEL_UNKNOWN)
    }
    if (overallScore != null) {
        regularln("")
        bold("$overallScore / 100")
        regularln("")
        val nextScore = if (nextBand != null && pointsToNext != null) overallScore + pointsToNext else null
        regularln(scoreBar(overallScore, nextScore))
        if (nextBand != null && pointsToNext != null) {
            regularln("")
            regularln("✨ $pointsToNext points to $nextBand")
        }
    }
    if (showExplanation) {
        val text = levelText.trim()
        if (text.isNotEmpty()) {
            regularln("")
            regularln(text)
        }
        regularln("")
        regular(LEVEL_ESTIMATE)
    }
}

internal fun grammarSlide(review: ConversationReview): TextSourcesList =
    skillSlide("✍️ Grammar", review.grammar)

private const val VOCABULARY_CARD_CHAR_LIMIT = 3900

internal fun vocabularySlide(review: ConversationReview): TextSourcesList {
    val base = skillSlide("📚 Vocabulary", review.vocabulary.copy(examples = emptyList()))
    val shown = mutableListOf<com.eliteteam.speakingcoach.ai.VocabularySuggestion>()
    var card = base
    for (suggestion in review.vocabulary.suggestions.take(10)) {
        val candidate = buildEntities {
            addAll(base)
            regularln("")
            regularln("")
            bold("Words and expressions to try")
            regularln("")
            regularln("")
            suggestionLines(shown + suggestion)
        }
        if (candidate.joinToString("") { it.source }.length <= VOCABULARY_CARD_CHAR_LIMIT) {
            shown.add(suggestion)
            card = candidate
        }
    }
    return card
}

internal fun fluencySlide(review: ConversationReview): TextSourcesList = buildEntities {
    bold("🎙 Fluency")
    regularln("")
    regularln("")
    bold(scoreLine(review.fluency.score))
    val text = review.fluency.text.trim()
    if (text.isNotEmpty()) {
        regularln("")
        regularln("")
        regularln(text)
    }
    val lines = fluencyLines(review.fluency)
    if (lines.isNotEmpty()) {
        regularln("")
        regular(lines.joinToString("\n"))
    }
}

internal fun practiceAsk(cefr: String?, score: Int?, nextBand: String?, pointsToNext: Int? = null): TextSourcesList = buildEntities {
    bold(PRACTICE_ASK_LEAD)
    regularln("")
    regularln("")
    if (cefr != null && score != null && nextBand != null) {
        val target = pointsToNext?.let { " · ${score + it}/100" }.orEmpty()
        bold("$cefr · $score/100 → $nextBand$target")
        regularln("")
        regularln("")
        regularln(PRACTICE_ASK_PROGRESS)
    } else {
        regularln(PRACTICE_ASK_BODY)
    }
    regularln("")
    regular(PRACTICE_ASK_QUESTION)
}

private fun skillSlide(title: String, skill: ReviewSkill): TextSourcesList = buildEntities {
    bold(title)
    regularln("")
    regularln("")
    bold(scoreLine(skill.score))
    val text = skill.text.trim()
    if (text.isNotEmpty()) {
        regularln("")
        regularln("")
        regularln(text)
    }
    if (skill.examples.isNotEmpty()) {
        regularln("")
        bold("What I noticed")
        regularln("")
        regularln("")
        skill.examples.take(5).forEachIndexed { index, example ->
            if (index > 0) regular("\n\n")
            addAll(inlineCorrection(Correction(example.wrong, example.better)))
            example.explanation.trim().takeIf { it.isNotEmpty() }?.let { explanation ->
                regular("\n\n💡 ")
                italic(explanation)
            }
        }
    }

}

private fun dev.inmo.tgbotapi.utils.EntitiesBuilder.suggestionLines(
    suggestions: List<com.eliteteam.speakingcoach.ai.VocabularySuggestion>,
) {
    suggestions.forEachIndexed { index, suggestion ->
        if (index > 0) regular("\n\n")
        regular("You said: ")
        regularln(suggestion.context.ifBlank { suggestion.original })
        regular("Try: ")
        bold(suggestion.editedContext.ifBlank { suggestion.alternative })
        suggestion.explanation.trim().takeIf { it.isNotEmpty() }?.let { explanation ->
            regular("\n\n💡 ")
            italic(explanation)
        }
    }
}

internal fun scoreBar(score: Int, nextScore: Int? = null): String {
    val filled = (score.coerceIn(0, 100) / 100.0 * 20).let { kotlin.math.round(it).toInt() }.coerceIn(0, 20)
    val bar = "█".repeat(filled) + "░".repeat(20 - filled)
    if (nextScore == null || nextScore <= score || nextScore >= 100) return bar
    val boundary = (nextScore / 100.0 * 20).let { kotlin.math.round(it).toInt() }.coerceIn(1, 19)
    return bar.substring(0, boundary) + "┃" + bar.substring(boundary)
}

private fun scoreLine(score: Int?): String = score?.let { "$it / 100" } ?: SKILL_UNKNOWN

internal fun fluencyLines(fluency: ReviewFluency): List<String> = buildList {
    fluency.paceWpm?.let { add("Speaking pace · $it words/min") }
    fluency.longPauses?.let { add("Long pauses · $it") }
    fluency.fillers?.takeIf { it > 0 }?.let { add("Detected filler words · $it") }
    fluency.longestStretchSec?.let { add("Longest stretch without a long pause · $it sec") }
}
