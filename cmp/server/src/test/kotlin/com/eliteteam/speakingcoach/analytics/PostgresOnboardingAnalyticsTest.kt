package com.eliteteam.speakingcoach.analytics

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import kotlinx.coroutines.test.runTest
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Set TEST_POSTGRES_URL to a disposable Postgres database to exercise Flyway and JDBC writes. */
class PostgresOnboardingAnalyticsTest {
    @Test
    fun migrationPreservesLegacyVersionAndClassifiesOldFailuresAsUnknown() = runTest {
        val url = System.getenv("TEST_POSTGRES_URL") ?: return@runTest
        val schema = "analytics_${UUID.randomUUID().toString().replace("-", "")}"
        val jdbcUrl = if (url.startsWith("jdbc:")) url else "jdbc:$url"
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        val schemaUrl = jdbcUrl + (if ('?' in jdbcUrl) "&" else "?") + "currentSchema=$schema"
        try {
            Flyway.configure().dataSource(schemaUrl, null, null)
                .schemas(schema).target(MigrationVersion.fromVersion("2")).load().migrate()
            DriverManager.getConnection(schemaUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("""
                        INSERT INTO onboarding_attempts
                            (run_id, session_id, attempt_number, trigger, is_primary, started_at)
                        VALUES ('legacy', 'legacy-user', 1, 'start', TRUE, '2026-09-28 07:00:00+00')
                    """.trimIndent())
                    statement.execute("""
                        INSERT INTO onboarding_voices
                            (attempt_id, request_id, session_id, voice_index, recognized, failure_reason,
                             processing_ms, created_at)
                        VALUES ('legacy', 'voice:1', 'legacy-user', 1, FALSE, 'stt_error',
                                250, '2026-09-28 07:01:00+00')
                    """.trimIndent())
                }
            }
            val analytics = PostgresOnboardingAnalytics(schemaUrl)
            try {
                val report = analytics.report(Instant.parse("2026-10-01T00:00:00Z"),
                    OnboardingFilter(version = "v1"))
                assertEquals(1, report.closedPrimary.single().size)
                assertEquals(1, report.diagnostics.outcomes["unknown"])
                assertEquals(0, report.decisions.resultDelivered)
            } finally {
                analytics.close()
            }
        } finally {
            DriverManager.getConnection(jdbcUrl).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    @Test
    fun migrationAndIdempotentVoiceWriteProduceTheReport() = runTest {
        val url = System.getenv("TEST_POSTGRES_URL") ?: return@runTest
        val runId = UUID.randomUUID().toString()
        val sessionId = "test-${UUID.randomUUID()}"
        val source = "test_${UUID.randomUUID()}"
        val at = Instant.parse("2026-09-28T07:00:00Z")
        val analytics = PostgresOnboardingAnalytics(url)
        try {
            analytics.startAttempt(sessionId, runId, "start", at, source)
            analytics.mark(runId, AttemptMark.LETS_CHAT, at.plusSeconds(10))
            val facts = OnboardingVoiceFacts(
                voiceIndex = 1,
                recognized = true,
                telegramDurationSec = 121.0,
                recognizedDurationSec = 121.0,
                milestones = listOf(30, 60, 90, 120),
                speechBeforeSec = 0.0,
                speechAfterSec = 121.0,
                completedNow = true,
                cefr = "B1",
                overallScore = 52,
                scoreAvailable = true,
                grammarExamples = 1,
                vocabularyExamples = 0,
                fluencyMetricsAvailable = true,
            )
            analytics.recordVoice(runId, sessionId, "message:1", facts, 750, at.plusSeconds(30), at.plusSeconds(20))
            analytics.recordVoice(runId, sessionId, "message:1", facts, 900, at.plusSeconds(40), at.plusSeconds(21))
            analytics.mark(runId, AttemptMark.RESULT_DELIVERED, at.plusSeconds(31))
            analytics.event(runId, "retry:1", "retry_requested", at.plusSeconds(32))
            analytics.event(runId, "retry:1", "retry_requested", at.plusSeconds(33))
            analytics.recordReturn(sessionId, Instant.parse("2026-09-29T10:00:00Z"))
            val report = analytics.report(Instant.parse("2026-10-01T00:00:00Z"), OnboardingFilter(version = "v2", source = source))
            val cohort = report.closedPrimary.single { it.day.toString() == "2026-09-28" }
            assertEquals(1, cohort.size)
            assertEquals(1, cohort.returnedNextDay)
            assertEquals(1, report.diagnostics.outcomes["recognized"])
            assertEquals(1, report.decisions.resultDelivered)
            assertEquals(1, report.decisions.grammarExamplesShown)
            assertEquals(1, report.decisions.retryRequested)
            DriverManager.getConnection(if (url.startsWith("jdbc:")) url else "jdbc:$url").use { connection ->
                connection.prepareStatement("SELECT COUNT(*), MIN(processing_ms) FROM onboarding_voices WHERE attempt_id = ?").use { statement ->
                    statement.setString(1, runId)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(1, rows.getInt(1))
                        assertEquals(750, rows.getInt(2))
                    }
                }
            }
        } finally {
            analytics.close()
        }
    }
}
