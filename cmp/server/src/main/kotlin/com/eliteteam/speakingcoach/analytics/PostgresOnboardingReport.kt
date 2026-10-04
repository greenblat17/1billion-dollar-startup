package com.eliteteam.speakingcoach.analytics

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/** Aggregate in Postgres so the admin page does not transfer every voice and attempt to Ktor. */
internal fun Connection.onboardingAggregateReport(now: Instant, filter: OnboardingFilter): OnboardingReport {
    val since = now.atZone(ONBOARDING_ZONE).toLocalDate()
        .minusDays(filter.days.toLong() - 1).atStartOfDay(ONBOARDING_ZONE).toInstant()
    val selector = ReportSelector(since, now, filter)
    val daily = selector.query(this, dailySql()) { rows ->
        buildList {
            while (rows.next()) {
                val day = rows.getDate("cohort_day").toLocalDate()
                val primary = rows.getBoolean("is_primary")
                val count = rows.getInt("starts")
                val stepCounts = listOf(count) + (1 until FUNNEL_COLUMNS.size).map { rows.getInt("step_$it") }
                val pairs = listOf(count) + (1 until FUNNEL_COLUMNS.size).map { rows.getInt("pair_$it") }
                val steps = FUNNEL_NAMES.indices.map { index ->
                    FunnelStep(
                        id = FUNNEL_STEP_IDS[index],
                        name = FUNNEL_NAMES[index],
                        count = stepCounts[index],
                        ofStartPercent = percentOf(stepCounts[index], count),
                        ofPreviousPercent = if (index == 0) 100 else percentOf(pairs[index], stepCounts[index - 1]),
                        withPreviousCount = pairs[index],
                    )
                }
                add(
                    DailyAggregate(
                        day = day,
                        primary = primary,
                        funnel = CohortFunnel(
                            day = day,
                            steps = steps,
                            returnedNextDay = rows.getInt("d1"),
                            returnedNextDayPercent = percentOf(rows.getInt("d1"), count),
                            levels = emptyMap(),
                            goals = emptyMap(),
                            size = count,
                            firstPractice = rows.getInt("first_practice"),
                            d7Eligible = if (primary && !now.isBefore(day.plusDays(8).atStartOfDay(ONBOARDING_ZONE).toInstant())) count else 0,
                            returnedDay7 = rows.getInt("d7"),
                            skippedPrevious = (1 until stepCounts.size).sumOf { stepCounts[it] - pairs[it] },
                            completedByD1 = rows.getInt("completed_d1"),
                            completedByD7 = if (primary && !now.isBefore(day.plusDays(8).atStartOfDay(ONBOARDING_ZONE).toInstant())) rows.getInt("completed_d7") else 0,
                        ),
                    ),
                )
            }
        }
    }
    val levels = selector.query(this, """
        SELECT cohort_day, is_primary, COALESCE(NULLIF(cefr, ''), 'нет уровня') AS label, COUNT(*) AS count
        FROM selected WHERE ${validStep("completed_at")}
        GROUP BY cohort_day, is_primary, label
    """.trimIndent()) { rows -> countMap(rows) }
    val goals = selector.query(this, """
        SELECT cohort_day, is_primary, goal_minutes::text AS label, COUNT(*) AS count
        FROM selected WHERE ${validStep("goal_selected_at")} AND goal_minutes IN (5, 10, 15)
        GROUP BY cohort_day, is_primary, goal_minutes
    """.trimIndent()) { rows -> countMap(rows) }
    val cohorts = daily.map { aggregate ->
        val key = aggregate.day to aggregate.primary
        aggregate.copy(funnel = aggregate.funnel.copy(
            levels = levels[key].orEmpty(),
            goals = goals[key].orEmpty().mapKeys { it.key.toInt() },
        ))
    }
    val decisions = selector.query(this, """
        SELECT COUNT(*) AS attempts,
               COUNT(*) FILTER (WHERE completed_at IS NOT NULL) AS result_built,
               COUNT(*) FILTER (WHERE practice_setup_at IS NOT NULL) AS goal_shown,
               COUNT(*) FILTER (WHERE goal_selected_at IS NOT NULL) AS goal_selected,
               COUNT(*) FILTER (WHERE result_delivered_at IS NOT NULL) AS result_delivered,
               COUNT(*) FILTER (WHERE result_delivered_at IS NOT NULL AND score_available = TRUE) AS scored,
               COUNT(*) FILTER (WHERE result_delivered_at IS NOT NULL AND grammar_examples_count > 0) AS grammar_examples,
               COUNT(*) FILTER (WHERE result_delivered_at IS NOT NULL AND vocabulary_examples_count > 0) AS vocabulary_examples,
               COUNT(*) FILTER (WHERE result_delivered_at IS NOT NULL AND fluency_metrics_available = TRUE) AS fluency_metrics,
               COUNT(*) FILTER (WHERE reminder_offered_at IS NOT NULL) AS reminder_offered,
               COUNT(*) FILTER (WHERE reminder_decision = 'set_reminder') AS reminder_accepted,
               COUNT(*) FILTER (WHERE reminder_decision = 'not_now') AS reminder_declined,
               COUNT(*) FILTER (WHERE reminder_set_at IS NOT NULL) AS reminder_set,
               COUNT(*) FILTER (WHERE profile_opened_at IS NOT NULL) AS profile_opened,
               COUNT(*) FILTER (WHERE bye_at IS NOT NULL) AS bye,
               COUNT(*) FILTER (WHERE first_practice_at IS NOT NULL) AS first_practice
        FROM selected
    """.trimIndent()) { rows ->
        rows.next()
        DecisionMetrics(
            attempts = rows.getInt("attempts"),
            resultBuilt = rows.getInt("result_built"),
            goalShown = rows.getInt("goal_shown"),
            goalSelected = rows.getInt("goal_selected"),
            resultDelivered = rows.getInt("result_delivered"),
            scored = rows.getInt("scored"),
            grammarExamplesShown = rows.getInt("grammar_examples"),
            vocabularyExamplesShown = rows.getInt("vocabulary_examples"),
            fluencyMeasurementsShown = rows.getInt("fluency_metrics"),
            reminderOffered = rows.getInt("reminder_offered"),
            reminderAccepted = rows.getInt("reminder_accepted"),
            reminderDeclined = rows.getInt("reminder_declined"),
            reminderSet = rows.getInt("reminder_set"),
            profileOpened = rows.getInt("profile_opened"),
            bye = rows.getInt("bye"),
            firstPractice = rows.getInt("first_practice"),
        )
    }
    val eventCounts = selector.query(this, """
        SELECT e.event_type, COUNT(*) AS count FROM onboarding_events e
        JOIN selected a ON a.run_id = e.attempt_id GROUP BY e.event_type
    """.trimIndent()) { rows ->
        buildMap { while (rows.next()) put(rows.getString("event_type"), rows.getInt("count")) }
    }
    val outcomeCounts = selector.query(this, """
        SELECT v.outcome, COUNT(*) AS count FROM onboarding_voices v
        JOIN selected a ON a.run_id = v.attempt_id GROUP BY v.outcome
    """.trimIndent()) { rows ->
        buildMap { while (rows.next()) put(rows.getString("outcome"), rows.getInt("count")) }
    }
    val stageOutcomes = selector.query(this, """
        SELECT CASE
            WHEN v.speech_before_sec IS NULL THEN 'неизвестно'
            WHEN v.speech_before_sec < 30 THEN '0–30 сек'
            WHEN v.speech_before_sec < 60 THEN '30–60 сек'
            WHEN v.speech_before_sec < 90 THEN '60–90 сек'
            WHEN v.speech_before_sec < 120 THEN '90–120 сек'
            ELSE '120+ сек' END AS stage, v.outcome, COUNT(*) AS count
        FROM onboarding_voices v JOIN selected a ON a.run_id = v.attempt_id
        GROUP BY stage, v.outcome
    """.trimIndent()) { rows ->
        val values = linkedMapOf<String, MutableMap<String, Int>>()
        while (rows.next()) values.getOrPut(rows.getString("stage")) { linkedMapOf() }[rows.getString("outcome")] = rows.getInt("count")
        values
    }
    val turns = selector.query(this, """
        SELECT turn_index, COUNT(*) AS voices,
               COUNT(*) FILTER (WHERE outcome = 'recognized') AS recognized,
               COUNT(*) FILTER (WHERE outcome = 'no_speech') AS no_speech,
               COUNT(*) FILTER (WHERE outcome IN ('stt_failure', 'processing_failure', 'delivery_failure', 'queue_full')) AS technical,
               COUNT(*) FILTER (WHERE next_at IS NOT NULL) AS next_voice,
               COUNT(*) FILTER (WHERE result_delivered_at >= received_at) AS result_after
        FROM (
            SELECT LEAST(GREATEST(v.voice_index, 1), 10) AS turn_index,
                   v.outcome, v.received_at, a.result_delivered_at,
                   LEAD(v.received_at) OVER (PARTITION BY v.attempt_id ORDER BY v.received_at, v.created_at) AS next_at
            FROM onboarding_voices v JOIN selected a ON a.run_id = v.attempt_id
        ) ordered GROUP BY turn_index ORDER BY turn_index
    """.trimIndent()) { rows ->
        buildList { while (rows.next()) add(VoiceTurnDiagnostic(
            rows.getInt("turn_index"), rows.getInt("voices"), rows.getInt("recognized"),
            rows.getInt("no_speech"), rows.getInt("technical"), rows.getInt("next_voice"), rows.getInt("result_after"))) }
    }
    val lastOutcomes = selector.query(this, """
        SELECT outcome, COUNT(*) AS attempts FROM (
            SELECT v.outcome, ROW_NUMBER() OVER
                (PARTITION BY v.attempt_id ORDER BY v.received_at DESC, v.created_at DESC) AS position
            FROM onboarding_voices v JOIN selected a ON a.run_id = v.attempt_id
        ) ordered WHERE position = 1 GROUP BY outcome
    """.trimIndent()) { rows ->
        buildMap { while (rows.next()) put(rows.getString("outcome"), rows.getInt("attempts")) }
    }
    val consecutiveFailures = selector.query(this, """
        SELECT COUNT(DISTINCT attempt_id) AS attempts FROM (
            SELECT v.attempt_id, v.outcome,
                   LAG(v.outcome) OVER (PARTITION BY v.attempt_id ORDER BY v.received_at, v.created_at) AS previous
            FROM onboarding_voices v JOIN selected a ON a.run_id = v.attempt_id
        ) ordered WHERE outcome IN ('no_speech', 'stt_failure')
                    AND previous IN ('no_speech', 'stt_failure')
    """.trimIndent()) { rows -> rows.next(); rows.getInt("attempts") }
    val timings = selector.query(this, """
        SELECT percentile_disc(0.5) WITHIN GROUP (ORDER BY v.processing_ms)
                   FILTER (WHERE v.outcome = 'recognized') AS p50,
               percentile_disc(0.95) WITHIN GROUP (ORDER BY v.processing_ms)
                   FILTER (WHERE v.outcome = 'recognized') AS p95
        FROM onboarding_voices v JOIN selected a ON a.run_id = v.attempt_id
    """.trimIndent()) { rows ->
        rows.next()
        (rows.getObject("p50") as? Number)?.toInt() to (rows.getObject("p95") as? Number)?.toInt()
    }
    val gaps = selector.query(this, """
        SELECT percentile_disc(0.5) WITHIN GROUP (ORDER BY gap_sec) AS p50,
               percentile_disc(0.95) WITHIN GROUP (ORDER BY gap_sec) AS p95
        FROM (
            SELECT EXTRACT(EPOCH FROM v.received_at - LAG(v.created_at)
                OVER (PARTITION BY v.attempt_id ORDER BY v.received_at))::integer AS gap_sec
            FROM onboarding_voices v JOIN selected a ON a.run_id = v.attempt_id
            WHERE v.outcome IN ('recognized', 'no_speech')
        ) gaps WHERE gap_sec >= 0
    """.trimIndent()) { rows ->
        rows.next()
        (rows.getObject("p50") as? Number)?.toInt() to (rows.getObject("p95") as? Number)?.toInt()
    }
    val voicesPerCompleted = selector.query(this, """
        SELECT percentile_disc(0.5) WITHIN GROUP (ORDER BY voice_count) AS p50
        FROM (
            SELECT COUNT(*)::integer AS voice_count FROM selected a
            JOIN onboarding_voices v ON v.attempt_id = a.run_id AND v.outcome = 'recognized'
            WHERE a.completed_at IS NOT NULL GROUP BY a.run_id
        ) totals
    """.trimIndent()) { rows -> rows.next(); (rows.getObject("p50") as? Number)?.toInt() }
    val voiceCountDistribution = selector.query(this, """
        SELECT voice_count, COUNT(*) AS people FROM (
            SELECT COUNT(*)::integer AS voice_count FROM selected a
            JOIN onboarding_voices v ON v.attempt_id = a.run_id AND v.outcome = 'recognized'
            WHERE a.completed_at IS NOT NULL GROUP BY a.run_id
        ) totals GROUP BY voice_count
    """.trimIndent()) { rows ->
        val values = linkedMapOf<String, Int>()
        while (rows.next()) {
            val bucket = voiceCountBucket(rows.getInt("voice_count"))
            values[bucket] = (values[bucket] ?: 0) + rows.getInt("people")
        }
        values
    }
    val recognition = selector.query(this, """
        SELECT COUNT(*) FILTER (WHERE v.outcome IN ('no_speech', 'stt_failure')) AS errors,
               COUNT(*) AS denominator,
               COUNT(DISTINCT v.session_id) FILTER (WHERE v.outcome IN ('no_speech', 'stt_failure')) AS people
        FROM onboarding_voices v JOIN selected a ON a.run_id = v.attempt_id
        WHERE v.created_at >= ? AND v.created_at <= ? AND v.outcome != 'queue_full'
    """.trimIndent(), extra = listOf(Timestamp.from(now.minusSeconds(7 * 86_400L)), Timestamp.from(now))) { rows ->
        rows.next(); ErrorStat(rows.getInt("errors"), rows.getInt("denominator"), rows.getInt("people"))
    }
    val assessment = selector.query(this, """
        SELECT COUNT(*) FILTER (WHERE assessment_failed_at IS NOT NULL) AS errors,
               COUNT(*) AS denominator,
               COUNT(DISTINCT session_id) FILTER (WHERE assessment_failed_at IS NOT NULL) AS people
        FROM selected WHERE speech_120_at >= ? AND speech_120_at <= ?
    """.trimIndent(), extra = listOf(Timestamp.from(now.minusSeconds(7 * 86_400L)), Timestamp.from(now))) { rows ->
        rows.next(); ErrorStat(rows.getInt("errors"), rows.getInt("denominator"), rows.getInt("people"))
    }
    val choices = ReportSelector(since, now, OnboardingFilter(days = filter.days)).query(this, """
        SELECT array_agg(DISTINCT onboarding_version) AS versions,
               array_agg(DISTINCT COALESCE(start_source, 'direct')) AS sources,
               array_agg(DISTINCT trigger) AS triggers FROM selected
    """.trimIndent()) { rows ->
        rows.next()
        Triple(rows.stringArray("versions"), rows.stringArray("sources"), rows.stringArray("triggers"))
    }
    val closedPrimary = cohorts.filter { it.primary && dayClosed(it.day.atStartOfDay(ONBOARDING_ZONE).toInstant(), now) }
        .map { it.funnel }
    return OnboardingReport(
        closedPrimary = closedPrimary,
        openPrimary = cohorts.filter { it.primary && !dayClosed(it.day.atStartOfDay(ONBOARDING_ZONE).toInstant(), now) }.map { it.funnel },
        closedRepeats = cohorts.filter { !it.primary && dayClosed(it.day.atStartOfDay(ONBOARDING_ZONE).toInstant(), now) }.map { it.funnel },
        openRepeatCount = cohorts.filter { !it.primary && !dayClosed(it.day.atStartOfDay(ONBOARDING_ZONE).toInstant(), now) }.sumOf { it.funnel.size },
        recognition = recognition,
        assessment = assessment,
        filter = filter,
        diagnostics = VoiceDiagnostics(outcomeCounts, timings.first, timings.second, gaps.first, gaps.second,
            voicesPerCompleted, voiceCountDistribution, stageOutcomes, turns, lastOutcomes, consecutiveFailures),
        decisions = decisions.copy(
            retryRequested = eventCounts["retry_requested"] ?: 0,
            retryRecovered = eventCounts["retry_recovered"] ?: 0,
            textHint = eventCounts["text_hint"] ?: 0,
            preBeginVoiceHint = eventCounts["pre_begin_voice_hint"] ?: 0,
        ),
        versions = choices.first,
        sources = choices.second,
        triggers = choices.third,
        activation = activationCohorts(now, filter),
    )
}

