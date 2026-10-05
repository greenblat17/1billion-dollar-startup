package com.eliteteam.speakingcoach.analytics

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/** The 90-day population is aggregated in SQL; only fifteen individual rows leave Postgres. */
internal fun Connection.onboardingJourneyReport(now: Instant, filter: OnboardingFilter): JourneyReport {
    val since = now.atZone(ONBOARDING_ZONE).toLocalDate().minusDays(filter.days.toLong() - 1)
        .atStartOfDay(ONBOARDING_ZONE).toInstant()
    val prefix = journeySqlPrefix(filter.journeyMode)
    fun <T> query(sql: String, read: (ResultSet) -> T): T = prepareStatement("$prefix\n$sql").use { statement ->
        statement.setTimestamp(1, Timestamp.from(since))
        statement.setTimestamp(2, Timestamp.from(now))
        statement.setString(3, filter.version)
        statement.setString(4, filter.version)
        statement.setString(5, filter.source)
        statement.setString(6, filter.source)
        statement.setString(7, filter.trigger)
        statement.setString(8, filter.trigger)
        statement.setTimestamp(9, Timestamp.from(now))
        statement.queryTimeout = 5
        statement.executeQuery().use(read)
    }
    val missing = "run_id IS NULL OR " + journeyGapSql()
    val columns = buildList {
        add("COUNT(*) AS total")
        add("COUNT(*) FILTER (WHERE start_at + interval '24 hours' > report_at) AS open_count")
        add("COUNT(*) FILTER (WHERE start_at + interval '24 hours' <= report_at AND ($missing)) AS incomplete")
        JOURNEY_STAGES.indices.forEach { index ->
            add("COUNT(*) FILTER (WHERE s$index) AS reached_$index")
            if (index < JOURNEY_STAGES.lastIndex) {
                add("COUNT(*) FILTER (WHERE s$index AND s${index + 1}) AS continued_$index")
                val stopped = "start_at + interval '24 hours' <= report_at AND run_id IS NOT NULL " +
                    "AND NOT (${journeyGapSql()}) AND s$index AND NOT s${index + 1}"
                add("COUNT(*) FILTER (WHERE $stopped) AS stopped_$index")
                add("COUNT(*) FILTER (WHERE $stopped AND ${journeyRelevantErrorSql(index)}) AS failed_$index")
            }
        }
    }
    val totals = query("SELECT ${columns.joinToString(",\n")} FROM flags") { rows ->
        rows.next()
        val steps = JOURNEY_STAGES.mapIndexed { index, stage ->
            JourneyStepCount(stage, rows.getInt("reached_$index"),
                if (index == JOURNEY_STAGES.lastIndex) 0 else rows.getInt("continued_$index"),
                if (index == JOURNEY_STAGES.lastIndex) 0 else rows.getInt("stopped_$index"),
                if (index == JOURNEY_STAGES.lastIndex) 0 else rows.getInt("failed_$index"))
        }
        JourneyReport(rows.getInt("total"), rows.getInt("open_count"),
            rows.getInt("total") - rows.getInt("open_count"), rows.getInt("incomplete"), steps)
    }
    val errors = query("""
        , failures AS (
            SELECT cohort_session_id AS session_id, run_id, 'start' AS stage_id, 'invitation' AS technical_stage,
                   journey_invitation_error AS reason
            FROM facts WHERE journey_invitation_error IS NOT NULL
            UNION ALL
            SELECT s.cohort_session_id AS session_id, s.run_id, ${voiceJourneyStageSql("v")} AS stage_id,
                   COALESCE(v.failure_stage, CASE v.outcome
                       WHEN 'no_speech' THEN 'stt' WHEN 'stt_failure' THEN 'stt'
                       WHEN 'delivery_failure' THEN 'telegram_delivery'
                       WHEN 'queue_full' THEN 'queue' ELSE 'other' END) AS technical_stage,
                   COALESCE(v.failure_code, v.outcome) AS reason
            FROM selected s JOIN onboarding_voices v ON v.attempt_id = s.run_id
            WHERE v.outcome <> 'recognized'
            UNION ALL
            SELECT s.cohort_session_id AS session_id, s.run_id, ${eventJourneyStageSql("ev")} AS stage_id,
                   COALESCE(ev.failure_stage, 'result_build') AS technical_stage,
                   COALESCE(ev.failure_code, 'unknown') AS reason
            FROM selected s JOIN onboarding_events ev ON ev.attempt_id = s.run_id
            WHERE ev.event_type IN ('stage_error', 'result_build_failed')
        )
        SELECT stage_id, technical_stage, reason, COUNT(*) AS events,
               COUNT(DISTINCT session_id) AS users, COUNT(DISTINCT run_id) AS attempts
        FROM failures GROUP BY stage_id, technical_stage, reason
        ORDER BY events DESC, stage_id, technical_stage, reason
    """.trimIndent()) { rows -> buildList {
        while (rows.next()) add(JourneyErrorCount(rows.getString("stage_id"),
            rows.getString("technical_stage"), rows.getString("reason"),
            rows.getInt("events"), rows.getInt("users"), rows.getInt("attempts")))
    } }
    val recent = query("""
        , recent_user AS (
            SELECT DISTINCT ON (cohort_session_id) * FROM flags
            ORDER BY cohort_session_id, start_at DESC
        ), latest AS (
            SELECT * FROM recent_user ORDER BY start_at DESC LIMIT 15
        )
        SELECT l.*, last_error.technical_stage AS last_error_stage,
               last_error.reason AS last_error_reason,
               GREATEST(l.start_at, l.invitation_at, l.begin_pressed_at,
                   l.first_question_delivered_at, l.first_voice_received_at, l.first_recognized_at,
                   l.speech_30_at, l.speech_60_at, l.speech_90_at, l.speech_120_at,
                   l.result_delivered_at, l.results_opened_at, l.practice_setup_at,
                   l.goal_selected_at, l.reminder_offered_at, l.reminder_resolved_at,
                   activity.last_event_at) AS last_at
        FROM latest l LEFT JOIN LATERAL (
            SELECT technical_stage, reason, at FROM (
                SELECT 'invitation' AS technical_stage, l.journey_invitation_error AS reason,
                       l.start_at AS at WHERE l.journey_invitation_error IS NOT NULL
                UNION ALL
                SELECT COALESCE(v.failure_stage, 'other'), COALESCE(v.failure_code, v.outcome), v.created_at
                FROM onboarding_voices v WHERE v.attempt_id = l.run_id AND v.outcome <> 'recognized'
                UNION ALL
                SELECT COALESCE(ev.failure_stage, 'result_build'), COALESCE(ev.failure_code, 'unknown'), ev.created_at
                FROM onboarding_events ev WHERE ev.attempt_id = l.run_id
                  AND ev.event_type IN ('stage_error', 'result_build_failed')
            ) all_errors ORDER BY at DESC LIMIT 1
        ) last_error ON TRUE
        LEFT JOIN LATERAL (
            SELECT GREATEST(
                (SELECT MAX(created_at) FROM onboarding_voices WHERE attempt_id = l.run_id),
                (SELECT MAX(created_at) FROM onboarding_events WHERE attempt_id = l.run_id)
            ) AS last_event_at
        ) activity ON TRUE
        ORDER BY l.start_at DESC
    """.trimIndent()) { rows -> buildList {
        while (rows.next()) {
            val start = rows.getTimestamp("start_at").toInstant()
            val flags = journeyColumns.map { column -> rows.getTimestamp(column)?.toInstant()?.let {
                !it.isBefore(start) && !it.isAfter(now)
            } == true }
            val gap = flags.indices.drop(1).any { flags[it] && !flags[it - 1] }
            val state = when {
                rows.getString("run_id") == null || gap -> "incomplete"
                flags.last() -> "completed"
                now.isBefore(start.plusSeconds(86_400)) -> "in_progress"
                else -> "stopped"
            }
            val errorStage = rows.getString("last_error_stage")
            val errorReason = rows.getString("last_error_reason")
            add(JourneyRecentUser(start, rows.getString("entry_username") ?: rows.getString("telegram_username"),
                (rows.getObject("entry_chat_id") as? Number)?.toLong()
                    ?: (rows.getObject("telegram_chat_id") as? Number)?.toLong(),
                rows.getString("entry_source") ?: rows.getString("start_source"),
                rows.getString("onboarding_version"),
                (rows.getObject("attempt_number") as? Number)?.toInt(),
                JOURNEY_STAGES[flags.indexOfLast { it }.coerceAtLeast(0)].id, state,
                rows.getTimestamp("last_at").toInstant(),
                if (errorStage == null) null else "$errorStage: $errorReason"))
        }
    } }
    return totals.copy(errors = errors, recent = recent)
}

