package com.eliteteam.speakingcoach.analytics

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
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
}

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
)

internal interface OnboardingAnalytics {
    suspend fun startAttempt(sessionId: String, runId: String, trigger: String, at: Instant)
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
    )
    suspend fun recordOutcome(attemptId: String, facts: OnboardingVoiceFacts, at: Instant)
    suspend fun recordReturn(sessionId: String, at: Instant)
    suspend fun report(now: Instant = Instant.now()): OnboardingReport
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

    override suspend fun startAttempt(sessionId: String, runId: String, trigger: String, at: Instant) {
        lock.withLock {
            if (attempts.containsKey(runId)) return
            val number = attempts.values.count { it.sessionId == sessionId } + 1
            attempts[runId] = MutableAttempt(
                runId = runId,
                sessionId = sessionId,
                trigger = trigger,
                isPrimary = number == 1 && trigger == "start",
                startedAt = at,
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
        }
        if (facts.assessmentFailed) attempt.times.putIfAbsent(AttemptMark.ASSESSMENT_FAILED, at)
    }

    override suspend fun recordReturn(sessionId: String, at: Instant) {
        lock.withLock {
            val attempt = attempts.values.firstOrNull { it.sessionId == sessionId && it.isPrimary } ?: return
            if (attempt.d1VoiceAt != null || attempt.times[AttemptMark.COMPLETED] == null) return
            val startDay = attempt.startedAt.atZone(ONBOARDING_ZONE).toLocalDate()
            val voiceDay = at.atZone(ONBOARDING_ZONE).toLocalDate()
            if (voiceDay == startDay.plusDays(1)) attempt.d1VoiceAt = at
        }
    }

    override suspend fun report(now: Instant): OnboardingReport = lock.withLock {
        onboardingReport(attempts.values.map { it.toRow() }, voices.values.toList(), now)
    }

    private class MutableAttempt(
        val runId: String,
        val sessionId: String,
        val trigger: String,
        val isPrimary: Boolean,
        val startedAt: Instant,
    ) {
        val times = linkedMapOf<AttemptMark, Instant>()
        var goalMinutes: Int? = null
        var reminderDecision: String? = null
        var cefr: String? = null
        var d1VoiceAt: Instant? = null

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

internal class PostgresOnboardingAnalytics(databaseUrl: String) : OnboardingAnalytics {
    private val dataSource: DataSource = hikari(databaseUrl)

    init {
        Flyway.configure().dataSource(dataSource).load().migrate()
    }

    override suspend fun startAttempt(sessionId: String, runId: String, trigger: String, at: Instant) {
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
                                (run_id, session_id, attempt_number, trigger, is_primary, started_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, runId)
                            statement.setString(2, sessionId)
                            statement.setInt(3, number)
                            statement.setString(4, trigger)
                            statement.setBoolean(5, number == 1 && trigger == "start")
                            statement.setTimestamp(6, Timestamp.from(at))
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
                    statement.executeUpdate()
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
                    statement.executeUpdate()
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
                    statement.executeUpdate()
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
    ) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO onboarding_voices (
                        attempt_id, request_id, session_id, voice_index,
                        telegram_duration_sec, recognized_duration_sec, recognized,
                        failure_reason, processing_ms, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    statement.executeUpdate()
                }
                if (facts.recognized) markOn(connection, attemptId, AttemptMark.FIRST_VOICE, at)
                applyOutcome(connection, attemptId, facts, at)
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

    override suspend fun recordReturn(sessionId: String, at: Instant) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE onboarding_attempts
                    SET d1_voice_at = ?
                    WHERE session_id = ?
                      AND is_primary = TRUE
                      AND completed_at IS NOT NULL
                      AND d1_voice_at IS NULL
                      AND ((started_at AT TIME ZONE 'Europe/Moscow')::date + 1)
                          = (? AT TIME ZONE 'Europe/Moscow')::date
                    """.trimIndent(),
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(at))
                    statement.setString(2, sessionId)
                    statement.setTimestamp(3, Timestamp.from(at))
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun report(now: Instant): OnboardingReport = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val attempts = connection.prepareStatement(
                """
                SELECT run_id, session_id, trigger, is_primary, started_at,
                       lets_chat_at, first_voice_at, speech_30_at, speech_60_at, speech_90_at, speech_120_at,
                       completed_at, results_opened_at, grammar_viewed_at, vocabulary_viewed_at, fluency_viewed_at,
                       practice_setup_at, goal_selected_at, goal_minutes, cefr, assessment_failed_at, d1_voice_at
                FROM onboarding_attempts
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(rows.toAttempt())
                    }
                }
            }
            val voices = connection.prepareStatement(
                """
                SELECT attempt_id, session_id, recognized, failure_reason, created_at
                FROM onboarding_voices
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                OnboardingVoiceRow(
                                    attemptId = rows.getString("attempt_id"),
                                    sessionId = rows.getString("session_id"),
                                    recognized = rows.getBoolean("recognized"),
                                    failureReason = rows.getString("failure_reason"),
                                    createdAt = rows.instant("created_at"),
                                ),
                            )
                        }
                    }
                }
            }
            onboardingReport(attempts, voices, now)
        }
    }

    override fun close() {
        (dataSource as? HikariDataSource)?.close()
    }

    private fun ResultSet.toAttempt(): OnboardingAttemptRow = OnboardingAttemptRow(
        runId = getString("run_id"),
        sessionId = getString("session_id"),
        trigger = getString("trigger"),
        isPrimary = getBoolean("is_primary"),
        startedAt = instant("started_at"),
        letsChatAt = optionalInstant("lets_chat_at"),
        firstVoiceAt = optionalInstant("first_voice_at"),
        speech30At = optionalInstant("speech_30_at"),
        speech60At = optionalInstant("speech_60_at"),
        speech90At = optionalInstant("speech_90_at"),
        speech120At = optionalInstant("speech_120_at"),
        completedAt = optionalInstant("completed_at"),
        resultsOpenedAt = optionalInstant("results_opened_at"),
        grammarViewedAt = optionalInstant("grammar_viewed_at"),
        vocabularyViewedAt = optionalInstant("vocabulary_viewed_at"),
        fluencyViewedAt = optionalInstant("fluency_viewed_at"),
        practiceSetupAt = optionalInstant("practice_setup_at"),
        goalSelectedAt = optionalInstant("goal_selected_at"),
        goalMinutes = (getObject("goal_minutes") as? Number)?.toInt(),
        cefr = getString("cefr"),
        assessmentFailedAt = optionalInstant("assessment_failed_at"),
        d1VoiceAt = optionalInstant("d1_voice_at"),
    )

    private fun applyOutcome(connection: java.sql.Connection, attemptId: String, facts: OnboardingVoiceFacts, at: Instant) {
        milestoneMarks(facts.milestones).forEach { markOn(connection, attemptId, it, at) }
        if (facts.completedNow) {
            markOn(connection, attemptId, AttemptMark.COMPLETED, at)
            connection.prepareStatement(
                """
                UPDATE onboarding_attempts
                SET cefr = COALESCE(cefr, ?),
                    overall_score = COALESCE(overall_score, ?),
                    score_available = COALESCE(score_available, ?)
                WHERE run_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, facts.cefr)
                if (facts.overallScore == null) statement.setNull(2, java.sql.Types.INTEGER) else statement.setInt(2, facts.overallScore)
                statement.setBoolean(3, facts.scoreAvailable)
                statement.setString(4, attemptId)
                statement.executeUpdate()
            }
        }
        if (facts.assessmentFailed) markOn(connection, attemptId, AttemptMark.ASSESSMENT_FAILED, at)
    }

    private fun markOn(connection: java.sql.Connection, runId: String, step: AttemptMark, at: Instant) {
        connection.prepareStatement(
            "UPDATE onboarding_attempts SET ${step.column} = COALESCE(${step.column}, ?) WHERE run_id = ?",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(at))
            statement.setString(2, runId)
            statement.executeUpdate()
        }
    }

    private fun ResultSet.instant(column: String): Instant = optionalInstant(column)
        ?: error("missing timestamp $column")

    private fun ResultSet.optionalInstant(column: String): Instant? =
        getTimestamp(column)?.toInstant()

    private companion object {
        fun hikari(databaseUrl: String): HikariDataSource {
            val config = HikariConfig()
            val trimmed = databaseUrl.trim()
            config.jdbcUrl = if (trimmed.startsWith("jdbc:")) trimmed else "jdbc:$trimmed"
            config.maximumPoolSize = 2
            return HikariDataSource(config)
        }
    }
}

internal suspend fun OnboardingAnalytics?.safely(block: suspend OnboardingAnalytics.() -> Unit) {
    val analytics = this ?: return
    try {
        analytics.block()
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Throwable) {
        org.slf4j.LoggerFactory.getLogger("OnboardingAnalytics").warn("Onboarding analytics write failed", error)
    }
}
