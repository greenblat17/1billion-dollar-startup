package com.eliteteam.speakingcoach.analytics

import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReminderEffectivenessTest {
    @Test
    fun separatesConfiguredInferredAndOnboardingAndStopsAtNextSend() = runTest {
        val raw = System.getenv("TEST_POSTGRES_URL") ?: return@runTest
        val jdbc = if (raw.startsWith("jdbc:")) raw else "jdbc:$raw"
        val schema = "reminder_effect_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(jdbc).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val scoped = jdbc + (if ('?' in jdbc) "&" else "?") + "currentSchema=$schema"
        try {
            val store = PostgresOnboardingAnalytics(scoped)
            try {
                val at = Instant.parse("2026-10-18T10:00:00Z")
                val now = at.plusSeconds(3 * 86_400)
                store.startAttempt("tg-123", "practice-run", "start", at.minusSeconds(86_400), chatId = 123)
                store.startAttempt("tg-456", "onboarding-run", "start", at.minusSeconds(86_400), chatId = 456)
                DriverManager.getConnection(scoped).use { db ->
                    db.createStatement().use { it.execute("""
                        INSERT INTO reminder_buttons
                            (token, session_id, chat_id, message_id, source, copy_kind, created_at, clicked_at, active)
                        VALUES ('00000000-0000-0000-0000-000000000001', 'tg-123', 123, 1,
                            'configured_practice', 'personalized', '$at', '${at.plusSeconds(30)}', FALSE)
                    """.trimIndent()) }
                    db.createStatement().use { it.execute("""
                        INSERT INTO configured_practice_attempts
                            (session_id, day, recorded_at, status)
                        VALUES ('tg-123', '2026-10-18', '$at', 'sent')
                    """.trimIndent()) }
                    db.createStatement().use { it.execute("""
                        INSERT INTO onboarding_nudges
                            (session_id, day, run_id, claimed_at, status, sent_at, message_id, journey_stage, copy_kind, preferred_utc_minute)
                        VALUES ('tg-123', '2026-10-19', 'practice-run', '${at.plusSeconds(86_400)}',
                            'sent', '${at.plusSeconds(86_400)}', 2, 'practice', 'general', 600),
                           ('tg-456', '2026-10-18', 'onboarding-run', '$at',
                            'sent', '$at', 3, 'first_question', 'general', 600)
                    """.trimIndent()) }
                    db.prepareStatement("UPDATE onboarding_attempts SET first_voice_at = ? WHERE run_id = 'onboarding-run'").use {
                        it.setTimestamp(1, Timestamp.from(at.plusSeconds(120)))
                        it.executeUpdate()
                    }
                    for ((chat, time) in listOf(123L to at.plusSeconds(300),
                        123L to at.plusSeconds(86_700))) {
                        db.prepareStatement("""
                            INSERT INTO interaction_events
                                (event_id, chat_id, direction, kind, status, occurred_at, received_at)
                            VALUES (?, ?, 'incoming', 'voice', 'received', ?, ?)
                        """.trimIndent()).use {
                            it.setString(1, UUID.randomUUID().toString())
                            it.setLong(2, chat)
                            it.setTimestamp(3, Timestamp.from(time))
                            it.setTimestamp(4, Timestamp.from(time))
                            it.executeUpdate()
                        }
                    }
                }
                val report = store.reminderEffectiveness(7, now)
                assertEquals(1, report.configured.sent)
                assertEquals(1, report.configuredDelivery.attempts)
                assertEquals(1, report.configuredDelivery.sent)
                assertEquals(1, report.configured.mature)
                assertEquals(1, report.configured.clicked)
                assertEquals(1, report.configured.firstVoice)
                assertEquals(1, report.configuredCopy.getValue("personalized").firstVoice)
                assertEquals(1, report.inferred.sent)
                assertEquals(1, report.inferred.firstVoice)
                assertEquals(1, report.inferredCopy.getValue("general").firstVoice)
                assertEquals(1, report.onboarding.progressed)
                assertEquals(1, report.inferredHours[13])
            } finally { store.close() }
        } finally {
            DriverManager.getConnection(jdbc).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") }
        }
    }
}
