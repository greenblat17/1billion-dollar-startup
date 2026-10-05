package com.eliteteam.speakingcoach.analytics

import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PostgresOnboardingJourneyTest {
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
}