private data class DailyAggregate(val day: LocalDate, val primary: Boolean, val funnel: CohortFunnel)

private val FUNNEL_NAMES = listOf(
    "Приветствие", "Let’s chat", "Первое голосовое", "30 сек", "60 сек", "90 сек", "120 сек",
    "Результат собран", "Результаты открыты", "Grammar", "Vocabulary", "Fluency", "Выбор минут", "Минуты выбраны",
)

private val FUNNEL_COLUMNS = listOf(
    "started_at", "lets_chat_at", "first_voice_at", "speech_30_at", "speech_60_at", "speech_90_at",
    "speech_120_at", "completed_at", "results_opened_at", "grammar_viewed_at", "vocabulary_viewed_at",
    "fluency_viewed_at", "practice_setup_at", "goal_selected_at",
)

private fun validStep(column: String): String =
    "$column >= started_at AND $column <= started_at + interval '24 hours'"

private fun dailySql(): String {
    val counts = (1 until FUNNEL_COLUMNS.size).joinToString(",\n") { index ->
        val current = validStep(FUNNEL_COLUMNS[index])
        val previous = if (index == 1) "TRUE" else validStep(FUNNEL_COLUMNS[index - 1])
        "COUNT(*) FILTER (WHERE $current) AS step_$index, " +
            "COUNT(*) FILTER (WHERE $current AND $previous) AS pair_$index"
    }
    return """
        SELECT cohort_day, is_primary, COUNT(*) AS starts, $counts,
               COUNT(*) FILTER (WHERE (d1_voice_at AT TIME ZONE 'Europe/Moscow')::date = cohort_day + 1) AS d1,
               COUNT(*) FILTER (WHERE (d7_voice_at AT TIME ZONE 'Europe/Moscow')::date = cohort_day + 7) AS d7,
               COUNT(*) FILTER (WHERE first_practice_at IS NOT NULL) AS first_practice,
               COUNT(*) FILTER (WHERE completed_at IS NOT NULL AND completed_at <
                   ((cohort_day + 2)::timestamp AT TIME ZONE 'Europe/Moscow')) AS completed_d1,
               COUNT(*) FILTER (WHERE completed_at IS NOT NULL AND completed_at <
                   ((cohort_day + 8)::timestamp AT TIME ZONE 'Europe/Moscow')) AS completed_d7
        FROM selected GROUP BY cohort_day, is_primary ORDER BY cohort_day DESC, is_primary DESC
    """.trimIndent()
}

