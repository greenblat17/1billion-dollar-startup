package com.eliteteam.speakingcoach.telegram

import kotlinx.coroutines.test.runTest
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderButtonStoreTest {
    @Test
    fun acceptedNextReminderInvalidatesPreviousButtonButKeepsItsMessage() = runTest {
        val raw = System.getenv("TEST_POSTGRES_URL") ?: return@runTest
        val jdbc = if (raw.startsWith("jdbc:")) raw else "jdbc:$raw"
        val schema = "buttons_${UUID.randomUUID().toString().replace("-", "")}" 
        DriverManager.getConnection(jdbc).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val scoped = jdbc + (if ('?' in jdbc) "&" else "?") + "currentSchema=$schema"
        try {
            ReminderButtonStore(scoped).use { store ->
                val first = ReminderButton(UUID.randomUUID(), 123, 10,
                    "What music have you enjoyed lately?")
                val second = ReminderButton(UUID.randomUUID(), 123, 11,
                    "What did you cook recently?")
                assertNull(store.activate("tg-123", first))
                assertEquals(first, store.active(first.token, 123, 10))
                assertNull(store.active(first.token, 124, 10))
                val firstCallback = "fu:practice:${first.token}"
                val secondCallback = "fu:practice:${second.token}"
                val opened = mutableListOf<String>()
                assertTrue(handleReminderPracticeButton(firstCallback, 123, 10, store) { opened += it })
                assertEquals(listOf("What music have you enjoyed lately?"), opened)
                assertEquals(first, store.activate("tg-ChatId(chatId=123)", second))
                assertNull(store.active(first.token, 123, 10))
                assertTrue(store.isStale(123, 10))
                assertFalse(store.isStale(123, 11))
                assertEquals(second, store.active(second.token, 123, 11))
                assertFalse(handleReminderPracticeButton(firstCallback, 123, 10, store) { opened += it })
                assertTrue(handleReminderPracticeButton(secondCallback, 123, 11, store) { opened += it })
                assertEquals(listOf("What music have you enjoyed lately?", "What did you cook recently?"), opened)
                assertEquals(listOf(first.copy(openingQuestion = null)), store.inactiveToClear())
                store.markCleared(first.token)
                assertEquals(emptyList(), store.inactiveToClear())
            }
        } finally {
            DriverManager.getConnection(jdbc).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") }
        }
    }
}
