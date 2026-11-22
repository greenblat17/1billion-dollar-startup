package com.eliteteam.speakingcoach.foundation

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonObject

internal val scheduledNoticeKinds = setOf("morning", "reminder", "invitation")

/** Agreed lesson only. Morning is never caught up after its one-minute dispatch window. */
internal fun trialReminderTimes(lesson: JsonObject): Map<String, Instant> {
    val start = Instant.parse(lesson.text("starts_at"))
    val agreed = Instant.parse(lesson.text("updated_at"))
    val zone = ZoneId.of(lesson.text("timezone"))
    val localMorning = start.atZone(zone).toLocalDate().atTime(LocalTime.of(8, 0))
    val offsets = zone.rules.getValidOffsets(localMorning)
    val morning = offsets.singleOrNull()?.let { localMorning.toInstant(it) }
    return buildMap {
        // A now booking needs only its invitation, not an immediate reminder as well.
        if (agreed < start.minusSeconds(3600)) put("reminder", start.minusSeconds(3600))
        if (morning != null && agreed < morning && morning < start && morning != start.minusSeconds(3600)) {
            put("morning", morning)
        }
        put("invitation", start)
    }
}

internal fun scheduledNoticeApplies(kind: String, due: Instant, start: Instant, now: Instant): Boolean =
    now >= due && when (kind) {
        "morning" -> now < due.plusSeconds(60) && now < start
        "reminder" -> now < start
        "invitation" -> now >= start && now < start.plusSeconds(300)
        else -> false
    }

internal fun scheduledNoticeText(kind: String, lesson: JsonObject): String {
    val time = Instant.parse(lesson.text("starts_at")).atZone(ZoneId.of(lesson.text("timezone")))
        .format(DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.forLanguageTag("ru")))
    val details = "$time (${lesson.text("timezone")}), ${lesson.number("duration_minutes")} минут."
    return when (kind) {
        "morning" -> "Сегодня у нас бесплатное пробное занятие: $details До встречи!"
        "reminder" -> "Напоминаю о нашем занятии: $details До скорого 🙂"
        else -> "Я готов! Можем начинать наш урок 👋"
    }
}
