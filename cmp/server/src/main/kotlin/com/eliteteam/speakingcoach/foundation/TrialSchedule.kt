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

/** Recurring agreements share one aggregate; demo payment never grants access. */
internal class TrialSchedule {
    fun save(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant, executable: Boolean = false): JsonObject {
        requireRole(scope, "text", "operator")
        args.only("sourceId", "evidence", "timezone", "localTime", "startDate", "weekdays", "durationMinutes", "scheduleId", "expectedVersion")
        evidence(c, scope, args, now)
        val zone = ZoneId.of(args.text("timezone"))
        if (!Regex("[0-9]{2}:[0-9]{2}").matches(args.text("localTime"))) reject("invalid_local_time")
        val time = LocalTime.parse(args.text("localTime"))
        val date = LocalDate.parse(args.text("startDate"))
        val days = args["weekdays"]?.jsonArray?.map { it.jsonPrimitive.int }?.distinct() ?: reject("invalid_weekdays")
        val duration = args.number("durationMinutes")
        if (days.isEmpty() || days.any { it !in 1..7 } || duration !in 15..60 || date < now.atZone(zone).toLocalDate()) reject("invalid_schedule")
        if (executable && (date.dayOfWeek.value !in days || scheduleInstant(date, time, zone) <= now)) reject("invalid_first_date")
        if (executable && args.optionalText("scheduleId") == null && c.document("SELECT to_jsonb(s) FROM schedule s WHERE student_id=? AND agreement->>'executable'='true' AND agreement->>'cancelled' IS DISTINCT FROM 'true'", scope.studentId) != null) reject("active_schedule_exists")
        val id = args.optionalText("scheduleId") ?: newId()
        val old = c.document("SELECT to_jsonb(s) FROM schedule s WHERE student_id=? AND id=?", scope.studentId, id)
        if (args.optionalText("scheduleId") != null && old == null) reject("not_owned")
        if (old != null && (old.number("version") != args.number("expectedVersion"))) reject("version_conflict")
        val agreement = json("demo" to true, "executable" to executable, "localTime" to time.toString(), "weekdays" to args["weekdays"], "durationMinutes" to duration, "startDate" to date.toString())
        if (old == null) c.update("INSERT INTO schedule(id,student_id,timezone,effective_from,agreement) VALUES (?,?,?,?,?::jsonb)", id, scope.studentId, zone.id, date.atStartOfDay(zone).toInstant(), agreement)
        else {
            c.update("UPDATE schedule SET timezone=?,agreement=?::jsonb,version=version+1,effective_from=? WHERE id=?", zone.id, agreement, date.atStartOfDay(zone).toInstant(), id)
            cancelFuture(c, id, now)
        }
        val saved = c.document("SELECT to_jsonb(s) FROM schedule s WHERE id=?", id)!!
        materialize(c, saved, now, strict = true)
        return json("schedule" to c.document("SELECT to_jsonb(s) FROM schedule s WHERE id=?", id), "demo" to true)
    }

