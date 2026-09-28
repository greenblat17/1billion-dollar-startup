package com.eliteteam.speakingcoach.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

internal class PostgresAppStore(databaseUrl: String) : AppStore {
    private val dataSource: DataSource = hikari(databaseUrl)

    init {
        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate()
    }

    override suspend fun createUser(email: String, passwordHash: String, displayName: String): AppUser =
        withContext(Dispatchers.IO) {
            val user = AppUser(
                id = UUID.randomUUID().toString(),
                email = email,
                displayName = displayName,
                passwordHash = passwordHash,
            )
            try {
                dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        "INSERT INTO users (id, email, password_hash, display_name) VALUES (?, ?, ?, ?)",
                    ).use { statement ->
                        statement.setString(1, user.id)
                        statement.setString(2, user.email)
                        statement.setString(3, user.passwordHash)
                        statement.setString(4, user.displayName)
                        statement.executeUpdate()
                    }
                }
            } catch (error: SQLException) {
                if (error.sqlState == UNIQUE_VIOLATION) {
                    throw DuplicateEmailException()
                }
                throw error
            }
            user
        }

    override suspend fun findUserByEmail(email: String): AppUser? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT id, email, password_hash, display_name FROM users WHERE email = ?",
                ).use { statement ->
                    statement.setString(1, email)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.toUser() else null
                    }
                }
            }
        }

    override suspend fun findUserById(id: String): AppUser? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT id, email, password_hash, display_name FROM users WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, id)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.toUser() else null
                    }
                }
            }
        }

    override suspend fun createSession(userId: String, topic: String, tutorVoice: String): SpeakingSession =
        withContext(Dispatchers.IO) {
            val session = SpeakingSession(
                id = "app-$userId-${UUID.randomUUID()}",
                userId = userId,
                topic = topic,
                tutorVoice = tutorVoice,
            )
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO speaking_sessions (id, user_id, topic, tutor_voice) VALUES (?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, session.id)
                    statement.setString(2, session.userId)
                    statement.setString(3, session.topic)
                    statement.setString(4, session.tutorVoice)
                    statement.executeUpdate()
                }
            }
            session
        }

    override suspend fun findSession(id: String): SpeakingSession? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                loadSession(connection, id)
            }
        }

    override suspend fun userHasActiveRtc(userId: String): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT 1 FROM speaking_sessions WHERE user_id = ? AND rtc_active = TRUE LIMIT 1",
                ).use { statement ->
                    statement.setString(1, userId)
                    statement.executeQuery().use { it.next() }
                }
            }
        }

    override suspend fun tryStartRtc(sessionId: String): RtcStart =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                startRtc(connection, sessionId)
            }
        }

    override suspend fun attachOpenaiCallId(sessionId: String, openaiCallId: String?) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE speaking_sessions SET openai_call_id = ? WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, openaiCallId)
                    statement.setString(2, sessionId)
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun releaseRtc(sessionId: String) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE speaking_sessions SET rtc_active = FALSE, status = ? WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, SessionStatus.Created.name)
                    statement.setString(2, sessionId)
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun markCompleted(sessionId: String, durationSec: Int, tooShort: Boolean): SpeakingSession? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val status = if (tooShort) SessionStatus.TooShort else SessionStatus.Reviewing
                connection.prepareStatement(
                    "UPDATE speaking_sessions SET rtc_active = FALSE, duration_sec = ?, status = ? WHERE id = ?",
                ).use { statement ->
                    statement.setInt(1, durationSec)
                    statement.setString(2, status.name)
                    statement.setString(3, sessionId)
                    statement.executeUpdate()
                }
                loadSession(connection, sessionId)
            }
        }

    override suspend fun saveReview(sessionId: String, payloadJson: String) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO reviews (session_id, payload) VALUES (?, ?::jsonb)
                    ON CONFLICT (session_id) DO UPDATE SET payload = EXCLUDED.payload
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.setString(2, payloadJson)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "UPDATE speaking_sessions SET status = ?, rtc_active = FALSE WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, SessionStatus.Ready.name)
                    statement.setString(2, sessionId)
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun findReviewJson(sessionId: String): String? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT payload::text FROM reviews WHERE session_id = ?",
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.getString(1) else null
                    }
                }
            }
        }

    override suspend fun markReviewFailed(sessionId: String) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE speaking_sessions SET status = ?, rtc_active = FALSE WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, SessionStatus.ReviewFailed.name)
                    statement.setString(2, sessionId)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun loadSession(connection: Connection, id: String): SpeakingSession? =
        connection.prepareStatement(
            """
            SELECT id, user_id, topic, tutor_voice, duration_sec, openai_call_id, rtc_active, status
            FROM speaking_sessions WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    return@use null
                }
                SpeakingSession(
                    id = rows.getString("id"),
                    userId = rows.getString("user_id"),
                    topic = rows.getString("topic"),
                    tutorVoice = rows.getString("tutor_voice"),
                    rtcActive = rows.getBoolean("rtc_active"),
                    openaiCallId = rows.getString("openai_call_id"),
                    durationSec = rows.getInt("duration_sec").takeUnless { rows.wasNull() },
                    status = SessionStatus.valueOf(rows.getString("status")),
                )
            }
        }

    private fun startRtc(connection: Connection, sessionId: String): RtcStart {
        connection.autoCommit = false
        try {
            val session = loadSession(connection, sessionId)
            if (session == null) {
                connection.rollback()
                return RtcStart.NotFound
            }
            connection.prepareStatement(
                "SELECT 1 FROM speaking_sessions WHERE user_id = ? AND rtc_active = TRUE FOR UPDATE",
            ).use { statement ->
                statement.setString(1, session.userId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) {
                        connection.rollback()
                        return RtcStart.Conflict
                    }
                }
            }
            connection.prepareStatement(
                "UPDATE speaking_sessions SET rtc_active = TRUE, status = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, SessionStatus.Rtc.name)
                statement.setString(2, sessionId)
                statement.executeUpdate()
            }
            connection.commit()
            return RtcStart.Ok(session.copy(rtcActive = true, status = SessionStatus.Rtc))
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun ResultSet.toUser(): AppUser = AppUser(
        id = getString("id"),
        email = getString("email"),
        displayName = getString("display_name"),
        passwordHash = getString("password_hash"),
    )

    private companion object {
        const val UNIQUE_VIOLATION = "23505"

        fun hikari(databaseUrl: String): HikariDataSource {
            val config = HikariConfig()
            config.jdbcUrl = jdbcUrl(databaseUrl)
            config.maximumPoolSize = 5
            return HikariDataSource(config)
        }

        fun jdbcUrl(databaseUrl: String): String {
            val trimmed = databaseUrl.trim()
            return if (trimmed.startsWith("jdbc:")) trimmed else "jdbc:$trimmed"
        }
    }
}
