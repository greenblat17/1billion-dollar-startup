package com.eliteteam.speakingcoach.analytics

import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresCallAnalyticsTest {
    @Test
    fun migratesWritesAndPrunesEvents() = runTest {
        val url = System.getenv("TEST_POSTGRES_URL") ?: return@runTest
        val root = if (url.startsWith("jdbc:")) url else "jdbc:$url"
        val schema = "calls_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(root).use { it.createStatement().use { statement -> statement.execute("CREATE SCHEMA $schema") } }
        val schemaUrl = root + (if ('?' in root) "&" else "?") + "currentSchema=$schema"
        try {
            val store = PostgresCallEventStore(schemaUrl)
            try {
                val now = Instant.now()
                val callId = "b".repeat(32)
                store.record(CallEvent("start:1", "start_pressed", 42, now, state = "eligible"))
                store.record(CallEvent("start:1", "start_pressed", 42, now, callId = callId, state = "ready"))
                store.record(CallEvent("reply:1", "reply_delivered", 42, now,
                    callId = callId, botMessageId = 90, milliseconds = 2100, state = "audio"))
                store.record(CallEvent("open:1", "call_open", 42, now, callId = callId,
                    state = "button", scenarioKind = "job"))
                store.record(CallEvent("open:1", "call_open", 42, now, callId = callId,
                    state = "voice", scenarioKind = "free"))
                assertEquals(callId, store.callIdForBot(42, 90))
                assertEquals("ready", store.snapshot(now.minusSeconds(1)).events.first { it.id == "start:1" }.state)
                assertEquals(3, store.byCall(callId).size)
                assertEquals("job", store.byCall(callId).first { it.id == "open:1" }.scenarioKind)
                store.record(CallEvent("old", "subtitle_click", 42, now.minusSeconds(31L * 86_400)))
                store.prune(now)
                assertEquals(3, store.snapshot(now.minusSeconds(40L * 86_400)).events.size)
            } finally { store.close() }
        } finally {
            DriverManager.getConnection(root).use { it.createStatement().use { statement -> statement.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
}
