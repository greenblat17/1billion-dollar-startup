package com.eliteteam.speakingcoach.analytics

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

private const val RETAIN_DAYS = 30L
private const val MAX_EVENTS = 50_000
private val callIdPattern = Regex("[a-f0-9]{32}")

internal data class CallEvent(
    val id: String,
    val kind: String,
    val chatId: Long,
    val at: Instant = Instant.now(),
    val username: String = "",
    val callId: String? = null,
    val messageId: Long? = null,
    val botMessageId: Long? = null,
    val milliseconds: Long? = null,
    val seconds: Double? = null,
    val amount: Int? = null,
    val state: String? = null,
    val scenarioKind: String? = null,
) {
    fun safe(): CallEvent = copy(
        id = id.take(160), kind = kind.take(40), username = username.trim().removePrefix("@").take(64),
        callId = callId?.takeIf { callIdPattern.matches(it) },
        milliseconds = milliseconds?.coerceIn(0, 3_600_000),
        seconds = seconds?.takeIf { it.isFinite() && it >= 0 }?.coerceAtMost(86_400.0),
        amount = amount?.coerceIn(0, 100), state = state?.take(40),
        scenarioKind = scenarioKind?.takeIf { it in setOf("free", "job", "manager", "custom") },
    )
}

internal data class CallEventsSnapshot(val events: List<CallEvent>, val truncated: Boolean, val writeFailures: Long)

internal interface CallEventStore : AutoCloseable {
    suspend fun record(event: CallEvent)
    suspend fun snapshot(since: Instant, writeFailures: Long = 0): CallEventsSnapshot
    suspend fun byCall(callId: String): List<CallEvent>
    suspend fun callIdForBot(chatId: Long, botMessageId: Long): String?
    suspend fun prune(now: Instant = Instant.now()) {}
    override fun close() {}
}

internal class MemoryCallEventStore : CallEventStore {
    private val lock = Mutex()
    private val rows = linkedMapOf<String, CallEvent>()

    override suspend fun record(event: CallEvent) = lock.withLock {
        val safe = event.safe()
        val old = rows[safe.id]
        rows[safe.id] = if (old == null) safe else old.copy(
            callId = safe.callId ?: old.callId,
            username = old.username.ifBlank { safe.username },
            botMessageId = safe.botMessageId ?: old.botMessageId,
            milliseconds = safe.milliseconds ?: old.milliseconds,
            seconds = old.seconds ?: safe.seconds,
            amount = safe.amount ?: old.amount,
            state = if (old.kind == "start_pressed") safe.state ?: old.state else old.state ?: safe.state,
            scenarioKind = if (safe.scenarioKind == null || safe.scenarioKind == "free")
                old.scenarioKind ?: safe.scenarioKind else safe.scenarioKind,
        )
        Unit
    }

    override suspend fun snapshot(since: Instant, writeFailures: Long): CallEventsSnapshot = lock.withLock {
        val rows = rows.values.filter { !it.at.isBefore(since) }.sortedByDescending { it.at }
        CallEventsSnapshot(rows.take(MAX_EVENTS), rows.size > MAX_EVENTS, writeFailures)
    }

    override suspend fun byCall(callId: String): List<CallEvent> = lock.withLock {
        rows.values.filter { it.callId == callId }.sortedBy { it.at }
    }

    override suspend fun callIdForBot(chatId: Long, botMessageId: Long): String? = lock.withLock {
        rows.values.firstOrNull { it.chatId == chatId && it.botMessageId == botMessageId && it.callId != null }?.callId
    }

    override suspend fun prune(now: Instant) = lock.withLock {
        val oldest = now.minusSeconds(RETAIN_DAYS * 86_400)
        rows.entries.removeIf { it.value.at.isBefore(oldest) }
        Unit
    }
}

