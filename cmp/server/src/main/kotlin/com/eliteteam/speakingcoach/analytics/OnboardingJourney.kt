package com.eliteteam.speakingcoach.analytics

import java.time.Duration
import java.time.Instant

internal enum class JourneyMode { PRIMARY, REPEATS }

internal data class JourneyStage(val id: String, val label: String)

internal val JOURNEY_STAGES = listOf(
    JourneyStage("start", "Начали"),
    JourneyStage("invitation", "Приглашение доставлено"),
    JourneyStage("begin", "Нажали Let’s chat"),
    JourneyStage("first_question", "Первый вопрос доставлен"),
    JourneyStage("voice_received", "Первый голос получен"),
    JourneyStage("voice_recognized", "Первый голос распознан"),
    JourneyStage("speech_30", "30 секунд речи"),
    JourneyStage("speech_60", "60 секунд речи"),
    JourneyStage("speech_90", "90 секунд речи"),
    JourneyStage("speech_120", "120 секунд речи"),
    JourneyStage("result_delivered", "Результат доставлен"),
    JourneyStage("result_opened", "Результат открыт"),
    JourneyStage("goal_shown", "Выбор минут показан"),
    JourneyStage("goal_decided", "Решение о минутах"),
    JourneyStage("reminder_offered", "Напоминание предложено"),
    JourneyStage("reminder_resolved", "Напоминание решено"),
)

internal data class JourneyStepCount(
    val stage: JourneyStage,
    val reached: Int,
    val continued: Int,
    val stopped: Int,
    val stoppedWithOutcome: Int,
)

internal data class JourneyErrorCount(
    val stageId: String,
    val technicalStage: String,
    val reason: String,
    val events: Int,
    val users: Int,
    val attempts: Int = users,
)

internal data class JourneyRecentUser(
    val startedAt: Instant,
    val username: String?,
    val chatId: Long?,
    val source: String?,
    val version: String?,
    val attemptNumber: Int?,
    val stageId: String,
    val state: String,
    val lastAt: Instant,
    val lastError: String?,
)

internal data class JourneyReport(
    val total: Int = 0,
    val open: Int = 0,
    val closed: Int = 0,
    val incomplete: Int = 0,
    val steps: List<JourneyStepCount> = emptyList(),
    val errors: List<JourneyErrorCount> = emptyList(),
    val recent: List<JourneyRecentUser> = emptyList(),
)

private data class JourneyItem(
    val sessionId: String,
    val start: Instant,
    val entry: OnboardingEntryRow?,
    val attempt: OnboardingAttemptRow?,
    val voices: List<OnboardingVoiceRow>,
    val events: List<OnboardingEventRow>,
) {
    val runId: String? get() = attempt?.runId
    val times: List<Instant?> get() {
        val a = attempt
        val reminderResolved = when {
            a?.reminderDecision == "not_now" -> a.reminderDecisionAt
            a?.reminderSetAt != null -> a.reminderSetAt
            else -> null
        }
        return listOf(
            start, entry?.invitationDeliveredAt ?: a?.invitationDeliveredAt,
            a?.beginPressedAt, a?.firstQuestionDeliveredAt,
            voices.minOfOrNull { it.receivedAt },
            voices.filter { it.outcome == "recognized" }.minOfOrNull { it.receivedAt },
            a?.speech30At, a?.speech60At, a?.speech90At, a?.speech120At,
            a?.resultDeliveredAt, a?.resultsOpenedAt, a?.practiceSetupAt, a?.goalSelectedAt,
            a?.reminderOfferedAt, reminderResolved,
        )
    }
}

private data class JourneyFailure(val sessionId: String, val runId: String?, val stageId: String, val technicalStage: String,
                                  val reason: String, val at: Instant)

