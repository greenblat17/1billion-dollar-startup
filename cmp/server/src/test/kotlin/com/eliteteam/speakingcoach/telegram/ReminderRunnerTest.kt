package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.ReminderReport
import com.eliteteam.speakingcoach.ai.ReminderSendResult
import com.eliteteam.speakingcoach.ai.ReminderTarget
import dev.inmo.tgbotapi.bot.exceptions.TooMuchRequestsException
import dev.inmo.tgbotapi.types.Response
import dev.inmo.tgbotapi.types.RetryAfterError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ReminderRunnerTest {

    private val day = LocalDate.of(2026, 9, 26)
    private val clock = { ZonedDateTime.of(day.atTime(19, 0), REMINDER_ZONE) }

    private class Blocked : RuntimeException("blocked")

    private class Limited : RuntimeException("limited")

    private val classify: (Throwable) -> SendFailure = { error ->
        when (error) {
            is Blocked -> SendFailure.Blocked
            is Limited -> SendFailure.RetryAfter(1.seconds)
            else -> SendFailure.Failed
        }
    }

    @Test
    fun classifiesEachSendAndReportsTheRound() = runTest {
        val reports = mutableListOf<ReminderReport>()
        val sent = mutableListOf<Pair<Long, String>>()
        var limitedOnce = false
        val runner = ReminderRunner(
            claim = {
                listOf(
                    ReminderTarget("tg-1", "Alex Green"),
                    ReminderTarget("tg-2"),
                    ReminderTarget("tg-3"),
                    ReminderTarget("app-4"),
                    ReminderTarget("tg-5"),
                )
            },
            report = { reports += it },
            send = { chatId, text ->
                when (chatId) {
                    2L -> throw Blocked()
                    3L -> error("boom")
                    5L -> if (!limitedOnce) {
                        limitedOnce = true
                        throw Limited()
                    }
                }
                sent += chatId to text
            },
            clock = clock,
            pause = Duration.ZERO,
            classify = classify,
        )

        val outcome = runner.runRound(ReminderMode.MANUAL)

        assertEquals(RoundOutcome(claimed = 5, sent = 2, blocked = 1, failed = 1), outcome)
        assertEquals(listOf(1L, 5L), sent.map { it.first })
        assertEquals(renderReminder(reminderTemplate(1, day), "Alex"), sent.first().second)
        val report = reports.single()
        assertEquals("manual", report.mode)
        assertEquals(5, report.claimed)
        assertEquals("2026-09-26T19:00+03:00", report.startedAt)
        assertEquals(
            listOf(
                ReminderSendResult("tg-1", reminderTemplate(1, day).id, "sent"),
                ReminderSendResult("tg-2", reminderTemplate(2, day).id, "blocked"),
                ReminderSendResult("tg-3", reminderTemplate(3, day).id, "failed"),
                ReminderSendResult("tg-5", reminderTemplate(5, day).id, "sent"),
            ),
            report.results,
        )
    }

    @Test
    fun secondRetryAfterGivesUp() = runTest {
        var attempts = 0
        val runner = ReminderRunner(
            claim = { listOf(ReminderTarget("tg-1")) },
            report = {},
            send = { _, _ ->
                attempts += 1
                throw Limited()
            },
            clock = clock,
            pause = Duration.ZERO,
            classify = classify,
        )

        assertEquals(1, runner.runRound(ReminderMode.AUTO)?.failed)
        assertEquals(2, attempts)
    }

    @Test
    fun busyRunnerReturnsNull() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runner = ReminderRunner(
            claim = {
                gate.await()
                emptyList()
            },
            report = {},
            send = { _, _ -> },
            clock = clock,
            pause = Duration.ZERO,
        )
        val first = async { runner.runRound(ReminderMode.AUTO) }
        testScheduler.runCurrent()

        assertTrue(runner.isRunning)
        assertNull(runner.runRound(ReminderMode.MANUAL))
        gate.complete(Unit)
        assertEquals(RoundOutcome(0, 0, 0, 0), first.await())
        assertFalse(runner.isRunning)
    }

    @Test
    fun testSendSkipsClaimAndReport() = runTest {
        val sent = mutableListOf<Pair<Long, String>>()
        val runner = ReminderRunner(
            claim = { error("claim must not run") },
            report = { error("report must not run") },
            send = { chatId, text -> sent += chatId to text },
            clock = clock,
            pause = Duration.ZERO,
        )

        assertTrue(runner.sendTest(42, null))
        assertTrue(runner.sendTest(42, "weekend_plan"))
        assertFalse(runner.sendTest(42, "missing"))
        assertEquals(
            listOf(
                42L to renderReminder(reminderTemplate(42, day), null),
                42L to renderReminder(reminderTemplateById("weekend_plan")!!, null),
            ),
            sent,
        )
    }

    @Test
    fun telegramRateLimitIsRetryAfter() {
        val error = TooMuchRequestsException(RetryAfterError(3, 0), Response(), "", "", null)
        assertEquals(SendFailure.RetryAfter(3.seconds), telegramSendFailure(error))
        assertEquals(SendFailure.Failed, telegramSendFailure(IllegalStateException()))
    }
}
