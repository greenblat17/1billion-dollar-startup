package com.eliteteam.speakingcoach.analytics

import com.eliteteam.speakingcoach.onboardingNudgeHtml
import kotlinx.coroutines.test.runTest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostgresNudgeReportTest {
    @Test
    fun countsDeliveryMatureWindowsAndUniqueCohortsWithoutDoubleAttribution() = runTest {
        withDatabase { store, connection ->
            val first = Instant.parse("2026-10-10T17:00:00Z")
            val second = first.plusSeconds(23 * 3600)
            val now = first.plusSeconds(8 * 86_400)
            val session = "tg-123"
            store.startAttempt(session, "run", "start", first.minusSeconds(3 * 86_400),
                chatId = 123, invitationAt = first.minusSeconds(3 * 86_400))
            // Legacy claims stay unknown and count towards the attempt ordinal.
            connection.createStatement().use { it.execute("""
                INSERT INTO onboarding_nudges(session_id, run_id, day, claimed_at)
                VALUES ('tg-123', 'run', '2026-10-09', '2026-10-09T17:00:00Z')
            """.trimIndent()) }
            suspend fun claim(at: Instant) {
                assertTrue(store.claimNudge("run", at.atZone(ONBOARDING_ZONE).toLocalDate(),
                    at.minusSeconds(86_400), at, "begin"))
                store.recordNudgeOutcome(NudgeOutcome("run", at.atZone(ONBOARDING_ZONE).toLocalDate(), "sent", at, 42))
            }
            claim(first)
            claim(second)
            // Duplicate outcome cannot change a confirmed delivery into an error.
            store.recordNudgeOutcome(NudgeOutcome("run", second.atZone(ONBOARDING_ZONE).toLocalDate(), "failed", second))
            val reply = second.plusSeconds(60)
            incoming(connection, "in:1", 123, reply)
            incoming(connection, "in:1", 123, reply) // webhook replay
            store.recordVoice("run", session, "voice", OnboardingVoiceFacts(recognized = true),
                10, reply.plusSeconds(5), reply)
            store.mark("run", AttemptMark.RESULT_DELIVERED, reply.plusSeconds(10))
            store.recordReturn(session, reply.plusSeconds(20))
            store.recordReturn(session, reply.plusSeconds(20))
            // Another user is observed but not yet mature, even though they already returned.
            val pending = now.minusSeconds(3600)
            store.startAttempt("tg-456", "pending", "start", first.minusSeconds(3 * 86_400),
                chatId = 456, invitationAt = first.minusSeconds(3 * 86_400))
            assertTrue(store.claimNudge("pending", pending.atZone(ONBOARDING_ZONE).toLocalDate(),
                pending.minusSeconds(86_400), pending, "voice"))
            store.recordNudgeOutcome(NudgeOutcome("pending", pending.atZone(ONBOARDING_ZONE).toLocalDate(), "sent", pending, 43))
            incoming(connection, "in:2", 456, pending.plusSeconds(60))
            val report = store.nudgeReport(30, now)
            assertEquals(4, report.totals.attempts)
            assertEquals(3, report.totals.sent)
            assertEquals(2, report.totals.recipients)
            assertEquals(1, report.totals.unknown)
            assertEquals(2, report.totals.mature24)
            assertEquals(1, report.totals.pending24)
            assertEquals(1, report.totals.returned24)
            assertEquals(1, report.totals.voice24)
            assertEquals(1, report.totals.result24)
            assertEquals(0, report.byNumber.single { it.label == "2" }.counts.returned24)
            assertEquals(1, report.byNumber.single { it.label == "3" }.counts.returned24)
            assertEquals(NudgeCohort(2, 1, 1, 1, 1), report.cohort)
            // Audit pruning must not erase observed return facts.
            connection.createStatement().use { it.execute("DELETE FROM interaction_events") }
            assertEquals(report.totals, store.nudgeReport(30, now).totals)
            val json = nudgeReportJson(report)
            assertTrue(json.contains("onboarding-nudges.v2"))
            assertFalse(json.contains(session))
            val html = onboardingNudgeHtml(report, "/admin/metrics", 30)
            assertTrue(html.contains("1 / 2 · 50.0%"))
            assertTrue(html.contains("ещё наблюдаем 1"))
            assertTrue(html.contains("onboarding.json?days=30"))
        }
    }

    @Test
    fun excludesBoundaryEventsAndFirstCohortsOutsidePeriod() = runTest {
        withDatabase { store, connection ->
            val first = Instant.parse("2026-10-01T17:00:00Z")
            val now = Instant.parse("2026-10-19T12:00:00Z")
            store.startAttempt("tg-1", "old", "onboarding", first.minusSeconds(2 * 86_400),
                chatId = 1, invitationAt = first.minusSeconds(2 * 86_400))
            for ((at, status) in listOf(first to "sent", now.minusSeconds(2 * 86_400) to "sent")) {
                val day = at.atZone(ONBOARDING_ZONE).toLocalDate()
                assertTrue(store.claimNudge("old", day, at.minusSeconds(86_400), at, "retry"))
                store.recordNudgeOutcome(NudgeOutcome("old", day, status, at, 10))
            }
            val recent = now.minusSeconds(2 * 86_400)
            incoming(connection, "same-time", 1, recent)
            incoming(connection, "24h-boundary", 1, recent.plusSeconds(86_400))
            val report = store.nudgeReport(7, now)
            assertEquals(1, report.totals.sent)
            assertEquals(0, report.totals.returned24)
            assertEquals(0, report.cohort.users)
            // Practice attribution also works for a non-primary onboarding attempt.
            store.mark("old", AttemptMark.RESULT_DELIVERED, recent.plusSeconds(100))
            store.recordReturn("tg-1", recent.plusSeconds(200))
            val full = store.nudgeReport(30, now)
            assertEquals(0, full.cohort.practice7) // practice is more than 7d after first send
            assertEquals(1, full.totals.result24)
            assertTrue(onboardingNudgeHtml(null, "/admin/metrics", 7).contains("Данные недоступны"))
        }
    }

    @Test
    fun separatesFailuresAndCountsPracticeForNonPrimaryAttempt() = runTest {
        withDatabase { store, connection ->
            val first = Instant.parse("2026-10-10T17:00:00Z")
            val day = first.atZone(ONBOARDING_ZONE).toLocalDate()
            assertEquals(0, store.nudgeReport(7, first).totals.attempts)
            for ((index, status) in listOf("sent", "blocked", "failed", "unknown").withIndex()) {
                val run = "status-$index"
                store.startAttempt("tg-${index + 1}", run, "onboarding", first.minusSeconds(2 * 86_400),
                    chatId = (index + 1).toLong(), invitationAt = first.minusSeconds(2 * 86_400))
                assertTrue(store.claimNudge(run, day, first.minusSeconds(86_400), first, "retry"))
                if (status != "unknown") store.recordNudgeOutcome(NudgeOutcome(run, day, status, first.plusSeconds(60)))
            }
            store.mark("status-0", AttemptMark.RESULT_DELIVERED, first.plusSeconds(120))
            store.recordReturn("tg-1", first.plusSeconds(7 * 86_400 + 30))
            // An outgoing or system audit event must not be counted as a user returning.
            connection.createStatement().use { it.execute("""
                INSERT INTO interaction_events(event_id, chat_id, direction, kind, status, occurred_at, received_at)
                VALUES ('out', 1, 'outgoing', 'text', 'received', '2026-10-10T17:01:00Z', '2026-10-10T17:01:00Z'),
                       ('system', 1, 'incoming', 'update', 'received', '2026-10-10T17:01:00Z', '2026-10-10T17:01:00Z')
            """.trimIndent()) }
            val report = store.nudgeReport(30, first.plusSeconds(7 * 86_400 + 60))
            assertEquals(1, report.totals.sent)
            assertEquals(1, report.totals.blocked)
            assertEquals(1, report.totals.failed)
            assertEquals(1, report.totals.unknown)
            assertEquals(0, report.totals.returned24)
            assertEquals(NudgeCohort(1, 1, 0, 1, 1), report.cohort)
        }
    }

    private fun incoming(connection: Connection, id: String, chat: Long, at: Instant) {
        connection.prepareStatement("""
            INSERT INTO interaction_events(event_id, chat_id, direction, kind, status, occurred_at, received_at)
            VALUES (?, ?, 'incoming', 'callback', 'received', ?, ?) ON CONFLICT DO NOTHING
        """.trimIndent()).use {
            it.setString(1, id)
            it.setLong(2, chat)
            it.setTimestamp(3, Timestamp.from(at))
            it.setTimestamp(4, Timestamp.from(at))
            it.executeUpdate()
        }
    }

    private suspend fun withDatabase(block: suspend (PostgresOnboardingAnalytics, Connection) -> Unit) {
        val url = System.getenv("TEST_POSTGRES_URL") ?: return
        val jdbc = if (url.startsWith("jdbc:")) url else "jdbc:$url"
        val schema = "nudge_report_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(jdbc).use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
        val scoped = jdbc + (if ('?' in jdbc) "&" else "?") + "currentSchema=$schema"
        try {
            val store = PostgresOnboardingAnalytics(scoped)
            try { DriverManager.getConnection(scoped).use { block(store, it) } }
            finally { store.close() }
        } finally {
            DriverManager.getConnection(jdbc).use {
                it.createStatement().use { s -> s.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