internal fun onboardingJourney(
    attempts: List<OnboardingAttemptRow>, voices: List<OnboardingVoiceRow>,
    events: List<OnboardingEventRow>, entries: List<OnboardingEntryRow>,
    now: Instant, filter: OnboardingFilter,
): JourneyReport {
    val since = now.atZone(ONBOARDING_ZONE).toLocalDate().minusDays(filter.days.toLong() - 1)
        .atStartOfDay(ONBOARDING_ZONE).toInstant()
    val byRun = attempts.associateBy { it.runId }
    val voicesByRun = voices.groupBy { it.attemptId }
    val eventsByRun = events.groupBy { it.attemptId }
    val items = when (filter.journeyMode) {
        JourneyMode.PRIMARY -> entries.asSequence().filter { it.eligible && it.trigger == "start" }
            .groupBy { it.sessionId }.values.mapNotNull { rows ->
                rows.minWithOrNull(compareBy<OnboardingEntryRow> { it.receivedAt }.thenBy { it.entryKey })
            }.filter { entry ->
                val attempt = byRun[entry.runId]
                !entry.receivedAt.isBefore(since) && !entry.receivedAt.isAfter(now) &&
                    (filter.source == null || (entry.source ?: "direct") == filter.source) &&
                    (filter.version == null || attempt?.version == filter.version) &&
                    (filter.trigger == null || filter.trigger == "start")
            }.map { entry ->
                val attempt = byRun[entry.runId]
                JourneyItem(entry.sessionId, entry.receivedAt, entry, attempt,
                    voicesByRun[entry.runId].orEmpty(), eventsByRun[entry.runId].orEmpty())
            }
        JourneyMode.REPEATS -> attempts.filter { !it.isPrimary && !it.startedAt.isBefore(since) &&
            !it.startedAt.isAfter(now) && (filter.source == null || (it.source ?: "direct") == filter.source) &&
            (filter.version == null || it.version == filter.version) &&
            (filter.trigger == null || it.trigger == filter.trigger) }
            .map { JourneyItem(it.sessionId, it.startedAt, null, it,
                voicesByRun[it.runId].orEmpty(), eventsByRun[it.runId].orEmpty()) }
    }
    val failures = items.flatMap(::journeyFailures)
    val counts = mutableMapOf<Triple<String, String, String>, MutableList<JourneyFailure>>()
    failures.forEach { failure -> counts.getOrPut(Triple(failure.stageId, failure.technicalStage, failure.reason)) {
        mutableListOf()
    }.add(failure) }
    val errors = counts.map { (key, rows) -> JourneyErrorCount(key.first, key.second, key.third,
        rows.size, rows.map { it.sessionId }.distinct().size, rows.mapNotNull { it.runId }.distinct().size) }
        .sortedWith(compareBy<JourneyErrorCount> { JOURNEY_STAGES.indexOfFirst { stage -> stage.id == it.stageId } }
            .thenByDescending { it.events })
    val flags = items.associateWith { item -> item.times.map { at ->
        at != null && !at.isBefore(item.start) && !at.isAfter(item.start.plus(Duration.ofHours(24)))
    } }
    fun hasGap(reached: List<Boolean>) = reached.indices.drop(1).any { reached[it] && !reached[it - 1] }
    val closed = items.filter { !now.isBefore(it.start.plus(Duration.ofHours(24))) }
    val incomplete = closed.count { it.attempt == null || hasGap(flags.getValue(it)) }
    val steps = JOURNEY_STAGES.mapIndexed { index, stage ->
        val reached = items.count { flags.getValue(it)[index] }
        val continued = if (index == JOURNEY_STAGES.lastIndex) 0 else items.count {
            flags.getValue(it)[index] && flags.getValue(it)[index + 1]
        }
        val stoppedItems = if (index == JOURNEY_STAGES.lastIndex) emptyList() else closed.filter { item ->
            val row = flags.getValue(item)
            item.attempt != null && !hasGap(row) && row[index] && !row[index + 1]
        }
        JourneyStepCount(stage, reached, continued, stoppedItems.size,
            stoppedItems.count { item -> failures.any { it.sessionId == item.sessionId && it.runId == item.runId &&
                (it.stageId == stage.id || it.stageId == JOURNEY_STAGES[index + 1].id) } })
    }
    val recent = items.groupBy { it.sessionId }.values.mapNotNull { rows -> rows.maxByOrNull { it.start } }
        .sortedByDescending { it.start }.take(15).map { item ->
            val row = item.times.map { at -> at != null && !at.isBefore(item.start) && !at.isAfter(now) }
            val gap = hasGap(row)
            val index = row.indices.lastOrNull { row[it] } ?: 0
            val latestError = failures.filter { it.sessionId == item.sessionId }.maxByOrNull { it.at }
            JourneyRecentUser(
                startedAt = item.start,
                username = item.entry?.username ?: item.attempt?.username,
                chatId = item.entry?.chatId ?: item.attempt?.chatId,
                source = item.entry?.source ?: item.attempt?.source,
                version = item.attempt?.version,
                attemptNumber = item.attempt?.attemptNumber,
                stageId = JOURNEY_STAGES[index].id,
                state = when {
                    item.attempt == null || gap -> "incomplete"
                    row.last() -> "completed"
                    now.isBefore(item.start.plus(Duration.ofHours(24))) -> "in_progress"
                    else -> "stopped"
                },
                lastAt = (item.times.filterNotNull() + item.voices.map { it.createdAt } +
                    item.events.map { it.createdAt }).maxOrNull() ?: item.start,
                lastError = latestError?.let { "${it.technicalStage}: ${it.reason}" },
            )
        }
    return JourneyReport(items.size, items.size - closed.size, closed.size, incomplete, steps, errors, recent)
}

private fun journeyFailures(item: JourneyItem): List<JourneyFailure> = buildList {
    (item.entry?.invitationError ?: item.attempt?.invitationError)?.let {
        add(JourneyFailure(item.sessionId, item.runId, "start", "invitation", it, item.start))
    }
    item.voices.filter { it.outcome != "recognized" }.forEach { voice ->
        val stageId = when {
            (voice.speechBeforeSec ?: 0.0) >= 120 -> "speech_120"
            (voice.speechBeforeSec ?: 0.0) >= 90 -> "speech_90"
            (voice.speechBeforeSec ?: 0.0) >= 60 -> "speech_60"
            (voice.speechBeforeSec ?: 0.0) >= 30 -> "speech_30"
            else -> "voice_received"
        }
        val technical = voice.failureStage ?: when (voice.outcome) {
            "no_speech", "stt_failure" -> "stt"
            "delivery_failure" -> "telegram_delivery"
            "queue_full" -> "queue"
            else -> "other"
        }
        add(JourneyFailure(item.sessionId, item.runId, stageId, technical, voice.failureCode ?: voice.outcome, voice.createdAt))
    }
    item.events.filter { it.type == "stage_error" || it.type == "result_build_failed" }.forEach { event ->
        val technical = event.failureStage ?: "result_build"
        val stageId = when (technical) {
            "first_question" -> "begin"
            "result_build", "result_delivery" -> "speech_120"
            "result_card" -> "result_delivered"
            "goal" -> "goal_shown"
            "reminder" -> "reminder_offered"
            else -> "start"
        }
        add(JourneyFailure(item.sessionId, item.runId, stageId, technical, event.failureCode ?: "unknown", event.createdAt))
    }
}
