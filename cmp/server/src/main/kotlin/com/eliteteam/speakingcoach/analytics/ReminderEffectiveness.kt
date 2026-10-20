package com.eliteteam.speakingcoach.analytics

import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

internal data class ReminderConversion(
    val sent: Int = 0,
    val mature: Int = 0,
    val pending: Int = 0,
    val clicked: Int = 0,
    val firstVoice: Int = 0,
    val progressed: Int = 0,
)

internal data class ReminderDeliveryCounts(
    val attempts: Int = 0,
    val sent: Int = 0,
    val blocked: Int = 0,
    val failed: Int = 0,
    val unknown: Int = 0,
)

internal data class ReminderErrorDay(val day: String, val source: String, val counts: ReminderDeliveryCounts)

internal data class ReminderEffectiveness(
    val configured: ReminderConversion,
    val inferred: ReminderConversion,
    val onboarding: ReminderConversion,
    val onboardingStages: Map<String, ReminderConversion> = emptyMap(),
    val onboardingCopy: Map<Pair<String, String>, ReminderConversion> = emptyMap(),
    val configuredCopy: Map<String, ReminderConversion>,
    val inferredCopy: Map<String, ReminderConversion>,
    val inferredHours: Map<Int, Int>,
    val configuredDelivery: ReminderDeliveryCounts,
    val configuredErrorReasons: Map<String, Int>,
    val errorDays: List<ReminderErrorDay>,
)

private data class ReminderFact(
    val source: String,
    val copyKind: String?,
    val journeyStage: String?,
    val copyId: String?,
    val mature: Boolean,
    val clicked: Boolean,
    val firstVoice: Boolean,
    val progressed: Boolean,
)

private fun List<ReminderFact>.conversion(): ReminderConversion = ReminderConversion(
    sent = size,
    mature = count { it.mature },
    pending = count { !it.mature },
    clicked = count { it.mature && it.clicked },
    firstVoice = count { it.mature && it.firstVoice },
    progressed = count { it.mature && it.progressed },
)