private fun countMap(rows: ResultSet): Map<Pair<LocalDate, Boolean>, Map<String, Int>> {
    val grouped = mutableMapOf<Pair<LocalDate, Boolean>, MutableMap<String, Int>>()
    while (rows.next()) {
        val key = rows.getDate("cohort_day").toLocalDate() to rows.getBoolean("is_primary")
        grouped.getOrPut(key) { linkedMapOf() }[rows.getString("label")] = rows.getInt("count")
    }
    return grouped
}

private fun ResultSet.stringArray(column: String): List<String> =
    (getArray(column)?.array as? Array<*>)?.mapNotNull { it as? String }?.sorted().orEmpty()

private class ReportSelector(private val since: Instant, private val now: Instant, private val filter: OnboardingFilter) {
    private val prefix = """
        WITH selected AS (
            SELECT *, (started_at AT TIME ZONE 'Europe/Moscow')::date AS cohort_day
            FROM onboarding_attempts
            WHERE started_at >= ? AND started_at <= ?
              AND (? IS NULL OR onboarding_version = ?)
              AND (? IS NULL OR COALESCE(start_source, 'direct') = ?)
              AND (? IS NULL OR trigger = ?)
        )
    """.trimIndent()

    fun <T> query(connection: Connection, sql: String, extra: List<Timestamp> = emptyList(), read: (ResultSet) -> T): T =
        connection.prepareStatement("$prefix\n$sql").use { statement ->
            statement.setTimestamp(1, Timestamp.from(since))
            statement.setTimestamp(2, Timestamp.from(now))
            statement.setString(3, filter.version)
            statement.setString(4, filter.version)
            statement.setString(5, filter.source)
            statement.setString(6, filter.source)
            statement.setString(7, filter.trigger)
            statement.setString(8, filter.trigger)
            extra.forEachIndexed { index, timestamp -> statement.setTimestamp(9 + index, timestamp) }
            statement.queryTimeout = 5
            statement.executeQuery().use(read)
        }
}