internal class PostgresCallEventStore(databaseUrl: String) : CallEventStore {
    private val pool = HikariDataSource(HikariConfig().apply {
        jdbcUrl = databaseUrl
        maximumPoolSize = 2
        isAutoCommit = true
        poolName = "call-analytics"
    })

    init { Flyway.configure().dataSource(pool).load().migrate() }

    override suspend fun record(event: CallEvent) = withContext(Dispatchers.IO) {
        val safe = event.safe()
        pool.connection.use { connection ->
            connection.prepareStatement("""
                INSERT INTO telegram_call_events
                  (event_id, call_id, chat_id, username, kind, occurred_at, message_id, bot_message_id, milliseconds, seconds, amount, state, scenario_kind)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO UPDATE SET
                  call_id = COALESCE(EXCLUDED.call_id, telegram_call_events.call_id),
                  username = CASE WHEN telegram_call_events.username = '' THEN EXCLUDED.username ELSE telegram_call_events.username END,
                  bot_message_id = COALESCE(EXCLUDED.bot_message_id, telegram_call_events.bot_message_id),
                  milliseconds = COALESCE(EXCLUDED.milliseconds, telegram_call_events.milliseconds),
                  seconds = COALESCE(telegram_call_events.seconds, EXCLUDED.seconds),
                  amount = COALESCE(EXCLUDED.amount, telegram_call_events.amount),
                  state = CASE WHEN telegram_call_events.kind = 'start_pressed'
                    THEN COALESCE(EXCLUDED.state, telegram_call_events.state)
                    ELSE COALESCE(telegram_call_events.state, EXCLUDED.state) END,
                  scenario_kind = CASE WHEN EXCLUDED.scenario_kind IS NULL OR EXCLUDED.scenario_kind = 'free'
                    THEN COALESCE(telegram_call_events.scenario_kind, EXCLUDED.scenario_kind)
                    ELSE EXCLUDED.scenario_kind END
            """.trimIndent()).use { statement ->
                statement.setString(1, safe.id)
                statement.setString(2, safe.callId)
                statement.setLong(3, safe.chatId)
                statement.setString(4, safe.username)
                statement.setString(5, safe.kind)
                statement.setTimestamp(6, Timestamp.from(safe.at))
                statement.setObject(7, safe.messageId)
                statement.setObject(8, safe.botMessageId)
                statement.setObject(9, safe.milliseconds)
                statement.setObject(10, safe.seconds)
                statement.setObject(11, safe.amount)
                statement.setString(12, safe.state)
                statement.setString(13, safe.scenarioKind)
                statement.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun snapshot(since: Instant, writeFailures: Long): CallEventsSnapshot = withContext(Dispatchers.IO) {
        val events = pool.connection.use { connection ->
            connection.prepareStatement("""
                SELECT event_id, call_id, chat_id, username, kind, occurred_at, message_id, bot_message_id,
                       milliseconds, seconds, amount, state, scenario_kind
                FROM telegram_call_events WHERE occurred_at >= ? ORDER BY occurred_at DESC LIMIT ?
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, Timestamp.from(since))
                statement.setInt(2, MAX_EVENTS + 1)
                statement.executeQuery().use { result -> buildList {
                    while (result.next()) add(result.callEvent())
                } }
            }
        }
        CallEventsSnapshot(events.take(MAX_EVENTS), events.size > MAX_EVENTS, writeFailures)
    }

    override suspend fun byCall(callId: String): List<CallEvent> = withContext(Dispatchers.IO) {
        pool.connection.use { connection ->
            connection.prepareStatement("""
                SELECT event_id, call_id, chat_id, username, kind, occurred_at, message_id, bot_message_id,
                       milliseconds, seconds, amount, state, scenario_kind
                FROM telegram_call_events WHERE call_id = ? ORDER BY occurred_at LIMIT 1000
            """.trimIndent()).use { statement ->
                statement.setString(1, callId)
                statement.executeQuery().use { result -> buildList {
                    while (result.next()) add(result.callEvent())
                } }
            }
        }
    }

    override suspend fun callIdForBot(chatId: Long, botMessageId: Long): String? = withContext(Dispatchers.IO) {
        pool.connection.use { connection ->
            connection.prepareStatement("""
                SELECT call_id FROM telegram_call_events
                WHERE chat_id = ? AND bot_message_id = ? AND call_id IS NOT NULL
                LIMIT 1
            """.trimIndent()).use { statement ->
                statement.setLong(1, chatId)
                statement.setLong(2, botMessageId)
                statement.executeQuery().use { result -> if (result.next()) result.getString(1)?.trim() else null }
            }
        }
    }

    override suspend fun prune(now: Instant) = withContext(Dispatchers.IO) {
        pool.connection.use { connection ->
            connection.prepareStatement("""
                DELETE FROM telegram_call_events WHERE event_id IN
                  (SELECT event_id FROM telegram_call_events WHERE occurred_at < ? LIMIT 1000)
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now.minusSeconds(RETAIN_DAYS * 86_400)))
                statement.executeUpdate()
            }
        }
        Unit
    }

    override fun close() = pool.close()
}

private fun java.sql.ResultSet.callEvent(): CallEvent = CallEvent(
    id = getString(1), callId = getString(2)?.trim(), chatId = getLong(3), username = getString(4), kind = getString(5),
    at = getTimestamp(6).toInstant(), messageId = getLong(7).takeUnless { wasNull() },
    botMessageId = getLong(8).takeUnless { wasNull() },
    milliseconds = getLong(9).takeUnless { wasNull() },
    seconds = getDouble(10).takeUnless { wasNull() },
    amount = getInt(11).takeUnless { wasNull() }, state = getString(12), scenarioKind = getString(13),
)

internal class CallEventRecorder(private val store: CallEventStore, scope: CoroutineScope) {
    val memoryOnly: Boolean get() = store is MemoryCallEventStore
    private val log = LoggerFactory.getLogger(CallEventRecorder::class.java)
    private val channel = Channel<CallEvent>(256)
    private val failed = AtomicLong()
    private val pending = ConcurrentHashMap<String, CallEvent>()
    private val worker = scope.launch {
        for (event in channel) {
            try { store.record(event) }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { failed.incrementAndGet(); log.warn("Call analytics write failed", error) }
            finally { pending.remove(event.id, event) }
        }
    }
    private val pruner = scope.launch {
        while (true) {
            try { store.prune() }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { failed.incrementAndGet(); log.warn("Call analytics retention failed", error) }
            delay(60 * 60 * 1_000L)
        }
    }

