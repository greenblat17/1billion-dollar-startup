package com.eliteteam.speakingcoach.analytics

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

private val moscow = ZoneId.of("Europe/Moscow")
private const val RETAIN_DAYS = 30L
private const val STALE_SECONDS = 180L
private val outcomes = setOf("delivered", "text_fallback", "queue_full", "download_failed", "upload_rejected", "ai_timeout", "ai_failed", "delivery_failed", "other_failed", "not_eligible", "unknown")
private val stages = setOf("telegram_download", "queue", "upload", "stt", "reply_llm", "correction_llm", "tts", "state", "telegram_delivery", "other")
private val reasons = setOf("timeout", "rate_limit", "provider_5xx", "provider_4xx", "network", "invalid_input", "internal", "unknown")

internal fun voiceAttemptId(chatId: Long, messageId: Long): String =
    UUID.nameUUIDFromBytes("speaky-voice:$chatId:$messageId".toByteArray(StandardCharsets.UTF_8)).toString()

internal data class VoiceAttempt(
    val chatId: Long,
    val messageId: Long,
    val receivedAt: Instant,
    val username: String = "",
    val eligible: Boolean? = null,
    val outcome: String? = null,
    val stage: String? = null,
    val reason: String? = null,
    val jobId: String? = null,
    val terminalAt: Instant? = null,
    val setupMs: Long? = null,
    val queueMs: Long? = null,
    val chatQueueMs: Long? = null,
    val downloadMs: Long? = null,
    val processingMs: Long? = null,
    val deliveryMs: Long? = null,
    val totalMs: Long? = null,
    val sttMs: Long? = null,
    val replyLlmMs: Long? = null,
    val correctionLlmMs: Long? = null,
    val ttsMs: Long? = null,
    val finalizeMs: Long? = null,
) {
    val attemptId: String get() = voiceAttemptId(chatId, messageId)

    fun safe(): VoiceAttempt = copy(
        username = username.trim().removePrefix("@").take(64),
        outcome = outcome?.takeIf(outcomes::contains),
        stage = stage?.takeIf(stages::contains),
        reason = reason?.takeIf(reasons::contains),
        jobId = jobId?.takeIf { it.length <= 80 && it.all { char -> char.isLetterOrDigit() || char == '-' } },
        setupMs = setupMs?.coerceIn(0, 300_000), queueMs = queueMs?.coerceIn(0, 300_000),
        chatQueueMs = chatQueueMs?.coerceIn(0, 300_000),
        downloadMs = downloadMs?.coerceIn(0, 300_000), processingMs = processingMs?.coerceIn(0, 300_000),
        deliveryMs = deliveryMs?.coerceIn(0, 300_000), totalMs = totalMs?.coerceIn(0, 300_000),
        sttMs = sttMs?.coerceIn(0, 300_000), replyLlmMs = replyLlmMs?.coerceIn(0, 300_000),
        correctionLlmMs = correctionLlmMs?.coerceIn(0, 300_000), ttsMs = ttsMs?.coerceIn(0, 300_000),
        finalizeMs = finalizeMs?.coerceIn(0, 300_000),
    )
}

internal data class VoiceDay(val day: String, val delivered: Int, val failed: Int, val pending: Int, val notEligible: Int, val affectedChats: Int)
internal data class VoiceBreakdown(val stage: String, val reason: String, val count: Int)
internal data class VoiceLatency(val stage: String, val outcome: String, val count: Int, val p50Ms: Long, val p95Ms: Long)
internal data class VoiceRepeated(val chatId: Long, val username: String, val failures: Int, val attemptId: String)
internal data class VoiceAttemptReport(
    val firstObserved: String?,
    val days: List<VoiceDay>,
    val breakdown: List<VoiceBreakdown>,
    val todayBreakdown: List<VoiceBreakdown>,
    val latencies: List<VoiceLatency>,
    val todayLatencies: List<VoiceLatency>,
    val affectedChats: Int,
    val affected: List<VoiceRepeated>,
    val repeated: List<VoiceRepeated>,
    val recent: List<VoiceAttempt>,
    val writeFailures: Long,
    val truncated: Boolean = false,
)

internal interface VoiceAttemptStore : AutoCloseable {
    suspend fun record(attempt: VoiceAttempt)
    suspend fun report(now: Instant = Instant.now(), writeFailures: Long = 0): VoiceAttemptReport
    suspend fun prune(now: Instant = Instant.now()) {}
    override fun close() {}
}

