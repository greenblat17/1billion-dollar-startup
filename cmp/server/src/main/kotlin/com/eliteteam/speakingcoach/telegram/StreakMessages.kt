package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.StreakProfileResponse
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

private val MOSCOW = ZoneId.of("Europe/Moscow")
private val WEEKDAY = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
private const val LABEL_GAP = "  "
private const val SQUARE_GAP = " "

internal fun streakKickoffText(
    current: Int,
    last7: List<Boolean>,
    today: LocalDate = LocalDate.now(MOSCOW),
): String {
    val headline = "Day $current. Glad you're here. Let's talk."
    val grid = weekGrid(last7, today) ?: return escapeHtml(headline)
    return "${escapeHtml(headline)}\n\n<pre>$grid</pre>"
}

internal fun streakProfileText(
    profile: StreakProfileResponse,
    today: LocalDate = LocalDate.now(MOSCOW),
): String {
    val headline = if (profile.current > 0) {
        val days = if (profile.current == 1) "day" else "days"
        "🔥 Current streak: ${profile.current} $days"
    } else {
        "No streak yet. Send me a voice message to start one!"
    }
    val grid = weekGrid(profile.last7, today) ?: return escapeHtml(headline)
    return "${escapeHtml(headline)}\n\n<pre>$grid</pre>"
}

private fun weekGrid(days: List<Boolean>, today: LocalDate): String? {
    if (days.isEmpty()) {
        return null
    }
    val active = days.indices.associate { index ->
        today.minusDays((days.lastIndex - index).toLong()) to days[index]
    }
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val squares = (0..6).map { offset ->
        val day = monday.plusDays(offset.toLong())
        if (active[day] == true) "🟩" else "⬜"
    }
    return WEEKDAY.joinToString(LABEL_GAP) + "\n" + squares.joinToString(SQUARE_GAP)
}

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

