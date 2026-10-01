package com.eliteteam.speakingcoach.analytics

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import javax.sql.DataSource

internal enum class AttemptMark(val column: String) {
    LETS_CHAT("lets_chat_at"),
    FIRST_VOICE("first_voice_at"),
    SPEECH_30("speech_30_at"),
    SPEECH_60("speech_60_at"),
    SPEECH_90("speech_90_at"),
    SPEECH_120("speech_120_at"),
    COMPLETED("completed_at"),
    RESULTS("results_opened_at"),
    GRAMMAR("grammar_viewed_at"),
    VOCABULARY("vocabulary_viewed_at"),
    FLUENCY("fluency_viewed_at"),
    PRACTICE("practice_setup_at"),
    GOAL("goal_selected_at"),
    REMINDER_DECISION("reminder_decision_at"),
    REMINDER_SET("reminder_set_at"),
    ASSESSMENT_FAILED("assessment_failed_at"),
    RESULT_DELIVERED("result_delivered_at"),
    REMINDER_OFFERED("reminder_offered_at"),
    PROFILE_OPENED("profile_opened_at"),
    BYE("bye_at"),
}

internal const val ONBOARDING_ANALYTICS_VERSION = "v2"

data class OnboardingVoiceFacts(
    val voiceIndex: Int = 0,
    val telegramDurationSec: Double = 0.0,
    val recognizedDurationSec: Double = 0.0,
    val recognized: Boolean = false,
    val failureReason: String? = null,
    val milestones: List<Int> = emptyList(),
    val completedNow: Boolean = false,
    val assessmentFailed: Boolean = false,
    val cefr: String? = null,
    val overallScore: Int? = null,
    val scoreAvailable: Boolean = false,
    val speechBeforeSec: Double? = null,
    val speechAfterSec: Double? = null,
    val grammarExamples: Int? = null,
    val vocabularyExamples: Int? = null,
    val fluencyMetricsAvailable: Boolean? = null,
    val outcome: String? = null,
)

internal interface OnboardingAnalytics {
    suspend fun startAttempt(sessionId: String, runId: String, trigger: String, at: Instant, source: String? = null)
    suspend fun mark(runId: String, step: AttemptMark, at: Instant)
    suspend fun markGoal(runId: String, minutes: Int, at: Instant)
    suspend fun markReminderDecision(runId: String, decision: String, at: Instant)
    suspend fun recordVoice(
        attemptId: String,
        sessionId: String,
        requestId: String,
        facts: OnboardingVoiceFacts,
        processingMs: Int,
        at: Instant,
        receivedAt: Instant = at,
    )
    suspend fun recordOutcome(attemptId: String, facts: OnboardingVoiceFacts, at: Instant)
    suspend fun event(attemptId: String, key: String, type: String, at: Instant)
    suspend fun recordReturn(sessionId: String, at: Instant)
    suspend fun report(now: Instant = Instant.now(), filter: OnboardingFilter = OnboardingFilter()): OnboardingReport
    fun close() {}
}

internal fun createOnboardingAnalytics(databaseUrl: String?): OnboardingAnalytics? {
    val url = databaseUrl?.takeIf { it.isNotBlank() } ?: return null
    return PostgresOnboardingAnalytics(url)
}

internal class MemoryOnboardingAnalytics : OnboardingAnalytics {
    private val lock = Mutex()
    private val attempts = linkedMapOf<String, MutableAttempt>()
    private val voices = linkedMapOf<Pair<String, String>, OnboardingVoiceRow>()
    private val events = linkedMapOf<Pair<String, String>, OnboardingEventRow>()

    override suspend fun startAttempt(sessionId: String, runId: String, trigger: String, at: Instant, source: String?) {
        lock.withLock {
            if (attempts.containsKey(runId)) return
            val number = attempts.values.count { it.sessionId == sessionId } + 1
            attempts[runId] = MutableAttempt(
                runId = runId,
                sessionId = sessionId,
                trigger = if (number > 1 && trigger == "start") "repeat_start" else trigger,
                isPrimary = number == 1 && trigger == "start",
                startedAt = at,
                source = source,
            )
        }
    }

