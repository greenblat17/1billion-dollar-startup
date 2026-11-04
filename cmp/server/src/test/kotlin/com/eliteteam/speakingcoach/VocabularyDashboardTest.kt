package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.MemoryVocabularyFactStore
import com.eliteteam.speakingcoach.analytics.VocabularyFact
import com.eliteteam.speakingcoach.analytics.VocabularyFactWord
import com.eliteteam.speakingcoach.analytics.VocabularyFactsBatch
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class VocabularyDashboardTest {
    private val now = Instant.parse("2026-11-04T12:00:00Z")
    private fun fact(id: String, kind: String, at: Instant = now,
                     lessonId: String = "a".repeat(12), category: String = "") = VocabularyFact(
        id = id, sessionId = "tg-42", at = at.toEpochMilli() / 1000.0,
        kind = kind, lessonId = lessonId, category = category,
    )

    @Test
    fun duplicateIngestDoesNotInflateLessonOrUseCounts() = runTest {
        val store = MemoryVocabularyFactStore()
        val lessonId = "a".repeat(12)
        val wordId = "b".repeat(12)
        val created = fact("created", "lesson_created").copy(format = 5, wordCount = 1,
            words = listOf(VocabularyFactWord(wordId, "manual")))
        val events = listOf(created, fact("opening", "opening_delivered"),
            fact("use", "answer", category = "used").copy(phase = "Use", itemId = wordId),
            fact("finish", "lesson_finished", category = "complete"))
        val batch = VocabularyFactsBatch(events, "10-0")
        store.ingest(batch)
        store.ingest(batch)
        assertEquals("10-0", store.cursor())
        assertEquals(4, store.byLesson(lessonId).size)
        val html = vocabularyDashboardPage(store.since(now.minusSeconds(86_400)), 1, 5, 0, 0, true, now = now)
        assertTrue(html.contains("Людей / уроков</dt><dd>1 / 1"))
        assertTrue(html.contains("Собственные фразы</dt><dd>1 / 1 (100%)"))
        assertTrue(html.contains("Полностью завершены</dt><dd>1 / 1 (100%)"))
    }

    @Test
    fun theFirstLessonDoesNotCountAsNextDayReturn() = runTest {
        val store = MemoryVocabularyFactStore()
        val firstDay = now.minusSeconds(2 * 86_400)
        store.record(fact("created", "lesson_created", firstDay).copy(format = 5, wordCount = 1,
            words = listOf(VocabularyFactWord("b".repeat(12), "manual"))))
        store.record(fact("opening", "opening_delivered", firstDay))
        val html = vocabularyDashboardPage(store.since(firstDay.minusSeconds(60)), 7, null, 0, 0, true, now = now)
        assertTrue(html.contains("D1</td><td>0 / 1 (0%)"))
    }

    @Test
    fun startErrorsWithoutLessonAreVisible() {
        val error = fact("start-error", "error", lessonId = "", category = "RuntimeError").copy(phase = "start")
        val html = vocabularyDashboardPage(listOf(error), 1, null, 0, 0, true, now = now)
        assertTrue(html.contains("start / RuntimeError</td><td>1</td>"))
    }
}
