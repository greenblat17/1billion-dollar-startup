package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.StreakProfileResponse
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

private val MOSCOW = ZoneId.of("Europe/Moscow")
private val WEEKDAY = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

internal fun streakKickoffText(
    current: Int,
    last7: List<Boolean>,
    today: LocalDate = LocalDate.now(MOSCOW),
): String {
    val headline = "Day $current. Glad you're here. Let's talk."
    val grid = weekGrid(last7, today) ?: return headline
    return "$headline\n$grid"
}

internal fun streakProfileText(
    profile: StreakProfileResponse,
    today: LocalDate = LocalDate.now(MOSCOW),
): String {
    val headline = if (profile.current > 0) {
        "🔥 Current streak: ${profile.current} days"
    } else {
        "No streak yet. Send me a voice message to start one!"
    }
    val grid = weekGrid(profile.last7, today) ?: return headline
    return "$headline\n$grid"
}

private fun weekGrid(days: List<Boolean>, today: LocalDate): String? {
    if (days.isEmpty()) {
        return null
    }
    val active = days.indices.associate { index ->
        today.minusDays((days.lastIndex - index).toLong()) to days[index]
    }
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return (0..6).map { offset ->
        val day = monday.plusDays(offset.toLong())
        val square = if (active[day] == true) "🟩" else "⬜"
        "${WEEKDAY[offset]} $square"
    }.chunked(4).joinToString("\n") { row -> row.joinToString("  ") }
}
