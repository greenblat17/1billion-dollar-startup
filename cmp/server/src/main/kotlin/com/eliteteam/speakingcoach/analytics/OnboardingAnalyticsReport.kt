package com.eliteteam.speakingcoach.analytics

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

internal val ONBOARDING_ZONE: ZoneId = ZoneId.of("Europe/Moscow")
private val WINDOW: Duration = Duration.ofHours(24)
private val ERROR_WINDOW: Duration = Duration.ofDays(7)

internal data class OnboardingAttemptRow(
    val runId: String,
    val sessionId: String,
    val trigger: String,
    val isPrimary: Boolean,
    val startedAt: Instant,
    val letsChatAt: Instant? = null,
    val firstVoiceAt: Instant? = null,
    val speech30At: Instant? = null,
    val speech60At: Instant? = null,
    val speech90At: Instant? = null,
    val speech120At: Instant? = null,
    val completedAt: Instant? = null,
    val resultsOpenedAt: Instant? = null,
    val grammarViewedAt: Instant? = null,
    val vocabularyViewedAt: Instant? = null,
    val fluencyViewedAt: Instant? = null,
    val practiceSetupAt: Instant? = null,
    val goalSelectedAt: Instant? = null,
    val goalMinutes: Int? = null,
    val cefr: String? = null,
    val assessmentFailedAt: Instant? = null,
    val d1VoiceAt: Instant? = null,
    val version: String = "v1",
    val source: String? = null,
    val resultDeliveredAt: Instant? = null,
    val reminderOfferedAt: Instant? = null,
    val reminderDecisionAt: Instant? = null,
    val reminderSetAt: Instant? = null,
    val reminderDecision: String? = null,
    val profileOpenedAt: Instant? = null,
    val byeAt: Instant? = null,
    val firstPracticeAt: Instant? = null,
    val d7VoiceAt: Instant? = null,
    val scoreAvailable: Boolean? = null,
    val grammarExamples: Int? = null,
    val vocabularyExamples: Int? = null,
    val fluencyMetricsAvailable: Boolean? = null,
)

internal data class OnboardingVoiceRow(
    val attemptId: String,
    val sessionId: String,
    val recognized: Boolean,
    val failureReason: String?,
    val createdAt: Instant,
    val outcome: String = if (recognized) "recognized" else when (failureReason) {
        "no_speech", "stt_failure", "processing_failure", "delivery_failure", "queue_full" -> failureReason
        else -> "unknown"
    },
    val processingMs: Int = 0,
    val voiceIndex: Int = 0,
    val speechBeforeSec: Double? = null,
    val speechAfterSec: Double? = null,
    val receivedAt: Instant = createdAt,
)

internal data class OnboardingFilter(
    val days: Int = 30,
    val version: String? = null,
    val source: String? = null,
    val trigger: String? = null,
) {
    init { require(days in 1..90) }
}

internal data class OnboardingEventRow(
    val attemptId: String,
    val type: String,
    val createdAt: Instant,
)

internal data class FunnelStep(
    val name: String,
    val count: Int,
    val ofStartPercent: Int,
    val ofPreviousPercent: Int,
)

internal data class CohortFunnel(
    val day: LocalDate,
    val steps: List<FunnelStep>,
    val returnedNextDay: Int,
    val returnedNextDayPercent: Int,
    val levels: Map<String, Int>,
    val goals: Map<Int, Int>,
    val size: Int = steps.firstOrNull()?.count ?: 0,
    val firstPractice: Int = 0,
    val d7Eligible: Int = 0,
    val returnedDay7: Int = 0,
    val skippedPrevious: Int = 0,
    val completedByD1: Int = 0,
    val completedByD7: Int = 0,
)

internal data class VoiceDiagnostics(
    val outcomes: Map<String, Int> = emptyMap(),
    val processingP50Ms: Int? = null,
    val processingP95Ms: Int? = null,
    val replyGapP50Sec: Int? = null,
    val replyGapP95Sec: Int? = null,
    val voicesPerCompletedP50: Int? = null,
    val voicesPerCompleted: Map<String, Int> = emptyMap(),
    val outcomesByStage: Map<String, Map<String, Int>> = emptyMap(),
)

