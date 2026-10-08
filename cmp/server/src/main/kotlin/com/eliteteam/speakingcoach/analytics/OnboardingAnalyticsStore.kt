package com.eliteteam.speakingcoach.analytics

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import javax.sql.DataSource

internal enum class AttemptMark(val column: String) {
    LETS_CHAT("lets_chat_at"),
    BEGIN_PRESSED("begin_pressed_at"),
    FIRST_QUESTION_DELIVERED("first_question_delivered_at"),
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

internal const val ONBOARDING_ANALYTICS_VERSION = "v3"

internal fun safeOnboardingUsername(value: String?): String? =
    value?.removePrefix("@")?.takeIf { it.length in 1..32 && it.all { char -> char.isLetterOrDigit() || char == '_' } }

internal fun safeFailureStage(value: String?): String? = value?.takeIf {
    it in setOf("invitation", "first_question", "queue", "telegram_download", "upload", "stt", "llm", "tts",
        "telegram_delivery", "result_build", "result_delivery", "result_card", "goal", "reminder", "other")
}

internal fun safeFailureCode(value: String?): String? = value?.takeIf {
    it in setOf("no_speech", "timeout", "rate_limit", "provider_5xx", "provider_4xx", "network",
        "invalid_input", "internal", "delivery_failed", "queue_full", "unknown")
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
    val speechBeforeSec: Double? = null,
    val speechAfterSec: Double? = null,
    val grammarExamples: Int? = null,
    val vocabularyExamples: Int? = null,
    val fluencyMetricsAvailable: Boolean? = null,
    val outcome: String? = null,
    val failureStage: String? = null,
    val failureCode: String? = null,
)

internal data class ReminderOfferSummary(val offered: Int, val saved: Int)

internal data class OnboardingNudgeCandidate(
    val sessionId: String,
    val runId: String,
    val chatId: Long,
    val began: Boolean,
)

internal interface OnboardingAnalytics {
    suspend fun recordEntry(sessionId: String, entryKey: String, at: Instant, eligible: Boolean,
                            trigger: String, reason: String?, source: String?, runId: String?, invitationAt: Instant? = null,
                            chatId: Long? = null, username: String? = null, invitationError: String? = null)
    suspend fun startAttempt(sessionId: String, runId: String, trigger: String, at: Instant, source: String? = null,
                             chatId: Long? = null, username: String? = null, invitationAt: Instant? = null,
                             invitationError: String? = null)
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
    suspend fun event(attemptId: String, key: String, type: String, at: Instant,
                      failureStage: String? = null, failureCode: String? = null)
    suspend fun recordReturn(sessionId: String, at: Instant)
    suspend fun report(now: Instant = Instant.now(), filter: OnboardingFilter = OnboardingFilter()): OnboardingReport
    suspend fun reminderOffers(days: Int, now: Instant = Instant.now()): ReminderOfferSummary? = null
    suspend fun nudgeCandidates(day: LocalDate, inactiveSince: Instant): List<OnboardingNudgeCandidate> = emptyList()
    suspend fun claimNudge(runId: String, day: LocalDate, inactiveSince: Instant, now: Instant): Boolean = false
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
    private val entries = linkedMapOf<Pair<String, String>, OnboardingEntryRow>()
    private val practiceDays = linkedMapOf<Pair<String, LocalDate>, PracticeDayRow>()

    override suspend fun recordEntry(sessionId: String, entryKey: String, at: Instant, eligible: Boolean,
                                     trigger: String, reason: String?, source: String?, runId: String?, invitationAt: Instant?,
                                     chatId: Long?, username: String?, invitationError: String?) {
        lock.withLock {
            entries.putIfAbsent(sessionId to entryKey,
                OnboardingEntryRow(sessionId, entryKey, at, eligible, trigger, reason, source, runId, invitationAt,
                    chatId, safeOnboardingUsername(username), safeFailureCode(invitationError)))
        }
    }

