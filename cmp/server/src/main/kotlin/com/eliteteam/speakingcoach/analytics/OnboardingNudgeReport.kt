package com.eliteteam.speakingcoach.analytics

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

internal data class NudgeOutcome(
    val runId: String,
    val day: LocalDate,
    val status: String,
    val at: Instant,
    val messageId: Long? = null,
)

@Serializable
internal data class NudgeCounts(
    val attempts: Int,
    val recipients: Int,
    val sent: Int,
    val blocked: Int,
    val failed: Int,
    val unknown: Int,
    val mature24: Int,
    val pending24: Int,
    val returned24: Int,
    val voice24: Int,
    val result24: Int,
)

@Serializable
internal data class NudgeGroup(val label: String, val counts: NudgeCounts)

@Serializable
internal data class NudgeCohort(
    val users: Int,
    val mature7: Int,
    val pending7: Int,
    val result7: Int,
    val practice7: Int,
)

@Serializable
internal data class NudgeReport(
    val schema: String = "onboarding-nudges.v1",
    val generatedAt: String,
    val from: String,
    val to: String,
    val trackingSince: String?,
    val totals: NudgeCounts,
    val cohort: NudgeCohort,
    val byDay: List<NudgeGroup>,
    val byStage: List<NudgeGroup>,
    val byNumber: List<NudgeGroup>,
    val byInactivity: List<NudgeGroup>,
    val byTemplate: List<NudgeGroup>,
    val definition: String = "24h: last successful nudge, before next successful nudge, mature sends only. " +
        "7d: unique users from first confirmed send observed by this instrumentation; mature cohorts only. " +
        "Sent means Telegram API accepted, not read. Observational association, not causal effect. " +
        "Unknown includes legacy claims and uncertain transport outcomes. No historical delivery backfill.",
)

private val nudgeJson = Json { encodeDefaults = true }

internal fun nudgeReportJson(report: NudgeReport): String = nudgeJson.encodeToString(report)

private data class NudgeFact(
    val session: String, val day: String, val stage: String, val number: String,
    val inactivity: String, val template: String, val status: String,
    val mature24: Boolean, val returned24: Boolean, val voice24: Boolean, val result24: Boolean,
    val first: Boolean, val mature7: Boolean, val result7: Boolean, val practice7: Boolean,
)

private fun List<NudgeFact>.counts(): NudgeCounts = NudgeCounts(
    attempts = size,
    recipients = filter { it.status == "sent" }.map { it.session }.distinct().size,
    sent = count { it.status == "sent" }, blocked = count { it.status == "blocked" },
    failed = count { it.status == "failed" }, unknown = count { it.status == "unknown" },
    mature24 = count { it.mature24 }, pending24 = count { it.status == "sent" && !it.mature24 },
    returned24 = count { it.mature24 && it.returned24 }, voice24 = count { it.mature24 && it.voice24 },
    result24 = count { it.mature24 && it.result24 },
)

private fun List<NudgeFact>.groups(key: (NudgeFact) -> String): List<NudgeGroup> =
    groupBy(key).toSortedMap().map { (label, facts) -> NudgeGroup(label, facts.counts()) }

