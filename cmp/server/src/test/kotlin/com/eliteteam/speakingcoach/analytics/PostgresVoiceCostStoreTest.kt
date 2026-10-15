package com.eliteteam.speakingcoach.analytics

import com.eliteteam.speakingcoach.LlmRange
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresVoiceCostStoreTest {
    @Test
    fun latePriceUpdatesOriginalExchangeAndDuplicateDeliveryDoesNotAddCost() = runTest {
        val url = System.getenv("TEST_POSTGRES_URL") ?: return@runTest
        val root = if (url.startsWith("jdbc:")) url else "jdbc:$url"
        val schema = "cost_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(root).use { it.createStatement().use { statement ->
            statement.execute("CREATE SCHEMA $schema")
        } }
        val schemaUrl = root + (if ('?' in root) "&" else "?") + "currentSchema=$schema"
        try {
            VoiceCostStore(schemaUrl).use { store ->
                val delivered = Instant.now()
                val day = delivered.atZone(ZoneId.of("Europe/Moscow")).toLocalDate()
                val range = LlmRange(day, day)
                val attempt = UUID.randomUUID().toString()
                val tts = VoiceCostEvent(UUID.randomUUID().toString(), attempt, "tts", "openrouter", "tts-model",
                    "success", delivered.toString(), null, "pending", "gen-1", 1)
                store.recordExchange(attempt, delivered, mapOf("stt" to 1, "llm" to 1, "tts" to 2))
                store.recordExchange(attempt, delivered)
                store.upsert(listOf(tts))
                assertEquals(1, store.load(range).exchanges)
                assertEquals(0, store.load(range).completeExchanges)
                assertEquals(day.toString(), store.load(range).trackingSince)

                val stt = tts.copy(callId = UUID.randomUUID().toString(), component = "stt",
                    costMicroUsd = 100, priceStatus = "known", generationId = null)
                val llm = stt.copy(callId = UUID.randomUUID().toString(), component = "llm", costMicroUsd = 200)
                store.upsert(listOf(stt, llm, tts.copy(costMicroUsd = 90, priceStatus = "known", revision = 2)))
                store.upsert(listOf(tts)) // old retry must not overwrite the settled price
                val result = store.load(range)
                assertEquals(1, result.exchanges)
                assertEquals(0, result.completeExchanges)
                assertEquals(390, result.knownCostMicro)
                assertEquals(1, result.unknownPrices)
                store.upsert(listOf(tts.copy(callId = UUID.randomUUID().toString(), costMicroUsd = 90,
                    priceStatus = "known", revision = 1)))
                val complete = store.load(range)
                assertEquals(1, complete.completeExchanges)
                assertEquals(480, complete.knownCostMicro)
                assertEquals(0, complete.unknownPrices)
            }
        } finally {
            DriverManager.getConnection(root).use { it.createStatement().use { statement ->
                statement.execute("DROP SCHEMA $schema CASCADE")
            } }
        }
    }
}
