package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.CallReviewResponse
import com.eliteteam.speakingcoach.ai.OnboardingReview
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.replyKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.simpleButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.buttons.KeyboardButtonStyle
import dev.inmo.tgbotapi.types.message.textsources.TextSourcesList
import dev.inmo.tgbotapi.utils.bold
import dev.inmo.tgbotapi.utils.buildEntities
import dev.inmo.tgbotapi.utils.regular
import dev.inmo.tgbotapi.utils.regularln
import dev.inmo.tgbotapi.utils.row

internal const val CALL_CLOCK_CALLBACK = "call:clock"
internal const val CALL_END_CALLBACK = "call:end"
internal const val CALL_CLOCK_HINT =
    "This tracks how much English you've spoken today."
internal const val CALL_RETRY_TEXT = "Couldn't score this conversation. Try again."
internal const val CALL_REVIEW_WAIT_TEXT = "Thanks for the chat 💙\nI’m putting your feedback together now."
internal const val CALL_YESTERDAY_TEXT = "Yesterday's conversation is ready."
internal const val START_CALL_BUTTON = "🎙 Start call"
internal const val START_CALL_CONNECTING = "Speaky is joining the chat… 💙"
internal const val CALL_ALREADY_ACTIVE_TEXT = "We're already talking. Send me a voice message."

internal fun isStartCallButton(text: String): Boolean = text == START_CALL_BUTTON

internal fun startCallKeyboard() = replyKeyboard(resizeKeyboard = true, persistent = true) {
    row { simpleButton(START_CALL_BUTTON, style = KeyboardButtonStyle.Success) }
}

internal data class CallCallback(val action: String, val callId: String)

private val callSlideActions = setOf("grammar", "vocab", "fluency", "progress", "retry", "review", "end")

internal fun callGate(onboardingStatus: String, overallScore: Int?, dailyMinutes: Int?, legacyUser: Boolean = false): String = when (onboardingStatus) {
    "waiting", "active", "pending" -> "onboarding"
    else -> when {
        (legacyUser || onboardingStatus == "exempt") && overallScore == null -> "legacy"
        overallScore == null -> "need-onboarding"
        dailyMinutes == null -> "need-goal"
        else -> "open"
    }
}

internal fun callClockLabel(todaySeconds: Double, goalSeconds: Double): String {
    val elapsed = if (todaySeconds.isFinite() && todaySeconds > 0.0) todaySeconds.toInt() else 0
    val goal = if (goalSeconds.isFinite() && goalSeconds > 0.0) goalSeconds.toInt() else 0
    return if (goal > 0) "🎯 ${clock(elapsed)} / ${clock(goal)}" else "🎯 ${clock(elapsed)} today"
}

internal fun callGoalReached(goalSeconds: Double): String {
    val minutes = if (goalSeconds.isFinite() && goalSeconds > 0.0) goalSeconds.toInt() / 60 else 0
    return "That's your $minutes minutes today."
}

internal fun callKeyboard(todaySeconds: Double, goalSeconds: Double, spoken: Boolean,
                          callId: String? = null): InlineKeyboardMarkup = inlineKeyboard {
    row {
        dataButton(callClockLabel(todaySeconds, goalSeconds), CALL_CLOCK_CALLBACK)
        if (spoken) dataButton(SPOKEN_TEXT_BUTTON, SPOKEN_TEXT_CALLBACK)
    }
    row { dataButton("End call", callId?.let { "call:end:$it" } ?: CALL_END_CALLBACK,
        style = KeyboardButtonStyle.Danger) }
}

internal fun parseCallCallback(data: String): CallCallback? = when (data) {
    CALL_CLOCK_CALLBACK -> CallCallback("clock", "")
    CALL_END_CALLBACK -> CallCallback("end", "")
    "call:profile" -> CallCallback("profile", "")
    "call:bye" -> CallCallback("bye", "")
    else -> {
        val parts = data.split(':')
        if (parts.size != 3 || parts[0] != "call" || parts[1] !in callSlideActions) {
            null
        } else if (!Regex("[a-f0-9]{32}").matches(parts[2])) {
            null
        } else {
            CallCallback(parts[1], parts[2])
        }
    }
}

internal fun callSlideKeyboard(action: String, callId: String): InlineKeyboardMarkup = inlineKeyboard {
    row {
        val label = when (action) {
            "grammar" -> "See what I noticed →"
            "vocab" -> "Vocabulary →"
            "fluency" -> "Fluency →"
            else -> "Continue →"
        }
        dataButton(label, "call:$action:$callId")
    }
}

internal fun callRetryKeyboard(callId: String): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton("Retry", "call:retry:$callId") }
}

internal const val FIRST_CALL_FEEDBACK_PROMPT = "Как тебе этот разговор со Speaky?"
internal const val FIRST_CALL_FEEDBACK_LIKED_QUESTION = "Что тебе особенно понравилось?"
internal const val FIRST_CALL_FEEDBACK_IMPROVE_QUESTION = "Что можно улучшить?"
internal const val FIRST_CALL_FEEDBACK_THANKS = "Спасибо за отзыв 💙"

internal data class CallFeedbackCallback(val action: String, val callId: String, val choice: String = "")

internal fun parseCallFeedbackCallback(data: String): CallFeedbackCallback? {
    val parts = data.split(':')
    if (parts.size !in 3..4 || parts[0] != "callfb" || !Regex("[a-f0-9]{32}").matches(parts[2])) return null
    return when {
        parts.size == 4 && parts[1] == "rate" && parts[3] in setOf("liked", "neutral", "disliked") ->
            CallFeedbackCallback("rate", parts[2], parts[3])
        parts.size == 3 && parts[1] == "skip" -> CallFeedbackCallback("skip", parts[2])
        else -> null
    }
}

internal fun firstCallFeedbackKeyboard(callId: String): InlineKeyboardMarkup = inlineKeyboard {
    row {
        dataButton("👍 Понравился", "callfb:rate:$callId:liked")
        dataButton("😐 Так себе", "callfb:rate:$callId:neutral")
        dataButton("👎 Не понравился", "callfb:rate:$callId:disliked")
    }
}

internal fun firstCallFeedbackSkipKeyboard(callId: String): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton("Пропустить", "callfb:skip:$callId") }
}

internal fun callYesterdayKeyboard(callId: String): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton("Yesterday's results", "call:review:$callId") }
}

internal fun callProgressMessage(review: CallReviewResponse, offerReminder: Boolean = false): TextSourcesList = buildEntities {
    val today = if (review.goalSeconds > 0.0) " today" else ""
    bold("${callClockLabel(review.todaySeconds, review.goalSeconds)}$today")
    val recap = review.recap.trim()
    if (recap.isNotEmpty()) {
        regularln("")
        regularln("")
        regular(recap)
    }
    if (review.streak > 0) {
        regularln("")
        regularln("")
        regular("🔥 ${review.streak} day streak")
    }
    if (offerReminder) {
        regularln("")
        regularln("")
        regular(REMINDER_OFFER)
    }
}

internal fun CallReviewResponse.asOnboardingReview(): OnboardingReview =
    OnboardingReview(levelText, grammar, vocabulary, fluency)

private fun clock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
