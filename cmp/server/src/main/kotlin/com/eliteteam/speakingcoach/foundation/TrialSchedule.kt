package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

/** Preliminary recurring agreement. It never grants paid access. */
internal class TrialSchedule {
    fun save(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "text", "operator")
        evidence(c, scope, args, now)
        val zone = ZoneId.of(args.text("timezone"))
        val time = LocalTime.parse(args.text("localTime"))
        val date = LocalDate.parse(args.text("startDate"))
        val days = args["weekdays"]?.jsonArray?.map { it.jsonPrimitive.int }?.distinct() ?: reject("invalid_weekdays")
        val duration = args.number("durationMinutes")
        if (days.isEmpty() || days.any { it !in 1..7 } || duration !in 15..60 || date < now.atZone(zone).toLocalDate()) reject("invalid_schedule")
        val id = args.optionalText("scheduleId") ?: newId()
        val old = c.document("SELECT to_jsonb(s) FROM schedule s WHERE student_id=? AND id=?", scope.studentId, id)
        if (args.optionalText("scheduleId") != null && old == null) reject("not_owned")
        if (old != null && (old.obj("agreement").optionalText("demo") != "true" || old.number("version") != args.number("expectedVersion"))) reject("version_conflict")
        val agreement = json("demo" to true, "localTime" to time.toString(), "weekdays" to args["weekdays"], "durationMinutes" to duration, "startDate" to date.toString())
        if (old == null) c.update("INSERT INTO schedule(id,student_id,timezone,effective_from,agreement) VALUES (?,?,?,?,?::jsonb)", id, scope.studentId, zone.id, date.atStartOfDay(zone).toInstant(), agreement)
        else {
            c.update("UPDATE schedule SET timezone=?,agreement=?::jsonb,version=version+1,effective_from=? WHERE id=?", zone.id, agreement, date.atStartOfDay(zone).toInstant(), id)
            c.update("UPDATE lesson SET status='cancelled',version=version+1 WHERE schedule_id=? AND status='scheduled'", id)
        }
        val revision = (old?.number("version") ?: 0) + 1
        repeat(14) { offset ->
            val day = date.plusDays(offset.toLong())
            if (day.dayOfWeek.value in days) {
                val local = day.atTime(time)
                val offsets = zone.rules.getValidOffsets(local)
                if (offsets.size != 1) reject("ambiguous_schedule_time")
                val start = local.toInstant(offsets.single())
                if (start > now) c.update("INSERT INTO lesson(id,student_id,kind,starts_at,duration_minutes,timezone,launch_mode,schedule_id,occurrence_key) VALUES (?,?,'regular',?,?,?,'placeholder',?,?)",
                    newId(), scope.studentId, start, duration, zone.id, id, "$revision:$day")
            }
        }
        return json("schedule" to c.document("SELECT to_jsonb(s) FROM schedule s WHERE id=?", id), "demo" to true)
    }

    fun cancel(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "text", "operator")
        evidence(c, scope, args, now)
        val current = c.document("SELECT to_jsonb(s) FROM schedule s WHERE student_id=? AND id=?", scope.studentId, args.text("scheduleId")) ?: reject("not_owned")
        if (current.obj("agreement").optionalText("demo") != "true" || current.number("version") != args.number("expectedVersion")) reject("version_conflict")
        c.update("UPDATE schedule SET agreement=agreement || '{\"cancelled\":true}'::jsonb,version=version+1 WHERE id=?", current.text("id"))
        c.update("UPDATE lesson SET status='cancelled',version=version+1 WHERE schedule_id=? AND status='scheduled'", current.text("id"))
        return json("cancelled" to true)
    }

    fun read(c: Connection, scope: ExecutionScope): JsonObject = json("schedules" to JsonArray(c.documents(
        "SELECT to_jsonb(s) FROM schedule s WHERE student_id=? ORDER BY effective_from DESC", scope.studentId)))
}
