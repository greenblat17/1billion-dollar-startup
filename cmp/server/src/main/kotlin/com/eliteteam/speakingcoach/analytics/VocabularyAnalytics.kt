package com.eliteteam.speakingcoach.analytics

import com.eliteteam.speakingcoach.ai.HttpClipClient
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Timestamp
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

private val vocabularyJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
private val sessionPattern = Regex("""tg-(-?\d+)|tg-ChatId\(chatId=(-?\d+)\)""")
private val lessonPattern = Regex("[a-f0-9]{12}")

@Serializable
internal data class VocabularyFactWord(
    val itemId: String = "",
    val source: String = "",
    val reviewDue: Boolean = false,
)

@Serializable
internal data class VocabularyFact(
    val id: String = "",
    val streamId: String = "",
    val sessionId: String = "",
    val at: Double = 0.0,
    val kind: String = "",
    val lessonId: String = "",
    val itemId: String = "",
    val format: Int = 0,
    val wordCount: Int = 0,
    val words: List<VocabularyFactWord> = emptyList(),
    val source: String = "",
    val dueAt: Double = 0.0,
    val reviewDue: Boolean = false,
    val phase: String = "",
    val category: String = "",
    val position: Int = 0,
    val turnId: String = "",
    val hinted: Boolean = false,
    val messageId: Long = 0,
    val botMessageId: Long = 0,
    val milliseconds: Long = 0,
) {
    val chatId: Long? get() {
        val match = sessionPattern.matchEntire(sessionId) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toLongOrNull()
    }
    val time: Instant get() = Instant.ofEpochMilli((at * 1000).toLong())
    fun safe(): VocabularyFact? = takeIf {
        id.length in 1..160 && chatId != null && at.isFinite() && at > 0 && kind.length in 1..40 &&
            (lessonId.isEmpty() || lessonPattern.matches(lessonId)) && itemId.length <= 40 &&
            words.size <= 5 && words.all { word -> word.itemId.length <= 40 && word.source in setOf("manual", "review") }
    }?.copy(
        streamId = "", source = source.take(20), phase = phase.take(32), category = category.take(48),
        turnId = turnId.take(80), position = position.coerceIn(0, 20),
        wordCount = wordCount.coerceIn(0, 5), milliseconds = milliseconds.coerceIn(0, 3_600_000),
    )
}

@Serializable
internal data class VocabularyFactsBatch(
    val events: List<VocabularyFact> = emptyList(),
    val cursor: String = "0-0",
)

internal interface VocabularyFactStore : AutoCloseable {
    suspend fun record(fact: VocabularyFact)
    suspend fun ingest(batch: VocabularyFactsBatch)
    suspend fun cursor(): String
    suspend fun since(at: Instant): List<VocabularyFact>
    suspend fun byLesson(id: String): List<VocabularyFact>
    suspend fun prune() {}
    override fun close() {}
}

internal class MemoryVocabularyFactStore : VocabularyFactStore {
    private val facts = linkedMapOf<String, VocabularyFact>()
    private val mutex = Mutex()
    private var watermark = "0-0"
    override suspend fun record(fact: VocabularyFact) {
        val safe = requireNotNull(fact.safe()) { "invalid vocabulary analytics fact" }
        mutex.withLock { facts.putIfAbsent(safe.id, safe) }
    }
    override suspend fun ingest(batch: VocabularyFactsBatch) {
        val safe = batch.events.map { requireNotNull(it.safe()) { "invalid vocabulary analytics fact" } }
        mutex.withLock {
            safe.forEach { facts.putIfAbsent(it.id, it) }
            watermark = batch.cursor
        }
    }
    override suspend fun cursor(): String = mutex.withLock { watermark }
    override suspend fun since(at: Instant): List<VocabularyFact> = mutex.withLock {
        facts.values.filter { !it.time.isBefore(at) }
    }
    override suspend fun byLesson(id: String): List<VocabularyFact> = mutex.withLock {
        facts.values.filter { it.lessonId == id }
    }
    override suspend fun prune() {
        val old = Instant.now().minusSeconds(365L * 86_400)
        val detail = Instant.now().minusSeconds(30L * 86_400)
        mutex.withLock {
            facts.values.removeIf { fact -> fact.time.isBefore(old) ||
                (fact.time.isBefore(detail) && fact.kind !in setOf("word_saved", "word_deleted", "word_due",
                    "lesson_created", "lesson_finished", "opening_delivered") &&
                    !(fact.kind == "answer" && fact.category in setOf("used", "correct", "give_up"))) }
        }
    }
}

internal class PostgresVocabularyFactStore(databaseUrl: String) : VocabularyFactStore {
    private val pool = HikariDataSource(HikariConfig().apply {
        jdbcUrl = databaseUrl
        maximumPoolSize = 2
        poolName = "vocabulary-analytics"
    })
    init { Flyway.configure().dataSource(pool).load().migrate() }

    override suspend fun record(fact: VocabularyFact): Unit = withContext(Dispatchers.IO) {
        pool.connection.use { connection -> insert(connection, fact) }
    }