    override suspend fun mark(runId: String, step: AttemptMark, at: Instant) {
        lock.withLock {
            val attempt = attempts[runId] ?: return
            attempt.times.putIfAbsent(step, at)
        }
    }

    override suspend fun markGoal(runId: String, minutes: Int, at: Instant) {
        lock.withLock {
            val attempt = attempts[runId] ?: return
            if (attempt.times.containsKey(AttemptMark.GOAL)) return
            attempt.times[AttemptMark.GOAL] = at
            attempt.goalMinutes = minutes
        }
    }

    override suspend fun markReminderDecision(runId: String, decision: String, at: Instant) {
        lock.withLock {
            val attempt = attempts[runId] ?: return
            if (attempt.times.containsKey(AttemptMark.REMINDER_DECISION)) return
            attempt.times[AttemptMark.REMINDER_DECISION] = at
            attempt.reminderDecision = decision
        }
    }

    override suspend fun recordVoice(
        attemptId: String,
        sessionId: String,
        requestId: String,
        facts: OnboardingVoiceFacts,
        processingMs: Int,
        at: Instant,
        receivedAt: Instant,
    ) {
        lock.withLock {
            val attempt = attempts[attemptId] ?: return
            voices.putIfAbsent(
                attemptId to requestId,
                OnboardingVoiceRow(
                    attemptId = attemptId,
                    sessionId = sessionId,
                    recognized = facts.recognized,
                    failureReason = facts.failureReason,
                    createdAt = at,
                    outcome = voiceOutcome(facts),
                    processingMs = processingMs,
                    voiceIndex = facts.voiceIndex,
                    speechBeforeSec = facts.speechBeforeSec,
                    speechAfterSec = facts.speechAfterSec,
                    receivedAt = receivedAt,
                ),
            )
            if (facts.recognized) attempt.times.putIfAbsent(AttemptMark.FIRST_VOICE, at)
            applyOutcome(attempt, facts, at)
        }
    }

    override suspend fun recordOutcome(attemptId: String, facts: OnboardingVoiceFacts, at: Instant) {
        lock.withLock {
            val attempt = attempts[attemptId] ?: return
            applyOutcome(attempt, facts, at)
        }
    }

    private fun applyOutcome(attempt: MutableAttempt, facts: OnboardingVoiceFacts, at: Instant) {
        milestoneMarks(facts.milestones).forEach { attempt.times.putIfAbsent(it, at) }
        if (facts.completedNow) {
            attempt.times.putIfAbsent(AttemptMark.COMPLETED, at)
            if (attempt.cefr == null) attempt.cefr = facts.cefr
            if (attempt.scoreAvailable == null) attempt.scoreAvailable = facts.scoreAvailable
            if (attempt.grammarExamples == null) attempt.grammarExamples = facts.grammarExamples
            if (attempt.vocabularyExamples == null) attempt.vocabularyExamples = facts.vocabularyExamples
            if (attempt.fluencyMetricsAvailable == null) attempt.fluencyMetricsAvailable = facts.fluencyMetricsAvailable
        }
        if (facts.assessmentFailed) attempt.times.putIfAbsent(AttemptMark.ASSESSMENT_FAILED, at)
    }

    override suspend fun event(attemptId: String, key: String, type: String, at: Instant) {
        lock.withLock {
            if (attempts[attemptId] == null) return
            events.putIfAbsent(attemptId to key, OnboardingEventRow(attemptId, type, at))
        }
    }

    override suspend fun recordReturn(sessionId: String, at: Instant) {
        lock.withLock {
            val attempt = attempts.values.firstOrNull { it.sessionId == sessionId && it.isPrimary } ?: return
            if (attempt.times[AttemptMark.COMPLETED] == null) return
            val startDay = attempt.startedAt.atZone(ONBOARDING_ZONE).toLocalDate()
            val voiceDay = at.atZone(ONBOARDING_ZONE).toLocalDate()
            if (attempt.firstPracticeAt == null) attempt.firstPracticeAt = at
            if (voiceDay == startDay.plusDays(1) && attempt.d1VoiceAt == null) attempt.d1VoiceAt = at
            if (voiceDay == startDay.plusDays(7) && attempt.d7VoiceAt == null) attempt.d7VoiceAt = at
        }
    }

