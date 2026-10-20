package com.eliteteam.speakingcoach.telegram

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.row
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.time.Instant

internal data class SoftNudgeCandidate(val kind: String, val flowId: String, val sessionId: String,
                                       val chatId: Long, val idleSince: Instant)
internal data class SoftNudgeStats(val kind: String, val sent: Int, val recipients: Int,
                                   val returned: Int, val returnedPeople: Int, val mature: Int,
                                   val matureReturned: Int, val failed: Int, val blocked: Int)

internal fun softNudgeText(kind: String): String = when (kind) {
    "onboarding" -> "Our first chat isn't finished yet 💙 Send me a voice message when you're ready, and let's pick up where we left off."
    else -> "Our conversation is still open 💙 I'm here when you're ready. Send me a voice message to keep talking, or tap End call to get your feedback."
}

internal fun softNudgeKeyboard(candidate: SoftNudgeCandidate): InlineKeyboardMarkup? =
    if (candidate.kind == "onboarding") null else inlineKeyboard {
        row { dataButton("End call", "call:end:${candidate.flowId}") }
}

/** A one-shot interruption check, independent of daily reminder claims and copy budgets. */
internal class SoftNudgeStore(databaseUrl: String) : AutoCloseable {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = databaseUrl.trim().let { if (it.startsWith("jdbc:")) it else "jdbc:$it" }
        maximumPoolSize = 3
        connectionTimeout = 2_000
        addDataSourceProperty("socketTimeout", "4")
    })
    init { Flyway.configure().dataSource(dataSource).load().migrate() }
    override fun close() = dataSource.close()

    suspend fun due(now: Instant, limit: Int = 100): List<SoftNudgeCandidate> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                WITH latest_onboarding AS (
                    SELECT DISTINCT ON (telegram_chat_id) run_id, session_id, telegram_chat_id,
                           completed_at
                    FROM onboarding_attempts
                    WHERE telegram_chat_id IS NOT NULL AND session_id LIKE 'tg-%'
                    ORDER BY telegram_chat_id, started_at DESC, attempt_number DESC
                ), candidates AS (
                    SELECT 'onboarding' AS kind, a.run_id AS flow_id, a.session_id,
                           a.telegram_chat_id AS chat_id, v.delivered_at AS idle_since
                    FROM latest_onboarding a
                    JOIN onboarding_last_bot_voice v ON v.run_id = a.run_id
                    WHERE a.completed_at IS NULL
                      AND v.delivered_at >= ? AND v.delivered_at <= ?
                    UNION ALL
                    SELECT 'call', e.call_id::text, 'tg-' || e.chat_id, e.chat_id,
                           GREATEST(e.occurred_at, voice.occurred_at)
                    FROM telegram_call_events e
                    JOIN LATERAL (
                        SELECT i.occurred_at FROM interaction_events i
                        WHERE i.chat_id = e.chat_id AND i.direction = 'outgoing'
                          AND i.kind = 'sendVoice' AND i.status = 'accepted'
                          AND i.occurred_at >= e.occurred_at
                        ORDER BY i.occurred_at DESC LIMIT 1
                    ) voice ON TRUE
                    WHERE e.kind = 'call_open' AND e.call_id IS NOT NULL
                      AND e.occurred_at >= ? AND e.occurred_at <= ?
                      AND NOT EXISTS (SELECT 1 FROM telegram_call_events closed
                          WHERE closed.call_id = e.call_id AND closed.kind = 'call_closed')
                )
                SELECT c.kind, c.flow_id, c.session_id, c.chat_id, c.idle_since
                FROM candidates c
                WHERE c.idle_since <= ?
                  AND NOT EXISTS (SELECT 1 FROM interaction_events i
                      WHERE i.chat_id = c.chat_id AND i.direction = 'incoming'
                        AND i.status = 'received' AND i.occurred_at > c.idle_since)
                  AND NOT EXISTS (SELECT 1 FROM soft_nudges s
                      WHERE s.kind = c.kind AND s.flow_id = c.flow_id)
                ORDER BY c.idle_since LIMIT ?
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now.minusSeconds(86_400)))
                statement.setTimestamp(2, Timestamp.from(now.minusSeconds(300)))
                statement.setTimestamp(3, Timestamp.from(now.minusSeconds(86_400)))
                statement.setTimestamp(4, Timestamp.from(now.minusSeconds(300)))
                statement.setTimestamp(5, Timestamp.from(now.minusSeconds(300)))
                statement.setInt(6, limit)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(SoftNudgeCandidate(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getLong(4), rows.getTimestamp(5).toInstant()))
                } }
            }
        }
    }

    suspend fun claim(candidate: SoftNudgeCandidate, now: Instant): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                INSERT INTO soft_nudges (kind, flow_id, session_id, chat_id, idle_since, claimed_at, status)
                SELECT ?, ?, ?, ?, ?, ?, 'claimed'
                WHERE NOT EXISTS (SELECT 1 FROM interaction_events i
                    WHERE i.chat_id = ? AND i.direction = 'incoming' AND i.status = 'received'
                      AND i.occurred_at > ?)
                ON CONFLICT (kind, flow_id) DO NOTHING
            """.trimIndent()).use { statement ->
                statement.setString(1, candidate.kind)
                statement.setString(2, candidate.flowId)
                statement.setString(3, candidate.sessionId)
                statement.setLong(4, candidate.chatId)
                statement.setTimestamp(5, Timestamp.from(candidate.idleSince))
                statement.setTimestamp(6, Timestamp.from(now))
                statement.setLong(7, candidate.chatId)
                statement.setTimestamp(8, Timestamp.from(candidate.idleSince))
                statement.executeUpdate() == 1
            }
        }
    }

    suspend fun report(candidate: SoftNudgeCandidate, status: String, at: Instant) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                UPDATE soft_nudges SET status = ?, sent_at = ?
                WHERE kind = ? AND flow_id = ? AND status = 'claimed'
            """.trimIndent()).use { statement ->
                statement.setString(1, status)
                statement.setTimestamp(2, if (status == "sent") Timestamp.from(at) else null)
                statement.setString(3, candidate.kind)
                statement.setString(4, candidate.flowId)
                statement.executeUpdate()
            }
            if (status == "sent") connection.prepareStatement("""
                UPDATE soft_nudges s SET returned_at = (
                    SELECT MIN(i.occurred_at) FROM interaction_events i
                    WHERE i.chat_id = s.chat_id AND i.direction = 'incoming'
                      AND i.kind = 'voice' AND i.status = 'received'
                      AND i.occurred_at > s.sent_at AND i.occurred_at <= s.sent_at + interval '24 hours'
                      AND (
                          (s.kind = 'onboarding' AND EXISTS (
                              SELECT 1 FROM onboarding_attempts a WHERE a.run_id = s.flow_id
                                AND (a.completed_at IS NULL OR a.completed_at >= i.occurred_at)))
                          OR (s.kind = 'call' AND NOT EXISTS (
                              SELECT 1 FROM telegram_call_events e WHERE e.call_id::text = s.flow_id
                                AND e.kind = 'call_closed' AND e.occurred_at <= i.occurred_at))
                      )
                ) WHERE s.kind = ? AND s.flow_id = ? AND s.status = 'sent'
            """.trimIndent()).use { statement ->
                statement.setString(1, candidate.kind)
                statement.setString(2, candidate.flowId)
                statement.executeUpdate()
            }
        }
    }

    suspend fun stats(now: Instant, days: Int): List<SoftNudgeStats> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT kind,
                       count(*) FILTER (WHERE status = 'sent') AS sent,
                       count(DISTINCT chat_id) FILTER (WHERE status = 'sent') AS recipients,
                       count(*) FILTER (WHERE returned_at IS NOT NULL) AS returned,
                       count(DISTINCT chat_id) FILTER (WHERE returned_at IS NOT NULL) AS returned_people,
                       count(*) FILTER (WHERE status = 'sent' AND sent_at <= ?) AS mature,
                       count(*) FILTER (WHERE returned_at IS NOT NULL AND sent_at <= ?) AS mature_returned,
                       count(*) FILTER (WHERE status IN ('failed', 'unknown') OR
                           (status = 'claimed' AND claimed_at <= ?)) AS failed,
                       count(*) FILTER (WHERE status = 'blocked') AS blocked
                FROM soft_nudges WHERE claimed_at >= ? GROUP BY kind ORDER BY kind
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now.minusSeconds(86_400)))
                statement.setTimestamp(2, Timestamp.from(now.minusSeconds(86_400)))
                statement.setTimestamp(3, Timestamp.from(now.minusSeconds(600)))
                statement.setTimestamp(4, Timestamp.from(now.minusSeconds(days.toLong() * 86_400)))
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(SoftNudgeStats(rows.getString(1), rows.getInt(2),
                        rows.getInt(3), rows.getInt(4), rows.getInt(5), rows.getInt(6),
                        rows.getInt(7), rows.getInt(8), rows.getInt(9)))
                } }
            }
        }
    }
}

