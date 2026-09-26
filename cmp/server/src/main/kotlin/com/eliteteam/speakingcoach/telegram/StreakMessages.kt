package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.StreakProfileResponse
import com.eliteteam.speakingcoach.speaking.TurnStreak

private val MILESTONES = mapOf(
    3 to "🔥 3 days in a row! You're building a habit.",
    7 to "🔥 One week streak! 7 days in a row.",
    14 to "🔥 Two weeks of English! 14 days in a row.",
    30 to "🏆 A whole month! 30 days in a row.",
    50 to "🏆 50 days in a row. Amazing!",
    100 to "🏆 100 days in a row. Amazing!",
)

internal fun streakMessage(update: TurnStreak): String {
    MILESTONES[update.current]?.let { return it }
    if (update.newRecord) {
        return "🔥 ${update.current} days in a row. New personal record!"
    }
    if (update.current == 1 && update.firstEver) {
        return "🔥 Day 1! Come back tomorrow to start your streak."
    }
    if (update.current == 1) {
        return "🔥 Day 1. See you tomorrow!"
    }
    return "🔥 ${update.current} days in a row! See you tomorrow."
}

internal fun streakProfileText(profile: StreakProfileResponse): String {
    val squares = profile.last7.joinToString("") { active -> if (active) "🟩" else "⬜" }
    val headline = if (profile.current > 0) {
        "🔥 Current streak: ${profile.current} days"
    } else {
        "No streak yet. Send me a voice message to start one!"
    }
    return if (squares.isEmpty()) headline else "$headline\n$squares"
}