    override suspend fun report(now: Instant, filter: OnboardingFilter): OnboardingReport = lock.withLock {
        onboardingReport(attempts.values.map { it.toRow() }, voices.values.toList(), now, filter, events.values.toList())
    }

    private class MutableAttempt(
        val runId: String,
        val sessionId: String,
        val trigger: String,
        val isPrimary: Boolean,
        val startedAt: Instant,
        val source: String?,
    ) {
        val times = linkedMapOf<AttemptMark, Instant>()
        var goalMinutes: Int? = null
        var reminderDecision: String? = null
        var cefr: String? = null
        var d1VoiceAt: Instant? = null
        var d7VoiceAt: Instant? = null
        var firstPracticeAt: Instant? = null
        var scoreAvailable: Boolean? = null
        var grammarExamples: Int? = null
        var vocabularyExamples: Int? = null
        var fluencyMetricsAvailable: Boolean? = null

        fun toRow(): OnboardingAttemptRow = OnboardingAttemptRow(
            runId = runId,
            sessionId = sessionId,
            trigger = trigger,
            isPrimary = isPrimary,
            startedAt = startedAt,
            letsChatAt = times[AttemptMark.LETS_CHAT],
            firstVoiceAt = times[AttemptMark.FIRST_VOICE],
            speech30At = times[AttemptMark.SPEECH_30],
            speech60At = times[AttemptMark.SPEECH_60],
            speech90At = times[AttemptMark.SPEECH_90],
            speech120At = times[AttemptMark.SPEECH_120],
            completedAt = times[AttemptMark.COMPLETED],
            resultsOpenedAt = times[AttemptMark.RESULTS],
            grammarViewedAt = times[AttemptMark.GRAMMAR],
            vocabularyViewedAt = times[AttemptMark.VOCABULARY],
            fluencyViewedAt = times[AttemptMark.FLUENCY],
            practiceSetupAt = times[AttemptMark.PRACTICE],
            goalSelectedAt = times[AttemptMark.GOAL],
            goalMinutes = goalMinutes,
            cefr = cefr,
            assessmentFailedAt = times[AttemptMark.ASSESSMENT_FAILED],
            d1VoiceAt = d1VoiceAt,
            d7VoiceAt = d7VoiceAt,
            firstPracticeAt = firstPracticeAt,
            source = source,
            resultDeliveredAt = times[AttemptMark.RESULT_DELIVERED],
            reminderOfferedAt = times[AttemptMark.REMINDER_OFFERED],
            reminderDecisionAt = times[AttemptMark.REMINDER_DECISION],
            reminderSetAt = times[AttemptMark.REMINDER_SET],
            reminderDecision = reminderDecision,
            profileOpenedAt = times[AttemptMark.PROFILE_OPENED],
            byeAt = times[AttemptMark.BYE],
            scoreAvailable = scoreAvailable,
            grammarExamples = grammarExamples,
            vocabularyExamples = vocabularyExamples,
            fluencyMetricsAvailable = fluencyMetricsAvailable,
            version = ONBOARDING_ANALYTICS_VERSION,
        )
    }
}

private fun milestoneMarks(milestones: List<Int>): List<AttemptMark> = milestones.mapNotNull { mark ->
    when (mark) {
        30 -> AttemptMark.SPEECH_30
        60 -> AttemptMark.SPEECH_60
        90 -> AttemptMark.SPEECH_90
        120 -> AttemptMark.SPEECH_120
        else -> null
    }
}

private fun voiceOutcome(facts: OnboardingVoiceFacts): String = when {
    facts.outcome in setOf("recognized", "no_speech", "stt_failure", "processing_failure", "delivery_failure", "queue_full") -> facts.outcome!!
    facts.recognized -> "recognized"
    facts.failureReason == "no_speech" -> "no_speech"
    facts.failureReason in setOf("stt_failure", "processing_failure", "delivery_failure", "queue_full") -> facts.failureReason!!
    else -> "unknown"
}

