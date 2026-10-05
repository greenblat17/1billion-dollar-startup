package com.eliteteam.speakingcoach.analytics

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

private const val CONTENT_SECONDS = 7L * 86_400L
private const val EVENT_SECONDS = 30L * 86_400L
private const val MAX_AUDIO_BYTES = 20 * 1024 * 1024

internal data class InteractionEvent(
    val id: String,
    val chatId: Long?,
    val direction: String,
    val kind: String,
    val status: String,
    val occurredAt: Instant = Instant.now(),
    val receivedAt: Instant = occurredAt,
    val updateId: Long? = null,
    val messageId: Long? = null,
    val attemptId: String? = null,
    val jobId: String? = null,
    val content: String? = null,
    val audioFile: String? = null,
    val audioSize: Int? = null,
    val audioSha256: String? = null,
    val voiceOutcome: String? = null,
    val voiceStage: String? = null,
    val voiceReason: String? = null,
    val telegramUsername: String? = null,
    val telegramName: String? = null,
)

internal data class InteractionChat(
    val chatId: Long,
    val username: String?,
    val displayName: String?,
    val lastIncomingAt: Instant,
)

/** Metadata lives for 30 days; content and original audio are readable for seven days. */
internal class InteractionAudit(databaseUrl: String, private val audioDir: Path) : AutoCloseable {
    private val log = LoggerFactory.getLogger(InteractionAudit::class.java)
    val writeFailures = AtomicLong()
    val pendingWrites = AtomicLong()
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queued = Channel<InteractionEvent>(1_024)
    private val writer = writerScope.launch {
        for (event in queued) {
            try {
                for (attempt in 0..2) {
                    try {
                        record(event)
                        break
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        if (attempt == 2) log.warn("Interaction audit event lost id={}", event.id, error)
                        else delay(200L * (attempt + 1))
                    }
                }
            } finally {
                pendingWrites.decrementAndGet()
            }
        }
    }
    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = databaseUrl.trim().let { if (it.startsWith("jdbc:")) it else "jdbc:$it" }
        maximumPoolSize = 2
        connectionTimeout = 1_000
        addDataSourceProperty("socketTimeout", "2")
    })

    init {
        Flyway.configure().dataSource(dataSource).load().migrate()
        Files.createDirectories(audioDir)
    }

    fun enqueue(event: InteractionEvent): Boolean {
        pendingWrites.incrementAndGet()
        if (queued.trySend(event).isSuccess) return true
        pendingWrites.decrementAndGet()
        writeFailures.incrementAndGet()
        return false
    }

    suspend fun record(event: InteractionEvent) = withContext(Dispatchers.IO) {
        require(event.id.length <= 160 && event.id.isNotBlank())
        try { dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
            connection.prepareStatement("""
                INSERT INTO interaction_events
                (event_id, chat_id, update_id, message_id, attempt_id, job_id, direction, kind, status,
                 occurred_at, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
            """.trimIndent()).use { statement ->
                statement.setString(1, event.id)
                statement.setObject(2, event.chatId)
                statement.setObject(3, event.updateId)
                statement.setObject(4, event.messageId)
                statement.setObject(5, event.attemptId?.let(UUID::fromString))
                statement.setString(6, event.jobId)
                statement.setString(7, event.direction.take(16))
                statement.setString(8, event.kind.take(48))
                statement.setString(9, event.status.take(32))
                statement.setTimestamp(10, Timestamp.from(event.occurredAt))
                statement.setTimestamp(11, Timestamp.from(event.receivedAt))
                val inserted = statement.executeUpdate() > 0
                if (inserted && event.direction == "incoming" && event.chatId != null) {
                    connection.prepareStatement("""
                        INSERT INTO interaction_chats (chat_id, username, display_name, last_incoming_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (chat_id) DO UPDATE SET
                            username = CASE WHEN EXCLUDED.last_incoming_at >= interaction_chats.last_incoming_at
                                AND (EXCLUDED.username IS NOT NULL OR EXCLUDED.display_name IS NOT NULL)
                                THEN EXCLUDED.username ELSE interaction_chats.username END,
                            display_name = CASE WHEN EXCLUDED.last_incoming_at >= interaction_chats.last_incoming_at
                                AND (EXCLUDED.username IS NOT NULL OR EXCLUDED.display_name IS NOT NULL)
                                THEN EXCLUDED.display_name ELSE interaction_chats.display_name END,
                            last_incoming_at = GREATEST(interaction_chats.last_incoming_at, EXCLUDED.last_incoming_at)
                    """.trimIndent()).use { chat ->
                        chat.setLong(1, event.chatId)
                        chat.setString(2, event.telegramUsername)
                        chat.setString(3, event.telegramName)
                        chat.setTimestamp(4, Timestamp.from(event.occurredAt))
                        chat.executeUpdate()
                    }
                }
                val unexpired = event.receivedAt.plusSeconds(CONTENT_SECONDS).isAfter(Instant.now())
                if (inserted && unexpired && (event.content != null || event.audioFile != null)) {
                    connection.prepareStatement("""
                        INSERT INTO interaction_content (event_id, content, audio_file, audio_size, audio_sha256)
                        VALUES (?, ?, ?, ?, ?)
                    """.trimIndent()).use { content ->
                        content.setString(1, event.id)
                        content.setString(2, event.content?.take(16_000))
                        content.setString(3, event.audioFile)
                        content.setObject(4, event.audioSize)
                        content.setString(5, event.audioSha256)
                        content.executeUpdate()
                    }
                }
                connection.commit()
                inserted
            }
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        } } catch (error: Throwable) {
            writeFailures.incrementAndGet()
            throw error
        }
    }

    suspend fun recordInbound(update: JsonObject, receivedAt: Instant = Instant.now()): Boolean {
        val updateId = update["update_id"]?.jsonPrimitive?.longOrNull ?: error("Telegram update ID missing")
        val callback = update["callback_query"] as? JsonObject
        val message = (update["message"] ?: update["edited_message"] ?: callback?.get("message")) as? JsonObject
        val chat = message?.get("chat") as? JsonObject
        val sender = (callback?.get("from") ?: message?.get("from")) as? JsonObject
        val chatId = chat?.get("id")?.jsonPrimitive?.longOrNull
        val messageId = message?.get("message_id")?.jsonPrimitive?.longOrNull
        val text = if (callback != null) callback["data"]?.jsonPrimitive?.content
            else message?.get("text")?.jsonPrimitive?.content ?: message?.get("caption")?.jsonPrimitive?.content
        val kind = when {
            callback != null -> "callback"
            message?.containsKey("voice") == true -> "voice"
            message?.containsKey("text") == true -> "text"
            message?.containsKey("photo") == true -> "photo"
            else -> "update"
        }
        return record(InteractionEvent(
            id = "in:$updateId", chatId = chatId, direction = "incoming", kind = kind,
            status = "received", occurredAt = receivedAt, receivedAt = receivedAt,
            updateId = updateId, messageId = messageId,
            attemptId = if (kind == "voice" && chatId != null && messageId != null)
                voiceAttemptId(chatId, messageId) else null,
            content = text,
            telegramUsername = safeOnboardingUsername(sender?.get("username")?.jsonPrimitive?.contentOrNull),
            telegramName = listOfNotNull(
                sender?.get("first_name")?.jsonPrimitive?.contentOrNull,
                sender?.get("last_name")?.jsonPrimitive?.contentOrNull,
            ).joinToString(" ").trim().filterNot { it.isISOControl() }.take(120).ifBlank { null },
        ))
    }

    suspend fun recentChats(
        search: String? = null, before: Instant? = null, beforeId: Long? = null, limit: Int = 51,
    ): List<InteractionChat> = withContext(Dispatchers.IO) {
        val rows = mutableListOf<InteractionChat>()
        val term = search?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() }?.take(80)
        val paged = before != null && beforeId != null
        val conditions = buildList {
            add("last_incoming_at > ?")
            if (term != null) add("(strpos(lower(coalesce(username, '')), lower(?)) > 0 " +
                "OR strpos(lower(coalesce(display_name, '')), lower(?)) > 0 OR strpos(chat_id::text, ?) > 0)")
            if (paged) add("(last_incoming_at, chat_id) < (?, ?)")
        }
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT chat_id, username, display_name, last_incoming_at
                FROM interaction_chats
                WHERE ${conditions.joinToString(" AND ")}
                ORDER BY last_incoming_at DESC, chat_id DESC
                LIMIT ?
            """.trimIndent()).use { statement ->
                var index = 1
                statement.setTimestamp(index++, Timestamp.from(Instant.now().minusSeconds(EVENT_SECONDS)))
                if (term != null) repeat(3) { statement.setString(index++, term) }
                if (before != null && beforeId != null) {
                    statement.setTimestamp(index++, Timestamp.from(before))
                    statement.setLong(index++, beforeId)
                }
                statement.setInt(index, limit.coerceIn(1, 101))
                statement.executeQuery().use { result ->
                    while (result.next()) rows += InteractionChat(
                        result.getLong(1), result.getString(2), result.getString(3), result.getTimestamp(4).toInstant(),
                    )
                }
            }
        }
        rows
    }

    suspend fun findChat(chatId: Long): InteractionChat? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT chat_id, username, display_name, last_incoming_at
                FROM interaction_chats WHERE chat_id = ? AND last_incoming_at > ?
            """.trimIndent()).use { statement ->
                statement.setLong(1, chatId)
                statement.setTimestamp(2, Timestamp.from(Instant.now().minusSeconds(EVENT_SECONDS)))
                statement.executeQuery().use { result ->
                    if (result.next()) InteractionChat(
                        result.getLong(1), result.getString(2), result.getString(3), result.getTimestamp(4).toInstant(),
                    ) else null
                }
            }
        }
    }

    suspend fun receiptTime(chatId: Long, messageId: Long): Instant? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT received_at FROM interaction_events
                WHERE chat_id = ? AND message_id = ? AND direction = 'incoming'
                ORDER BY received_at LIMIT 1
            """.trimIndent()).use { statement ->
                statement.setLong(1, chatId)
                statement.setLong(2, messageId)
                statement.executeQuery().use { result ->
                    if (result.next()) result.getTimestamp(1).toInstant() else null
                }
            }
        }
    }

    suspend fun pendingArtifacts(limit: Int = 500): List<InteractionEvent> = withContext(Dispatchers.IO) {
        val rows = mutableListOf<InteractionEvent>()
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT i.event_id, i.chat_id, i.message_id, i.attempt_id, i.received_at
                FROM interaction_events i
                JOIN telegram_voice_attempts v ON v.attempt_id = i.attempt_id
                WHERE i.kind = 'voice' AND i.attempt_id IS NOT NULL AND i.received_at > ?
                  AND v.eligible = true AND v.outcome NOT IN ('queue_full', 'download_failed', 'not_eligible')
                  AND i.reconcile_count < 8
                  AND (i.reconciled_at IS NULL OR i.reconciled_at < now() - interval '1 hour')
                  AND (NOT EXISTS (SELECT 1 FROM interaction_events t WHERE t.event_id = 'stt:' || i.attempt_id)
                    OR NOT EXISTS (SELECT 1 FROM interaction_events r WHERE r.event_id = 'generated:' || i.attempt_id))
                ORDER BY i.received_at DESC LIMIT ?
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(CONTENT_SECONDS)))
                statement.setInt(2, limit.coerceIn(1, 2_000))
                statement.executeQuery().use { result ->
                    while (result.next()) rows += InteractionEvent(
                        id = result.getString(1), chatId = result.getLong(2), messageId = result.getLong(3),
                        attemptId = result.getObject(4).toString(), receivedAt = result.getTimestamp(5).toInstant(),
                        direction = "incoming", kind = "voice", status = "received",
                    )
                }
            }
        }
        rows
    }

    suspend fun markReconciled(eventId: String) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                UPDATE interaction_events SET reconcile_count = reconcile_count + 1, reconciled_at = now()
                WHERE event_id = ? AND kind = 'voice'
            """.trimIndent()).use { statement ->
                statement.setString(1, eventId)
                statement.executeUpdate()
            }
        }
    }

    suspend fun saveAudio(chatId: Long, messageId: Long, receivedAt: Instant, bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty() || bytes.size > MAX_AUDIO_BYTES ||
                !receivedAt.plusSeconds(CONTENT_SECONDS).isAfter(Instant.now())) return@withContext false
            if (Files.getFileStore(audioDir).usableSpace < maxOf(100L * 1024 * 1024, bytes.size * 2L)) return@withContext false
            val attemptId = voiceAttemptId(chatId, messageId)
            val name = "$attemptId.ogg"
            val target = audioDir.resolve(name)
            val temporary = Files.createTempFile(audioDir, "incoming-", ".tmp")
            try {
                Files.write(temporary, bytes)
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                try {
                    record(InteractionEvent(
                        id = "audio:$attemptId", chatId = chatId, direction = "incoming", kind = "audio",
                        status = "saved", receivedAt = receivedAt, attemptId = attemptId,
                        messageId = messageId, audioFile = name, audioSize = bytes.size,
                        audioSha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                            .joinToString("") { "%02x".format(it) },
                    ))
                } catch (error: Throwable) {
                    Files.deleteIfExists(target)
                    throw error
                }
                true
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

    suspend fun list(
        chatId: Long?, attemptId: String? = null, jobId: String? = null,
        from: Instant? = null, to: Instant? = null,
        before: Instant? = null, beforeId: String? = null, limit: Int = 100,
    ): List<InteractionEvent> = withContext(Dispatchers.IO) {
        if (chatId == null && attemptId == null && jobId == null) return@withContext emptyList()
        val rows = mutableListOf<InteractionEvent>()
        val clauses = mutableListOf("i.occurred_at > ?")
        if (chatId != null) clauses += "i.chat_id = ?"
        if (attemptId != null) clauses += "i.attempt_id = ?"
        if (jobId != null) clauses += "i.job_id = ?"
        if (to != null) clauses += "i.occurred_at < ?"
        if (before != null && beforeId != null) clauses += "(i.occurred_at, i.event_id) < (?, ?)"
        val floor = maxOf(Instant.now().minusSeconds(EVENT_SECONDS), from ?: Instant.EPOCH)
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT i.event_id, i.chat_id, i.update_id, i.message_id, i.attempt_id, i.job_id, i.direction, i.kind, i.status,
                       i.occurred_at, i.received_at,
                       CASE WHEN i.received_at > ? THEN c.content ELSE NULL END, c.audio_file,
                       v.outcome, v.stage, v.reason
                FROM interaction_events i
                LEFT JOIN interaction_content c ON i.event_id = c.event_id
                LEFT JOIN telegram_voice_attempts v ON i.kind = 'voice' AND i.attempt_id = v.attempt_id
                WHERE ${clauses.joinToString(" AND ")}
                ORDER BY i.occurred_at DESC, i.event_id DESC LIMIT ?
            """.trimIndent()).use { statement ->
                var index = 1
                statement.setTimestamp(index++, Timestamp.from(Instant.now().minusSeconds(CONTENT_SECONDS)))
                statement.setTimestamp(index++, Timestamp.from(floor))
                if (chatId != null) statement.setLong(index++, chatId)
                if (attemptId != null) statement.setObject(index++, UUID.fromString(attemptId))
                if (jobId != null) statement.setString(index++, jobId)
                if (to != null) statement.setTimestamp(index++, Timestamp.from(to))
                if (before != null && beforeId != null) {
                    statement.setTimestamp(index++, Timestamp.from(before))
                    statement.setString(index++, beforeId)
                }
                statement.setInt(index, limit.coerceIn(1, 200))
                statement.executeQuery().use { result ->
                    while (result.next()) rows += InteractionEvent(
                        id = result.getString(1), chatId = result.getLong(2),
                        updateId = result.getLong(3).takeUnless { result.wasNull() },
                        messageId = result.getLong(4).takeUnless { result.wasNull() },
                        attemptId = result.getObject(5)?.toString(), jobId = result.getString(6),
                        direction = result.getString(7), kind = result.getString(8), status = result.getString(9),
                        occurredAt = result.getTimestamp(10).toInstant(), receivedAt = result.getTimestamp(11).toInstant(),
                        content = result.getString(12), audioFile = result.getString(13),
                        voiceOutcome = result.getString(14), voiceStage = result.getString(15),
                        voiceReason = result.getString(16),
                    )
                }
            }
        }
        rows
    }

    suspend fun readAudio(attemptId: String): ByteArray? = withContext(Dispatchers.IO) {
        val id = runCatching { UUID.fromString(attemptId) }.getOrNull() ?: return@withContext null
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT c.audio_file FROM interaction_events i
                JOIN interaction_content c ON c.event_id = i.event_id
                WHERE i.attempt_id = ? AND i.kind = 'audio' AND i.received_at > ? LIMIT 1
            """.trimIndent()).use { statement ->
                statement.setObject(1, id)
                statement.setTimestamp(2, Timestamp.from(Instant.now().minusSeconds(CONTENT_SECONDS)))
                statement.executeQuery().use { result ->
                    if (!result.next()) return@withContext null
                    val name = result.getString(1) ?: return@withContext null
                    if (name != "$id.ogg") return@withContext null
                    val path = audioDir.resolve(name)
                    if (Files.isRegularFile(path)) Files.readAllBytes(path) else null
                }
            }
        }
    }

    suspend fun prune(now: Instant = Instant.now()) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            var more: Boolean
            do {
                val batch = mutableListOf<Pair<String, String?>>()
                connection.prepareStatement("""
                    SELECT i.event_id, c.audio_file FROM interaction_events i
                    JOIN interaction_content c ON c.event_id = i.event_id
                    WHERE i.received_at <= ?
                    ORDER BY i.received_at, i.event_id LIMIT 500
                """.trimIndent()).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now.minusSeconds(CONTENT_SECONDS)))
                    statement.executeQuery().use { result ->
                        while (result.next()) batch += result.getString(1) to result.getString(2)
                    }
                }
                batch.forEach { (id, name) ->
                    if (name != null && name.matches(Regex("[0-9a-f-]{36}\\.ogg"))) {
                        Files.deleteIfExists(audioDir.resolve(name))
                    }
                    connection.prepareStatement("DELETE FROM interaction_content WHERE event_id = ?").use {
                        it.setString(1, id)
                        it.executeUpdate()
                    }
                }
                more = batch.size == 500
            } while (more)
            var deleted: Int
            do {
                connection.prepareStatement("""
                    DELETE FROM interaction_events WHERE event_id IN
                    (SELECT event_id FROM interaction_events WHERE received_at <= ? LIMIT 500)
                """.trimIndent()).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now.minusSeconds(EVENT_SECONDS)))
                    deleted = statement.executeUpdate()
                }
            } while (deleted == 500)
            connection.prepareStatement("DELETE FROM interaction_chats WHERE last_incoming_at <= ?").use {
                it.setTimestamp(1, Timestamp.from(now.minusSeconds(EVENT_SECONDS)))
                it.executeUpdate()
            }
            val referenced = mutableSetOf<String>()
            connection.prepareStatement("SELECT audio_file FROM interaction_content WHERE audio_file IS NOT NULL").use {
                it.executeQuery().use { result -> while (result.next()) referenced += result.getString(1) }
            }
            Files.list(audioDir).use { files ->
                files.filter { Files.isRegularFile(it) &&
                    (it.fileName.toString().endsWith(".tmp") || it.fileName.toString().endsWith(".ogg")) &&
                    it.fileName.toString() !in referenced &&
                    Files.getLastModifiedTime(it).toInstant().isBefore(now.minusSeconds(86_400))
                }.forEach { Files.deleteIfExists(it) }
            }
        }
    }

    override fun close() {
        queued.close()
        runBlocking { withTimeoutOrNull(5_000) { writer.join() } }
        if (pendingWrites.get() > 0) writeFailures.addAndGet(pendingWrites.get())
        writerScope.cancel()
        dataSource.close()
    }
}
