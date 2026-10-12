package com.eliteteam.speakingcoach.analytics

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

internal data class OnboardingEntryRow(
    val sessionId: String,
    val entryKey: String,
    val receivedAt: Instant,
    val eligible: Boolean,
    val trigger: String,
    val exclusionReason: String?,
    val source: String?,
    val runId: String?,
    val invitationDeliveredAt: Instant? = null,
    val chatId: Long? = null,
    val username: String? = null,
    val invitationError: String? = null,
)

internal data class PracticeDayRow(val runId: String, val day: LocalDate, val firstReplyAt: Instant)

internal data class ActivationCohort(
    val day: LocalDate,
    val eligible: Int,
    val invitations: Int,
    val beginPressed: Int,
    val firstQuestions: Int,
    val firstVoices: Int,
    val recognizedVoices: Int,
    val results: Int,
    val mature: Int,
    val activated: Int,
    val practiceTwoDays: Int,
    val missingAttempt: Int,
    val resultP50Sec: Int?,
    val practiceP50Sec: Int?,
)

internal fun activationCohorts(
    entries: List<OnboardingEntryRow>, attempts: List<OnboardingAttemptRow>,
    voices: List<OnboardingVoiceRow>, practiceDays: List<PracticeDayRow>,
    now: Instant, filter: OnboardingFilter,
): List<ActivationCohort> {
    val since = now.atZone(ONBOARDING_ZONE).toLocalDate().minusDays(filter.days.toLong() - 1)
    val attemptsById = attempts.associateBy { it.runId }
    val voicesById = voices.groupBy { it.attemptId }
    val practiceById = practiceDays.groupBy { it.runId }
    val first = entries.asSequence().filter { it.eligible && it.trigger == "start" }
        .groupBy { it.sessionId }.values.mapNotNull { rows -> rows.minWithOrNull(compareBy<OnboardingEntryRow> { it.receivedAt }.thenBy { it.entryKey }) }
    return first.asSequence().filter { entry ->
        val attempt = attemptsById[entry.runId]
        !entry.receivedAt.isAfter(now) &&
            !entry.receivedAt.atZone(ONBOARDING_ZONE).toLocalDate().isBefore(since) &&
            (filter.source == null || (entry.source ?: "direct") == filter.source) &&
            (filter.version == null || attempt?.version == filter.version) &&
            (filter.trigger == null || filter.trigger == "start")
    }.groupBy { it.receivedAt.atZone(ONBOARDING_ZONE).toLocalDate() }
        .toSortedMap(compareByDescending { it })
        .map { (day, rows) ->
            fun within(at: Instant?, start: Instant, window: Duration) =
                at != null && !at.isBefore(start) && !at.isAfter(start.plus(window))
            fun <T> count(test: (OnboardingEntryRow, OnboardingAttemptRow?, List<OnboardingVoiceRow>, List<PracticeDayRow>) -> T?): Int =
                rows.count { entry ->
                    val attempt = attemptsById[entry.runId]
                    test(entry, attempt, voicesById[entry.runId].orEmpty(), practiceById[entry.runId].orEmpty()) != null
                }
            val sevenDays = Duration.ofDays(7)
            val oneDay = Duration.ofDays(1)
            val resultTimes = rows.mapNotNull { entry -> attemptsById[entry.runId]?.resultDeliveredAt
                ?.takeIf { within(it, entry.receivedAt, sevenDays) }
                ?.let { Duration.between(entry.receivedAt, it).seconds.toInt() } }
            val practiceTimes = rows.mapNotNull { entry ->
                val resultAt = attemptsById[entry.runId]?.resultDeliveredAt ?: return@mapNotNull null
                practiceById[entry.runId].orEmpty().map { it.firstReplyAt }
                    .filter { !it.isBefore(resultAt) && within(it, entry.receivedAt, sevenDays) }
                    .minOrNull()?.let { entry to Duration.between(entry.receivedAt, it).seconds.toInt() }
            }
            ActivationCohort(
                day = day,
                eligible = rows.size,
                invitations = count { e, _, _, _ -> e.invitationDeliveredAt?.takeIf { within(it, e.receivedAt, oneDay) } },
                beginPressed = count { e, a, _, _ -> a?.beginPressedAt?.takeIf { within(it, e.receivedAt, oneDay) } },
                firstQuestions = count { e, a, _, _ -> a?.firstQuestionDeliveredAt?.takeIf { within(it, e.receivedAt, oneDay) } },
                firstVoices = count { e, _, v, _ -> v.minOfOrNull { it.receivedAt }?.takeIf { within(it, e.receivedAt, oneDay) } },
                recognizedVoices = count { e, _, v, _ -> v.filter { it.outcome == "recognized" }
                    .minOfOrNull { it.receivedAt }?.takeIf { within(it, e.receivedAt, oneDay) } },
                results = resultTimes.size,
                mature = rows.count { !now.isBefore(it.receivedAt.plus(sevenDays)) },
                activated = practiceTimes.count { (entry, _) -> !now.isBefore(entry.receivedAt.plus(sevenDays)) },
                practiceTwoDays = count { e, a, _, days ->
                    if (now.isBefore(e.receivedAt.plus(sevenDays))) return@count null
                    val resultAt = a?.resultDeliveredAt ?: return@count null
                    days.count { !it.firstReplyAt.isBefore(resultAt) && within(it.firstReplyAt, e.receivedAt, sevenDays) }
                        .takeIf { it >= 2 }
                },
                missingAttempt = rows.count { attemptsById[it.runId] == null },
                resultP50Sec = discreteP50(resultTimes),
                practiceP50Sec = discreteP50(practiceTimes.map { it.second }),
            )
        }
}

