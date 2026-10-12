package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.analytics.OnboardingNudgeCandidate
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class OnboardingNudgeTest {
    private val day = LocalDate.parse("2026-10-06")
    private val candidate = OnboardingNudgeCandidate("tg-123", "run-1", 123, false)

    @Test
    fun sendsAtTwentyAndClaimsOnlyOncePerDay() = runTest {
        var now = ZonedDateTime.parse("2026-10-06T19:59:00+03:00[Europe/Moscow]")
        val claimed = mutableSetOf<LocalDate>()
        val sent = mutableListOf<String>()
        val runner = OnboardingNudgeRunner(
            candidates = { _, _ -> listOf(candidate) },
            state = { it.runId to "waiting" },
            claim = { _, date, _, _ -> claimed.add(date) },
            send = { _, action -> sent += action },
            clock = { now },
        )
        assertEquals(0, runner.runRound())
        now = now.plusMinutes(1)
        assertEquals(1, runner.runRound())
        assertEquals(0, runner.runRound())
        now = now.plusDays(1)
        assertEquals(1, runner.runRound())
        assertEquals(listOf("begin", "begin"), sent)
        assertEquals(setOf(day, day.plusDays(1)), claimed)
    }

    @Test
    fun skipsCompletedAndReplacedAttempts() = runTest {
        val sent = mutableListOf<String>()
        var state = "completed"
        var runId = candidate.runId
        val runner = OnboardingNudgeRunner(
            candidates = { _, _ -> listOf(candidate) },
            state = { runId to state },
            claim = { _, _, _, _ -> true },
            send = { _, action -> sent += action },
            clock = { ZonedDateTime.parse("2026-10-06T20:00:00+03:00[Europe/Moscow]") },
        )
        assertEquals(0, runner.runRound())
        state = "waiting"
        runId = "new-run"
        assertEquals(0, runner.runRound())
        assertEquals(emptyList(), sent)
    }

    @Test
    fun resumesCurrentStep() {
        assertEquals("begin", nudgeAction("waiting", false))
        assertEquals("voice", nudgeAction("active", true))
        assertEquals("retry", nudgeAction("pending", true))
        assertEquals(null, nudgeAction("completed", true))
    }
}