    fun cancel(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "text", "operator")
        args.only("scheduleId", "expectedVersion", "sourceId", "evidence")
        evidence(c, scope, args, now)
        val current = c.document("SELECT to_jsonb(s) FROM schedule s WHERE student_id=? AND id=?", scope.studentId, args.text("scheduleId")) ?: reject("not_owned")
        if (current.number("version") != args.number("expectedVersion")) reject("version_conflict")
        c.update("UPDATE schedule SET agreement=agreement || '{\"cancelled\":true}'::jsonb,version=version+1 WHERE id=?", current.text("id"))
        cancelFuture(c, current.text("id"), now)
        return json("cancelled" to true)
    }

    fun read(c: Connection, scope: ExecutionScope): JsonObject = json("schedules" to JsonArray(c.documents(
        "SELECT to_jsonb(s) FROM schedule s WHERE student_id=? ORDER BY effective_from DESC", scope.studentId)), "timeIssues" to JsonArray(c.documents(
        "SELECT to_jsonb(i) FROM schedule_time_issue i WHERE student_id=? AND status='pending' ORDER BY local_date", scope.studentId)))
    private fun cancelFuture(c: Connection, id: String, now: Instant) {
        c.update("UPDATE lesson SET status='cancelled',version=version+1 WHERE schedule_id=? AND status='scheduled' AND starts_at>?", id, now)
        c.update("UPDATE schedule_time_issue SET status='cancelled',delivery='cancelled' WHERE schedule_id=? AND status='pending'", id)
        c.update("UPDATE trial_notice SET status='cancelled',fence=fence+1 WHERE lesson_id IN (SELECT id FROM lesson WHERE schedule_id=? AND status='cancelled') AND status IN ('pending','generating','ready')", id)
        c.update("UPDATE foundation_job SET status='cancelled' WHERE target_id IN (SELECT id FROM lesson WHERE schedule_id=? AND status='cancelled') AND status IN ('queued','running','retry_wait')", id)
    }

    fun materialize(c: Connection, schedule: JsonObject, now: Instant, strict: Boolean = false) {
        val agreement = schedule.obj("agreement")
        if (agreement.optionalText("cancelled") == "true") return
        val executable = agreement.optionalText("executable") == "true"
        val zone = ZoneId.of(schedule.text("timezone"))
        val time = LocalTime.parse(agreement.text("localTime"))
        val first = LocalDate.parse(agreement.text("startDate"))
        val today = now.atZone(zone).toLocalDate()
        val from = if (first > today) first else today
        val days = agreement["weekdays"]!!.jsonArray.map { it.jsonPrimitive.int }
        val student = schedule.text("student_id")
        val scope = ExecutionScope(student, "operator", c.rows("SELECT data_epoch FROM student WHERE id=?", student) { it.getLong(1) }.single())
        repeat(14) { offset ->
            val date = from.plusDays(offset.toLong())
            if (date.dayOfWeek.value !in days) return@repeat
            val key = "${schedule.number("version")}:$date"
            if (c.document("SELECT to_jsonb(l) FROM lesson l WHERE schedule_id=? AND occurrence_key=?", schedule.text("id"), key) != null) return@repeat
            val offsets = zone.rules.getValidOffsets(date.atTime(time))
            if (offsets.size != 1) {
                if (strict) reject("schedule_time_needs_clarification:$date:$time:${zone.id}")
                c.update("INSERT INTO schedule_time_issue(student_id,schedule_id,occurrence_key,local_date,local_time,timezone,schedule_version,data_epoch) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    student, schedule.text("id"), key, date.toString(), time.toString(), zone.id, schedule.number("version"), scope.epoch)
                return@repeat
            }
            val start = date.atTime(time).toInstant(offsets.single())
            if (start <= now) return@repeat
            LessonManagement().create(c, scope, json("kind" to "regular", "launchMode" to if (executable) "executable" else "placeholder",
                "startsAt" to start.toString(), "timezone" to zone.id, "durationMinutes" to agreement.number("durationMinutes"),
                "scheduleId" to schedule.text("id"), "occurrenceKey" to key), now)
        }
    }

    fun resolve(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "text", "operator")
        args.only("scheduleId", "expectedVersion", "occurrenceKey", "localTime", "sourceId", "evidence")
        evidence(c, scope, args, now)
        val schedule = c.document("SELECT to_jsonb(s) FROM schedule s WHERE student_id=? AND id=?", scope.studentId, args.text("scheduleId")) ?: reject("not_owned")
        if (schedule.number("version") != args.number("expectedVersion")) reject("version_conflict")
        val issue = c.document("SELECT to_jsonb(i) FROM schedule_time_issue i WHERE schedule_id=? AND occurrence_key=? AND status='pending'", schedule.text("id"), args.text("occurrenceKey")) ?: reject("invalid_state")
        if (issue.number("schedule_version") != schedule.number("version")) reject("version_conflict")
        val start = scheduleInstant(LocalDate.parse(issue.text("local_date")), LocalTime.parse(args.text("localTime")), ZoneId.of(issue.text("timezone")))
        if (start <= now) reject("time_in_past")
        val lesson = LessonManagement().create(c, scope.copy(role = "operator"), json("kind" to "regular", "launchMode" to "executable", "startsAt" to start.toString(),
            "timezone" to issue.text("timezone"), "durationMinutes" to schedule.obj("agreement").number("durationMinutes"),
            "scheduleId" to schedule.text("id"), "occurrenceKey" to issue.text("occurrence_key")), now)
        c.update("UPDATE schedule_time_issue SET status='resolved',delivery='cancelled' WHERE schedule_id=? AND occurrence_key=?", schedule.text("id"), issue.text("occurrence_key"))
        return lesson
    }

}


internal fun scheduleInstant(date: LocalDate, time: LocalTime, zone: ZoneId): Instant {
    val offsets = zone.rules.getValidOffsets(date.atTime(time))
    if (offsets.size != 1) reject("schedule_time_needs_clarification:$date:$time:${zone.id}")
    return date.atTime(time).toInstant(offsets.single())
}
