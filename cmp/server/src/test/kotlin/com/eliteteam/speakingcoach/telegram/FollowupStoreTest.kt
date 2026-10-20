package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.analytics.PostgresOnboardingAnalytics
import com.eliteteam.speakingcoach.analytics.AttemptMark
import kotlinx.coroutines.test.runTest
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FollowupStoreTest {
    @Test
    fun practiceUsesFourteenDistinctDaysThenFinalEvenAfterFirstCall() = runTest {
        withDatabase { url ->
            val analytics = PostgresOnboardingAnalytics(url)
            val store = FollowupStore(url)
            try {
                val start = Instant.parse("2026-10-20T11:35:00Z")
                analytics.startAttempt("tg-130", "run-practice", "start", start, chatId = 130,
                    invitationAt = start)
                analytics.mark("run-practice", AttemptMark.RESULT_DELIVERED, start.plusSeconds(60))
                analytics.markGoal("run-practice", 5, start.plusSeconds(90))
                store.refreshDirty(start)
                val copyIds = mutableSetOf<String>()
                for (number in 1..14) {
                    val now = start.plusSeconds(number * 86_400L + if (number == 1) 0 else 2)
                    val claim = assertNotNull(store.claim(store.due(now).single(), "practice", now))
                    assertFalse(claim.finalMessage)
                    assertTrue(copyIds.add(claim.copy.id))
                    store.report(claim, "sent", now, number.toLong(), "general")
                    if (number == 1) {
                        insertIncoming(url, 130, now.plusSeconds(1))
                        analytics.recordReturn("tg-130", now.plusSeconds(1))
                        store.refreshDirty(now.plusSeconds(1))
                    }
                }
                assertEquals(14, copyIds.size)
                val finalAt = start.plusSeconds(15 * 86_400L + 2)
                val final = assertNotNull(store.claim(store.due(finalAt).single(), "practice", finalAt))
                assertTrue(final.finalMessage)
                store.report(final, "sent", finalAt, 15)
                assertEquals(emptyList(), store.due(finalAt.plusSeconds(86_400)))
            } finally {
                store.close()
                analytics.close()
            }
        }
    }

    @Test
    fun lastDeliveredBotVoiceIsDurablePerAttempt() = runTest {
        withDatabase { url ->
            FollowupStore(url).use { first ->
                first.recordBotVoice("run-voice", 11, Instant.parse("2026-10-20T10:00:00Z"))
                first.recordBotVoice("run-voice", 12, Instant.parse("2026-10-20T10:01:00Z"))
                first.recordBotVoice("run-voice", 10, Instant.parse("2026-10-20T09:59:00Z"))
                assertEquals(12, first.lastBotVoice("run-voice"))
            }
            FollowupStore(url).use { reopened ->
                assertEquals(12, reopened.lastBotVoice("run-voice"))
                assertNull(reopened.lastBotVoice("another-run"))
            }
        }
    }

    @Test
    fun firstInboundAuditBeforeAttemptStillSchedulesFromStart() = runTest {
        withDatabase { url ->
            val analytics = PostgresOnboardingAnalytics(url)
            val store = FollowupStore(url)
            try {
                val start = Instant.parse("2026-10-20T11:35:37Z")
                insertIncoming(url, 122, start)
                analytics.startAttempt("tg-122", "run-0", "start", start, chatId = 122,
                    invitationAt = start)
                store.refreshDirty(start)
                val due = store.due(start.plusSeconds(86_400)).single()
                assertEquals(1, due.visitCount)
                assertEquals(start.plusSeconds(86_400), due.scheduledFor)
            } finally {
                store.close()
                analytics.close()
            }
        }
    }

    @Test
    fun firstStartSchedulesAndTenDistinctCopiesEndWithOneFinalNote() = runTest {
        withDatabase { url ->
            val analytics = PostgresOnboardingAnalytics(url)
            val store = FollowupStore(url)
            try {
                val start = Instant.parse("2026-10-20T11:35:37Z")
                analytics.startAttempt("tg-123", "run-1", "start", start, chatId = 123, invitationAt = start)
                store.refreshDirty(start)
                assertEquals(emptyList(), store.due(start))
                val ids = mutableSetOf<String>()
                for (number in 1..10) {
                    val now = start.plusSeconds(number * 86_400L)
                    val candidate = store.due(now).single()
                    assertEquals("invitation", candidate.stage)
                    val claim = assertNotNull(store.claim(candidate, "begin", now))
                    assertFalse(claim.finalMessage)
                    assertTrue(ids.add(claim.copy.id))
                    assertNull(store.claim(candidate, "begin", now))
                    store.report(claim, "sent", now, number.toLong())
                    assertEquals(claim.copy.id, store.alreadyClaimedToday("tg-123", now)?.copyId)
                }
                assertEquals(10, ids.size)
                val last = start.plusSeconds(11 * 86_400L)
                val final = assertNotNull(store.claim(store.due(last).single(), "begin", last))
                assertTrue(final.finalMessage)
                assertEquals("final:1", final.copy.id)
                store.report(final, "sent", last, 11L)
                assertEquals(emptyList(), store.due(last.plusSeconds(86_400)))
                val report = analytics.nudgeReport(30, last.plusSeconds(86_400))
                assertNotNull(report)
                assertEquals(11, report.byStage.single { it.label == "invitation" }.counts.sent)
                assertEquals(1, report.finalSent)
                assertEquals(11, report.byTimingPolicy.single { it.label == "personal-v1" }.counts.sent)
                analytics.mark("run-1", AttemptMark.BEGIN_PRESSED, last.plusSeconds(86_400))
                store.refreshDirty(last.plusSeconds(86_400))
                assertEquals("begin", store.due(last.plusSeconds(86_400)).single().stage)
            } finally {
                store.close()
                analytics.close()
            }
        }
    }

    @Test
    fun inboundActivityPostponesDueAndStopSuppressesFurtherSends() = runTest {
        withDatabase { url ->
            val analytics = PostgresOnboardingAnalytics(url)
            val store = FollowupStore(url)
            try {
                val start = Instant.parse("2026-10-20T11:35:37Z")
                analytics.startAttempt("tg-124", "run-2", "start", start, chatId = 124, invitationAt = start)
                store.refreshDirty(start)
                val inbound = start.plusSeconds(86_000)
                DriverManager.getConnection(url).use { connection ->
                    connection.prepareStatement("""
                        INSERT INTO interaction_events
                          (event_id, chat_id, direction, kind, status, occurred_at, received_at)
                        VALUES (?, ?, 'incoming', 'callback', 'received', ?, ?)
                    """.trimIndent()).use { statement ->
                        statement.setString(1, "callback:new")
                        statement.setLong(2, 124)
                        statement.setTimestamp(3, Timestamp.from(inbound))
                        statement.setTimestamp(4, Timestamp.from(inbound))
                        statement.executeUpdate()
                    }
                }
                store.refreshDirty(inbound)
                assertEquals(emptyList(), store.due(start.plusSeconds(86_400)))
                val candidate = store.due(inbound.plusSeconds(86_400)).single()
                assertEquals(2, candidate.visitCount)
                assertTrue(store.stop("tg-124", inbound.plusSeconds(86_400)))
                assertEquals(emptyList(), store.due(inbound.plusSeconds(2 * 86_400)))
            } finally {
                store.close()
                analytics.close()
            }
        }
    }

    @Test
    fun staleStageOrNewInboundCannotBeClaimed() = runTest {
        withDatabase { url ->
            val analytics = PostgresOnboardingAnalytics(url)
            val store = FollowupStore(url)
            try {
                val start = Instant.parse("2026-10-20T11:35:00Z")
                val due = start.plusSeconds(86_400)
                analytics.startAttempt("tg-125", "run-3", "start", start, chatId = 125, invitationAt = start)
                store.refreshDirty(start)
                val candidate = store.due(due).single()
                analytics.mark("run-3", AttemptMark.BEGIN_PRESSED, due)
                assertNull(store.claim(candidate, "begin", due))
                val updated = store.due(due).single()
                assertEquals("begin", updated.stage)
                insertIncoming(url, 125, due.plusSeconds(1))
                assertNull(store.claim(updated, "begin", due.plusSeconds(1)))
            } finally {
                store.close()
                analytics.close()
            }
        }
    }

    @Test
    fun notNowStopsPracticeFollowups() = runTest {
        withDatabase { url ->
            val analytics = PostgresOnboardingAnalytics(url)
            val store = FollowupStore(url)
            try {
                val start = Instant.parse("2026-10-20T11:35:00Z")
                val due = start.plusSeconds(86_400)
                analytics.startAttempt("tg-126", "run-4", "start", start, chatId = 126, invitationAt = start)
                analytics.mark("run-4", AttemptMark.RESULT_DELIVERED, start.plusSeconds(60))
                analytics.mark("run-4", AttemptMark.REMINDER_OFFERED, start.plusSeconds(90))
                store.refreshDirty(start)
                assertEquals("reminder_offered", store.due(due).single().stage)
                analytics.markReminderDecision("run-4", "not_now", due)
                assertEquals(emptyList(), store.due(due))
            } finally {
                store.close()
                analytics.close()
            }
        }
    }

    @Test
    fun firstOrdinaryPracticeKeepsPracticeFollowupsEligible() = runTest {
        withDatabase { url ->
            val analytics = PostgresOnboardingAnalytics(url)
            val store = FollowupStore(url)
            try {
                val start = Instant.parse("2026-10-20T11:35:00Z")
                val due = start.plusSeconds(86_400)
                analytics.startAttempt("tg-127", "run-5", "onboarding", start, chatId = 127,
                    invitationAt = start)
                analytics.mark("run-5", AttemptMark.RESULT_DELIVERED, start.plusSeconds(60))
                analytics.markGoal("run-5", 5, start.plusSeconds(90))
                store.refreshDirty(start)
                assertEquals("practice", store.due(due).single().stage)
                insertIncoming(url, 127, due)
                analytics.recordReturn("tg-127", due)
                store.refreshDirty(due)
                assertEquals("practice", store.due(due.plusSeconds(86_400)).single().stage)
            } finally {
                store.close()
                analytics.close()
            }
        }
    }

    private fun insertIncoming(url: String, chatId: Long, at: Instant) {
        DriverManager.getConnection(url).use { connection ->
            connection.prepareStatement("""
                INSERT INTO interaction_events (event_id, chat_id, direction, kind, status, occurred_at, received_at)
                VALUES (?, ?, 'incoming', 'callback', 'received', ?, ?)
            """.trimIndent()).use { statement ->
                statement.setString(1, "callback:${UUID.randomUUID()}")
                statement.setLong(2, chatId)
                statement.setTimestamp(3, Timestamp.from(at))
                statement.setTimestamp(4, Timestamp.from(at))
                statement.executeUpdate()
            }
        }
    }

    private inline fun withDatabase(block: (String) -> Unit) {
        val raw = System.getenv("TEST_POSTGRES_URL") ?: return
        val jdbc = if (raw.startsWith("jdbc:")) raw else "jdbc:$raw"
        val schema = "followups_${UUID.randomUUID().toString().replace("-", "")}" 
        DriverManager.getConnection(jdbc).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val scoped = jdbc + (if ('?' in jdbc) "&" else "?") + "currentSchema=$schema"
        try { block(scoped) } finally {
            DriverManager.getConnection(jdbc).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") }
        }
    }
}