internal class SoftNudgeRunner(
    private val store: SoftNudgeStore,
    private val active: suspend (SoftNudgeCandidate) -> Boolean,
    private val send: suspend (SoftNudgeCandidate) -> Unit,
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun runRound(): Int {
        var sent = 0
        for (candidate in store.due(clock())) {
            try {
                if (!active(candidate) || !store.claim(candidate, clock())) continue
                val status = try {
                    send(candidate)
                    sent++
                    "sent"
                } catch (error: CancellationException) { throw error
                } catch (error: Throwable) {
                    if (error is RequestException && error.response.errorCode == 403) "blocked"
                    else if (error is RequestException) "failed" else "unknown"
                }
                store.report(candidate, status, clock())
                delay(50)
            } catch (error: CancellationException) { throw error
            } catch (error: Throwable) { log.warn("Soft nudge failed kind={} flow={}", candidate.kind, candidate.flowId, error) }
        }
        return sent
    }
    private companion object { val log = LoggerFactory.getLogger(SoftNudgeRunner::class.java) }
}

internal fun CoroutineScope.launchSoftNudges(runner: SoftNudgeRunner): Job = launch {
    val log = LoggerFactory.getLogger("SoftNudgeRunner")
    while (isActive) {
        try { runner.runRound() }
        catch (error: CancellationException) { throw error }
        catch (error: Throwable) { log.warn("Soft nudge round failed", error) }
        delay(60_000)
    }
}