internal fun loadReminderEffectiveness(connection: Connection, days: Int, now: Instant): ReminderEffectiveness {
    val to = now.atZone(ONBOARDING_ZONE).toLocalDate()
    val from = to.minusDays((if (days == 30) 30 else 7) - 1L)
    val facts = connection.prepareStatement(REMINDER_EFFECTIVENESS_SQL).use { statement ->
        statement.setTimestamp(1, Timestamp.from(now))
        statement.setObject(2, from)
        statement.setObject(3, to)
        statement.executeQuery().use { rows -> buildList {
            while (rows.next()) add(ReminderFact(
                rows.getString("source"), rows.getString("copy_kind"),
                rows.getString("journey_stage"), rows.getString("copy_id"), rows.getBoolean("mature"),
                rows.getBoolean("clicked"), rows.getBoolean("first_voice"), rows.getBoolean("progressed"),
            ))
        } }
    }
    val hours = connection.prepareStatement("""
        SELECT ((preferred_utc_minute + 180) / 60) % 24 AS hour, count(*)
        FROM (
            SELECT DISTINCT ON (session_id) session_id, preferred_utc_minute
            FROM onboarding_nudges
            WHERE journey_stage = 'practice' AND status = 'sent'
              AND preferred_utc_minute IS NOT NULL AND day BETWEEN ? AND ?
            ORDER BY session_id, sent_at DESC
        ) users GROUP BY hour ORDER BY hour
    """.trimIndent()).use { statement ->
        statement.setObject(1, from)
        statement.setObject(2, to)
        statement.executeQuery().use { rows -> buildMap {
            while (rows.next()) put(rows.getInt(1), rows.getInt(2))
        } }
    }
    val attemptsByReason = connection.prepareStatement("""
        SELECT status, error_code, count(*) FROM configured_practice_attempts
        WHERE day BETWEEN ? AND ? GROUP BY status, error_code
    """.trimIndent()).use { statement ->
        statement.setObject(1, from)
        statement.setObject(2, to)
        statement.executeQuery().use { rows -> buildList {
            while (rows.next()) add(Triple(rows.getString(1), rows.getString(2), rows.getInt(3)))
        } }
    }
    val delivery = ReminderDeliveryCounts(
        attempts = attemptsByReason.sumOf { it.third },
        sent = attemptsByReason.filter { it.first == "sent" }.sumOf { it.third },
        blocked = attemptsByReason.filter { it.first == "blocked" }.sumOf { it.third },
        failed = attemptsByReason.filter { it.first == "failed" }.sumOf { it.third },
        unknown = attemptsByReason.filter { it.first == "unknown" }.sumOf { it.third },
    )
    val dayRows = connection.prepareStatement("""
        SELECT day, source, status, count(*) FROM (
            SELECT day, 'configured'::text AS source, status FROM configured_practice_attempts
            UNION ALL
            SELECT day, CASE WHEN journey_stage = 'practice' THEN 'inferred' ELSE 'onboarding' END,
                   status FROM onboarding_nudges
            UNION ALL
            SELECT (claimed_at AT TIME ZONE 'Europe/Moscow')::date,
                   'soft_' || kind,
                   CASE WHEN status = 'claimed' AND claimed_at <= ? THEN 'unknown' ELSE status END
            FROM soft_nudges
        ) attempts WHERE day BETWEEN ? AND ?
        GROUP BY day, source, status ORDER BY day DESC, source
    """.trimIndent()).use { statement ->
        statement.setTimestamp(1, Timestamp.from(now.minusSeconds(600)))
        statement.setObject(2, from)
        statement.setObject(3, to)
        statement.executeQuery().use { rows -> buildList {
            while (rows.next()) add(Triple(rows.getString(1) to rows.getString(2), rows.getString(3), rows.getInt(4)))
        } }
    }
    val errorDays = dayRows.groupBy { it.first }.map { (key, rows) ->
        ReminderErrorDay(key.first, key.second, ReminderDeliveryCounts(
            attempts = rows.sumOf { it.third },
            sent = rows.filter { it.second == "sent" }.sumOf { it.third },
            blocked = rows.filter { it.second == "blocked" }.sumOf { it.third },
            failed = rows.filter { it.second == "failed" }.sumOf { it.third },
            unknown = rows.filter { it.second == "unknown" }.sumOf { it.third },
        ))
    }
    return ReminderEffectiveness(
        configured = facts.filter { it.source == "configured" }.conversion(),
        inferred = facts.filter { it.source == "inferred" }.conversion(),
        onboarding = facts.filter { it.source == "onboarding" }.conversion(),
        onboardingStages = facts.filter { it.source == "onboarding" }
            .groupBy { it.journeyStage ?: "unknown" }.mapValues { it.value.conversion() },
        onboardingCopy = facts.filter { it.source == "onboarding" }
            .groupBy { (it.journeyStage ?: "unknown") to (it.copyId ?: "unknown") }
            .mapValues { it.value.conversion() },
        configuredCopy = facts.filter { it.source == "configured" }.groupBy { it.copyKind ?: "unknown" }
            .mapValues { it.value.conversion() },
        inferredCopy = facts.filter { it.source == "inferred" }.groupBy { it.copyKind ?: "unknown" }
            .mapValues { it.value.conversion() },
        inferredHours = hours,
        configuredDelivery = delivery,
        configuredErrorReasons = attemptsByReason.filter { it.first != "sent" }
            .groupBy { it.second ?: "unknown" }.mapValues { (_, rows) -> rows.sumOf { it.third } },
        errorDays = errorDays,
    )
}

