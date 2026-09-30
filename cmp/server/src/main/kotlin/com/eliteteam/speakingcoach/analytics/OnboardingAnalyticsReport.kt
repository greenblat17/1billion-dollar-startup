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
)

internal data class OnboardingVoiceRow(
    val attemptId: String,
    val sessionId: String,
    val recognized: Boolean,
    val failureReason: String?,
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
)

internal fun onboardingReport(
    attempts: List<OnboardingAttemptRow>,
    voices: List<OnboardingVoiceRow>,
    now: Instant,
): OnboardingReport {
    val primary = attempts.filter { it.isPrimary }
    val repeats = attempts.filter { !it.isPrimary }
    return OnboardingReport(
        closedPrimary = cohorts(primary, now, closed = true, withReturn = true),
        openPrimary = cohorts(primary, now, closed = false, withReturn = false),
        closedRepeats = cohorts(repeats, now, closed = true, withReturn = false),
        openRepeatCount = repeats.count { !dayClosed(it.startedAt, now) },
        recognition = recognitionErrors(voices, now),
        assessment = assessmentErrors(attempts, now),
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
        .map { (day, rows) -> cohort(day, rows, withReturn) }
}

private fun cohort(day: LocalDate, rows: List<OnboardingAttemptRow>, withReturn: Boolean): CohortFunnel {
    val reached = FUNNEL.map { (name, at) -> rows.count { withinWindow(at(it), it.startedAt) } }
    val steps = FUNNEL.indices.map { index ->
        val count = reached[index]
        FunnelStep(
            name = FUNNEL[index].first,
            count = count,
            ofStartPercent = percentOf(count, reached.first()),
            ofPreviousPercent = if (index == 0) 100 else percentOf(count, reached[index - 1]),
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
    at != null && !at.isAfter(startedAt.plus(WINDOW))

private fun returnedNextDay(attempt: OnboardingAttemptRow): Boolean {
    val voice = attempt.d1VoiceAt ?: return false
    val startDay = attempt.startedAt.atZone(ONBOARDING_ZONE).toLocalDate()
    val voiceDay = voice.atZone(ONBOARDING_ZONE).toLocalDate()
    return voiceDay == startDay.plusDays(1)
}

private fun levelLabel(cefr: String?): String = cefr?.takeIf { it.isNotBlank() } ?: "нет уровня"

private fun recognitionErrors(voices: List<OnboardingVoiceRow>, now: Instant): ErrorStat {
    val recent = voices.filter { !it.createdAt.isBefore(now.minus(ERROR_WINDOW)) }
    val failed = recent.filter { !it.failureReason.isNullOrBlank() }
    return ErrorStat(
        errors = failed.size,
        denominator = recent.size,
        people = failed.map { it.sessionId }.distinct().size,
    )
}

private fun assessmentErrors(attempts: List<OnboardingAttemptRow>, now: Instant): ErrorStat {
    val recent = attempts.filter { attempt ->
        val reached = attempt.speech120At ?: return@filter false
        !reached.isBefore(now.minus(ERROR_WINDOW))
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
