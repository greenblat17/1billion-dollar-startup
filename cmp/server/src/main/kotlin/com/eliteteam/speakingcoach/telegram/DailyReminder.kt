package com.eliteteam.speakingcoach.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

internal val REMINDER_ZONE: ZoneId = ZoneId.of("Europe/Moscow")

private val log = LoggerFactory.getLogger("DailyReminder")

// telegramSessionId(message.chat.id) stringifies tgbotapi ChatId, so live ids look like "tg-ChatId(chatId=123)".
private val telegramSessionPattern = Regex("""tg-(-?\d+)|tg-ChatId\(chatId=(-?\d+)\)""")

internal fun reminderChatId(sessionId: String): Long? {
    val match = telegramSessionPattern.matchEntire(sessionId) ?: return null
    return match.groupValues.drop(1).first { it.isNotEmpty() }.toLongOrNull()
}

internal fun CoroutineScope.launchDailyReminder(
    runner: ReminderRunner,
    clock: () -> ZonedDateTime = { ZonedDateTime.now(REMINDER_ZONE) },
    tick: Duration = 1.minutes,
): Job = launch {
    while (isActive) {
        val now = clock().withZoneSameInstant(REMINDER_ZONE)
        try {
            runner.runRound(ReminderMode.AUTO)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.error("Daily reminder round failed for {}", now.toLocalDate(), error)
        }
        delay(tick)
    }
}
