package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.CallReviewResponse
import com.eliteteam.speakingcoach.ai.OnboardingReview
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.message.textsources.TextSourcesList
import dev.inmo.tgbotapi.utils.bold
import dev.inmo.tgbotapi.utils.buildEntities
import dev.inmo.tgbotapi.utils.regular
import dev.inmo.tgbotapi.utils.regularln
import dev.inmo.tgbotapi.utils.row

internal const val CALL_CLOCK_CALLBACK = "call:clock"
internal const val CALL_END_CALLBACK = "call:end"
internal const val CALL_CLOCK_HINT =
    "This tracks how much English you've spoken today, toward your daily goal."
internal const val CALL_RETRY_TEXT = "Couldn't score this conversation. Try again."
internal const val CALL_YESTERDAY_TEXT = "Yesterday's conversation is ready."

internal data class CallCallback(val action: String, val callId: String)

private val callSlideActions = setOf("grammar", "vocab", "fluency", "progress", "retry", "review")

internal fun callGate(onboardingStatus: String, overallScore: Int?, dailyMinutes: Int?): String = when (onboardingStatus) {
    "waiting", "active", "pending" -> "onboarding"
    else -> when {
        overallScore == null -> "need-onboarding"
        dailyMinutes == null -> "need-goal"
        else -> "open"
    }
}

internal fun callClockLabel(todaySeconds: Double, goalSeconds: Double): String {
    val elapsed = if (todaySeconds.isFinite() && todaySeconds > 0.0) todaySeconds.toInt() else 0
    val goal = if (goalSeconds.isFinite() && goalSeconds > 0.0) goalSeconds.toInt() else 0
    return "🎯 ${clock(elapsed)} / ${clock(goal)}"
}

internal fun callGoalReached(goalSeconds: Double): String {
    val minutes = if (goalSeconds.isFinite() && goalSeconds > 0.0) goalSeconds.toInt() / 60 else 0
    return "That's your $minutes minutes today."
}

internal fun callKeyboard(todaySeconds: Double, goalSeconds: Double, spoken: Boolean): InlineKeyboardMarkup = inlineKeyboard {
    row {
        dataButton(callClockLabel(todaySeconds, goalSeconds), CALL_CLOCK_CALLBACK)
        if (spoken) dataButton(SPOKEN_TEXT_BUTTON, SPOKEN_TEXT_CALLBACK)
    }
    row { dataButton("End conversation 📞", CALL_END_CALLBACK) }
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

internal fun callYesterdayKeyboard(callId: String): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton("Yesterday's results", "call:review:$callId") }
}

internal fun callReturnKeyboard(): InlineKeyboardMarkup = inlineKeyboard {
    row {
        dataButton("Profile", "call:profile")
        dataButton("Finish for today", "call:bye")
    }
}

internal fun callProgressMessage(review: CallReviewResponse, offerReminder: Boolean = false): TextSourcesList = buildEntities {
    bold("${callClockLabel(review.todaySeconds, review.goalSeconds)} today")
    val current = review.overallScore
    val previous = review.previousScore
    when {
        current != null && previous != null && current > previous -> {
            regularln("")
            bold("$previous → $current ↑")
            regularln("")
            regular("Nice — your speaking score went up.")
        }
        current != null && previous != null && current < previous -> {
            regularln("")
            bold("$previous → $current ↓")
            regularln("")
            regular("This one came out a little lower.")
            val reason = review.levelText.trim()
            if (reason.isNotEmpty()) {
                regularln("")
                regular(reason)
            }
        }
        current != null -> {
            regularln("")
            regular("Speaking score: $current")
        }
    }
    val band = cefrBandName(review.cefr)
    if (review.cefr != null && band != null) {
        regularln("")
        regular("${review.cefr} · $band")
    }
    if (review.streak > 0) {
        regularln("")
        regular("🔥 ${review.streak} day streak")
    }
    if (offerReminder) {
        regularln("")
        regular(REMINDER_OFFER)
    }
}

internal fun CallReviewResponse.asOnboardingReview(): OnboardingReview =
    OnboardingReview(levelText, grammar, vocabulary, fluency)

private fun clock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