internal data class DecisionMetrics(
    val attempts: Int = 0,
    val resultBuilt: Int = 0,
    val goalShown: Int = 0,
    val goalSelected: Int = 0,
    val resultDelivered: Int = 0,
    val scored: Int = 0,
    val grammarExamplesShown: Int = 0,
    val vocabularyExamplesShown: Int = 0,
    val fluencyMeasurementsShown: Int = 0,
    val reminderOffered: Int = 0,
    val reminderAccepted: Int = 0,
    val reminderDeclined: Int = 0,
    val reminderSet: Int = 0,
    val profileOpened: Int = 0,
    val bye: Int = 0,
    val firstPractice: Int = 0,
    val retryRequested: Int = 0,
    val retryRecovered: Int = 0,
    val textHint: Int = 0,
    val preBeginVoiceHint: Int = 0,
)

internal data class ErrorStat(
    val errors: Int,
    val denominator: Int,
    val people: Int,
) {
    val percent: Int get() = percentOf(errors, denominator)
}

internal data class OnboardingReport(
    val closedPrimary: List<CohortFunnel>,
    val openPrimary: List<CohortFunnel>,
    val closedRepeats: List<CohortFunnel>,
    val openRepeatCount: Int,
    val recognition: ErrorStat,
    val assessment: ErrorStat,
    val filter: OnboardingFilter = OnboardingFilter(),
    val diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
    val decisions: DecisionMetrics = DecisionMetrics(),
    val versions: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val triggers: List<String> = emptyList(),
)

internal fun onboardingReport(
    attempts: List<OnboardingAttemptRow>,
    voices: List<OnboardingVoiceRow>,
    now: Instant,
    filter: OnboardingFilter = OnboardingFilter(),
    events: List<OnboardingEventRow> = emptyList(),
): OnboardingReport {
    val startDay = now.atZone(ONBOARDING_ZONE).toLocalDate().minusDays(filter.days.toLong() - 1)
    val selected = attempts.filter { attempt ->
        !attempt.startedAt.isAfter(now) &&
            !attempt.startedAt.atZone(ONBOARDING_ZONE).toLocalDate().isBefore(startDay) &&
            (filter.version == null || attempt.version == filter.version) &&
            (filter.source == null || (attempt.source ?: "direct") == filter.source) &&
            (filter.trigger == null || attempt.trigger == filter.trigger)
    }
    val selectedIds = selected.mapTo(hashSetOf()) { it.runId }
    val selectedVoices = voices.filter { it.attemptId in selectedIds }
    val selectedEvents = events.filter { it.attemptId in selectedIds }
    val primary = selected.filter { it.isPrimary }
    val repeats = selected.filter { !it.isPrimary }
    return OnboardingReport(
        closedPrimary = cohorts(primary, now, closed = true, withReturn = true),
        openPrimary = cohorts(primary, now, closed = false, withReturn = false),
        closedRepeats = cohorts(repeats, now, closed = true, withReturn = false),
        openRepeatCount = repeats.count { !dayClosed(it.startedAt, now) },
        recognition = recognitionErrors(selectedVoices, now),
        assessment = assessmentErrors(selected, now),
        filter = filter,
        diagnostics = voiceDiagnostics(selected, selectedVoices),
        decisions = decisionMetrics(selected, selectedEvents),
        versions = attempts.map { it.version }.distinct().sorted(),
        sources = attempts.map { it.source ?: "direct" }.distinct().sorted(),
        triggers = attempts.map { it.trigger }.distinct().sorted(),
    )
}

internal fun dayClosed(startedAt: Instant, now: Instant): Boolean {
    val day = startedAt.atZone(ONBOARDING_ZONE).toLocalDate()
    val readyAt = day.plusDays(2).atStartOfDay(ONBOARDING_ZONE).toInstant()
    return !now.isBefore(readyAt)
}

private fun cohorts(
    attempts: List<OnboardingAttemptRow>,
    now: Instant,
    closed: Boolean,
    withReturn: Boolean,
): List<CohortFunnel> {
    return attempts
        .filter { dayClosed(it.startedAt, now) == closed }
        .groupBy { it.startedAt.atZone(ONBOARDING_ZONE).toLocalDate() }
        .toSortedMap(compareByDescending { it })
        .map { (day, rows) -> cohort(day, rows, withReturn, now) }
}

