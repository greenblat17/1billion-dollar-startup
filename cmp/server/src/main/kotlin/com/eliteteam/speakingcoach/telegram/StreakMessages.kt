package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.StreakProfileResponse
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

private val MOSCOW = ZoneId.of("Europe/Moscow")

internal enum class WeekCell {
    Done,
    Missed,
    TodayOpen,
    Future,
}

internal val WEEKDAY_LABELS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

internal fun weekCells(
    last7: List<Boolean>,
    today: LocalDate = LocalDate.now(MOSCOW),
): List<WeekCell> {
    val active = if (last7.isEmpty()) {
        emptyMap()
    } else {
        last7.indices.associate { index ->
            today.minusDays((last7.lastIndex - index).toLong()) to last7[index]
        }
    }
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return (0..6).map { offset ->
        val day = monday.plusDays(offset.toLong())
        val spoke = active[day] == true
        when {
            day.isAfter(today) -> WeekCell.Future
            day == today && !spoke -> WeekCell.TodayOpen
            spoke -> WeekCell.Done
            else -> WeekCell.Missed
        }
    }
}

internal fun streakKickoffCaption(current: Int): String = "Day $current. Glad you're here. Let's talk."

internal fun streakProfileCaption(profile: StreakProfileResponse): String = if (profile.current > 0) {
    val days = if (profile.current == 1) "day" else "days"
    "Current streak: ${profile.current} $days"
} else {
    "No streak yet. Send me a voice message to start one!"
}
