package com.eliteteam.speakingcoach.analytics

import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostgresVoiceAttemptsTest {
    @Test
    fun flywayWriteIsIdempotentAndReportReadsTimings() = runTest {
        val url = System.getenv("TEST_POSTGRES_URL") ?: return@runTest
        val root = if (url.startsWith("jdbc:")) url else "jdbc:$url"
        val schema = "voice_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(root).use { it.createStatement().use { statement -> statement.execute("CREATE SCHEMA $schema") } }
        val schemaUrl = root + (if ('?' in root) "&" else "?") + "currentSchema=$schema"
        try {
            val store = PostgresVoiceAttemptStore(schemaUrl)
            try {
                val now = Instant.now()
                val started = VoiceAttempt(99123, 1, now.minusSeconds(10), eligible = true, chatQueueMs = 25)
                store.record(started)
                store.record(started.copy(outcome = "ai_failed", stage = "stt", reason = "network", totalMs = 200,
                    sttMs = 100, jobId = "job-123"))
                store.record(started.copy(outcome = "delivered", totalMs = 50))
                val report = store.report(now)
                assertEquals(1, report.days.first().failed)
                assertEquals(0, report.days.first().delivered)
                assertEquals(25, report.latencies.single { it.stage == "chat_queue" }.p95Ms)
                assertEquals(100, report.latencies.single { it.stage == "stt" }.p95Ms)
                assertEquals("job-123", report.recent.single().jobId)
                assertTrue(report.recent.single().attemptId.isNotBlank())
                store.record(VoiceAttempt(99123, 2, now.minusSeconds(31L * 86_400), eligible = true,
                    outcome = "ai_failed"))
                store.prune(now)
                DriverManager.getConnection(schemaUrl).use { connection ->
                    connection.prepareStatement("SELECT count(*) FROM telegram_voice_attempts WHERE message_id = 2").use { statement ->
                        statement.executeQuery().use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
                    }
                }
            } finally { store.close() }
        } finally {
            DriverManager.getConnection(root).use { it.createStatement().use { statement -> statement.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
}