    fun record(event: CallEvent) {
        val safe = event.safe()
        pending[safe.id] = safe
        if (channel.trySend(safe).isFailure) {
            pending.remove(safe.id, safe)
            failed.incrementAndGet()
            log.warn("Call analytics queue full")
        }
    }

    suspend fun snapshot(since: Instant): CallEventsSnapshot {
        val stored = store.snapshot(since, failed.get())
        val all = (stored.events + pending.values.filter { !it.at.isBefore(since) }).associateBy { it.id }.values
        return stored.copy(events = all.sortedByDescending { it.at })
    }

    suspend fun byCall(callId: String): List<CallEvent> =
        (store.byCall(callId) + pending.values.filter { it.callId == callId }).associateBy { it.id }.values.sortedBy { it.at }

    suspend fun callIdForBot(chatId: Long, botMessageId: Long): String? =
        pending.values.firstOrNull { it.chatId == chatId && it.botMessageId == botMessageId && it.callId != null }?.callId
            ?: store.callIdForBot(chatId, botMessageId)

    suspend fun close() { channel.close(); worker.join(); pruner.cancelAndJoin(); store.close() }
}

internal fun createCallEventStore(databaseUrl: String?): CallEventStore =
    if (databaseUrl.isNullOrBlank()) MemoryCallEventStore() else PostgresCallEventStore(databaseUrl)