internal class PostgresOnboardingAnalytics(databaseUrl: String) : OnboardingAnalytics {
    private val dataSource: DataSource = hikari(databaseUrl)

    init {
        Flyway.configure().dataSource(dataSource).load().migrate()
    }

    override suspend fun startAttempt(sessionId: String, runId: String, trigger: String, at: Instant, source: String?) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val exists = connection.prepareStatement(
                        "SELECT 1 FROM onboarding_attempts WHERE run_id = ?",
                    ).use { statement ->
                        statement.setString(1, runId)
                        statement.executeQuery().use { it.next() }
                    }
                    if (!exists) {
                        val number = connection.prepareStatement(
                            "SELECT COUNT(*) FROM onboarding_attempts WHERE session_id = ?",
                        ).use { statement ->
                            statement.setString(1, sessionId)
                            statement.executeQuery().use { rows ->
                                rows.next()
                                rows.getInt(1) + 1
                            }
                        }
                        connection.prepareStatement(
                            """
                            INSERT INTO onboarding_attempts
                                (run_id, session_id, attempt_number, trigger, is_primary, started_at,
                                 start_source, onboarding_version)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, runId)
                            statement.setString(2, sessionId)
                            statement.setInt(3, number)
                            statement.setString(4, if (number > 1 && trigger == "start") "repeat_start" else trigger)
                            statement.setBoolean(5, number == 1 && trigger == "start")
                            statement.setTimestamp(6, Timestamp.from(at))
                            statement.setString(7, source)
                            statement.setString(8, ONBOARDING_ANALYTICS_VERSION)
                            statement.executeUpdate()
                        }
                    }
                    connection.commit()
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    override suspend fun mark(runId: String, step: AttemptMark, at: Instant) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE onboarding_attempts SET ${step.column} = COALESCE(${step.column}, ?) WHERE run_id = ?",
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(at))
                    statement.setString(2, runId)
                    if (statement.executeUpdate() == 0) OnboardingAnalyticsHealth.recordMissingAttempt()
                }
            }
        }
    }

    override suspend fun markGoal(runId: String, minutes: Int, at: Instant) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE onboarding_attempts
                    SET goal_selected_at = COALESCE(goal_selected_at, ?),
                        goal_minutes = COALESCE(goal_minutes, ?)
                    WHERE run_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(at))
                    statement.setInt(2, minutes)
                    statement.setString(3, runId)
                    if (statement.executeUpdate() == 0) OnboardingAnalyticsHealth.recordMissingAttempt()
                }
            }
        }
    }

    override suspend fun markReminderDecision(runId: String, decision: String, at: Instant) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE onboarding_attempts
                    SET reminder_decision_at = COALESCE(reminder_decision_at, ?),
                        reminder_decision = COALESCE(reminder_decision, ?)
                    WHERE run_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(at))
                    statement.setString(2, decision)
                    statement.setString(3, runId)
                    if (statement.executeUpdate() == 0) OnboardingAnalyticsHealth.recordMissingAttempt()
                }
            }
        }
    }

    override suspend fun recordVoice(
        attemptId: String,
        sessionId: String,
        requestId: String,
        facts: OnboardingVoiceFacts,
        processingMs: Int,
        at: Instant,
        receivedAt: Instant,
    ) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                    """
                    INSERT INTO onboarding_voices (
                        attempt_id, request_id, session_id, voice_index,
                        telegram_duration_sec, recognized_duration_sec, recognized,
                        failure_reason, processing_ms, created_at, outcome,
                        speech_before_sec, speech_after_sec, received_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (attempt_id, request_id) DO NOTHING
                    """.trimIndent(),
                    ).use { statement ->
                    statement.setString(1, attemptId)
                    statement.setString(2, requestId)
                    statement.setString(3, sessionId)
                    statement.setInt(4, facts.voiceIndex)
                    statement.setDouble(5, facts.telegramDurationSec)
                    statement.setDouble(6, facts.recognizedDurationSec)
                    statement.setBoolean(7, facts.recognized)
                    statement.setString(8, facts.failureReason)
                    statement.setInt(9, processingMs)
                    statement.setTimestamp(10, Timestamp.from(at))
                    statement.setString(11, voiceOutcome(facts))
                    statement.setObject(12, facts.speechBeforeSec)
                    statement.setObject(13, facts.speechAfterSec)
                    statement.setTimestamp(14, Timestamp.from(receivedAt))
                        statement.executeUpdate()
                    }
                    applyOutcome(connection, attemptId, facts, at, firstVoice = facts.recognized)
                    connection.commit()
                } catch (error: Throwable) {
                    connection.rollback()
                    if ((error as? java.sql.SQLException)?.sqlState == "23503") {
                        OnboardingAnalyticsHealth.recordMissingAttempt()
                    }
                    throw error
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    override suspend fun recordOutcome(attemptId: String, facts: OnboardingVoiceFacts, at: Instant) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                applyOutcome(connection, attemptId, facts, at)
            }
        }
    }

    override suspend fun event(attemptId: String, key: String, type: String, at: Instant) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO onboarding_events (attempt_id, event_key, event_type, created_at) " +
                        "VALUES (?, ?, ?, ?) ON CONFLICT (attempt_id, event_key) DO NOTHING",
                ).use { statement ->
                    statement.setString(1, attemptId)
                    statement.setString(2, key)
                    statement.setString(3, type)
                    statement.setTimestamp(4, Timestamp.from(at))
                    try {
                        statement.executeUpdate()
                    } catch (error: java.sql.SQLException) {
                        if (error.sqlState == "23503") OnboardingAnalyticsHealth.recordMissingAttempt()
                        throw error
                    }
                }
            }
        }
    }

    override suspend fun recordReturn(sessionId: String, at: Instant) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE onboarding_attempts
                    SET first_practice_at = COALESCE(first_practice_at, ?),
                        d1_voice_at = CASE WHEN ((started_at AT TIME ZONE 'Europe/Moscow')::date + 1)
                            = (? AT TIME ZONE 'Europe/Moscow')::date THEN COALESCE(d1_voice_at, ?) ELSE d1_voice_at END,
                        d7_voice_at = CASE WHEN ((started_at AT TIME ZONE 'Europe/Moscow')::date + 7)
                            = (? AT TIME ZONE 'Europe/Moscow')::date THEN COALESCE(d7_voice_at, ?) ELSE d7_voice_at END
                    WHERE session_id = ?
                      AND is_primary = TRUE
                      AND completed_at IS NOT NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(at))
                    statement.setTimestamp(2, Timestamp.from(at))
                    statement.setTimestamp(3, Timestamp.from(at))
                    statement.setTimestamp(4, Timestamp.from(at))
                    statement.setTimestamp(5, Timestamp.from(at))
                    statement.setString(6, sessionId)
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun report(now: Instant, filter: OnboardingFilter): OnboardingReport = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection -> connection.onboardingAggregateReport(now, filter) }
    }

    override fun close() {
        (dataSource as? HikariDataSource)?.close()
    }

    private fun applyOutcome(
        connection: java.sql.Connection,
        attemptId: String,
        facts: OnboardingVoiceFacts,
        at: Instant,
        firstVoice: Boolean = false,
    ) {
        connection.prepareStatement(
            """
            UPDATE onboarding_attempts SET
                first_voice_at = CASE WHEN ? THEN COALESCE(first_voice_at, ?) ELSE first_voice_at END,
                speech_30_at = CASE WHEN ? THEN COALESCE(speech_30_at, ?) ELSE speech_30_at END,
                speech_60_at = CASE WHEN ? THEN COALESCE(speech_60_at, ?) ELSE speech_60_at END,
                speech_90_at = CASE WHEN ? THEN COALESCE(speech_90_at, ?) ELSE speech_90_at END,
                speech_120_at = CASE WHEN ? THEN COALESCE(speech_120_at, ?) ELSE speech_120_at END,
                completed_at = CASE WHEN ? THEN COALESCE(completed_at, ?) ELSE completed_at END,
                assessment_failed_at = CASE WHEN ? THEN COALESCE(assessment_failed_at, ?) ELSE assessment_failed_at END,
                cefr = CASE WHEN ? THEN COALESCE(cefr, ?) ELSE cefr END,
                overall_score = CASE WHEN ? THEN COALESCE(overall_score, ?) ELSE overall_score END,
                score_available = CASE WHEN ? THEN COALESCE(score_available, ?) ELSE score_available END,
                grammar_examples_count = COALESCE(grammar_examples_count, ?),
                vocabulary_examples_count = COALESCE(vocabulary_examples_count, ?),
                fluency_metrics_available = COALESCE(fluency_metrics_available, ?)
            WHERE run_id = ?
            """.trimIndent(),
        ).use { statement ->
            var index = 1
            for (active in listOf(firstVoice, 30 in facts.milestones, 60 in facts.milestones,
                90 in facts.milestones, 120 in facts.milestones, facts.completedNow, facts.assessmentFailed)) {
                statement.setBoolean(index++, active)
                statement.setTimestamp(index++, Timestamp.from(at))
            }
            statement.setBoolean(index++, facts.completedNow)
            statement.setString(index++, facts.cefr)
            statement.setBoolean(index++, facts.completedNow)
            statement.setObject(index++, facts.overallScore)
            statement.setBoolean(index++, facts.completedNow)
            statement.setBoolean(index++, facts.scoreAvailable)
            statement.setObject(index++, facts.grammarExamples)
            statement.setObject(index++, facts.vocabularyExamples)
            statement.setObject(index++, facts.fluencyMetricsAvailable)
            statement.setString(index, attemptId)
            if (statement.executeUpdate() == 0) OnboardingAnalyticsHealth.recordMissingAttempt()
        }
    }

    private companion object {
        fun hikari(databaseUrl: String): HikariDataSource {
            val config = HikariConfig()
            val trimmed = databaseUrl.trim()
            config.jdbcUrl = if (trimmed.startsWith("jdbc:")) trimmed else "jdbc:$trimmed"
            config.maximumPoolSize = 2
            config.connectionTimeout = 1_000
            config.addDataSourceProperty("socketTimeout", "2")
            return HikariDataSource(config)
        }
    }
}

internal suspend fun OnboardingAnalytics?.safely(block: suspend OnboardingAnalytics.() -> Unit) {
    val analytics = this ?: return
    val buffer = currentCoroutineContext()[AnalyticsWriteBuffer]
    if (buffer != null) {
        buffer.add { analytics.writeSafely(block) }
        return
    }
    analytics.writeSafely(block)
}

internal class AnalyticsWriteBuffer : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AnalyticsWriteBuffer>

    private val writes = mutableListOf<suspend () -> Unit>()

    fun add(write: suspend () -> Unit) {
        writes.add(write)
    }

    suspend fun flush() {
        writes.forEach { it() }
    }
}

internal object OnboardingAnalyticsHealth {
    private val attemptedWrites = AtomicLong()
    private val failedWrites = AtomicLong()
    private val missingAttempts = AtomicLong()
    fun attemptedWrites(): Long = attemptedWrites.get()
    fun failedWrites(): Long = failedWrites.get()
    fun missingAttempts(): Long = missingAttempts.get()
    fun recordAttempt() { attemptedWrites.incrementAndGet() }
    fun recordFailure() { failedWrites.incrementAndGet() }
    fun recordMissingAttempt() { missingAttempts.incrementAndGet() }
}

private suspend fun OnboardingAnalytics.writeSafely(block: suspend OnboardingAnalytics.() -> Unit) {
    OnboardingAnalyticsHealth.recordAttempt()
    try {
        block()
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Throwable) {
        OnboardingAnalyticsHealth.recordFailure()
        org.slf4j.LoggerFactory.getLogger("OnboardingAnalytics").warn("Onboarding analytics write failed", error)
    }
}
