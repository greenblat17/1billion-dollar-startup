package com.eliteteam.speakingcoach.analytics

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import com.eliteteam.speakingcoach.LlmRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.flywaydb.core.Flyway

@Serializable
internal data class VoiceCostEvent(
    val callId: String,
    val attemptId: String,
    val component: String,
    val provider: String,
    val model: String,
    val result: String,
    val occurredAt: String,
    val costMicroUsd: Long?,
    val priceStatus: String,
    val generationId: String? = null,
    val revision: Int,
    val lookupStep: Int = 0,
    val createdEpoch: Double = 0.0,
)

@Serializable
internal data class VoiceCostEvents(val events: List<VoiceCostEvent>)

@Serializable
internal data class VoiceCostReceipt(val callId: String, val revision: Int)

@Serializable
internal data class VoiceCostAcknowledge(val receipts: List<VoiceCostReceipt>)

internal data class VoiceCostDay(val day: String, val exchanges: Long, val knownCostMicro: Long,
                                 val completeExchanges: Long, val unknownPrices: Long)

internal data class VoiceCostOverview(val days: List<VoiceCostDay>, val trackingSince: String? = null) {
    val exchanges: Long get() = days.sumOf { it.exchanges }
    val knownCostMicro: Long get() = days.sumOf { it.knownCostMicro }
    val completeExchanges: Long get() = days.sumOf { it.completeExchanges }
    val unknownPrices: Long get() = days.sumOf { it.unknownPrices }
}

