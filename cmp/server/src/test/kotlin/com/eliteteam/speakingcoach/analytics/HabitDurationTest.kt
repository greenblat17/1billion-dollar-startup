package com.eliteteam.speakingcoach.analytics

import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HabitDurationTest {
    @Test
    fun postgresKeepsIdempotentCrossMidnightDurationAfterPruning() = runTest {
        val url = System.getenv("TEST_POSTGRES_URL") ?: return@runTest
        val root = if (url.startsWith("jdbc:")) url else "jdbc:$url"
        val schema = "habits_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(root).use { it.createStatement().use { st -> st.execute("CREATE SCHEMA $schema") } }
        val scoped = root + (if ('?' in root) "&" else "?") + "currentSchema=$schema"
        try {
            PostgresCallEventStore(scoped).use { store ->
                val opened = Instant.parse("2026-10-10T20:59:00Z")
                val call = "a".repeat(32)
                store.record(CallEvent("open", "call_open", 1, opened, callId = call))
                store.record(CallEvent("received", "voice_received", 1, opened.plusSeconds(120), callId = call, messageId = 7))
                val processed = CallEvent("processed", "voice_processed", 1, opened.plusSeconds(125),
                    callId = call, messageId = 7, seconds = 25.0)
                store.record(processed)
                store.record(processed)
                store.record(processed.copy(id = "duplicate-message"))
                assertEquals(0L, store.duration(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10")).calls)
                store.record(CallEvent("closed", "call_closed", 1, opened.plusSeconds(172800), callId = call))
                val before = store.duration(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10"))
                assertEquals(1L, before.calls)
                assertEquals(25.0, before.speechSeconds)
                assertEquals(0L, store.duration(LocalDate.parse("2026-10-11"), LocalDate.parse("2026-10-11")).calls)
                store.prune(opened.plusSeconds(40L * 86400))
                assertEquals(emptyList(), store.byCall(call))
                assertEquals(before, store.duration(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10")))
                store.record(processed)
                assertEquals(before, store.duration(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10")))
                store.prune(opened.plusSeconds(370L * 86400))
                assertEquals(0L, store.duration(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10")).calls)
            }
        } finally {
            DriverManager.getConnection(root).use { it.createStatement().use { st -> st.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }

    @Test
    fun unknownSecondsAndMismatchedMessagesAreExcluded() = runTest {
        val store = MemoryCallEventStore()
        val at = Instant.parse("2026-10-10T12:00:00Z")
        val id = "b".repeat(32)
        store.record(CallEvent("open", "call_open", 1, at, callId = id))
        store.record(CallEvent("close", "call_closed", 1, at, callId = id))
        store.record(CallEvent("voice", "voice_received", 1, at, callId = id, messageId = 1))
        store.record(CallEvent("processed", "voice_processed", 1, at, callId = id, messageId = 1))
        val day = LocalDate.parse("2026-10-10")
        assertEquals(0L, store.duration(day, day).calls)
        assertEquals(1L, store.duration(day, day).incomplete)
        store.record(CallEvent("processed", "voice_processed", 1, at, callId = id, messageId = 1, seconds = 20.0))
        assertEquals(1L, store.duration(day, day).calls)
        assertEquals(20.0, store.duration(day, day).speechSeconds)
    }
}