private val REMINDER_EFFECTIVENESS_SQL = """
    WITH params AS (SELECT ?::timestamptz AS now, ?::date AS from_day, ?::date AS to_day), sends AS (
        SELECT 'configured'::text AS source, b.session_id, b.chat_id,
               b.created_at AS sent_at, b.copy_kind, b.clicked_at,
               NULL::text AS journey_stage, NULL::text AS run_id, NULL::text AS copy_id
        FROM reminder_buttons b CROSS JOIN params p
        WHERE b.source = 'configured_practice'
          AND b.created_at >= (p.from_day::timestamp AT TIME ZONE 'Europe/Moscow')
          AND b.created_at <= p.now
        UNION ALL
        SELECT CASE WHEN n.journey_stage = 'practice' THEN 'inferred' ELSE 'onboarding' END,
               n.session_id, s.chat_id, n.sent_at, n.copy_kind, b.clicked_at,
               n.journey_stage, n.run_id, n.copy_id
        FROM onboarding_nudges n CROSS JOIN params p
        JOIN onboarding_followup_schedule s ON s.session_id = n.session_id
        LEFT JOIN reminder_buttons b ON b.chat_id = s.chat_id AND b.message_id = n.message_id
        WHERE n.status = 'sent' AND n.sent_at IS NOT NULL
          AND n.day BETWEEN p.from_day AND p.to_day
          AND n.journey_stage IS NOT NULL AND NOT n.final_message
    ), ordered AS (
        SELECT sends.*, LEAD(sent_at) OVER (PARTITION BY session_id ORDER BY sent_at) AS next_at
        FROM sends
    ), windows AS (
        SELECT o.*, LEAST(o.sent_at + interval '24 hours', COALESCE(o.next_at, p.now), p.now) AS end_at,
               (o.next_at IS NOT NULL OR o.sent_at + interval '24 hours' <= p.now) AS mature
        FROM ordered o CROSS JOIN params p
        WHERE (o.sent_at AT TIME ZONE 'Europe/Moscow')::date BETWEEN p.from_day AND p.to_day
    )
    SELECT w.source, w.copy_kind, w.journey_stage, w.copy_id, w.mature,
           COALESCE(w.clicked_at > w.sent_at AND w.clicked_at < w.end_at, FALSE) AS clicked,
           EXISTS (SELECT 1 FROM interaction_events i
                   WHERE i.chat_id = w.chat_id AND i.direction = 'incoming'
                     AND i.kind = 'voice' AND i.status = 'received'
                     AND i.occurred_at > w.sent_at AND i.occurred_at < w.end_at) AS first_voice,
           (w.source = 'onboarding' AND (
               EXISTS (
                   SELECT 1 FROM onboarding_attempts a
                   CROSS JOIN LATERAL (VALUES
                       (1, a.invitation_delivered_at), (2, a.begin_pressed_at),
                       (3, a.first_question_delivered_at), (4, a.first_voice_at),
                       (5, (SELECT MIN(v.received_at) FROM onboarding_voices v
                            WHERE v.attempt_id = a.run_id AND v.recognized)),
                       (6, a.speech_30_at), (7, a.speech_60_at), (8, a.speech_90_at),
                       (9, a.speech_120_at), (10, a.result_delivered_at),
                       (11, a.results_opened_at), (12, a.practice_setup_at),
                       (13, a.goal_selected_at), (14, a.reminder_offered_at),
                       (15, COALESCE(a.reminder_decision_at, a.reminder_set_at))
                   ) AS later(rank, at)
                   WHERE a.run_id = w.run_id
                     AND later.rank > CASE w.journey_stage
                         WHEN 'start' THEN 0 WHEN 'invitation' THEN 1 WHEN 'begin' THEN 2
                         WHEN 'first_question' THEN 3 WHEN 'voice_received' THEN 4
                         WHEN 'voice_recognized' THEN 5 WHEN 'speech_30' THEN 6
                         WHEN 'speech_60' THEN 7 WHEN 'speech_90' THEN 8
                         WHEN 'speech_120' THEN 9 WHEN 'result_delivered' THEN 10
                         WHEN 'result_opened' THEN 11 WHEN 'goal_shown' THEN 12
                         WHEN 'goal_decided' THEN 13 WHEN 'reminder_offered' THEN 14
                         ELSE 15 END
                     AND later.at > w.sent_at AND later.at < w.end_at
               ) OR (w.journey_stage = 'reminder_resolved' AND EXISTS (
                   SELECT 1 FROM interaction_events i WHERE i.chat_id = w.chat_id
                     AND i.direction = 'incoming' AND i.kind = 'voice' AND i.status = 'received'
                     AND i.occurred_at > w.sent_at AND i.occurred_at < w.end_at
               ))
           )) AS progressed
    FROM windows w
""".trimIndent()
