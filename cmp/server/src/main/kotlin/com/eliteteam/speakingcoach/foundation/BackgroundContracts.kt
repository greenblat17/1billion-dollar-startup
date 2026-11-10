package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Explicit requests only. SP-00 does not infer a recurring calendar or enable Telegram reminders. */
internal class BackgroundContracts {
    fun request(c: Connection, scope: ExecutionScope, kind: String, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        val target: String
        val inputs: JsonObject
        if (kind == "schedule.materialize") {
            args.only("scheduleId", "expectedVersion", "from", "until")
            target = args.text("scheduleId")
            val schedule = c.document("SELECT to_jsonb(s) FROM schedule s WHERE student_id=? AND id=?", scope.studentId, target) ?: reject("not_owned")
            if (schedule.number("version") != args.number("expectedVersion")) reject("version_conflict")
            val from = Instant.parse(args.text("from"))
            val until = Instant.parse(args.text("until"))
            if (until <= from || until > from.plusSeconds(31 * 86400)) reject("invalid_horizon")
            inputs = json("scheduleVersion" to schedule.number("version"), "from" to from.toString(), "until" to until.toString())
        } else {
            args.only("lessonId", "expectedVersion", "dueAt", "deadline")
            target = args.text("lessonId")
            val lesson = c.lesson(scope, target)
            if (lesson.number("version") != args.number("expectedVersion")) reject("version_conflict")
            if (lesson.text("status") != "scheduled") reject("invalid_state")
            inputs = json("lessonVersion" to lesson.number("version"))
        }
        val id = c.enqueueJob(scope.studentId, kind, "$target:${digest(canonical(args))}", target, inputs, now)
        if (kind == "notification.reminder") {
            val due = Instant.parse(args.text("dueAt"))
            val deadline = Instant.parse(args.text("deadline"))
            if (deadline <= due || deadline <= now || deadline > now.plusSeconds(HISTORY_SECONDS)) reject("invalid_period")
            c.update("UPDATE foundation_job SET due_at=?,deadline=? WHERE id=? AND attempts=0", due, deadline, id)
        }
        return json("jobId" to id)
    }

    fun current(c: Connection, job: JsonObject): Boolean {
        if (job.text("kind") !in setOf("schedule.materialize", "notification.reminder")) return true
        val target = job.optionalText("target_id") ?: return false
        val inputs = job.obj("input_refs")
        return when (job.text("kind")) {
            "schedule.materialize" -> c.rows("SELECT version=? FROM schedule WHERE student_id=? AND id=?", inputs.number("scheduleVersion"), job.text("student_id"), target) { it.getBoolean(1) }.singleOrNull() == true
            "notification.reminder" -> c.rows("SELECT version=? AND status='scheduled' FROM lesson WHERE student_id=? AND id=?", inputs.number("lessonVersion"), job.text("student_id"), target) { it.getBoolean(1) }.singleOrNull() == true
            else -> true
        }
    }

    fun apply(c: Connection, scope: ExecutionScope, job: JsonObject, result: JsonObject, now: Instant): Boolean {
        if (!current(c, job)) return false
        val inputs = job.obj("input_refs")
        if (job.text("kind") == "schedule.materialize") {
            result.only("occurrences")
            val occurrences = result["occurrences"]?.jsonArray ?: reject("invalid_result")
            if (occurrences.size > 64) reject("invalid_result")
            val schedule = c.document("SELECT to_jsonb(s) FROM schedule s WHERE id=?", job.text("target_id")) ?: reject("not_owned")
            occurrences.forEach { item ->
                val occurrence = item.jsonObject
                occurrence.only("key", "startsAt", "durationMinutes")
                val starts = Instant.parse(occurrence.text("startsAt"))
                if (starts < Instant.parse(inputs.text("from")) || starts >= Instant.parse(inputs.text("until")) || occurrence.text("key").isBlank()) reject("invalid_occurrence")
                LessonManagement().create(c, scope.copy(role = "operator", lessonId = null), json("kind" to "regular", "launchMode" to "executable",
                    "scheduleId" to schedule.text("id"), "occurrenceKey" to occurrence.text("key"), "startsAt" to starts.toString(),
                    "durationMinutes" to occurrence.number("durationMinutes"), "timezone" to schedule.text("timezone")), now)
            }
        } else {
            result.only("outcome", "receipt")
            if (result.text("outcome") !in setOf("sent", "not_sent") || (result.optionalText("receipt")?.length ?: 0) > 200) reject("invalid_result")
            if (result.text("outcome") == "sent" && result.optionalText("receipt").isNullOrBlank()) reject("invalid_result")
            c.update("UPDATE reminder_delivery SET outcome=?,receipt=? WHERE job_id=?", result.text("outcome"), result.optionalText("receipt"), job.text("id"))
        }
        return true
    }
}