private val journeyColumns = listOf(
    "start_at", "invitation_at", "begin_pressed_at", "first_question_delivered_at",
    "first_voice_received_at", "first_recognized_at", "speech_30_at", "speech_60_at",
    "speech_90_at", "speech_120_at", "result_delivered_at", "results_opened_at",
    "practice_setup_at", "goal_selected_at", "reminder_offered_at", "reminder_resolved_at",
)

private fun journeyGapSql(): String = JOURNEY_STAGES.indices.drop(1)
    .joinToString(" OR ") { "(s$it AND NOT s${it - 1})" }

private fun voiceJourneyStageSql(alias: String): String = """CASE
    WHEN COALESCE($alias.speech_before_sec, 0) >= 120 THEN 'speech_120'
    WHEN COALESCE($alias.speech_before_sec, 0) >= 90 THEN 'speech_90'
    WHEN COALESCE($alias.speech_before_sec, 0) >= 60 THEN 'speech_60'
    WHEN COALESCE($alias.speech_before_sec, 0) >= 30 THEN 'speech_30'
    ELSE 'voice_received' END"""

private fun eventJourneyStageSql(alias: String): String = """CASE $alias.failure_stage
    WHEN 'first_question' THEN 'begin'
    WHEN 'result_build' THEN 'speech_120'
    WHEN 'result_delivery' THEN 'speech_120'
    WHEN 'result_card' THEN 'result_delivered'
    WHEN 'goal' THEN 'goal_shown'
    WHEN 'reminder' THEN 'reminder_offered'
    ELSE 'start' END"""