private fun cohort(day: LocalDate, rows: List<OnboardingAttemptRow>, withReturn: Boolean, now: Instant): CohortFunnel {
    val reached = FUNNEL.map { (name, at) -> rows.count { withinWindow(at(it), it.startedAt) } }
    val steps = FUNNEL.indices.map { index ->
        val count = reached[index]
        FunnelStep(
            name = FUNNEL[index].first,
            count = count,
            ofStartPercent = percentOf(count, reached.first()),
            ofPreviousPercent = if (index == 0) 100 else percentOf(
                rows.count { withinWindow(FUNNEL[index - 1].second(it), it.startedAt) && withinWindow(FUNNEL[index].second(it), it.startedAt) },
                reached[index - 1],
            ),
        )
    }
    val returned = if (withReturn) rows.count { returnedNextDay(it) } else 0
    val completed = rows.filter { withinWindow(it.completedAt, it.startedAt) }
    return CohortFunnel(
        day = day,
        steps = steps,
        returnedNextDay = returned,
        returnedNextDayPercent = percentOf(returned, rows.size),
        levels = completed.groupingBy { levelLabel(it.cefr) }.eachCount(),
        goals = rows.filter { withinWindow(it.goalSelectedAt, it.startedAt) && it.goalMinutes in GOAL_MINUTES }
            .groupingBy { it.goalMinutes ?: 0 }
            .eachCount(),
        size = rows.size,
        firstPractice = rows.count { it.firstPracticeAt != null },
        d7Eligible = if (withReturn && !now.isBefore(day.plusDays(8).atStartOfDay(ONBOARDING_ZONE).toInstant())) rows.size else 0,
        returnedDay7 = if (withReturn) rows.count { returnedDay7(it) } else 0,
        skippedPrevious = FUNNEL.indices.drop(1).sumOf { index ->
            rows.count { withinWindow(FUNNEL[index].second(it), it.startedAt) && !withinWindow(FUNNEL[index - 1].second(it), it.startedAt) }
        },
        completedByD1 = rows.count { it.completedAt != null && it.completedAt.isBefore(day.plusDays(2).atStartOfDay(ONBOARDING_ZONE).toInstant()) },
        completedByD7 = if (withReturn && !now.isBefore(day.plusDays(8).atStartOfDay(ONBOARDING_ZONE).toInstant())) {
            rows.count { it.completedAt != null && it.completedAt.isBefore(day.plusDays(8).atStartOfDay(ONBOARDING_ZONE).toInstant()) }
        } else 0,
    )
}

private fun voiceDiagnostics(attempts: List<OnboardingAttemptRow>, voices: List<OnboardingVoiceRow>): VoiceDiagnostics {
    val recognized = voices.filter { it.outcome == "recognized" }
    val gaps = voices.filter { it.outcome == "recognized" || it.outcome == "no_speech" }
        .groupBy { it.attemptId }.values.flatMap { rows ->
            rows.sortedBy { it.receivedAt }.zipWithNext().mapNotNull { (previous, current) ->
                (current.receivedAt.epochSecond - previous.createdAt.epochSecond).takeIf { it >= 0 }?.toInt()
            }
        }
    val completedIds = attempts.filter { it.completedAt != null }.mapTo(hashSetOf()) { it.runId }
    val voicesPerCompleted = voices.filter { it.attemptId in completedIds && it.outcome == "recognized" }
        .groupingBy { it.attemptId }.eachCount().values.toList()
    val outcomesByStage = voices.groupBy { stageLabel(it.speechBeforeSec) }
        .mapValues { (_, rows) -> rows.groupingBy { it.outcome }.eachCount() }
    return VoiceDiagnostics(
        outcomes = voices.groupingBy { it.outcome }.eachCount(),
        processingP50Ms = percentile(recognized.map { it.processingMs }, 50),
        processingP95Ms = percentile(recognized.map { it.processingMs }, 95),
        replyGapP50Sec = percentile(gaps, 50),
        replyGapP95Sec = percentile(gaps, 95),
        voicesPerCompletedP50 = percentile(voicesPerCompleted, 50),
        voicesPerCompleted = voicesPerCompleted.groupingBy(::voiceCountBucket).eachCount(),
        outcomesByStage = outcomesByStage,
    )
}

internal fun stageLabel(seconds: Double?): String = when {
    seconds == null -> "неизвестно"
    seconds < 30 -> "0–30 сек"
    seconds < 60 -> "30–60 сек"
    seconds < 90 -> "60–90 сек"
    seconds < 120 -> "90–120 сек"
    else -> "120+ сек"
}

internal fun voiceCountBucket(count: Int): String = if (count >= 4) "4+" else count.toString()

private fun percentile(values: List<Int>, percent: Int): Int? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    return sorted[((sorted.size * percent + 99) / 100 - 1).coerceAtLeast(0)]
}