    override suspend fun startAttempt(sessionId: String, runId: String, trigger: String, at: Instant, source: String?,
                                      chatId: Long?, username: String?, invitationAt: Instant?, invitationError: String?) {
        lock.withLock {
            if (attempts.containsKey(runId)) return
            val number = attempts.values.count { it.sessionId == sessionId } + 1
            attempts[runId] = MutableAttempt(
                runId = runId,
                sessionId = sessionId,
                trigger = if (number > 1 && trigger == "start") "repeat_start" else trigger,
                isPrimary = number == 1 && trigger == "start",
                attemptNumber = number,
                startedAt = at,
                source = source,
                chatId = chatId,
                username = safeOnboardingUsername(username),
                invitationAt = invitationAt,
                invitationError = safeFailureCode(invitationError),
            )
        }
    }

    override suspend fun mark(runId: String, step: AttemptMark, at: Instant) {
        lock.withLock {
            val attempt = attempts[runId] ?: return
            attempt.times.putIfAbsent(step, at)
            if (step == AttemptMark.REMINDER_SET) attempt.postCompletionPracticeObservable = true
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
            if (decision == "not_now") attempt.postCompletionPracticeObservable = true
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
                    failureStage = safeFailureStage(facts.failureStage),
                    failureCode = safeFailureCode(facts.failureCode),
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

    override suspend fun event(attemptId: String, key: String, type: String, at: Instant,
                               failureStage: String?, failureCode: String?) {
        lock.withLock {
            if (attempts[attemptId] == null) return
            events.putIfAbsent(attemptId to key, OnboardingEventRow(attemptId, type, at,
                safeFailureStage(failureStage), safeFailureCode(failureCode)))
        }
    }

    override suspend fun recordReturn(sessionId: String, at: Instant) {
        lock.withLock {
            val attempt = attempts.values.firstOrNull { it.sessionId == sessionId && it.isPrimary } ?: return
            val resultAt = attempt.times[AttemptMark.RESULT_DELIVERED] ?: return
            if (at.isBefore(resultAt)) return
            val startDay = attempt.startedAt.atZone(ONBOARDING_ZONE).toLocalDate()
            val voiceDay = at.atZone(ONBOARDING_ZONE).toLocalDate()
            practiceDays.putIfAbsent(attempt.runId to voiceDay, PracticeDayRow(attempt.runId, voiceDay, at))
            if (attempt.firstPracticeAt == null) attempt.firstPracticeAt = at
            val completedAt = when {
                attempt.reminderDecision == "not_now" -> attempt.times[AttemptMark.REMINDER_DECISION]
                else -> attempt.times[AttemptMark.REMINDER_SET]
            }
            if (attempt.postCompletionPracticeObservable && completedAt != null && !at.isBefore(completedAt) &&
                attempt.firstPostCompletionPracticeAt == null) attempt.firstPostCompletionPracticeAt = at
            if (voiceDay == startDay.plusDays(1) && attempt.d1VoiceAt == null) attempt.d1VoiceAt = at
            if (voiceDay == startDay.plusDays(7) && attempt.d7VoiceAt == null) attempt.d7VoiceAt = at
        }
    }

    override suspend fun report(now: Instant, filter: OnboardingFilter): OnboardingReport = lock.withLock {
        onboardingReport(attempts.values.map { it.toRow() }, voices.values.toList(), now, filter, events.values.toList(),
            entries.values.toList(), practiceDays.values.toList())
    }

    override suspend fun reminderOffers(days: Int, now: Instant): ReminderOfferSummary = lock.withLock {
        val since = now.atZone(ONBOARDING_ZONE).toLocalDate().minusDays(days.toLong() - 1)
            .atStartOfDay(ONBOARDING_ZONE).toInstant()
        val offered = attempts.values.filter { attempt ->
            val at = attempt.times[AttemptMark.REMINDER_OFFERED]
            attempt.sessionId.startsWith("tg-") && at != null && !at.isBefore(since) && !at.isAfter(now)
        }
        ReminderOfferSummary(
            offered = offered.map { it.sessionId }.toSet().size,
            saved = offered.filter { attempt ->
                val saved = attempt.times[AttemptMark.REMINDER_SET]
                saved != null && !saved.isBefore(attempt.times.getValue(AttemptMark.REMINDER_OFFERED)) && !saved.isAfter(now)
            }.map { it.sessionId }.toSet().size,
        )
    }

    private class MutableAttempt(
        val runId: String,
        val sessionId: String,
        val trigger: String,
        val isPrimary: Boolean,
        val attemptNumber: Int,
        val startedAt: Instant,
        val source: String?,
        val chatId: Long?,
        val username: String?,
        val invitationAt: Instant?,
        val invitationError: String?,
    ) {
        val times = linkedMapOf<AttemptMark, Instant>()
        var goalMinutes: Int? = null
        var reminderDecision: String? = null
        var cefr: String? = null
        var d1VoiceAt: Instant? = null
        var d7VoiceAt: Instant? = null
        var firstPracticeAt: Instant? = null
        var firstPostCompletionPracticeAt: Instant? = null
        var postCompletionPracticeObservable: Boolean = false
        var scoreAvailable: Boolean? = null
        var grammarExamples: Int? = null
        var vocabularyExamples: Int? = null
        var fluencyMetricsAvailable: Boolean? = null

        fun toRow(): OnboardingAttemptRow = OnboardingAttemptRow(
            runId = runId,
            sessionId = sessionId,
            trigger = trigger,
            isPrimary = isPrimary,
            attemptNumber = attemptNumber,
            startedAt = startedAt,
            letsChatAt = times[AttemptMark.LETS_CHAT],
            beginPressedAt = times[AttemptMark.BEGIN_PRESSED],
            firstQuestionDeliveredAt = times[AttemptMark.FIRST_QUESTION_DELIVERED],
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
            chatId = chatId,
            username = username,
            invitationDeliveredAt = invitationAt,
            invitationError = invitationError,
            postCompletionPracticeObservable = postCompletionPracticeObservable,
            firstPostCompletionPracticeAt = firstPostCompletionPracticeAt,
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
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nudgeEligibleSql = """
        SELECT a.session_id, a.run_id, a.telegram_chat_id, (a.begin_pressed_at IS NOT NULL) AS began
        FROM (
            SELECT DISTINCT ON (session_id) * FROM onboarding_attempts
            WHERE session_id LIKE 'tg-%'
            ORDER BY session_id, started_at DESC, attempt_number DESC
        ) a
        LEFT JOIN LATERAL (
            SELECT MAX(received_at) AS last_voice FROM onboarding_voices WHERE attempt_id = a.run_id
        ) v ON TRUE
        LEFT JOIN LATERAL (
            SELECT MAX(created_at) AS last_event FROM onboarding_events WHERE attempt_id = a.run_id
        ) e ON TRUE
        WHERE a.telegram_chat_id IS NOT NULL
          AND (a.invitation_delivered_at IS NOT NULL OR a.begin_pressed_at IS NOT NULL)
          AND a.result_delivered_at IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM onboarding_attempts done
              WHERE done.session_id = a.session_id AND done.result_delivered_at IS NOT NULL
          )
          AND GREATEST(a.started_at, a.begin_pressed_at, a.first_question_delivered_at,
                       v.last_voice, e.last_event) <= ?
    """.trimIndent()

    init {
        Flyway.configure().dataSource(dataSource).load().migrate()
        cleanupScope.launch {
            while (isActive) {
                try {
                    val cutoff = Timestamp.from(Instant.now().minusSeconds(90L * 86_400))
                    while (isActive && purgePersonalSnapshots(cutoff) > 0) delay(25)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    org.slf4j.LoggerFactory.getLogger("OnboardingAnalytics")
                        .warn("Onboarding username cleanup failed", error)
                }
                delay(3_600_000)
            }
        }
    }

    override suspend fun nudgeCandidates(day: LocalDate, inactiveSince: Instant): List<OnboardingNudgeCandidate> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("""
                    $nudgeEligibleSql
                    AND NOT EXISTS (SELECT 1 FROM onboarding_nudges n WHERE n.session_id = a.session_id AND n.day = ?)
                    ORDER BY a.started_at, a.run_id
                """.trimIndent()).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(inactiveSince))
                    statement.setObject(2, day)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) add(OnboardingNudgeCandidate(
                                rows.getString("session_id"), rows.getString("run_id"),
                                rows.getLong("telegram_chat_id"), rows.getBoolean("began"),
                            ))
                        }
                    }
                }
            }
        }

    override suspend fun claimNudge(runId: String, day: LocalDate, inactiveSince: Instant, now: Instant): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("""
                    INSERT INTO onboarding_nudges (session_id, day, run_id, claimed_at)
                    SELECT eligible.session_id, ?, eligible.run_id, ?
                    FROM ($nudgeEligibleSql AND a.run_id = ?) eligible
                    WHERE TRUE
                    ON CONFLICT (session_id, day) DO NOTHING
                """.trimIndent()).use { statement ->
                    statement.setObject(1, day)
                    statement.setTimestamp(2, Timestamp.from(now))
                    statement.setTimestamp(3, Timestamp.from(inactiveSince))
                    statement.setString(4, runId)
                    statement.executeUpdate() == 1
                }
            }
        }

    override suspend fun reminderOffers(days: Int, now: Instant): ReminderOfferSummary = withContext(Dispatchers.IO) {
        val since = now.atZone(ONBOARDING_ZONE).toLocalDate().minusDays(days.toLong() - 1)
            .atStartOfDay(ONBOARDING_ZONE).toInstant()
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT COUNT(DISTINCT session_id) AS offered,
                       COUNT(DISTINCT session_id) FILTER
                           (WHERE reminder_set_at >= reminder_offered_at AND reminder_set_at <= ?) AS saved
                FROM onboarding_attempts
                WHERE reminder_offered_at >= ? AND reminder_offered_at <= ?
                  AND session_id LIKE 'tg-%'
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.setTimestamp(2, Timestamp.from(since))
                statement.setTimestamp(3, Timestamp.from(now))
                statement.executeQuery().use { rows ->
                    rows.next()
                    ReminderOfferSummary(rows.getInt("offered"), rows.getInt("saved"))
                }
            }
        }
    }

    override suspend fun recordEntry(sessionId: String, entryKey: String, at: Instant, eligible: Boolean,
                                     trigger: String, reason: String?, source: String?, runId: String?, invitationAt: Instant?,
                                     chatId: Long?, username: String?, invitationError: String?) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("""
                    INSERT INTO onboarding_entries
                        (session_id, entry_key, received_at, eligible, trigger, exclusion_reason, start_source, run_id,
                         invitation_delivered_at, telegram_chat_id, telegram_username, invitation_error_reason)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (session_id, entry_key) DO NOTHING
                """.trimIndent()).use { statement ->
                    statement.setString(1, sessionId)
                    statement.setString(2, entryKey)
                    statement.setTimestamp(3, Timestamp.from(at))
                    statement.setBoolean(4, eligible)
                    statement.setString(5, trigger)
                    statement.setString(6, reason)
                    statement.setString(7, source)
                    statement.setString(8, runId)
                    statement.setTimestamp(9, invitationAt?.let(Timestamp::from))
                    statement.setObject(10, chatId)
                    statement.setString(11, safeOnboardingUsername(username))
                    statement.setString(12, safeFailureCode(invitationError))
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun startAttempt(sessionId: String, runId: String, trigger: String, at: Instant, source: String?,
                                      chatId: Long?, username: String?, invitationAt: Instant?, invitationError: String?) {
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
                                 start_source, onboarding_version, telegram_chat_id, telegram_username,
                                 invitation_delivered_at, invitation_error_reason)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                            statement.setObject(9, chatId)
                            statement.setString(10, safeOnboardingUsername(username))
                            statement.setTimestamp(11, invitationAt?.let(Timestamp::from))
                            statement.setString(12, safeFailureCode(invitationError))
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
                    "UPDATE onboarding_attempts SET ${step.column} = COALESCE(${step.column}, ?)" +
                        if (step == AttemptMark.REMINDER_SET) ", post_completion_practice_observable = TRUE WHERE run_id = ?"
                        else " WHERE run_id = ?",
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
                        reminder_decision = COALESCE(reminder_decision, ?),
                        post_completion_practice_observable =
                            post_completion_practice_observable OR (reminder_decision IS NULL AND ? = 'not_now')
                    WHERE run_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(at))
                    statement.setString(2, decision)
                    statement.setString(3, decision)
                    statement.setString(4, runId)
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
                        speech_before_sec, speech_after_sec, received_at, failure_stage, failure_code
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    statement.setString(15, safeFailureStage(facts.failureStage))
                    statement.setString(16, safeFailureCode(facts.failureCode))
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

    override suspend fun event(attemptId: String, key: String, type: String, at: Instant,
                               failureStage: String?, failureCode: String?) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO onboarding_events (attempt_id, event_key, event_type, created_at, failure_stage, failure_code) " +
                        "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (attempt_id, event_key) DO NOTHING",
                ).use { statement ->
                    statement.setString(1, attemptId)
                    statement.setString(2, key)
                    statement.setString(3, type)
                    statement.setTimestamp(4, Timestamp.from(at))
                    statement.setString(5, safeFailureStage(failureStage))
                    statement.setString(6, safeFailureCode(failureCode))
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
                connection.autoCommit = false
                try {
                connection.prepareStatement(
                    """
                    UPDATE onboarding_attempts
                    SET first_practice_at = COALESCE(first_practice_at, ?),
                        first_post_completion_practice_at = CASE
                            WHEN post_completion_practice_observable AND
                                (CASE WHEN reminder_decision = 'not_now' THEN reminder_decision_at
                                      ELSE reminder_set_at END) <= ?
                            THEN COALESCE(first_post_completion_practice_at, ?)
                            ELSE first_post_completion_practice_at END,
                        d1_voice_at = CASE WHEN ((started_at AT TIME ZONE 'Europe/Moscow')::date + 1)
                            = (? AT TIME ZONE 'Europe/Moscow')::date THEN COALESCE(d1_voice_at, ?) ELSE d1_voice_at END,
                        d7_voice_at = CASE WHEN ((started_at AT TIME ZONE 'Europe/Moscow')::date + 7)
                            = (? AT TIME ZONE 'Europe/Moscow')::date THEN COALESCE(d7_voice_at, ?) ELSE d7_voice_at END
                    WHERE session_id = ?
                      AND is_primary = TRUE
                      AND result_delivered_at IS NOT NULL
                      AND result_delivered_at <= ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(at))
                    statement.setTimestamp(2, Timestamp.from(at))
                    statement.setTimestamp(3, Timestamp.from(at))
                    statement.setTimestamp(4, Timestamp.from(at))
                    statement.setTimestamp(5, Timestamp.from(at))
                    statement.setTimestamp(6, Timestamp.from(at))
                    statement.setTimestamp(7, Timestamp.from(at))
                    statement.setString(8, sessionId)
                    statement.setTimestamp(9, Timestamp.from(at))
                    statement.executeUpdate()
                }
                connection.prepareStatement("""
                    INSERT INTO onboarding_practice_days (primary_run_id, practice_day, first_reply_at)
                    SELECT run_id, (? AT TIME ZONE 'Europe/Moscow')::date, ?
                    FROM onboarding_attempts
                    WHERE session_id = ? AND is_primary = TRUE
                      AND result_delivered_at IS NOT NULL AND result_delivered_at <= ?
                    ON CONFLICT (primary_run_id, practice_day) DO UPDATE
                    SET first_reply_at = LEAST(onboarding_practice_days.first_reply_at, EXCLUDED.first_reply_at)
                """.trimIndent()).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(at))
                    statement.setTimestamp(2, Timestamp.from(at))
                    statement.setString(3, sessionId)
                    statement.setTimestamp(4, Timestamp.from(at))
                    statement.executeUpdate()
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

    override suspend fun report(now: Instant, filter: OnboardingFilter): OnboardingReport = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection -> connection.onboardingAggregateReport(now, filter) }
    }

    override fun close() {
        cleanupScope.cancel()
        (dataSource as? HikariDataSource)?.close()
    }

    private fun purgePersonalSnapshots(cutoff: Timestamp): Int = dataSource.connection.use { connection ->
        listOf("onboarding_entries" to "received_at", "onboarding_attempts" to "started_at").sumOf { (table, at) ->
            connection.prepareStatement("""
                WITH expired AS (
                    SELECT ctid FROM $table WHERE $at < ?
                      AND (telegram_username IS NOT NULL OR telegram_chat_id IS NOT NULL)
                    LIMIT 1000
                )
                UPDATE $table AS row SET telegram_username = NULL, telegram_chat_id = NULL
                FROM expired WHERE row.ctid = expired.ctid
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, cutoff)
                statement.queryTimeout = 5
                statement.executeUpdate()
            }
        }
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
