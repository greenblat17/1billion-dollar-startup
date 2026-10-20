package com.eliteteam.speakingcoach.telegram

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal data class ReminderButton(val token: UUID, val chatId: Long, val messageId: Long,
                                   val openingQuestion: String?, val source: String? = null,
                                   val copyKind: String? = null)

/** The latest accepted reminder owns the only live reminder button for a session. */
internal class ReminderButtonStore(databaseUrl: String) : AutoCloseable {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = databaseUrl.trim().let { if (it.startsWith("jdbc:")) it else "jdbc:$it" }
        maximumPoolSize = 3
        connectionTimeout = 2_000
        addDataSourceProperty("socketTimeout", "4")
    })

    init { Flyway.configure().dataSource(dataSource).load().migrate() }

    override fun close() = dataSource.close()

    suspend fun activate(sessionId: String, button: ReminderButton): ReminderButton? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
                    statement.setLong(1, button.chatId)
                    statement.executeQuery().close()
                }
                val previous = connection.prepareStatement("""
                    SELECT token, chat_id, message_id, opening_question FROM reminder_buttons
                    WHERE chat_id = ? AND active FOR UPDATE
                """.trimIndent()).use { statement ->
                    statement.setLong(1, button.chatId)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) ReminderButton(rows.getObject(1, UUID::class.java), rows.getLong(2),
                            rows.getLong(3), rows.getString(4)) else null
                    }
                }
                connection.prepareStatement("""
                    UPDATE reminder_buttons SET active = FALSE, opening_question = NULL
                    WHERE chat_id = ? AND active
                """.trimIndent())
                    .use { statement -> statement.setLong(1, button.chatId); statement.executeUpdate() }
                connection.prepareStatement("""
                    INSERT INTO reminder_buttons
                      (token, session_id, chat_id, message_id, opening_question, source, copy_kind)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
                    statement.setObject(1, button.token)
                    statement.setString(2, sessionId)
                    statement.setLong(3, button.chatId)
                    statement.setLong(4, button.messageId)
                    statement.setString(5, button.openingQuestion)
                    statement.setString(6, button.source)
                    statement.setString(7, button.copyKind)
                    statement.executeUpdate()
                }
                connection.commit()
                previous
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    suspend fun active(token: UUID, chatId: Long, messageId: Long): ReminderButton? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT opening_question FROM reminder_buttons
                WHERE token = ? AND chat_id = ? AND message_id = ? AND active
            """.trimIndent()).use { statement ->
                statement.setObject(1, token)
                statement.setLong(2, chatId)
                statement.setLong(3, messageId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) ReminderButton(token, chatId, messageId, rows.getString(1)) else null
                }
            }
        }
    }

    suspend fun markClicked(token: UUID, at: Instant = Instant.now()) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                UPDATE reminder_buttons SET clicked_at = COALESCE(clicked_at, ?)
                WHERE token = ? AND active
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, Timestamp.from(at))
                statement.setObject(2, token)
                statement.executeUpdate()
            }
        }
    }

    suspend fun markClicked(chatId: Long, messageId: Long, at: Instant) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                UPDATE reminder_buttons SET clicked_at = COALESCE(clicked_at, ?)
                WHERE chat_id = ? AND message_id = ? AND active
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, Timestamp.from(at))
                statement.setLong(2, chatId)
                statement.setLong(3, messageId)
                statement.executeUpdate()
            }
        }
    }

    suspend fun recordConfiguredAttempt(sessionId: String, day: LocalDate, at: Instant,
                                        status: String, errorCode: String?) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                INSERT INTO configured_practice_attempts (session_id, day, recorded_at, status, error_code)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (session_id, day) DO NOTHING
            """.trimIndent()).use { statement ->
                statement.setString(1, sessionId)
                statement.setObject(2, day)
                statement.setTimestamp(3, Timestamp.from(at))
                statement.setString(4, status)
                statement.setString(5, errorCode)
                statement.executeUpdate()
            }
        }
    }

    suspend fun isStale(chatId: Long, messageId: Long): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT NOT active FROM reminder_buttons WHERE chat_id = ? AND message_id = ?
            """.trimIndent()).use { statement ->
                statement.setLong(1, chatId)
                statement.setLong(2, messageId)
                statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
            }
        }
    }

    suspend fun inactiveToClear(limit: Int = 100): List<ReminderButton> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT token, chat_id, message_id, opening_question FROM reminder_buttons
                WHERE NOT active AND markup_cleared_at IS NULL
                ORDER BY created_at LIMIT ?
            """.trimIndent()).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(ReminderButton(rows.getObject(1, UUID::class.java),
                        rows.getLong(2), rows.getLong(3), rows.getString(4)))
                } }
            }
        }
    }

    suspend fun markCleared(token: UUID, at: Instant = Instant.now()) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                UPDATE reminder_buttons SET markup_cleared_at = ? WHERE token = ? AND NOT active
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, Timestamp.from(at))
                statement.setObject(2, token)
                statement.executeUpdate()
            }
        }
    }
}