private fun decisionMetrics(attempts: List<OnboardingAttemptRow>, events: List<OnboardingEventRow>): DecisionMetrics {
    val types = events.groupingBy { it.type }.eachCount()
    return DecisionMetrics(
        attempts = attempts.size,
        resultBuilt = attempts.count { it.completedAt != null },
        goalShown = attempts.count { it.practiceSetupAt != null },
        goalSelected = attempts.count { it.goalSelectedAt != null },
        resultDelivered = attempts.count { it.resultDeliveredAt != null },
        scored = attempts.count { it.resultDeliveredAt != null && it.scoreAvailable == true },
        grammarExamplesShown = attempts.count { it.resultDeliveredAt != null && (it.grammarExamples ?: 0) > 0 },
        vocabularyExamplesShown = attempts.count { it.resultDeliveredAt != null && (it.vocabularyExamples ?: 0) > 0 },
        fluencyMeasurementsShown = attempts.count { it.resultDeliveredAt != null && it.fluencyMetricsAvailable == true },
        reminderOffered = attempts.count { it.reminderOfferedAt != null },
        reminderAccepted = attempts.count { it.reminderDecision == "set_reminder" },
        reminderDeclined = attempts.count { it.reminderDecision == "not_now" },
        reminderSet = attempts.count { it.reminderSetAt != null },
        profileOpened = attempts.count { it.profileOpenedAt != null },
        bye = attempts.count { it.byeAt != null },
        firstPractice = attempts.count { it.firstPracticeAt != null },
        retryRequested = types["retry_requested"] ?: 0,
        retryRecovered = types["retry_recovered"] ?: 0,
        textHint = types["text_hint"] ?: 0,
        preBeginVoiceHint = types["pre_begin_voice_hint"] ?: 0,
    )
}

private val FUNNEL: List<Pair<String, (OnboardingAttemptRow) -> Instant?>> = listOf(
    "Приветствие" to { it.startedAt },
    "Let’s chat" to { it.letsChatAt },
    "Первое голосовое" to { it.firstVoiceAt },
    "30 сек" to { it.speech30At },
    "60 сек" to { it.speech60At },
    "90 сек" to { it.speech90At },
    "120 сек" to { it.speech120At },
    "Результат собран" to { it.completedAt },
    "Результаты открыты" to { it.resultsOpenedAt },
    "Grammar" to { it.grammarViewedAt },
    "Vocabulary" to { it.vocabularyViewedAt },
    "Fluency" to { it.fluencyViewedAt },
    "Выбор минут" to { it.practiceSetupAt },
    "Минуты выбраны" to { it.goalSelectedAt },
)

private val GOAL_MINUTES = setOf(5, 10, 15)

private fun withinWindow(at: Instant?, startedAt: Instant): Boolean =
    at != null && !at.isBefore(startedAt) && !at.isAfter(startedAt.plus(WINDOW))

private fun returnedNextDay(attempt: OnboardingAttemptRow): Boolean {
    val voice = attempt.d1VoiceAt ?: return false
    val startDay = attempt.startedAt.atZone(ONBOARDING_ZONE).toLocalDate()
    val voiceDay = voice.atZone(ONBOARDING_ZONE).toLocalDate()
    return voiceDay == startDay.plusDays(1)
}

private fun returnedDay7(attempt: OnboardingAttemptRow): Boolean {
    val voice = attempt.d7VoiceAt ?: return false
    val startDay = attempt.startedAt.atZone(ONBOARDING_ZONE).toLocalDate()
    return voice.atZone(ONBOARDING_ZONE).toLocalDate() == startDay.plusDays(7)
}

private fun levelLabel(cefr: String?): String = cefr?.takeIf { it.isNotBlank() } ?: "нет уровня"

private fun recognitionErrors(voices: List<OnboardingVoiceRow>, now: Instant): ErrorStat {
    val recent = voices.filter { !it.createdAt.isBefore(now.minus(ERROR_WINDOW)) && !it.createdAt.isAfter(now) && it.outcome != "queue_full" }
    val failed = recent.filter { it.outcome == "no_speech" || it.outcome == "stt_failure" }
    return ErrorStat(
        errors = failed.size,
        denominator = recent.size,
        people = failed.map { it.sessionId }.distinct().size,
    )
}

private fun assessmentErrors(attempts: List<OnboardingAttemptRow>, now: Instant): ErrorStat {
    val recent = attempts.filter { attempt ->
        val reached = attempt.speech120At ?: return@filter false
        !reached.isBefore(now.minus(ERROR_WINDOW)) && !reached.isAfter(now)
    }
    val failed = recent.filter { it.assessmentFailedAt != null }
    return ErrorStat(
        errors = failed.size,
        denominator = recent.size,
        people = failed.map { it.sessionId }.distinct().size,
    )
}

internal fun percentOf(part: Int, whole: Int): Int {
    if (whole <= 0) return 0
    return ((part * 100.0) / whole).roundToInt()
}
