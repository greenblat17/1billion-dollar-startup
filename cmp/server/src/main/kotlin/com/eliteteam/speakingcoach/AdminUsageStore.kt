package com.eliteteam.speakingcoach

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Timestamp
import java.time.ZoneId
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AdminUsageDay(
    val day: String,
    val activeChats: Long = 0,
    val voices: Long = 0,
    val exchanges: Long = 0,
    val voiceErrors: Long = 0,
    val telegramApiCalls: Long = 0,
)

internal data class AdminUsageOverview(
    val activeChatsInPeriod: Long,
    val days: List<AdminUsageDay>,
    val firstObservedDay: String?,
)

internal fun interface AdminUsageStore {
    suspend fun load(range: LlmRange): AdminUsageOverview
}

/** Read-only daily aggregates from the retained Telegram interaction journal. */
internal class PostgresAdminUsageStore(databaseUrl: String) : AdminUsageStore, AutoCloseable {
    private val zone = ZoneId.of("Europe/Moscow")
    private val dataSource: DataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = databaseUrl.trim().let { if (it.startsWith("jdbc:")) it else "jdbc:$it" }
        maximumPoolSize = 2
        connectionTimeout = 1_000
        addDataSourceProperty("socketTimeout", "3")
    })

    override suspend fun load(range: LlmRange): AdminUsageOverview = withContext(Dispatchers.IO) {
        val start = Timestamp.from(range.from.atStartOfDay(zone).toInstant())
        val end = Timestamp.from(range.to.plusDays(1).atStartOfDay(zone).toInstant())
        val rows = linkedMapOf<String, AdminUsageDay>()
        var activeInPeriod = 0L
        var firstObservedDay: String? = null
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT min((received_at AT TIME ZONE 'Europe/Moscow')::date)
                FROM interaction_events WHERE direction = 'incoming'
            """.trimIndent()).use { statement ->
                statement.executeQuery().use { result ->
                    if (result.next()) firstObservedDay = result.getString(1)
                }
            }
            connection.prepareStatement("""
                SELECT count(DISTINCT chat_id)
                FROM interaction_events
                WHERE occurred_at >= ? AND occurred_at < ?
                  AND direction = 'incoming' AND status = 'received'
                  AND kind IN ('voice', 'text', 'callback') AND chat_id IS NOT NULL
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, start)
                statement.setTimestamp(2, end)
                statement.executeQuery().use { result ->
                    if (result.next()) activeInPeriod = result.getLong(1)
                }
            }
            connection.prepareStatement("""
                SELECT (occurred_at AT TIME ZONE 'Europe/Moscow')::date AS day,
                       count(DISTINCT chat_id) AS active_chats
                FROM interaction_events
                WHERE occurred_at >= ? AND occurred_at < ?
                  AND direction = 'incoming' AND status = 'received'
                  AND kind IN ('voice', 'text', 'callback') AND chat_id IS NOT NULL
                GROUP BY 1
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, start)
                statement.setTimestamp(2, end)
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val day = result.getString("day")
                        rows[day] = AdminUsageDay(day, activeChats = result.getLong("active_chats"))
                    }
                }
            }
            connection.prepareStatement("""
                SELECT (v.received_at AT TIME ZONE 'Europe/Moscow')::date AS day,
                       count(*) AS voices,
                       count(*) FILTER (WHERE v.outcome = 'delivered' AND EXISTS (
                           SELECT 1 FROM interaction_events e
                           WHERE e.attempt_id = v.attempt_id AND e.direction = 'internal'
                             AND e.kind = 'transcript' AND e.status = 'ready'
                       )) AS exchanges,
                       count(*) FILTER (WHERE v.eligible = true AND v.outcome IS NOT NULL
                           AND v.outcome NOT IN ('delivered', 'not_eligible')) AS voice_errors
                FROM telegram_voice_attempts v
                WHERE v.received_at >= ? AND v.received_at < ?
                GROUP BY 1
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, start)
                statement.setTimestamp(2, end)
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val day = result.getString("day")
                        val old = rows[day] ?: AdminUsageDay(day)
                        rows[day] = old.copy(voices = result.getLong("voices"),
                            exchanges = result.getLong("exchanges"), voiceErrors = result.getLong("voice_errors"))
                    }
                }
            }
            connection.prepareStatement("""
                SELECT (outgoing.occurred_at AT TIME ZONE 'Europe/Moscow')::date AS day,
                       count(*) AS telegram_api_calls
                FROM interaction_events outgoing
                WHERE outgoing.occurred_at >= ? AND outgoing.occurred_at < ?
                  AND outgoing.direction = 'outgoing' AND outgoing.status = 'attempted'
                  AND outgoing.attempt_id IS NOT NULL
                GROUP BY 1
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, start)
                statement.setTimestamp(2, end)
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val day = result.getString("day")
                        val old = rows[day] ?: AdminUsageDay(day)
                        rows[day] = old.copy(telegramApiCalls = result.getLong("telegram_api_calls"))
                    }
                }
            }
        }
        AdminUsageOverview(activeInPeriod,
            generateSequence(range.from) { previous -> previous.plusDays(1).takeIf { it <= range.to } }
                .map { day -> rows[day.toString()] ?: AdminUsageDay(day.toString()) }.toList(),
            firstObservedDay)
    }

    override fun close() = (dataSource as HikariDataSource).close()
}
