package com.eliteteam.speakingcoach.analytics

import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class VocabularyAnalyticsStoreTest {
    @Test
    fun postgresMigrationAndCursorCommitKeepOneCopyPerFact() = runTest {
        val root = System.getenv("TEST_POSTGRES_URL") ?: return@runTest
        val schema = "vocab_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(root).use { it.createStatement().use { statement ->
            statement.execute("CREATE SCHEMA $schema")
        } }
        val scoped = root + (if ('?' in root) "&" else "?") + "currentSchema=$schema"
        try {
            PostgresVocabularyFactStore(scoped).use { store ->
                val at = Instant.parse("2026-11-04T12:00:00Z")
                val fact = VocabularyFact(id = "lesson:created", sessionId = "tg-42",
                    at = at.toEpochMilli() / 1000.0, kind = "lesson_created",
                    lessonId = "a".repeat(12), format = 5, wordCount = 1,
                    words = listOf(VocabularyFactWord("b".repeat(12), "manual")))
                val batch = VocabularyFactsBatch(listOf(fact, fact), "10-0")
                store.ingest(batch)
                store.ingest(batch)
                assertEquals("10-0", store.cursor())
                assertEquals(1, store.byLesson(fact.lessonId).size)
                assertEquals(1, store.since(at.minusSeconds(1)).size)
            }
        } finally {
            DriverManager.getConnection(root).use { it.createStatement().use { statement ->
                statement.execute("DROP SCHEMA $schema CASCADE")
            } }
        }
    }
}
