package com.eliteteam.speakingcoach.telegram

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal data class FollowupCandidate(
    val sessionId: String,
    val runId: String,
    val chatId: Long,
    val stage: String,
    val scheduledFor: Instant,
    val preferredUtcMinute: Int,
    val visitCount: Int,
)

internal data class ClaimedFollowup(
    val candidate: FollowupCandidate,
    val action: String,
    val copy: FollowupCopy,
    val finalMessage: Boolean,
    val day: LocalDate,
)

private data class PendingSchedule(val session: String, val chat: Long, val lastInbound: Instant,
                                   val pausedStage: String?)

/** PostgreSQL owns due time, stage budget and the one-per-Moscow-day claim. */
internal class FollowupStore(databaseUrl: String) : AutoCloseable {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = databaseUrl.trim().let { if (it.startsWith("jdbc:")) it else "jdbc:$it" }
        maximumPoolSize = 3
        connectionTimeout = 2_000
        addDataSourceProperty("socketTimeout", "4")
    })

    init { Flyway.configure().dataSource(dataSource).load().migrate() }

    override fun close() = dataSource.close()

    suspend fun recordBotVoice(runId: String, messageId: Long, at: Instant) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                INSERT INTO onboarding_last_bot_voice (run_id, message_id, delivered_at)
                VALUES (?, ?, ?)
                ON CONFLICT (run_id) DO UPDATE SET message_id = EXCLUDED.message_id,
                    delivered_at = EXCLUDED.delivered_at
                WHERE onboarding_last_bot_voice.delivered_at <= EXCLUDED.delivered_at
            """.trimIndent()).use { statement ->
                statement.setString(1, runId)
                statement.setLong(2, messageId)
                statement.setTimestamp(3, Timestamp.from(at))
                statement.executeUpdate()
            }
        }
    }

    suspend fun lastBotVoice(runId: String): Long? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT message_id FROM onboarding_last_bot_voice WHERE run_id = ?").use { statement ->
                statement.setString(1, runId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
            }
        }
    }

    suspend fun currentJourney(sessionId: String): Pair<String, String?>? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT a.*, EXISTS (SELECT 1 FROM onboarding_voices v WHERE v.attempt_id = a.run_id
                                    AND v.outcome = 'recognized') AS has_recognized_voice
                FROM onboarding_attempts a WHERE a.session_id = ?
                ORDER BY a.started_at DESC, a.attempt_number DESC LIMIT 1
            """.trimIndent()).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getString("run_id") to followupStage(rows) else null
                }
            }
        }
    }

    suspend fun refreshDirty(now: Instant, limit: Int = 100): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT session_id, chat_id, last_inbound_at, paused_stage FROM onboarding_followup_schedule
                WHERE dirty AND stopped_at IS NULL
                ORDER BY updated_at LIMIT ?
            """.trimIndent()).use { query ->
                query.setInt(1, limit)
                query.executeQuery().use { rows ->
                    val pending = buildList {
                        while (rows.next()) add(PendingSchedule(rows.getString(1), rows.getLong(2),
                            rows.getTimestamp(3).toInstant(), rows.getString(4)))
                    }
                    pending.forEach { pendingRow ->
                        val (session, chat, last, paused) = pendingRow
                        val events = connection.prepareStatement("""
                            SELECT occurred_at FROM interaction_events
                            WHERE chat_id = ? AND direction = 'incoming' AND status = 'received'
                              AND kind IN ('callback', 'voice', 'text', 'photo') AND occurred_at >= ?
                            ORDER BY occurred_at, event_id
                        """.trimIndent()).use { statement ->
                            statement.setLong(1, chat)
                            statement.setTimestamp(2, Timestamp.from(now.minus(30, ChronoUnit.DAYS)))
                            statement.executeQuery().use { eventRows ->
                                buildList { while (eventRows.next()) add(eventRows.getTimestamp(1).toInstant()) }
                            }
                        }
                        val starts = connection.prepareStatement("""
                            SELECT started_at FROM onboarding_attempts
                            WHERE session_id = ? AND started_at >= ? ORDER BY started_at
                        """.trimIndent()).use { statement ->
                            statement.setString(1, session)
                            statement.setTimestamp(2, Timestamp.from(now.minus(30, ChronoUnit.DAYS)))
                            statement.executeQuery().use { rows ->
                                buildList { while (rows.next()) add(rows.getTimestamp(1).toInstant()) }
                            }
                        }
                        val estimate = preferredVisitTime((events + starts).ifEmpty { listOf(last) })!!
                        val stage = if (paused == null) null else connection.prepareStatement("""
                            SELECT a.*, EXISTS (SELECT 1 FROM onboarding_voices v WHERE v.attempt_id = a.run_id
                                                AND v.outcome = 'recognized') AS has_recognized_voice
                            FROM onboarding_attempts a WHERE a.session_id = ?
                            ORDER BY a.started_at DESC, a.attempt_number DESC LIMIT 1
                        """.trimIndent()).use { statement ->
                            statement.setString(1, session)
                            statement.executeQuery().use { rows -> if (rows.next()) followupStage(rows) else null }
                        }
                        val sameStage = paused != null && paused == stage
                        val due = if (sameStage) null else
                            nextFollowupDue(estimate.utcMinute, last.plus(24, ChronoUnit.HOURS))
                        connection.prepareStatement("""
                            UPDATE onboarding_followup_schedule SET preferred_utc_minute = ?, visit_count = ?,
                                next_due_at = ?, paused_stage = CASE WHEN ? THEN paused_stage ELSE NULL END,
                                dirty = FALSE, updated_at = ?
                            WHERE session_id = ? AND last_inbound_at = ? AND stopped_at IS NULL
                        """.trimIndent()).use { update ->
                            update.setInt(1, estimate.utcMinute)
                            update.setInt(2, estimate.visitCount)
                            update.setTimestamp(3, due?.let(Timestamp::from))
                            update.setBoolean(4, sameStage)
                            update.setTimestamp(5, Timestamp.from(now))
                            update.setString(6, session)
                            update.setTimestamp(7, Timestamp.from(last))
                            update.executeUpdate()
                        }
                    }
                    pending.size
                }
            }
        }
    }

    suspend fun due(now: Instant, limit: Int = 100): List<FollowupCandidate> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT a.*, s.chat_id AS followup_chat_id, s.next_due_at, s.preferred_utc_minute, s.visit_count,
                       EXISTS (SELECT 1 FROM onboarding_voices v WHERE v.attempt_id = a.run_id
                               AND v.outcome = 'recognized') AS has_recognized_voice
                FROM onboarding_followup_schedule s
                JOIN LATERAL (
                    SELECT * FROM onboarding_attempts a WHERE a.session_id = s.session_id
                    ORDER BY a.started_at DESC, a.attempt_number DESC LIMIT 1
                ) a ON TRUE
                WHERE s.dirty = FALSE AND s.stopped_at IS NULL
                  AND s.next_due_at <= ?
                  AND NOT EXISTS (SELECT 1 FROM onboarding_attempts d WHERE d.session_id = s.session_id
                                  AND d.reminder_decision = 'not_now')
                  AND NOT EXISTS (SELECT 1 FROM onboarding_nudges n WHERE n.session_id = s.session_id
                                  AND n.day = (? AT TIME ZONE 'Europe/Moscow')::date)
                ORDER BY s.next_due_at, s.session_id LIMIT ?
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.setTimestamp(2, Timestamp.from(now))
                statement.setInt(3, limit)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val stage = followupStage(rows) ?: continue
                            add(candidate(rows, stage))
                        }
                    }
                }
            }
        }
    }

    suspend fun reminderCandidate(sessionId: String, now: Instant): FollowupCandidate? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT a.*, s.chat_id AS followup_chat_id, s.next_due_at, s.preferred_utc_minute, s.visit_count,
                       EXISTS (SELECT 1 FROM onboarding_voices v WHERE v.attempt_id = a.run_id
                               AND v.outcome = 'recognized') AS has_recognized_voice
                FROM onboarding_followup_schedule s
                JOIN LATERAL (
                    SELECT * FROM onboarding_attempts a WHERE a.session_id = s.session_id
                    ORDER BY a.started_at DESC, a.attempt_number DESC LIMIT 1
                ) a ON TRUE
                WHERE s.session_id = ? AND s.dirty = FALSE AND s.stopped_at IS NULL
                  AND s.last_inbound_at <= ?
                  AND NOT EXISTS (SELECT 1 FROM onboarding_attempts d WHERE d.session_id = s.session_id
                                  AND d.reminder_decision = 'not_now')
            """.trimIndent()).use { statement ->
                statement.setString(1, sessionId)
                statement.setTimestamp(2, Timestamp.from(now.minus(24, ChronoUnit.HOURS)))
                statement.executeQuery().use { rows ->
                    if (rows.next()) followupStage(rows)?.takeUnless { it == "practice" }
                        ?.let { candidate(rows, it) } else null
                }
            }
        }
    }

    suspend fun alreadyClaimedToday(sessionId: String, now: Instant): FollowupDelivery? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("""
                    SELECT copy_id, status FROM onboarding_nudges
                    WHERE session_id = ? AND day = (? AT TIME ZONE 'Europe/Moscow')::date
                      AND status <> 'failed'
                """.trimIndent()).use { statement ->
                    statement.setString(1, sessionId)
                    statement.setTimestamp(2, Timestamp.from(now))
                    statement.executeQuery().use { rows ->
                        if (rows.next()) FollowupDelivery(rows.getString(1) ?: "legacy", rows.getString(2))
                        else null
                    }
                }
            }
        }

    suspend fun deferExpired(candidate: FollowupCandidate, now: Instant) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                UPDATE onboarding_followup_schedule SET next_due_at = ?, updated_at = ?
                WHERE session_id = ? AND dirty = FALSE AND next_due_at = ?
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, Timestamp.from(nextFollowupDay(candidate.preferredUtcMinute, now)))
                statement.setTimestamp(2, Timestamp.from(now))
                statement.setString(3, candidate.sessionId)
                statement.setTimestamp(4, Timestamp.from(candidate.scheduledFor))
                statement.executeUpdate()
            }
        }
    }

    suspend fun stop(sessionId: String, now: Instant): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                UPDATE onboarding_followup_schedule SET stopped_at = COALESCE(stopped_at, ?),
                    stop_reason = COALESCE(stop_reason, 'user'), next_due_at = NULL, updated_at = ?
                WHERE session_id = ?
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.setTimestamp(2, Timestamp.from(now))
                statement.setString(3, sessionId)
                statement.executeUpdate() == 1
            }
        }
    }

    suspend fun claim(candidate: FollowupCandidate, action: String, now: Instant,
                      dailyReminderSlot: Boolean = false): ClaimedFollowup? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val schedule = connection.prepareStatement("""
                        SELECT * FROM onboarding_followup_schedule WHERE session_id = ? FOR UPDATE
                    """.trimIndent()).use { statement ->
                        statement.setString(1, candidate.sessionId)
                        statement.executeQuery().use { rows ->
                            if (rows.next()) Triple(rows.getTimestamp("last_inbound_at").toInstant(),
                                rows.getTimestamp("next_due_at")?.toInstant(),
                                    rows.getBoolean("dirty") || rows.getTimestamp("stopped_at") != null)
                            else null
                        }
                    }
                    if (schedule == null || schedule.third || schedule.second != candidate.scheduledFor ||
                        schedule.first > now.minus(24, ChronoUnit.HOURS) ||
                        (!dailyReminderSlot && (candidate.scheduledFor > now ||
                            candidate.scheduledFor < now.minus(2, ChronoUnit.HOURS)))) {
                        connection.rollback(); return@withContext null
                    }
                    val current = connection.prepareStatement("""
                        SELECT a.*, EXISTS (SELECT 1 FROM onboarding_voices v WHERE v.attempt_id = a.run_id
                                            AND v.outcome = 'recognized') AS has_recognized_voice
                        FROM onboarding_attempts a WHERE a.session_id = ?
                        ORDER BY a.started_at DESC, a.attempt_number DESC LIMIT 1
                    """.trimIndent()).use { statement ->
                        statement.setString(1, candidate.sessionId)
                        statement.executeQuery().use { rows ->
                            if (rows.next()) rows.getString("run_id") to followupStage(rows) else null
                        }
                    }
                    if (current != (candidate.runId to candidate.stage) ||
                        disqualified(connection, candidate.sessionId, now)) {
                        connection.rollback(); return@withContext null
                    }
                    val sent = connection.prepareStatement("""
                        SELECT copy_id, status, final_message FROM onboarding_nudges
                        WHERE session_id = ? AND journey_stage = ? AND copy_id IS NOT NULL
                    """.trimIndent()).use { statement ->
                        statement.setString(1, candidate.sessionId)
                        statement.setString(2, if (action == "retry") "pending" else candidate.stage)
                        statement.executeQuery().use { rows ->
                            buildList { while (rows.next()) add(Triple(rows.getString(1), rows.getString(2), rows.getBoolean(3))) }
                        }
                    }
                    val stage = if (action == "retry") "pending" else candidate.stage
                    if (sent.any { it.third && it.second in setOf("sent", "unknown") }) {
                        pause(connection, candidate.sessionId, candidate.stage, now)
                        connection.commit(); return@withContext null
                    }
                    val consumed = sent.filter { it.second != "failed" && it.second != "blocked" }.map { it.first }.toSet()
                    val copy = FollowupCatalog.select(stage, candidate.sessionId, consumed)
                    val final = copy == null && consumed.size >= FollowupCatalog.variants(stage).size &&
                        sent.any { it.second == "sent" }
                    if (copy == null && !final) {
                        pause(connection, candidate.sessionId, candidate.stage, now)
                        connection.commit(); return@withContext null
                    }
                    val chosen = if (final) FollowupCatalog.finalMessage() else copy!!
                    val day = now.atZone(REMINDER_ZONE).toLocalDate()
                    val inserted = connection.prepareStatement("""
                        INSERT INTO onboarding_nudges
                          (session_id, day, run_id, claimed_at, action, template_version, attempt_number,
                           inactive_seconds, scheduled_for, visit_count, timing_policy, journey_stage,
                           copy_id, catalog_version, preferred_utc_minute, final_message)
                        VALUES (?, ?, ?, ?, ?, ?, (SELECT COUNT(*) + 1 FROM onboarding_nudges WHERE session_id = ?),
                                ?, ?, ?, 'personal-v1', ?, ?, ?, ?, ?)
                        ON CONFLICT (session_id, day) DO NOTHING
                    """.trimIndent()).use { statement ->
                        statement.setString(1, candidate.sessionId)
                        statement.setObject(2, day)
                        statement.setString(3, candidate.runId)
                        statement.setTimestamp(4, Timestamp.from(now))
                        statement.setString(5, action)
                        statement.setString(6, FOLLOWUP_CATALOG_VERSION)
                        statement.setString(7, candidate.sessionId)
                        statement.setLong(8, ChronoUnit.SECONDS.between(schedule.first, now))
                        statement.setTimestamp(9, Timestamp.from(if (dailyReminderSlot) now else candidate.scheduledFor))
                        statement.setInt(10, candidate.visitCount)
                        statement.setString(11, stage)
                        statement.setString(12, chosen.id)
                        statement.setString(13, FOLLOWUP_CATALOG_VERSION)
                        statement.setInt(14, candidate.preferredUtcMinute)
                        statement.setBoolean(15, final)
                        statement.executeUpdate() == 1
                    }
                    if (!inserted) { connection.rollback(); return@withContext null }
                    connection.prepareStatement("""
                        UPDATE onboarding_followup_schedule SET next_due_at = ?, updated_at = ? WHERE session_id = ?
                    """.trimIndent()).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(nextFollowupDay(candidate.preferredUtcMinute, now)))
                        statement.setTimestamp(2, Timestamp.from(now))
                        statement.setString(3, candidate.sessionId)
                        statement.executeUpdate()
                    }
                    connection.commit()
                    ClaimedFollowup(candidate, action, chosen, final, day)
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = true
                }
            }
        }

    suspend fun report(claim: ClaimedFollowup, status: String, at: Instant, messageId: Long? = null,
                       copyKind: String? = null) =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("""
                    UPDATE onboarding_nudges SET status = ?, sent_at = ?, message_id = ?, outcome_at = ?, copy_kind = ?
                    WHERE session_id = ? AND day = ? AND copy_id = ? AND outcome_at IS NULL
                """.trimIndent()).use { statement ->
                    statement.setString(1, status)
                    statement.setTimestamp(2, if (status == "sent") Timestamp.from(at) else null)
                    statement.setObject(3, messageId)
                    statement.setTimestamp(4, Timestamp.from(at))
                    statement.setString(5, copyKind)
                    statement.setString(6, claim.candidate.sessionId)
                    statement.setObject(7, claim.day)
                    statement.setString(8, claim.copy.id)
                    statement.executeUpdate()
                }
                if (status == "blocked") connection.prepareStatement("""
                    UPDATE onboarding_followup_schedule SET stopped_at = COALESCE(stopped_at, ?),
                        stop_reason = COALESCE(stop_reason, 'blocked'), next_due_at = NULL
                    WHERE session_id = ?
                """.trimIndent()).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(at))
                    statement.setString(2, claim.candidate.sessionId)
                    statement.executeUpdate()
                }
                if (claim.finalMessage && status in setOf("sent", "unknown"))
                    pause(connection, claim.candidate.sessionId, claim.candidate.stage, at)
            }
        }

    private fun disqualified(connection: Connection, sessionId: String, now: Instant): Boolean =
        connection.prepareStatement("""
            SELECT EXISTS (
                SELECT 1 FROM onboarding_attempts WHERE session_id = ?
                AND reminder_decision = 'not_now'
            ) OR EXISTS (
                SELECT 1 FROM onboarding_nudges WHERE session_id = ?
                AND day = (? AT TIME ZONE 'Europe/Moscow')::date
            )
        """.trimIndent()).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, sessionId)
            statement.setTimestamp(3, Timestamp.from(now))
            statement.executeQuery().use { rows -> rows.next(); rows.getBoolean(1) }
        }

    private fun pause(connection: Connection, sessionId: String, stage: String, now: Instant) {
        connection.prepareStatement("""
            UPDATE onboarding_followup_schedule SET paused_stage = ?, next_due_at = NULL, updated_at = ?
            WHERE session_id = ? AND stopped_at IS NULL
        """.trimIndent()).use { statement ->
            statement.setString(1, stage)
            statement.setTimestamp(2, Timestamp.from(now))
            statement.setString(3, sessionId)
            statement.executeUpdate()
        }
    }

    private fun candidate(rows: ResultSet, stage: String) = FollowupCandidate(
        rows.getString("session_id"), rows.getString("run_id"), rows.getLong("followup_chat_id"),
        stage, rows.getTimestamp("next_due_at").toInstant(), rows.getInt("preferred_utc_minute"),
        rows.getInt("visit_count"),
    )
}

