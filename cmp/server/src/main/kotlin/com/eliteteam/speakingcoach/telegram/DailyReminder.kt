package com.eliteteam.speakingcoach.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

internal val REMINDER_ZONE: ZoneId = ZoneId.of("Europe/Moscow")
internal val REMINDER_WINDOW_START: LocalTime = LocalTime.of(19, 0)
internal val REMINDER_WINDOW_END: LocalTime = LocalTime.of(21, 0)

private val log = LoggerFactory.getLogger("DailyReminder")

internal fun shouldRunReminder(now: ZonedDateTime, lastRunDay: LocalDate?): Boolean {
    val local = now.withZoneSameInstant(REMINDER_ZONE)
    val time = local.toLocalTime()
    return time >= REMINDER_WINDOW_START && time < REMINDER_WINDOW_END && local.toLocalDate() != lastRunDay
}

internal fun reminderChatId(sessionId: String): Long? =
    sessionId.removePrefix("tg-").takeIf { it != sessionId }?.toLongOrNull()

internal fun CoroutineScope.launchDailyReminder(
    runner: ReminderRunner,
    clock: () -> ZonedDateTime = { ZonedDateTime.now(REMINDER_ZONE) },
    tick: Duration = 1.minutes,
): Job = launch {
    var lastRunDay: LocalDate? = null
    while (isActive) {
        val now = clock().withZoneSameInstant(REMINDER_ZONE)
        if (shouldRunReminder(now, lastRunDay)) {
            try {
                if (runner.runRound(ReminderMode.AUTO) != null) {
                    lastRunDay = now.toLocalDate()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error("Daily reminder round failed for {}", now.toLocalDate(), error)
            }
        }
        delay(tick)
    }
}
