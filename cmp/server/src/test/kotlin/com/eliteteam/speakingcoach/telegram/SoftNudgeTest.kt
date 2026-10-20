package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.analytics.AttemptMark
import com.eliteteam.speakingcoach.analytics.PostgresOnboardingAnalytics
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoftNudgeTest {
    @Test
    fun copyInvitesFinishingTheConversationWithoutAnOnboardingButton() {
        val onboarding = SoftNudgeCandidate("onboarding", "run-1", "tg-501", 501,
            Instant.parse("2026-10-20T10:00:00Z"))
        assertEquals("Our first chat isn't finished yet 💙 Send me a voice message when you're ready, and let's pick up where we left off.",
            softNudgeText(onboarding.kind))
        assertNull(softNudgeKeyboard(onboarding))

        val call = onboarding.copy(kind = "call", flowId = "a".repeat(32))
        assertEquals("Our conversation is still open 💙 I'm here when you're ready. Send me a voice message to keep talking, or tap End call to get your feedback.",
            softNudgeText(call.kind))
        val end = softNudgeKeyboard(call)!!.keyboard.single().single() as CallbackDataInlineKeyboardButton
        assertEquals("End call", end.text)
        assertEquals("call:end:${call.flowId}", end.callbackData)
    }

    @Test
    fun activeOnboardingGetsOneNudgeAfterFiveMinutesAndCountsNextVoice() = runTest {
        withDatabase { url ->
            val at = Instant.parse("2026-10-20T10:00:00Z")
            val analytics = PostgresOnboardingAnalytics(url)
            try {
                SoftNudgeStore(url).use { store ->
                    FollowupStore(url).use { voices ->
                        analytics.startAttempt("tg-501", "soft-run", "start", at.minusSeconds(30),
                            chatId = 501, invitationAt = at.minusSeconds(30))
                        analytics.mark("soft-run", AttemptMark.BEGIN_PRESSED, at.minusSeconds(20))
                        voices.recordBotVoice("soft-run", 8, at)
                        assertEquals(emptyList(), store.due(at.plusSeconds(299)))
                        val candidate = store.due(at.plusSeconds(300)).single()
                        assertEquals("onboarding", candidate.kind)
                        assertEquals("soft-run", candidate.flowId)
                        assertTrue(store.claim(candidate, at.plusSeconds(300)))
                        assertFalse(store.claim(candidate, at.plusSeconds(300)))
                        store.report(candidate, "sent", at.plusSeconds(301))
                        assertEquals(emptyList(), store.due(at.plusSeconds(601)))
                        assertEquals(0, store.stats(at.plusSeconds(400), 7).single().returned)
                        inbound(url, 501, "voice", at.plusSeconds(360))
                        val stats = store.stats(at.plusSeconds(400), 7).single()
                        assertEquals(1, stats.sent)
                        assertEquals(1, stats.returned)
                        assertEquals(0, stats.mature)
                        assertEquals(1, store.stats(at.plusSeconds(86_800), 7).single().matureReturned)
                    }
                }
            } finally { analytics.close() }
        }
    }

    @Test
    fun incomingUpdateSuppressesNudgeAndCallIsIndependentFromOnboarding() = runTest {
        withDatabase { url ->
            val at = Instant.parse("2026-10-20T10:00:00Z")
            SoftNudgeStore(url).use { store ->
                val callId = "a".repeat(32)
                DriverManager.getConnection(url).use { connection ->
                    connection.prepareStatement("""
                        INSERT INTO telegram_call_events (event_id, call_id, chat_id, kind, occurred_at)
                        VALUES ('soft-call', ?, 502, 'call_open', ?)
                    """.trimIndent()).use { statement ->
                        statement.setString(1, callId)
                        statement.setTimestamp(2, Timestamp.from(at.minusSeconds(10)))
                        statement.executeUpdate()
                    }
                    connection.prepareStatement("""
                        INSERT INTO interaction_events
                            (event_id, chat_id, direction, kind, status, occurred_at, received_at)
                        VALUES ('soft-voice', 502, 'outgoing', 'sendVoice', 'accepted', ?, ?)
                    """.trimIndent()).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(at))
                        statement.setTimestamp(2, Timestamp.from(at))
                        statement.executeUpdate()
                    }
                }
                assertEquals(emptyList(), store.due(at.plusSeconds(299)))
                assertEquals(callId, store.due(at.plusSeconds(300)).single().flowId)
                val delivered = mutableListOf<SoftNudgeCandidate>()
                val runner = SoftNudgeRunner(store, active = { false }, send = { delivered += it },
                    clock = { at.plusSeconds(300) })
                assertEquals(0, runner.runRound())
                assertTrue(delivered.isEmpty())
                assertEquals(callId, store.due(at.plusSeconds(300)).single().flowId)
                inbound(url, 502, "callback", at.plusSeconds(301))
                assertEquals(emptyList(), store.due(at.plusSeconds(600)))
            }
        }
    }

    @Test
    fun voiceAfterEndingInterruptedCallDoesNotCountAsItsReturn() = runTest {
        withDatabase { url ->
            val at = Instant.parse("2026-10-20T10:00:00Z")
            val callId = "c".repeat(32)
            SoftNudgeStore(url).use { store ->
                DriverManager.getConnection(url).use { connection ->
                    connection.prepareStatement("""
                        INSERT INTO telegram_call_events (event_id, call_id, chat_id, kind, occurred_at)
                        VALUES ('soft-open', ?, 504, 'call_open', ?)
                    """.trimIndent()).use { statement ->
                        statement.setString(1, callId)
                        statement.setTimestamp(2, Timestamp.from(at.minusSeconds(10)))
                        statement.executeUpdate()
                    }
                    connection.prepareStatement("""
                        INSERT INTO interaction_events
                            (event_id, chat_id, direction, kind, status, occurred_at, received_at)
                        VALUES ('soft-voice', 504, 'outgoing', 'sendVoice', 'accepted', ?, ?)
                    """.trimIndent()).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(at))
                        statement.setTimestamp(2, Timestamp.from(at))
                        statement.executeUpdate()
                    }
                }
                val candidate = store.due(at.plusSeconds(300)).single()
                assertTrue(store.claim(candidate, at.plusSeconds(300)))
                store.report(candidate, "sent", at.plusSeconds(301))
                DriverManager.getConnection(url).use { connection ->
                    connection.prepareStatement("""
                        INSERT INTO telegram_call_events (event_id, call_id, chat_id, kind, occurred_at)
                        VALUES ('soft-closed', ?, 504, 'call_closed', ?)
                    """.trimIndent()).use { statement ->
                        statement.setString(1, callId)
                        statement.setTimestamp(2, Timestamp.from(at.plusSeconds(330)))
                        statement.executeUpdate()
                    }
                }
                inbound(url, 504, "voice", at.plusSeconds(360))
                val stats = store.stats(at.plusSeconds(400), 7).single()
                assertEquals(1, stats.sent)
                assertEquals(0, stats.returned)
                assertEquals(0, stats.returnedPeople)
            }
        }
    }

    @Test
    fun voiceAndEndCallCountAsOneFiveMinuteSuccess() = runTest {
        withDatabase { url ->
            val at = Instant.parse("2026-10-20T10:00:00Z")
            val callId = "d".repeat(32)
            SoftNudgeStore(url).use { store ->
                DriverManager.getConnection(url).use { connection ->
                    connection.createStatement().use { it.execute("""
                        INSERT INTO telegram_call_events (event_id, call_id, chat_id, kind, occurred_at)
                        VALUES ('soft-open', '$callId', 505, 'call_open', '${at.minusSeconds(10)}')
                    """.trimIndent()) }
                    connection.createStatement().use { it.execute("""
                        INSERT INTO interaction_events
                            (event_id, chat_id, direction, kind, status, occurred_at, received_at)
                        VALUES ('soft-reply', 505, 'outgoing', 'sendVoice', 'accepted', '$at', '$at')
                    """.trimIndent()) }
                }
                val candidate = store.due(at.plusSeconds(300)).single()
                assertTrue(store.claim(candidate, at.plusSeconds(300)))
                store.report(candidate, "sent", at.plusSeconds(301))
                inbound(url, 505, "voice", at.plusSeconds(360))
                DriverManager.getConnection(url).use { connection ->
                    connection.createStatement().use { it.execute("""
                        INSERT INTO telegram_call_events (event_id, call_id, chat_id, kind, occurred_at)
                        VALUES ('soft-end', '$callId', 505, 'end_pressed', '${at.plusSeconds(400)}')
                    """.trimIndent()) }
                }
                val row = store.stats(at.plusSeconds(700), 7).single()
                assertEquals(1, row.mature5)
                assertEquals(1, row.voice5)
                assertEquals(1, row.ended5)
                assertEquals(1, row.succeeded5)
            }
        }
    }

    private fun inbound(url: String, chatId: Long, kind: String, at: Instant) {
        DriverManager.getConnection(url).use { connection ->
            connection.prepareStatement("""
                INSERT INTO interaction_events
                    (event_id, chat_id, direction, kind, status, occurred_at, received_at)
                VALUES (?, ?, 'incoming', ?, 'received', ?, ?)
            """.trimIndent()).use { statement ->
                statement.setString(1, UUID.randomUUID().toString())
                statement.setLong(2, chatId)
                statement.setString(3, kind)
                statement.setTimestamp(4, Timestamp.from(at))
                statement.setTimestamp(5, Timestamp.from(at))
                statement.executeUpdate()
            }
        }
    }

    private inline fun withDatabase(block: (String) -> Unit) {
        val raw = System.getenv("TEST_POSTGRES_URL") ?: return
        val jdbc = if (raw.startsWith("jdbc:")) raw else "jdbc:$raw"
        val schema = "soft_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(jdbc).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val scoped = jdbc + (if ('?' in jdbc) "&" else "?") + "currentSchema=$schema"
        try { block(scoped) } finally {
            DriverManager.getConnection(jdbc).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") }
        }
    }
}
