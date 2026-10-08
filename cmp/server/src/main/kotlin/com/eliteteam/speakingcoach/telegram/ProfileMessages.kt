package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.ProgressProfileResponse
import dev.inmo.tgbotapi.types.message.textsources.TextSourcesList
import dev.inmo.tgbotapi.utils.bold
import dev.inmo.tgbotapi.utils.buildEntities
import dev.inmo.tgbotapi.utils.regular
import dev.inmo.tgbotapi.utils.regularln

internal const val PROFILE_STREAK_CALLBACK = "profile:streak"

internal fun profileMessage(firstName: String?, profile: ProgressProfileResponse): TextSourcesList = buildEntities {
    bold("👤 ${firstName?.trim()?.takeIf { it.isNotEmpty() } ?: "Your profile"}")
    regularln("")
    regularln("")
    bold("🎯 English level")
    regularln("")
    val assessment = profile.assessment
    if (assessment == null) {
        regularln("Complete /onboarding to get your first assessment.")
    } else {
        regularln(if (assessment.preliminary) "Latest assessment · preliminary" else "Latest assessment")
        val cefr = assessment.cefr
        if (cefr == null) {
            regularln(LEVEL_UNKNOWN)
        } else {
            val score = assessment.overallScore?.let { " · $it/100" }.orEmpty()
            bold("$cefr$score")
            regularln("")
        }
        if (!assessment.preliminary && assessment.nextBand != null && assessment.pointsToNext != null) {
            regularln("✨ ${assessment.pointsToNext} points to ${assessment.nextBand}")
        }
        if (!assessment.preliminary) {
            regularln("")
            regularln("✍️ Grammar · ${profileScore(assessment.grammar)}")
            regularln("📚 Vocabulary · ${profileScore(assessment.vocabulary)}")
            regularln("🎙 Fluency · ${profileScore(assessment.fluency)}")
        }
    }
    regularln("")
    bold("⏱ Daily goal")
    regularln("")
    regularln(profile.dailyMinutes?.takeIf { it > 0 }?.let { "$it min/day" } ?: "Not set yet")
    regularln("")
    bold("🔥 Current streak")
    regularln("")
    val days = profile.currentStreak.coerceAtLeast(0)
    regular("$days ${if (days == 1) "day" else "days"}")
}

private fun profileScore(score: Int?): String = score?.let { "$it/100" } ?: "Not enough evidence yet"