internal class MemoryVoiceAttemptStore : VoiceAttemptStore {
    private val lock = Mutex()
    private val rows = linkedMapOf<String, VoiceAttempt>()

    override suspend fun record(attempt: VoiceAttempt) {
        val safe = attempt.safe()
        lock.withLock {
            val old = rows[safe.attemptId]
            if (old?.outcome != null) return@withLock
            rows[safe.attemptId] = if (old == null) safe else safe.copy(
                receivedAt = old.receivedAt, eligible = safe.eligible ?: old.eligible,
                username = safe.username.ifBlank { old.username },
                chatQueueMs = safe.chatQueueMs ?: old.chatQueueMs,
            )
        }
    }

    override suspend fun prune(now: Instant) = lock.withLock {
        val oldest = now.minusSeconds(RETAIN_DAYS * 86_400)
        rows.entries.removeIf { it.value.receivedAt.isBefore(oldest) }
        Unit
    }

    override suspend fun report(now: Instant, writeFailures: Long): VoiceAttemptReport = lock.withLock {
        voiceReport(rows.values.toList(), now, writeFailures)
    }
}

internal class PostgresVoiceAttemptStore(databaseUrl: String) : VoiceAttemptStore {
    private val dataSource: DataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = databaseUrl.trim().let { if (it.startsWith("jdbc:")) it else "jdbc:$it" }
        maximumPoolSize = 2
        connectionTimeout = 1_000
        addDataSourceProperty("socketTimeout", "2")
    })
    init { Flyway.configure().dataSource(dataSource).load().migrate() }

    override suspend fun record(attempt: VoiceAttempt) = withContext(Dispatchers.IO) {
        val item = attempt.safe()
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                INSERT INTO telegram_voice_attempts
                (attempt_id, chat_id, message_id, username, received_at, eligible, outcome, stage, reason, job_id, terminal_at,
                 setup_ms, queue_ms, chat_queue_ms, download_ms, processing_ms, delivery_ms, total_ms,
                 stt_ms, reply_llm_ms, correction_llm_ms, tts_ms, finalize_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (chat_id, message_id) DO UPDATE SET
                    username = CASE WHEN EXCLUDED.username <> '' THEN EXCLUDED.username ELSE telegram_voice_attempts.username END,
                    eligible = COALESCE(telegram_voice_attempts.eligible, EXCLUDED.eligible),
                    outcome = EXCLUDED.outcome, stage = EXCLUDED.stage, reason = EXCLUDED.reason,
                    job_id = COALESCE(EXCLUDED.job_id, telegram_voice_attempts.job_id), terminal_at = EXCLUDED.terminal_at,
                    setup_ms = COALESCE(EXCLUDED.setup_ms, telegram_voice_attempts.setup_ms),
                    queue_ms = COALESCE(EXCLUDED.queue_ms, telegram_voice_attempts.queue_ms),
                    chat_queue_ms = COALESCE(EXCLUDED.chat_queue_ms, telegram_voice_attempts.chat_queue_ms),
                    download_ms = COALESCE(EXCLUDED.download_ms, telegram_voice_attempts.download_ms),
                    processing_ms = COALESCE(EXCLUDED.processing_ms, telegram_voice_attempts.processing_ms),
                    delivery_ms = COALESCE(EXCLUDED.delivery_ms, telegram_voice_attempts.delivery_ms),
                    total_ms = COALESCE(EXCLUDED.total_ms, telegram_voice_attempts.total_ms),
                    stt_ms = EXCLUDED.stt_ms, reply_llm_ms = EXCLUDED.reply_llm_ms,
                    correction_llm_ms = EXCLUDED.correction_llm_ms, tts_ms = EXCLUDED.tts_ms,
                    finalize_ms = EXCLUDED.finalize_ms
                WHERE telegram_voice_attempts.outcome IS NULL
            """.trimIndent()).use { statement ->
                statement.setObject(1, UUID.fromString(item.attemptId))
                statement.setLong(2, item.chatId)
                statement.setLong(3, item.messageId)
                statement.setString(4, item.username)
                statement.setTimestamp(5, Timestamp.from(item.receivedAt))
                statement.setObject(6, item.eligible)
                statement.setString(7, item.outcome)
                statement.setString(8, item.stage)
                statement.setString(9, item.reason)
                statement.setString(10, item.jobId)
                statement.setTimestamp(11, item.terminalAt?.let(Timestamp::from))
                listOf(item.setupMs, item.queueMs, item.chatQueueMs, item.downloadMs, item.processingMs, item.deliveryMs, item.totalMs,
                    item.sttMs, item.replyLlmMs, item.correctionLlmMs, item.ttsMs, item.finalizeMs)
                    .forEachIndexed { index, value -> statement.setObject(12 + index, value) }
                statement.executeUpdate()
            }
            Unit
        }
    }

    override suspend fun prune(now: Instant) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            var deleted: Int
            do {
                connection.prepareStatement("""
                    DELETE FROM telegram_voice_attempts WHERE attempt_id IN
                    (SELECT attempt_id FROM telegram_voice_attempts WHERE received_at < ? LIMIT 500)
                """.trimIndent()).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now.minusSeconds(RETAIN_DAYS * 86_400)))
                    deleted = statement.executeUpdate()
                }
            } while (deleted == 500)
        }
    }

    override suspend fun report(now: Instant, writeFailures: Long): VoiceAttemptReport = withContext(Dispatchers.IO) {
        val rows = mutableListOf<VoiceAttempt>()
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT chat_id, message_id, username, received_at, eligible, outcome, stage, reason, job_id, terminal_at,
                       setup_ms, queue_ms, chat_queue_ms, download_ms, processing_ms, delivery_ms, total_ms,
                       stt_ms, reply_llm_ms, correction_llm_ms, tts_ms, finalize_ms
                FROM telegram_voice_attempts WHERE received_at >= ? AND received_at <= ? ORDER BY received_at DESC LIMIT 50001
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now.minusSeconds(14 * 86_400L)))
                statement.setTimestamp(2, Timestamp.from(now))
                statement.executeQuery().use { result ->
                    while (result.next()) rows += VoiceAttempt(
                        chatId = result.getLong(1), messageId = result.getLong(2), username = result.getString(3),
                        receivedAt = result.getTimestamp(4).toInstant(), eligible = result.getObject(5) as Boolean?,
                        outcome = result.getString(6), stage = result.getString(7), reason = result.getString(8),
                        jobId = result.getString(9), terminalAt = result.getTimestamp(10)?.toInstant(),
                        setupMs = result.getLongOrNull(11), queueMs = result.getLongOrNull(12),
                        chatQueueMs = result.getLongOrNull(13), downloadMs = result.getLongOrNull(14),
                        processingMs = result.getLongOrNull(15), deliveryMs = result.getLongOrNull(16),
                        totalMs = result.getLongOrNull(17), sttMs = result.getLongOrNull(18),
                        replyLlmMs = result.getLongOrNull(19), correctionLlmMs = result.getLongOrNull(20),
                        ttsMs = result.getLongOrNull(21), finalizeMs = result.getLongOrNull(22),
                    )
                }
            }
        }
        voiceReport(rows.take(50_000), now, writeFailures, truncated = rows.size > 50_000)
    }

    override fun close() { (dataSource as HikariDataSource).close() }
}

private fun java.sql.ResultSet.getLongOrNull(index: Int): Long? = getLong(index).takeUnless { wasNull() }

internal class VoiceAttemptRecorder(private val store: VoiceAttemptStore, scope: CoroutineScope) {
    private val log = LoggerFactory.getLogger(VoiceAttemptRecorder::class.java)
    private val channel = Channel<VoiceAttempt>(256)
    private val failed = AtomicLong()

    private val worker = scope.launch {
            for (item in channel) {
                try { store.record(item) }
                catch (error: CancellationException) { throw error }
                catch (error: Throwable) {
                    failed.incrementAndGet()
                    log.warn("Voice attempt metric write failed", error)
                }
            }
    }
    private val pruner = scope.launch {
        while (true) {
            try { store.prune() }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { failed.incrementAndGet(); log.warn("Voice attempt retention failed", error) }
            delay(60 * 60 * 1_000L)
        }
    }

    fun record(attempt: VoiceAttempt) {
        if (channel.trySend(attempt).isFailure) {
            failed.incrementAndGet()
            log.warn("Voice attempt metric queue full")
        }
    }

    suspend fun report(now: Instant = Instant.now()): VoiceAttemptReport = store.report(now, failed.get())
    suspend fun close() { channel.close(); worker.join(); pruner.cancelAndJoin(); store.close() }
}

private fun voiceReport(input: List<VoiceAttempt>, now: Instant, failedWrites: Long,
                        truncated: Boolean = false): VoiceAttemptReport {
    val today = now.atZone(moscow).toLocalDate()
    val first = today.minusDays(13)
    val rows = input.asSequence().filter { !it.receivedAt.atZone(moscow).toLocalDate().isBefore(first) }
        .map { if (it.outcome == null && it.receivedAt.isBefore(now.minusSeconds(STALE_SECONDS)))
            it.copy(eligible = true, outcome = "unknown", stage = "other", reason = "unknown") else it }
        .toList()
    val firstObserved = rows.minOfOrNull { it.receivedAt.atZone(moscow).toLocalDate() }
    val dayRows = (0L..13L).mapNotNull { offset ->
        val date = today.minusDays(offset)
        if (firstObserved == null || date.isBefore(firstObserved)) return@mapNotNull null
        val matching = rows.filter { it.receivedAt.atZone(moscow).toLocalDate() == date }
        val failures = matching.filter { it.eligible == true && it.outcome != null && it.outcome != "delivered" }
        VoiceDay(date.toString(), matching.count { it.outcome == "delivered" }, failures.size,
            matching.count { it.eligible == true && it.outcome == null }, matching.count { it.outcome == "not_eligible" },
            failures.map { it.chatId }.distinct().size)
    }
    val failures = rows.filter { it.eligible == true && it.outcome != null && it.outcome != "delivered" }
    fun breakdown(source: List<VoiceAttempt>) = source.groupingBy { (it.stage ?: "other") to (it.reason ?: "unknown") }
        .eachCount().map { VoiceBreakdown(it.key.first, it.key.second, it.value) }.sortedByDescending { it.count }
    val allBreakdown = breakdown(failures)
    val todayBreakdown = breakdown(failures.filter { it.receivedAt.atZone(moscow).toLocalDate() == today })
    val timingFields = mapOf<String, (VoiceAttempt) -> Long?>(
        "total" to { it.totalMs }, "setup" to { it.setupMs }, "chat_queue" to { it.chatQueueMs },
        "clip_queue" to { it.queueMs },
        "download" to { it.downloadMs }, "processing" to { it.processingMs }, "delivery" to { it.deliveryMs },
        "stt" to { it.sttMs }, "reply_llm" to { it.replyLlmMs }, "correction_llm" to { it.correctionLlmMs },
        "tts" to { it.ttsMs }, "finalize" to { it.finalizeMs },
    )
    fun latencyRows(source: List<VoiceAttempt>) = listOf("delivered", "failed").flatMap { group ->
        val set = source.filter { it.eligible == true && it.outcome != null && (it.outcome == "delivered") == (group == "delivered") }
        timingFields.mapNotNull { (stage, read) ->
            val values = set.mapNotNull(read).sorted()
            if (values.isEmpty()) null else VoiceLatency(stage, group, values.size, percentile(values, .5), percentile(values, .95))
        }
    }
    val latencies = latencyRows(rows)
    val todayLatencies = latencyRows(rows.filter { it.receivedAt.atZone(moscow).toLocalDate() == today })
    val repeated = rows.filter { it.eligible == true && it.outcome != null }.groupBy { it.chatId }.mapNotNull { (chatId, attempts) ->
        val latest = attempts.sortedByDescending { it.receivedAt }
        val streak = latest.takeWhile { it.outcome != "delivered" }
        if (streak.size < 3) null else VoiceRepeated(chatId, latest.first().username, streak.size, latest.first().attemptId)
    }.sortedByDescending { it.failures }.take(15)
    val affected = failures.groupBy { it.chatId }.map { (chatId, attempts) ->
        val latest = attempts.maxBy { it.receivedAt }
        VoiceRepeated(chatId, latest.username, attempts.size, latest.attemptId)
    }.sortedByDescending { it.failures }.take(15)
    return VoiceAttemptReport(firstObserved?.toString(), dayRows, allBreakdown, todayBreakdown,
        latencies, todayLatencies,
        failures.map { it.chatId }.distinct().size, affected, repeated,
        failures.sortedByDescending { it.receivedAt }.take(15), failedWrites, truncated)
}

private fun percentile(values: List<Long>, fraction: Double): Long =
    values[(ceil(values.size * fraction).toInt() - 1).coerceIn(0, values.lastIndex)]

internal fun createVoiceAttemptStore(databaseUrl: String?): VoiceAttemptStore =
    if (databaseUrl.isNullOrBlank()) MemoryVoiceAttemptStore() else PostgresVoiceAttemptStore(databaseUrl)
