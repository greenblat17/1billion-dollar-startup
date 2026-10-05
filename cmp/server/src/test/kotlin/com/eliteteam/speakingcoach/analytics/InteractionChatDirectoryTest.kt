package com.eliteteam.speakingcoach.analytics

import java.nio.file.Files
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InteractionChatDirectoryTest {
    @Test
    fun backfillsExistingChatsAndFindsNewChatsByNameOrId() = runTest {
        val url = System.getenv("TEST_POSTGRES_URL") ?: return@runTest
        val jdbcUrl = if (url.startsWith("jdbc:")) url else "jdbc:$url"
        val schema = "history_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        val schemaUrl = jdbcUrl + (if ('?' in jdbcUrl) "&" else "?") + "currentSchema=$schema"
        val audioDir = Files.createTempDirectory("history-audio-")
        try {
            Flyway.configure().dataSource(schemaUrl, null, null)
                .schemas(schema).target(MigrationVersion.fromVersion("9")).load().migrate()
            val now = Instant.now()
            DriverManager.getConnection(schemaUrl).use { connection ->
                connection.prepareStatement("""
                    INSERT INTO interaction_events
                    (event_id, chat_id, direction, kind, status, occurred_at, received_at)
                    VALUES ('old', 101, 'incoming', 'text', 'received', ?, ?)
                """.trimIndent()).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now.minusSeconds(90)))
                    statement.setTimestamp(2, Timestamp.from(now.minusSeconds(90)))
                    statement.executeUpdate()
                }
            }
            InteractionAudit(schemaUrl, audioDir).use { audit ->
                assertEquals(101L, audit.recentChats().single().chatId)
                assertEquals(null, audit.recentChats().single().displayName)

                val update = Json.parseToJsonElement("""
                    {"update_id": 1, "message": {"message_id": 1, "chat": {"id": 202},
                      "from": {"id": 202, "first_name": "Анна", "username": "anna_test"}, "text": "Привет"}}
                """.trimIndent()).jsonObject
                assertTrue(audit.recordInbound(update, now.minusSeconds(30)))
                assertEquals(false, audit.recordInbound(update, now))
                assertEquals(listOf(202L, 101L), audit.recentChats().map { it.chatId })
                assertEquals("Анна", audit.recentChats("@anna").single().displayName)
                assertEquals(202L, audit.recentChats("202").single().chatId)
                assertEquals("anna_test", audit.findChat(202)?.username)
                assertEquals(101L, audit.recentChats(before = now.minusSeconds(30), beforeId = 202).single().chatId)

                audit.prune(now.plusSeconds(31L * 86_400L))
                assertTrue(audit.recentChats().isEmpty())
            }
        } finally {
            DriverManager.getConnection(jdbcUrl).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
            Files.deleteIfExists(audioDir)
        }
    }
}