internal fun journeyStage(rows: ResultSet): String? {
    val stages = listOf(
        "reminder_resolved" to (rows.getString("reminder_decision") == "not_now" || rows.getTimestamp("reminder_set_at") != null),
        "reminder_offered" to (rows.getTimestamp("reminder_offered_at") != null),
        "goal_decided" to (rows.getTimestamp("goal_selected_at") != null),
        "goal_shown" to (rows.getTimestamp("practice_setup_at") != null),
        "result_opened" to (rows.getTimestamp("results_opened_at") != null),
        "result_delivered" to (rows.getTimestamp("result_delivered_at") != null),
        "speech_120" to (rows.getTimestamp("speech_120_at") != null),
        "speech_90" to (rows.getTimestamp("speech_90_at") != null),
        "speech_60" to (rows.getTimestamp("speech_60_at") != null),
        "speech_30" to (rows.getTimestamp("speech_30_at") != null),
        "voice_recognized" to rows.getBoolean("has_recognized_voice"),
        "voice_received" to (rows.getTimestamp("first_voice_at") != null),
        "first_question" to (rows.getTimestamp("first_question_delivered_at") != null),
        "begin" to (rows.getTimestamp("begin_pressed_at") != null),
        "invitation" to (rows.getTimestamp("invitation_delivered_at") != null),
        "start" to true,
    )
    return stages.firstOrNull { it.second }?.first
}

/** Practice invitations have one shared budget after the goal is chosen. */
internal fun followupStage(rows: ResultSet): String? =
    if (rows.getTimestamp("goal_selected_at") != null) "practice" else journeyStage(rows)