    private fun insert(connection: java.sql.Connection, fact: VocabularyFact) {
        val safe = requireNotNull(fact.safe()) { "invalid vocabulary analytics fact" }
        connection.prepareStatement("""
            INSERT INTO vocabulary_analytics_events
                (event_id, occurred_at, chat_id, lesson_id, item_id, kind, fact)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (event_id) DO NOTHING
        """.trimIndent()).use { statement ->
            statement.setString(1, safe.id)
            statement.setTimestamp(2, Timestamp.from(safe.time))
            statement.setLong(3, checkNotNull(safe.chatId))
            statement.setString(4, safe.lessonId.ifEmpty { null })
            statement.setString(5, safe.itemId.ifEmpty { null })
            statement.setString(6, safe.kind)
            statement.setString(7, vocabularyJson.encodeToString(safe))
            statement.executeUpdate()
        }
    }

    override suspend fun ingest(batch: VocabularyFactsBatch): Unit = withContext(Dispatchers.IO) {
        pool.connection.use { connection ->
            connection.autoCommit = false
            try {
                batch.events.forEach { insert(connection, it) }
                connection.prepareStatement("""
                    INSERT INTO vocabulary_analytics_cursor (source, stream_id) VALUES ('ai', ?)
                    ON CONFLICT (source) DO UPDATE SET stream_id = EXCLUDED.stream_id
                """.trimIndent()).use { statement ->
                    statement.setString(1, batch.cursor)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }

    override suspend fun cursor(): String = withContext(Dispatchers.IO) {
        pool.connection.use { connection ->
            connection.prepareStatement("SELECT stream_id FROM vocabulary_analytics_cursor WHERE source = 'ai'")
                .use { statement -> statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else "0-0" } }
        }
    }

    private suspend fun query(sql: String, argument: Any): List<VocabularyFact> = withContext(Dispatchers.IO) {
        pool.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                when (argument) {
                    is Instant -> statement.setTimestamp(1, Timestamp.from(argument))
                    else -> statement.setString(1, argument.toString())
                }
                statement.fetchSize = 1000
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(vocabularyJson.decodeFromString<VocabularyFact>(rows.getString(1))) }
                }
            }
        }
    }
    override suspend fun since(at: Instant): List<VocabularyFact> =
        query("SELECT fact::text FROM vocabulary_analytics_events WHERE occurred_at >= ? ORDER BY occurred_at", at)
    override suspend fun byLesson(id: String): List<VocabularyFact> =
        query("SELECT fact::text FROM vocabulary_analytics_events WHERE lesson_id = ? ORDER BY occurred_at", id)
    override suspend fun prune(): Unit = withContext(Dispatchers.IO) {
        pool.connection.use { connection ->
            connection.prepareStatement("""
                DELETE FROM vocabulary_analytics_events WHERE event_id IN
                    (SELECT event_id FROM vocabulary_analytics_events
                     WHERE occurred_at < now() - interval '365 days'
                        OR (occurred_at < now() - interval '30 days' AND kind NOT IN
                            ('word_saved', 'word_deleted', 'word_due', 'lesson_created',
                             'lesson_finished', 'opening_delivered') AND NOT
                             (kind = 'answer' AND fact->>'category' IN ('used', 'correct', 'give_up')))
                     LIMIT 1000)
            """.trimIndent()).use { it.executeUpdate() }
        }
    }
    override fun close() { pool.close() }
}

internal class VocabularyAnalytics(
    private val store: VocabularyFactStore,
    private val ai: HttpClipClient?,
    scope: CoroutineScope,
) : AutoCloseable {
    val memoryOnly: Boolean get() = store is MemoryVocabularyFactStore
    val writeFailures = AtomicLong()
    @Volatile var lastSyncAt: Instant? = null
        private set
    @Volatile var sourceHealthy: Boolean = ai == null
        private set
    @Volatile var backlogAt: Instant? = null
        private set
    private val log = LoggerFactory.getLogger(javaClass)
    private val queue = Channel<VocabularyFact>(256)
    private val writer = scope.launch {
        for (fact in queue) try { store.record(fact) }
        catch (error: CancellationException) { throw error }
        catch (error: Throwable) { writeFailures.incrementAndGet(); log.warn("Vocabulary fact write failed", error) }
    }
    private val sync: Job? = ai?.let { client -> scope.launch {
        while (isActive) {
            try {
                val batch = client.vocabularyAnalytics(store.cursor())
                if (batch.events.isNotEmpty()) store.ingest(batch)
                val committed = store.cursor()
                if (committed != "0-0") client.vocabularyAnalyticsAck(committed)
                lastSyncAt = Instant.now()
                sourceHealthy = true
                backlogAt = if (batch.events.size >= 100) batch.events.first().time else null
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) {
                sourceHealthy = false
                writeFailures.incrementAndGet()
                log.warn("Vocabulary sync failed", error)
            }
            delay(10_000)
        }
    } }
    private val pruner = scope.launch {
        while (isActive) {
            try { store.prune() }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { log.warn("Vocabulary prune failed", error) }
            delay(3_600_000)
        }
    }

    fun record(fact: VocabularyFact) {
        if (queue.trySend(fact).isFailure) writeFailures.incrementAndGet()
    }
    suspend fun since(at: Instant): List<VocabularyFact> = store.since(at)
    suspend fun byLesson(id: String): List<VocabularyFact> = store.byLesson(id)
    override fun close() { queue.close(); sync?.cancel(); pruner.cancel(); store.close() }
    suspend fun shutdown() { queue.close(); writer.join(); sync?.cancel(); pruner.cancel(); store.close() }
}

internal fun createVocabularyFactStore(databaseUrl: String?): VocabularyFactStore =
    if (databaseUrl.isNullOrBlank()) MemoryVocabularyFactStore() else PostgresVocabularyFactStore(databaseUrl)
