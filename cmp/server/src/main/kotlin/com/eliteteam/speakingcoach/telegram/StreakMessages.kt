package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.StreakProfileResponse
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private val MOSCOW = ZoneId.of("Europe/Moscow")
private val ENGLISH = Locale.US

internal enum class WeekCell {
    Done,
    Missed,
    TodayOpen,
}

internal data class WeekDay(
    val label: String,
    val cell: WeekCell,
    val isToday: Boolean,
)

internal data class WeekStrip(
    val header: String,
    val days: List<WeekDay>,
)

private val WEEKDAY_LABELS = mapOf(
    DayOfWeek.MONDAY to "Mon",
    DayOfWeek.TUESDAY to "Tue",
    DayOfWeek.WEDNESDAY to "Wed",
    DayOfWeek.THURSDAY to "Thu",
    DayOfWeek.FRIDAY to "Fri",
    DayOfWeek.SATURDAY to "Sat",
    DayOfWeek.SUNDAY to "Sun",
)

internal fun weekStrip(
    last7: List<Boolean>,
    today: LocalDate = LocalDate.now(MOSCOW),
): WeekStrip {
    val active = if (last7.isEmpty()) {
        emptyMap()
    } else {
        last7.indices.associate { index ->
            today.minusDays((last7.lastIndex - index).toLong()) to last7[index]
        }
    }
    val start = today.minusDays(6)
    val days = (0..6).map { offset ->
        val day = start.plusDays(offset.toLong())
        val spoke = active[day] == true
        WeekDay(
            label = WEEKDAY_LABELS.getValue(day.dayOfWeek),
            cell = when {
                day == today && !spoke -> WeekCell.TodayOpen
                spoke -> WeekCell.Done
                else -> WeekCell.Missed
            },
            isToday = day == today,
        )
    }
    return WeekStrip(header = "${monthDay(start)} - ${monthDay(today)}", days = days)
}

internal fun streakKickoffCaption(current: Int): String = "Day $current. Glad you're here. Let's talk."

internal fun streakProfileCaption(profile: StreakProfileResponse): String = if (profile.current > 0) {
    val days = if (profile.current == 1) "day" else "days"
    "🔥 Current streak: ${profile.current} $days"
} else {
    "No streak yet. Send me a voice message to start one!"
}

private fun monthDay(date: LocalDate): String {
    val month = date.month.getDisplayName(TextStyle.SHORT, ENGLISH).uppercase(ENGLISH)
    return "$month ${date.dayOfMonth}"
}