private fun journeyRelevantErrorSql(index: Int): String {
    val ids = listOf(JOURNEY_STAGES[index].id, JOURNEY_STAGES[index + 1].id)
        .joinToString(",") { "'$it'" }
    return """(
        (journey_invitation_error IS NOT NULL AND 'start' IN ($ids))
        OR EXISTS (SELECT 1 FROM onboarding_voices v WHERE v.attempt_id = flags.run_id
            AND v.outcome <> 'recognized' AND ${voiceJourneyStageSql("v")} IN ($ids))
        OR EXISTS (SELECT 1 FROM onboarding_events ev WHERE ev.attempt_id = flags.run_id
            AND ev.event_type IN ('stage_error', 'result_build_failed')
            AND ${eventJourneyStageSql("ev")} IN ($ids))
    )"""
}

private fun journeySqlPrefix(mode: JourneyMode): String {
    val selected = if (mode == JourneyMode.PRIMARY) """
        WITH first_entry AS (
            SELECT DISTINCT ON (session_id) * FROM onboarding_entries
            WHERE eligible = TRUE AND trigger = 'start'
            ORDER BY session_id, received_at, entry_key
        ), selected AS (
            SELECT e.session_id AS cohort_session_id, e.received_at AS start_at, e.run_id AS entry_run_id,
                   e.invitation_delivered_at AS invitation_at,
                   e.invitation_error_reason AS entry_invitation_error, e.telegram_username AS entry_username,
                   e.telegram_chat_id AS entry_chat_id, e.start_source AS entry_source, a.*
            FROM first_entry e LEFT JOIN onboarding_attempts a ON a.run_id = e.run_id
            WHERE e.received_at >= ? AND e.received_at <= ?
              AND (? IS NULL OR a.onboarding_version = ?)
              AND (? IS NULL OR COALESCE(e.start_source, 'direct') = ?)
              AND (? IS NULL OR ? = 'start')
        )
    """ else """
        WITH selected AS (
            SELECT a.session_id AS cohort_session_id, a.started_at AS start_at, a.run_id AS entry_run_id,
                   a.invitation_delivered_at AS invitation_at,
                   NULL::text AS entry_invitation_error, NULL::text AS entry_username,
                   NULL::bigint AS entry_chat_id, a.start_source AS entry_source, a.*
            FROM onboarding_attempts a
            WHERE a.is_primary = FALSE AND a.started_at >= ? AND a.started_at <= ?
              AND (? IS NULL OR a.onboarding_version = ?)
              AND (? IS NULL OR COALESCE(a.start_source, 'direct') = ?)
              AND (? IS NULL OR a.trigger = ?)
        )
    """
    val flags = journeyColumns.mapIndexed { index, column ->
        "($column IS NOT NULL AND $column >= start_at AND $column <= start_at + interval '24 hours') AS s$index"
    }
    return """${selected.trimIndent()}, facts AS (
        SELECT s.*, ?::timestamptz AS report_at, v.first_voice_received_at, v.first_recognized_at,
               COALESCE(s.entry_invitation_error, s.invitation_error_reason) AS journey_invitation_error,
               CASE WHEN s.reminder_decision = 'not_now' THEN s.reminder_decision_at
                    WHEN s.reminder_set_at IS NOT NULL THEN s.reminder_set_at END AS reminder_resolved_at
        FROM selected s LEFT JOIN LATERAL (
            SELECT MIN(received_at) AS first_voice_received_at,
                   MIN(received_at) FILTER (WHERE outcome = 'recognized') AS first_recognized_at
            FROM onboarding_voices WHERE attempt_id = s.run_id
        ) v ON TRUE
    ), flags AS (
        SELECT facts.*, ${flags.joinToString(",\n")} FROM facts
    )"""
}
