package com.eliteteam.speakingcoach.analytics

import java.sql.Connection
import java.time.LocalDate
import java.time.ZoneId

internal data class HabitDuration(
    val calls: Long = 0,
    val speechSeconds: Double = 0.0,
    val excluded: Long = 0,
    val incomplete: Long = 0,
    val historySince: String? = null,
    val writeFailures: Long = 0,
    val memoryOnly: Boolean = false,
)

internal fun Connection.habitDuration(from: LocalDate, to: LocalDate): HabitDuration =
    prepareStatement("""
        SELECT COALESCE(sum(calls), 0), COALESCE(sum(speech_seconds), 0),
               COALESCE(sum(excluded), 0), COALESCE(sum(incomplete), 0),
               (SELECT min(day)::text FROM habit_call_daily)
        FROM habit_call_daily WHERE day BETWEEN ? AND ?
    """.trimIndent()).use { statement ->
        statement.setObject(1, from)
        statement.setObject(2, to)
        statement.executeQuery().use { result ->
            result.next()
            HabitDuration(result.getLong(1), result.getDouble(2), result.getLong(3),
                result.getLong(4), result.getString(5))
        }
    }

internal fun memoryHabitDuration(events: Collection<CallEvent>, from: LocalDate, to: LocalDate): HabitDuration {
    val zone = ZoneId.of("Europe/Moscow")
    val rows = events.filter { it.callId != null }.groupBy { it.callId }.values
    var calls = 0L
    var seconds = 0.0
    var excluded = 0L
    var incomplete = 0L
    val history = events.filter { it.kind == "call_open" }.minOfOrNull { it.at.atZone(zone).toLocalDate() }
    for (row in rows) {
        val opened = row.filter { it.kind == "call_open" }.minOfOrNull { it.at }
        if (opened == null) {
            if (row.maxOf { it.at }.atZone(zone).toLocalDate() in from..to) {
                incomplete++
                excluded++
            }
            continue
        }
        if (opened.atZone(zone).toLocalDate() !in from..to) continue
        val processed = row.filter { it.kind == "voice_processed" }.distinctBy { it.messageId ?: it.id }
        val received = row.filter { it.kind == "voice_received" }.distinctBy { it.messageId ?: it.id }
        val complete = processed.none { it.seconds == null } && processed.size == received.size &&
            processed.map { it.messageId }.toSet() == received.map { it.messageId }.toSet()
        if (!complete) incomplete++
        if (complete && row.any { it.kind == "call_closed" } && processed.any { (it.seconds ?: 0.0) > 0 }) {
            calls++
            seconds += processed.sumOf { it.seconds ?: 0.0 }
        } else excluded++
    }
    return HabitDuration(calls, seconds, excluded, incomplete, history?.toString(), memoryOnly = true)
}