/** PostgreSQL is the reporting source; Redis is only the AI-to-CMP handoff. */
internal class VoiceCostStore(databaseUrl: String) : AutoCloseable {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = databaseUrl.trim().let { if (it.startsWith("jdbc:")) it else "jdbc:$it" }
        maximumPoolSize = 2
        connectionTimeout = 1_000
        addDataSourceProperty("socketTimeout", "3")
    })

    init { Flyway.configure().dataSource(dataSource).load().migrate() }

    suspend fun upsert(events: List<VoiceCostEvent>) = withContext(Dispatchers.IO) {
        require(events.size <= 100)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("""
                    INSERT INTO voice_cost_calls
                    (call_id, attempt_id, component, provider, model, result, occurred_at,
                     cost_micro_usd, price_status, generation_id, revision)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (call_id) DO UPDATE SET
                        cost_micro_usd = EXCLUDED.cost_micro_usd,
                        price_status = EXCLUDED.price_status,
                        generation_id = EXCLUDED.generation_id,
                        revision = EXCLUDED.revision,
                        updated_at = now()
                    WHERE voice_cost_calls.revision < EXCLUDED.revision
                """.trimIndent()).use { statement ->
                    for (item in events) {
                        require(item.component in setOf("stt", "llm", "tts"))
                        require(item.result in setOf("success", "error", "cancelled"))
                        require(item.priceStatus in setOf("pending", "known", "unavailable"))
                        require((item.costMicroUsd != null) == (item.priceStatus == "known"))
                        statement.setObject(1, UUID.fromString(item.callId))
                        statement.setObject(2, UUID.fromString(item.attemptId))
                        statement.setString(3, item.component)
                        statement.setString(4, item.provider.take(80))
                        statement.setString(5, item.model.take(160))
                        statement.setString(6, item.result)
                        statement.setTimestamp(7, Timestamp.from(Instant.parse(item.occurredAt)))
                        statement.setObject(8, item.costMicroUsd)
                        statement.setString(9, item.priceStatus)
                        statement.setString(10, item.generationId?.take(200))
                        statement.setInt(11, item.revision)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
        Unit
    }

    suspend fun recordExchange(attemptId: String, deliveredAt: Instant = Instant.now(),
                               expectedCalls: Map<String, Int>? = null) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                INSERT INTO voice_cost_exchanges
                (attempt_id, delivered_at, expected_stt, expected_llm, expected_tts)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (attempt_id) DO UPDATE SET
                    expected_stt = COALESCE(EXCLUDED.expected_stt, voice_cost_exchanges.expected_stt),
                    expected_llm = COALESCE(EXCLUDED.expected_llm, voice_cost_exchanges.expected_llm),
                    expected_tts = COALESCE(EXCLUDED.expected_tts, voice_cost_exchanges.expected_tts)
            """.trimIndent()).use { statement ->
                statement.setObject(1, UUID.fromString(attemptId))
                statement.setTimestamp(2, Timestamp.from(deliveredAt))
                statement.setObject(3, expectedCalls?.get("stt"))
                statement.setObject(4, expectedCalls?.get("llm"))
                statement.setObject(5, expectedCalls?.get("tts"))
                statement.executeUpdate()
            }
        }
        Unit
    }

    suspend fun load(range: LlmRange): VoiceCostOverview = withContext(Dispatchers.IO) {
        val zone = ZoneId.of("Europe/Moscow")
        val start = range.from.atStartOfDay(zone).toInstant()
        val end = range.to.plusDays(1).atStartOfDay(zone).toInstant()
        val days = mutableListOf<VoiceCostDay>()
        var trackingSince: String? = null
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT min((delivered_at AT TIME ZONE 'Europe/Moscow')::date)::text AS first_day
                FROM voice_cost_exchanges
                WHERE expected_stt IS NOT NULL AND expected_llm IS NOT NULL AND expected_tts IS NOT NULL
            """.trimIndent()).use { statement ->
                statement.executeQuery().use { result ->
                    if (result.next()) trackingSince = result.getString("first_day")
                }
            }
            connection.prepareStatement("""
                WITH per_exchange AS (
                    SELECT (e.delivered_at AT TIME ZONE 'Europe/Moscow')::date AS day,
                           e.expected_stt, e.expected_llm, e.expected_tts,
                           coalesce(sum(c.cost_micro_usd), 0) AS known_cost,
                           count(c.call_id) AS calls,
                           count(c.call_id) FILTER (WHERE c.price_status = 'known') AS priced,
                           count(c.call_id) FILTER (WHERE c.component = 'stt') AS stt,
                           count(c.call_id) FILTER (WHERE c.component = 'llm') AS llm,
                           count(c.call_id) FILTER (WHERE c.component = 'tts') AS tts
                    FROM voice_cost_exchanges e
                    LEFT JOIN voice_cost_calls c ON c.attempt_id = e.attempt_id
                    WHERE e.delivered_at >= ? AND e.delivered_at < ?
                    GROUP BY e.attempt_id, day
                )
                SELECT day, count(*) AS exchanges, sum(known_cost) AS known_cost,
                       count(*) FILTER (WHERE expected_stt > 0 AND expected_llm > 0 AND expected_tts > 0
                           AND stt = expected_stt AND llm = expected_llm AND tts = expected_tts
                           AND calls = priced) AS complete,
                       sum(calls - priced + CASE WHEN expected_stt IS NULL OR expected_llm IS NULL
                           OR expected_tts IS NULL THEN 1 ELSE
                           abs(expected_stt - stt) + abs(expected_llm - llm)
                           + abs(expected_tts - tts) END) AS unknown
                FROM per_exchange GROUP BY day ORDER BY day
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, Timestamp.from(start))
                statement.setTimestamp(2, Timestamp.from(end))
                statement.executeQuery().use { result ->
                    while (result.next()) days += VoiceCostDay(
                        result.getString("day"), result.getLong("exchanges"), result.getLong("known_cost"),
                        result.getLong("complete"), result.getLong("unknown"))
                }
            }
        }
        VoiceCostOverview(days, trackingSince)
    }

    suspend fun prune(now: Instant = Instant.now()) = withContext(Dispatchers.IO) {
        val callsCutoff = Timestamp.from(now.minusSeconds(186L * 86400))
        val exchangesCutoff = Timestamp.from(now.minusSeconds(185L * 86400))
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM voice_cost_calls WHERE occurred_at < ?").use {
                it.setTimestamp(1, callsCutoff)
                it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM voice_cost_exchanges WHERE delivered_at < ?").use {
                it.setTimestamp(1, exchangesCutoff)
                it.executeUpdate()
            }
        }
        Unit
    }

    override fun close() = dataSource.close()
}