internal fun loadNudgeReport(connection: Connection, days: Int, now: Instant): NudgeReport {
    val to = now.atZone(ONBOARDING_ZONE).toLocalDate()
    val from = to.minusDays((if (days == 30) 30 else 7) - 1L)
    val facts = connection.prepareStatement(NUDGE_REPORT_SQL).use { statement ->
        statement.setTimestamp(1, Timestamp.from(now))
        statement.setObject(2, from)
        statement.setObject(3, to)
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    val number = rows.getInt("attempt_number").takeUnless { rows.wasNull() }
                    val inactive = rows.getLong("inactive_seconds").takeUnless { rows.wasNull() }
                    val stage = rows.getString("action") ?: "unknown"
                    add(NudgeFact(
                        session = rows.getString("session_id"), day = rows.getString("day"), stage = stage,
                        number = when { number == null -> "unknown"; number >= 4 -> "4+"; else -> number.toString() },
                        inactivity = when {
                            inactive == null -> "unknown"
                            inactive < 3 * 86_400 -> "1–2 дня"
                            inactive < 7 * 86_400 -> "3–6 дней"
                            else -> "7+ дней"
                        },
                        template = "$stage:${rows.getString("template_version") ?: "unknown"}",
                        status = rows.getString("status"), mature24 = rows.getBoolean("mature24"),
                        returned24 = rows.getBoolean("returned24"), voice24 = rows.getBoolean("voice24"),
                        result24 = rows.getBoolean("result24"), first = rows.getBoolean("first_send"),
                        mature7 = rows.getBoolean("mature7"), result7 = rows.getBoolean("result7"),
                        practice7 = rows.getBoolean("practice7"),
                    ))
                }
            }
        }
    }
    val tracking = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT MIN(claimed_at) FROM onboarding_nudges WHERE template_version IS NOT NULL").use {
            it.next()
            it.getTimestamp(1)?.toInstant()?.toString()
        }
    }
    val first = facts.filter { it.first }
    return NudgeReport(
        generatedAt = now.toString(), from = from.toString(), to = to.toString(), trackingSince = tracking,
        totals = facts.counts(),
        cohort = NudgeCohort(first.size, first.count { it.mature7 }, first.count { !it.mature7 },
            first.count { it.mature7 && it.result7 }, first.count { it.mature7 && it.practice7 }),
        byDay = facts.groups { it.day }, byStage = facts.groups { it.stage },
        byNumber = facts.groups { it.number }, byInactivity = facts.groups { it.inactivity },
        byTemplate = facts.groups { it.template },
    )
}

// Per-session indexed probes; no raw content or identities leave this report.
private val NUDGE_REPORT_SQL = """
    WITH params AS (SELECT ?::timestamptz AS now), selected AS (
        SELECT n.*, p.now,
               LEAST(n.sent_at + interval '24 hours', next.sent_at, p.now) AS window_end,
               n.status = 'sent' AND n.sent_at + interval '24 hours' <= p.now AS mature24,
               n.status = 'sent' AND n.sent_at = first.sent_at AS first_send,
               n.status = 'sent' AND n.sent_at + interval '7 days' <= p.now AS mature7
        FROM onboarding_nudges n CROSS JOIN params p
        LEFT JOIN LATERAL (
            SELECT MIN(sent_at) AS sent_at FROM onboarding_nudges
            WHERE session_id = n.session_id AND status = 'sent' AND sent_at > n.sent_at
        ) next ON TRUE
        LEFT JOIN LATERAL (
            SELECT MIN(sent_at) AS sent_at FROM onboarding_nudges
            WHERE session_id = n.session_id AND status = 'sent'
        ) first ON TRUE
        WHERE n.day BETWEEN ? AND ? AND n.claimed_at <= p.now
    )
    SELECT n.*,
        EXISTS (SELECT 1 FROM onboarding_nudge_interactions i
                WHERE i.session_id = n.session_id AND i.kind = 'incoming' AND i.occurred_at > n.sent_at
                  AND i.occurred_at < n.window_end) AS returned24,
        EXISTS (SELECT 1 FROM onboarding_voices v
                WHERE v.session_id = n.session_id AND v.received_at > n.sent_at
                  AND v.received_at < n.window_end) AS voice24,
        EXISTS (SELECT 1 FROM onboarding_attempts a
                WHERE a.session_id = n.session_id AND a.result_delivered_at > n.sent_at
                  AND a.result_delivered_at < n.window_end) AS result24,
        EXISTS (SELECT 1 FROM onboarding_attempts a
                WHERE a.session_id = n.session_id AND a.result_delivered_at > n.sent_at
                  AND a.result_delivered_at < n.sent_at + interval '7 days'
                  AND a.result_delivered_at <= n.now) AS result7,
        EXISTS (SELECT 1 FROM onboarding_attempts a
                JOIN onboarding_nudge_interactions p ON p.session_id = a.session_id AND p.kind = 'practice'
                WHERE a.session_id = n.session_id AND a.result_delivered_at > n.sent_at
                  AND p.occurred_at >= a.result_delivered_at
                  AND p.occurred_at < n.sent_at + interval '7 days'
                  AND p.occurred_at <= n.now) AS practice7
    FROM selected n
""".trimIndent()
