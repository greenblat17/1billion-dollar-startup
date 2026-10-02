package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.OnboardingFluency
import com.eliteteam.speakingcoach.ai.OnboardingReview
import com.eliteteam.speakingcoach.ai.OnboardingSkill
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

internal const val ONBOARDING_VOICE_HINT =
    "🎙 Reply with a voice message in English\n\n" +
        "No need to talk for 2 minutes at once. Just answer naturally — I’ll keep the conversation going."
internal const val LEVEL_TITLE = "Your English level"
internal const val LEVEL_ESTIMATE = "I'll make this more accurate as we talk more."
internal const val LEVEL_UNKNOWN = "I don't have a clear level from this chat yet."
internal const val SKILL_UNKNOWN = "Not enough from this chat yet."
internal const val PRACTICE_ASK_LEAD = "🚀 This is your starting point."
internal const val PRACTICE_ASK_BODY = "A little practice every day can make a big difference."
internal const val PRACTICE_ASK_PROGRESS = "Now let's make progress one conversation at a time."
internal const val PRACTICE_ASK_QUESTION = "How much time do you want to practice each day?"
internal const val SEE_YOU_TOMORROW = "See you tomorrow. I'll be here when you're ready."
internal const val FOUNDER_NOTE =
    "Кстати, я Саша, один из создателей Speaky 👋\n\n" +
        "Сейчас мы активно развиваем нашего English Buddy, поэтому мне правда интересно, как тебе первый разговор.\n\n" +
        "Если что-то понравилось, не понравилось или просто появилась идея — напиши мне напрямую: @alexgusev93\n\n" +
        "Я читаю каждое сообщение и всегда отвечаю сам. Буду рад любому фидбеку 😊"
internal const val ONBOARDING_BEGIN_HINT = "Tap Let’s chat 👋 to start."
internal const val LEGACY_ONBOARDING_CALLBACK = "campaign:onboarding"
internal const val ONBOARDING_PROGRESS_SECONDS = 120.0
internal const val ONBOARDING_PROGRESS_CALLBACK = "ob:progress"
internal const val SPOKEN_TEXT_CALLBACK = "said"
internal const val SPOKEN_TEXT_BUTTON = "Subtitles"
internal const val ONBOARDING_PROGRESS_HINT =
    "🎙 This tracks how much English you've spoken. Around 2 minutes gives me enough to get to know you and estimate your English level."

internal fun onboardingProgressLabel(seconds: Double): String {
    val elapsed = if (seconds.isFinite() && seconds > 0.0) seconds else 0.0
    if (elapsed >= ONBOARDING_PROGRESS_SECONDS) return "🎯 2:00+"
    val whole = elapsed.toInt()
    val clock = "%d:%02d".format(whole / 60, whole % 60)
    return "🎯 $clock / 2:00"
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
        "Let’s talk in English for about 2 minutes. I’ll get to know you and see what your English level is."
}

internal data class OnboardingCallback(val action: String, val runId: String)

private val onboardingActions = setOf(
    "begin", "retry", "continue", "level", "results", "vocab", "fluency", "finish", "talk", "profile", "bye",
    "m5", "m10", "m15", "skip", "remind", "later",
)

internal fun onboardingCallbackRequestId(action: String, runId: String, queryId: String): String = when (action) {
    "retry", "level", "results", "vocab", "fluency", "finish", "profile" -> "callback:$queryId"
    "m5", "m10", "m15", "skip" -> "callback:goal:$runId"
    else -> "callback:$action:$runId"
}

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
            "retry" -> "Retry"
            "level" -> "🔥 See my results"
            "results" -> "See what I noticed →"
            "vocab" -> "Vocabulary →"
            "fluency" -> "Fluency →"
            "finish" -> "Continue →"
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
internal const val PRACTICE_SKIP_LABEL = "No goal for now"

internal fun practiceMinutesKeyboard(runId: String): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton(PRACTICE_5_LABEL, "ob:m5:$runId") }
    row { dataButton(PRACTICE_10_LABEL, "ob:m10:$runId") }
    row { dataButton(PRACTICE_15_LABEL, "ob:m15:$runId") }
    row { dataButton(PRACTICE_SKIP_LABEL, "ob:skip:$runId") }
}

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

internal fun onboardingReminderSaved(time: String): TextSourcesList = buildEntities {
    regular("Perfect. I'll remind you every day at $time 🔔\n\n")
    addAll(nextChatHint())
}

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
): TextSourcesList = buildEntities {
    bold("🎯 $LEVEL_TITLE")
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
    val text = levelText.trim()
    if (text.isNotEmpty()) {
        regularln("")
        regularln(text)
    }
    regularln("")
    regular(LEVEL_ESTIMATE)
}

internal fun grammarSlide(review: OnboardingReview): TextSourcesList =
    skillSlide("✍️ Grammar", review.grammar)

internal fun vocabularySlide(review: OnboardingReview): TextSourcesList =
    skillSlide("📚 Vocabulary", review.vocabulary, showSuggestions = true)

internal fun fluencySlide(review: OnboardingReview): TextSourcesList = buildEntities {
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

private fun skillSlide(title: String, skill: OnboardingSkill, showSuggestions: Boolean = false): TextSourcesList = buildEntities {
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
    } else if (showSuggestions && skill.suggestions.isNotEmpty()) {
        regularln("")
        bold("Another way to say it")
        regularln("")
        regularln("")
        skill.suggestions.take(3).forEachIndexed { index, suggestion ->
            if (index > 0) regular("\n\n")
            regularln(suggestion.original)
            regular("→ ")
            bold(suggestion.alternative)
            suggestion.explanation.trim().takeIf { it.isNotEmpty() }?.let { explanation ->
                regular("\n\n💡 ")
                italic(explanation)
            }
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

internal fun fluencyLines(fluency: OnboardingFluency): List<String> = buildList {
    fluency.paceWpm?.let { add("Speaking pace · $it words/min") }
    fluency.longPauses?.let { add("Long pauses · $it") }
    fluency.fillers?.takeIf { it > 0 }?.let { add("Detected filler words · $it") }
    fluency.longestStretchSec?.let { add("Longest stretch without a long pause · $it sec") }
}