private fun discreteP50(values: List<Int>): Int? = values.sorted().takeIf { it.isNotEmpty() }?.get((values.size - 1) / 2)

/** One bounded SQL aggregation. The first eligible entry is chosen before date filtering. */
internal fun Connection.activationCohorts(now: Instant, filter: OnboardingFilter): List<ActivationCohort> {
    val since = now.atZone(ONBOARDING_ZONE).toLocalDate().minusDays(filter.days.toLong() - 1)
        .atStartOfDay(ONBOARDING_ZONE).toInstant()
    val sql = """
        WITH first_entries AS (
            SELECT DISTINCT ON (session_id) * FROM onboarding_entries
            WHERE eligible = TRUE AND trigger = 'start'
            ORDER BY session_id, received_at, entry_key
        ), selected AS (
            SELECT e.*, a.onboarding_version, a.begin_pressed_at, a.first_question_delivered_at,
                   a.result_delivered_at,
                   (e.received_at AT TIME ZONE 'Europe/Moscow')::date AS cohort_day
            FROM first_entries e LEFT JOIN onboarding_attempts a ON a.run_id = e.run_id
            WHERE e.received_at >= ? AND e.received_at <= ?
              AND (? IS NULL OR a.onboarding_version = ?)
              AND (? IS NULL OR COALESCE(e.start_source, 'direct') = ?)
              AND (? IS NULL OR ? = 'start')
        ), facts AS (
            SELECT e.*,
                   v.first_voice, v.first_recognized,
                   p.first_practice, p.practice_days,
                   e.received_at + interval '24 hours' AS day_end,
                   e.received_at + interval '7 days' AS week_end
            FROM selected e
            LEFT JOIN LATERAL (
                SELECT MIN(received_at) AS first_voice,
                       MIN(received_at) FILTER (WHERE outcome = 'recognized') AS first_recognized
                FROM onboarding_voices WHERE attempt_id = e.run_id
            ) v ON TRUE
            LEFT JOIN LATERAL (
                SELECT MIN(first_reply_at) AS first_practice, COUNT(*) AS practice_days
                FROM onboarding_practice_days
                WHERE primary_run_id = e.run_id
                  AND first_reply_at >= e.result_delivered_at
                  AND first_reply_at >= e.received_at
                  AND first_reply_at <= e.received_at + interval '7 days'
            ) p ON TRUE
        )
        SELECT cohort_day, COUNT(*) AS eligible,
               COUNT(*) FILTER (WHERE invitation_delivered_at BETWEEN received_at AND day_end) AS invitations,
               COUNT(*) FILTER (WHERE begin_pressed_at BETWEEN received_at AND day_end) AS begin_pressed,
               COUNT(*) FILTER (WHERE first_question_delivered_at BETWEEN received_at AND day_end) AS first_questions,
               COUNT(*) FILTER (WHERE first_voice BETWEEN received_at AND day_end) AS first_voices,
               COUNT(*) FILTER (WHERE first_recognized BETWEEN received_at AND day_end) AS recognized_voices,
               COUNT(*) FILTER (WHERE result_delivered_at BETWEEN received_at AND week_end) AS results,
               COUNT(*) FILTER (WHERE week_end <= ?) AS mature,
               COUNT(*) FILTER (WHERE week_end <= ? AND first_practice IS NOT NULL) AS activated,
               COUNT(*) FILTER (WHERE week_end <= ? AND practice_days >= 2) AS practice_two_days,
               COUNT(*) FILTER (WHERE run_id IS NULL OR onboarding_version IS NULL) AS missing_attempt,
               percentile_disc(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM result_delivered_at - received_at))
                   FILTER (WHERE result_delivered_at BETWEEN received_at AND week_end) AS result_p50,
               percentile_disc(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM first_practice - received_at))
                   FILTER (WHERE first_practice IS NOT NULL) AS practice_p50
        FROM facts GROUP BY cohort_day ORDER BY cohort_day DESC
    """.trimIndent()
    return prepareStatement(sql).use { statement ->
        statement.setTimestamp(1, Timestamp.from(since))
        statement.setTimestamp(2, Timestamp.from(now))
        statement.setString(3, filter.version)
        statement.setString(4, filter.version)
        statement.setString(5, filter.source)
        statement.setString(6, filter.source)
        statement.setString(7, filter.trigger)
        statement.setString(8, filter.trigger)
        statement.setTimestamp(9, Timestamp.from(now))
        statement.setTimestamp(10, Timestamp.from(now))
        statement.setTimestamp(11, Timestamp.from(now))
        statement.queryTimeout = 5
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.activationCohort()) } }
    }
}

private fun ResultSet.activationCohort() = ActivationCohort(
    day = getDate("cohort_day").toLocalDate(), eligible = getInt("eligible"),
    invitations = getInt("invitations"), beginPressed = getInt("begin_pressed"),
    firstQuestions = getInt("first_questions"), firstVoices = getInt("first_voices"),
    recognizedVoices = getInt("recognized_voices"), results = getInt("results"),
    mature = getInt("mature"), activated = getInt("activated"),
    practiceTwoDays = getInt("practice_two_days"), missingAttempt = getInt("missing_attempt"),
    resultP50Sec = (getObject("result_p50") as? Number)?.toInt(),
    practiceP50Sec = (getObject("practice_p50") as? Number)?.toInt(),
)
