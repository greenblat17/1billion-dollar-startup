package com.eliteteam.speakingcoach.analytics

import com.eliteteam.speakingcoach.onboardingReportHtml
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostgresOnboardingJourneyTest {
    @Test
    fun shortResultCompletesTheV3JourneyWithoutTwoMinuteSpeech() = runTest {
        val url = System.getenv("TEST_POSTGRES_URL") ?: return@runTest
        val jdbcUrl = if (url.startsWith("jdbc:")) url else "jdbc:$url"
        val schema = "short_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(jdbcUrl).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val schemaUrl = jdbcUrl + (if ('?' in jdbcUrl) "&" else "?") + "currentSchema=$schema"
        try {
            val analytics = PostgresOnboardingAnalytics(schemaUrl)
            try {
                val start = Instant.parse("2026-10-08T07:00:00Z")
                val run = "short-run"
                analytics.recordEntry("tg-short", "start", start, true, "start", null, null, run,
                    start.plusSeconds(1))
                analytics.startAttempt("tg-short", run, "start", start, invitationAt = start.plusSeconds(1))
                analytics.mark(run, AttemptMark.BEGIN_PRESSED, start.plusSeconds(2))
                analytics.mark(run, AttemptMark.FIRST_QUESTION_DELIVERED, start.plusSeconds(3))
                analytics.mark(run, AttemptMark.LETS_CHAT, start.plusSeconds(3))
                analytics.recordVoice(run, "tg-short", "voice:1", OnboardingVoiceFacts(
                    recognized = true, outcome = "recognized", milestones = listOf(30),
                    speechBeforeSec = 0.0, speechAfterSec = 32.0,
                ), 100, start.plusSeconds(5), start.plusSeconds(4))
                analytics.event(run, "short:offer", "short_offer_delivered", start.plusSeconds(5))
                analytics.event(run, "short:chosen", "short_result_chosen", start.plusSeconds(6))
                analytics.recordOutcome(run, OnboardingVoiceFacts(
                    completedNow = true, cefr = "B1", overallScore = 52, scoreAvailable = true,
                ), start.plusSeconds(7))
                analytics.mark(run, AttemptMark.RESULT_DELIVERED, start.plusSeconds(8))
                analytics.mark(run, AttemptMark.RESULTS, start.plusSeconds(8))
                analytics.event(run, "short:delivered", "short_result_delivered", start.plusSeconds(8))
                analytics.mark(run, AttemptMark.PRACTICE, start.plusSeconds(9))
                analytics.markGoal(run, 5, start.plusSeconds(10))
                analytics.mark(run, AttemptMark.REMINDER_OFFERED, start.plusSeconds(11))
                analytics.markReminderDecision(run, "not_now", start.plusSeconds(12))

                val report = analytics.report(start.plusSeconds(3 * 86_400), OnboardingFilter(version = "v3"))
                assertEquals(0, report.journey.incomplete)
                assertEquals(1, report.journey.steps.first { it.stage.id == "result_delivered" }.reached)
                assertEquals(1, report.decisions.shortOffers)
                assertEquals(1, report.decisions.shortChoices)
                assertEquals(1, report.decisions.shortDelivered)
                assertEquals(0, report.decisions.fullSpeech)
                assertEquals(0, report.closedPrimary.single().skippedPrevious)
            } finally {
                analytics.close()
            }
        } finally {
            DriverManager.getConnection(jdbcUrl).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") }
        }
    }

    @Test
    fun postgresReportKeepsCohortErrorsAndRecentUsersTogether() = runTest {
        val url = System.getenv("TEST_POSTGRES_URL") ?: return@runTest
        val jdbcUrl = if (url.startsWith("jdbc:")) url else "jdbc:$url"
        val schema = "journey_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(jdbcUrl).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val schemaUrl = jdbcUrl + (if ('?' in jdbcUrl) "&" else "?") + "currentSchema=$schema"
        try {
            val analytics = PostgresOnboardingAnalytics(schemaUrl)
            try {
                val start = Instant.parse("2026-10-01T07:00:00Z")
                val now = start.plusSeconds(86_431)
                analytics.recordEntry("tg-1", "message:1", start, true, "start", null, "campaign", "run-1",
                    start.plusSeconds(2), 1, "speaker_one")
                analytics.startAttempt("tg-1", "run-1", "start", start.plusSeconds(3), "campaign", 1,
                    "speaker_one", start.plusSeconds(2))
                analytics.mark("run-1", AttemptMark.BEGIN_PRESSED, start.plusSeconds(4))
                analytics.mark("run-1", AttemptMark.FIRST_QUESTION_DELIVERED, start.plusSeconds(5))
                analytics.recordVoice("run-1", "tg-1", "message:2",
                    OnboardingVoiceFacts(outcome = "stt_failure", failureReason = "stt_failure",
                        failureStage = "stt", failureCode = "timeout", voiceIndex = 1),
                    200, start.plusSeconds(10), start.plusSeconds(6))
                analytics.recordVoice("run-1", "tg-1", "message:2",
                    OnboardingVoiceFacts(outcome = "stt_failure", failureReason = "stt_failure",
                        failureStage = "stt", failureCode = "network", voiceIndex = 1),
                    200, start.plusSeconds(11), start.plusSeconds(6))
                analytics.recordEntry("tg-2", "message:3", start.plusSeconds(30), true, "start", null,
                    "campaign", "missing", null, 2, "speaker_two", "network")
                val report = analytics.report(now, OnboardingFilter(source = "campaign"))
                assertEquals(2, report.journey.total)
                assertEquals(1, report.journey.incomplete)
                assertEquals(1, report.journey.steps.first { it.stage.id == "voice_received" }.stopped)
                assertEquals(1, report.journey.errors.first { it.reason == "timeout" }.events)
                assertEquals(1, report.journey.errors.first { it.reason == "network" }.events)
                assertEquals("speaker_two", report.journey.recent.first().username)
                assertEquals("incomplete", report.journey.recent.first().state)
                assertEquals("speaker_one", report.journey.recent.last().username)
                assertFalse(onboardingAgentJson(report, now).contains("speaker_one"))
            } finally {
                analytics.close()
            }
        } finally {
            DriverManager.getConnection(jdbcUrl).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") }
        }
    }

    @Test
    fun postgresTracksRecoveryAndFirstPracticeAfterFinalStep() = runTest {
        val url = System.getenv("TEST_POSTGRES_URL") ?: return@runTest
        val jdbcUrl = if (url.startsWith("jdbc:")) url else "jdbc:$url"
        val schema = "journey_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(jdbcUrl).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val schemaUrl = jdbcUrl + (if ('?' in jdbcUrl) "&" else "?") + "currentSchema=$schema"
        try {
            val analytics = PostgresOnboardingAnalytics(schemaUrl)
            try {
                val start = Instant.parse("2026-10-01T07:00:00Z")
                analytics.recordEntry("tg-1", "message:1", start, true, "start", null, "campaign", "run-1",
                    start.plusSeconds(2))
                analytics.startAttempt("tg-1", "run-1", "start", start.plusSeconds(1), "campaign",
                    invitationAt = start.plusSeconds(2))
                analytics.mark("run-1", AttemptMark.BEGIN_PRESSED, start.plusSeconds(5))
                analytics.event("run-1", "callback:1:attempt", "action_attempt", start.plusSeconds(6), "first_question")
                analytics.event("run-1", "callback:1:error", "stage_error", start.plusSeconds(8),
                    "first_question", "timeout")
                analytics.event("run-1", "callback:2:attempt", "action_attempt", start.plusSeconds(10), "first_question")
                analytics.mark("run-1", AttemptMark.FIRST_QUESTION_DELIVERED, start.plusSeconds(12))
                analytics.recordVoice("run-1", "tg-1", "message:2",
                    OnboardingVoiceFacts(recognized = true, outcome = "recognized",
                        milestones = listOf(30, 60, 90, 120)), 100, start.plusSeconds(14), start.plusSeconds(13))
                analytics.mark("run-1", AttemptMark.RESULT_DELIVERED, start.plusSeconds(15))
                analytics.mark("run-1", AttemptMark.RESULTS, start.plusSeconds(16))
                analytics.mark("run-1", AttemptMark.PRACTICE, start.plusSeconds(17))
                analytics.markGoal("run-1", 0, start.plusSeconds(18))
                analytics.mark("run-1", AttemptMark.REMINDER_OFFERED, start.plusSeconds(19))
                analytics.recordReturn("tg-1", start.plusSeconds(30))
                analytics.markReminderDecision("run-1", "not_now", start.plusSeconds(40))
                analytics.recordReturn("tg-1", start.plusSeconds(50))
                val report = analytics.report(start.plusSeconds(8 * 86_400), OnboardingFilter(source = "campaign"))
                assertEquals(JourneyPractice(1, 1, 1, 1, 1, 1), report.journey.practice)
                val error = report.journey.errors.single()
                assertEquals(1, error.retryMeasurable)
                assertEquals(1, error.retried)
                assertEquals(1, error.reachedNext)
                assertEquals(1, error.completed)
                val html = onboardingReportHtml(report)
                assertTrue(html.contains("Практика за 24 часа"))
                assertTrue(html.contains("1 / 1 · 100%"))
                val journey = Json.parseToJsonElement(onboardingAgentJson(report, start.plusSeconds(8 * 86_400)))
                    .jsonObject.getValue("journey").jsonObject
                val exportedError = journey.getValue("errors").jsonArray.single().jsonObject
                assertEquals("1", exportedError.getValue("retried").jsonPrimitive.content)
                assertEquals("1", exportedError.getValue("completed_after_error").jsonPrimitive.content)
                val practice = journey.getValue("post_completion_practice").jsonObject
                assertEquals("1", practice.getValue("within_24_hours").jsonObject.getValue("numerator").jsonPrimitive.content)
                assertEquals("1", practice.getValue("within_7_days").jsonObject.getValue("denominator").jsonPrimitive.content)
            } finally {
                analytics.close()
            }
        } finally {
            DriverManager.getConnection(jdbcUrl).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") }
        }
    }
}
