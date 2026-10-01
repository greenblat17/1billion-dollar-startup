package com.eliteteam.speakingcoach.analytics

import com.eliteteam.speakingcoach.onboardingReportHtml
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingAnalyticsTest {
    private val start = Instant.parse("2026-09-28T07:00:00Z")
    private val closedNow = Instant.parse("2026-09-30T00:00:00Z")

    @Test
    fun closedCohortKeepsTheFirstStartAndIgnoresALateResult() {
        val primary = sample(runId = "primary", sessionId = "tg-1", primary = true).copy(
            letsChatAt = start.plusSeconds(60),
            firstVoiceAt = start.plus(Duration.ofHours(2)),
            completedAt = start.plus(Duration.ofHours(30)),
            d1VoiceAt = Instant.parse("2026-09-29T10:00:00Z"),
        )
        val repeat = sample(runId = "repeat", sessionId = "tg-1", primary = false)
        val restart = sample(runId = "restart", sessionId = "tg-2", primary = false, trigger = "automatic_restart")
        val report = onboardingReport(listOf(primary, repeat, restart), emptyList(), closedNow)
        val closed = report.closedPrimary.single()
        assertEquals(1, count(closed, "Приветствие"))
        assertEquals(1, count(closed, "Let’s chat"))
        assertEquals(1, count(closed, "Первое голосовое"))
        assertEquals(0, count(closed, "Результат собран"))
        assertEquals(1, closed.returnedNextDay)
        assertEquals(100, closed.returnedNextDayPercent)
        assertEquals(2, report.closedRepeats.single().steps.first().count)
        assertTrue(report.closedPrimary.none { it.steps.first().count == 2 })
    }

    @Test
    fun yesterdaysCohortStaysOpenUntilTheNextDayEnds() {
        val now = Instant.parse("2026-09-29T08:00:00Z")
        val report = onboardingReport(listOf(sample(runId = "primary", sessionId = "tg-1", primary = true)), emptyList(), now)
        assertTrue(report.closedPrimary.isEmpty())
        assertEquals(1, report.openPrimary.single().steps.first().count)
    }

    @Test
    fun sameDayVoiceIsNotAReturnAndARestartIsNotPrimary() {
        val primary = sample(runId = "primary", sessionId = "tg-1", primary = true).copy(
            d1VoiceAt = start.plus(Duration.ofHours(3)),
        )
        val report = onboardingReport(listOf(primary), emptyList(), closedNow)
        assertEquals(0, report.closedPrimary.single().returnedNextDay)
    }

    @Test
    fun errorsUseADenominatorAndCountPeople() {
        val now = closedNow
        val voices = listOf(
            voice("tg-1", "no_speech", now.minus(Duration.ofDays(1)), "ok"),
            voice("tg-1", null, now.minus(Duration.ofDays(1)), "ok"),
            voice("tg-2", "stt_error", now.minus(Duration.ofDays(20))),
        )
        val reached = sample(runId = "ok", sessionId = "tg-1", primary = true).copy(speech120At = now.minus(Duration.ofDays(1)))
        val failed = sample(runId = "bad", sessionId = "tg-3", primary = true).copy(
            speech120At = now.minus(Duration.ofDays(1)),
            assessmentFailedAt = now.minus(Duration.ofDays(1)),
        )
        val report = onboardingReport(listOf(reached, failed), voices, now)
        assertEquals(1, report.recognition.errors)
        assertEquals(2, report.recognition.denominator)
        assertEquals(1, report.recognition.people)
        assertEquals(50, report.recognition.percent)
        assertEquals(1, report.assessment.errors)
        assertEquals(2, report.assessment.denominator)
        assertEquals(1, report.assessment.people)
    }

    @Test
    fun repeatedWritesDoNotMoveTheFirstStepOrCountTheVoiceTwice() = runTest {
        val analytics = MemoryOnboardingAnalytics()
        analytics.startAttempt("tg-1", "run-1", "start", start)
        analytics.startAttempt("tg-1", "run-1", "start", start.plusSeconds(10))
        analytics.mark("run-1", AttemptMark.LETS_CHAT, start.plusSeconds(30))
        analytics.mark("run-1", AttemptMark.LETS_CHAT, start.plusSeconds(90))
        val facts = OnboardingVoiceFacts(voiceIndex = 1, recognized = true, milestones = listOf(30), recognizedDurationSec = 30.0)
        analytics.recordVoice("run-1", "tg-1", "message:1", facts, 400, start.plusSeconds(40))
        analytics.recordVoice("run-1", "tg-1", "message:1", facts, 900, start.plusSeconds(50))
        analytics.mark("run-1", AttemptMark.COMPLETED, start.plusSeconds(40))
        analytics.startAttempt("tg-1", "run-2", "onboarding_command", start.plusSeconds(5))
        analytics.recordReturn("tg-1", start.plus(Duration.ofHours(3)))
        analytics.recordReturn("tg-1", Instant.parse("2026-09-29T10:00:00Z"))
        val report = analytics.report(closedNow)
        val attempt = report.closedPrimary.single()
        assertEquals(1, count(attempt, "Let’s chat"))
        assertEquals(1, report.recognition.denominator)
        assertEquals(1, attempt.returnedNextDay)
        assertEquals(1, report.closedRepeats.single().steps.first().count)
    }

    @Test
    fun pageShowsCountsPercentsAndTheEmptyDatabase() {
        val report = onboardingReport(
            listOf(
                sample(runId = "primary", sessionId = "tg-1", primary = true).copy(
                    letsChatAt = start.plusSeconds(10),
                    goalSelectedAt = start.plusSeconds(20),
                    goalMinutes = 10,
                    completedAt = start.plusSeconds(30),
                    cefr = null,
                ),
            ),
            listOf(voice("tg-1", "no_speech", closedNow.minus(Duration.ofHours(1)), "primary")),
            closedNow,
        )
        val html = onboardingReportHtml(report)
        assertTrue(html.contains("1"))
        assertTrue(html.contains("100%"))
        assertTrue(html.contains("нет уровня 1"))
        assertTrue(html.contains("10 мин 1"))
        assertTrue(html.contains("Не распознано: 1 из 1 голосовых (100%), затронуто 1"))
        assertTrue(onboardingReportHtml(null).contains("История онбординга не пишется: нет базы."))
        assertNull(report.closedPrimary.single().levels["A1"])
    }

    @Test
    fun filtersSeparateVersionsSourcesAndFailureStages() {
        val old = sample("old", "tg-old", true).copy(source = null, version = "v1")
        val current = sample("current", "tg-current", true).copy(source = "campaign", version = "v2")
        val voices = listOf(
            voice("tg-current", "no_speech", start.plusSeconds(10), "current")
                .copy(speechBeforeSec = 20.0),
            voice("tg-current", "processing_failure", start.plusSeconds(20), "current")
                .copy(speechBeforeSec = 40.0),
            voice("tg-current", null, start.plusSeconds(30), "current")
                .copy(speechBeforeSec = 60.0, processingMs = 100),
            voice("tg-current", null, start.plusSeconds(40), "current")
                .copy(speechBeforeSec = 90.0, processingMs = 900),
        )
        val report = onboardingReport(listOf(old, current), voices, closedNow,
            OnboardingFilter(version = "v2", source = "campaign"))
        assertEquals(1, report.closedPrimary.single().size)
        assertEquals(1, report.recognition.errors)
        assertEquals(4, report.recognition.denominator)
        assertEquals(1, report.diagnostics.outcomesByStage["30–60 сек"]?.get("processing_failure"))
        assertEquals(100, report.diagnostics.processingP50Ms)
        assertEquals(900, report.diagnostics.processingP95Ms)
        assertEquals(listOf("direct", "campaign"), report.sources.sortedDescending())
    }

    @Test
    fun daySevenReturnAppearsOnlyForTheCorrectMoscowDayAndMatureCohort() {
        val returned = sample("returned", "tg-returned", true).copy(
            completedAt = start.plusSeconds(100),
            d1VoiceAt = Instant.parse("2026-09-29T08:00:00Z"),
            d7VoiceAt = Instant.parse("2026-10-05T08:00:00Z"),
        )
        val wrongDay = sample("wrong", "tg-wrong", true).copy(
            d1VoiceAt = Instant.parse("2026-09-30T08:00:00Z"),
            d7VoiceAt = Instant.parse("2026-10-04T08:00:00Z"),
        )
        val immature = onboardingReport(listOf(returned, wrongDay), emptyList(),
            Instant.parse("2026-10-05T10:00:00Z")).closedPrimary.single()
        assertEquals(0, immature.d7Eligible)
        assertEquals(1, immature.returnedNextDay)
        val mature = onboardingReport(listOf(returned, wrongDay), emptyList(),
            Instant.parse("2026-10-06T00:00:00Z")).closedPrimary.single()
        assertEquals(2, mature.d7Eligible)
        assertEquals(1, mature.returnedDay7)
        assertEquals(1, mature.completedByD7)
    }

    private fun count(cohort: CohortFunnel, name: String): Int = cohort.steps.first { it.name == name }.count

    private fun sample(
        runId: String,
        sessionId: String,
        primary: Boolean,
        trigger: String = if (primary) "start" else "onboarding_command",
    ) = OnboardingAttemptRow(
        runId = runId,
        sessionId = sessionId,
        trigger = trigger,
        isPrimary = primary,
        startedAt = start,
    )

    private fun voice(sessionId: String, failure: String?, at: Instant, attemptId: String = "voice-$sessionId") = OnboardingVoiceRow(
        attemptId = attemptId,
        sessionId = sessionId,
        recognized = failure == null,
        failureReason = failure,
        createdAt = at,
    )
}
