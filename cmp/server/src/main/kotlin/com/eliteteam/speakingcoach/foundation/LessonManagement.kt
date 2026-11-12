package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal class LessonManagement {
    fun create(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "text", "operator")
        args.only("startsAt", "timezone", "durationMinutes", "kind", "launchMode", "sourceId", "evidence", "scheduleId", "occurrenceKey", "timing")
        val kind = args.text("kind")
        val mode = args.text("launchMode")
        val duration = args.number("durationMinutes")
        val starts = if (args.optionalText("timing") == "now") now else Instant.parse(args.text("startsAt"))
        ZoneId.of(args.text("timezone"))
        if (kind !in setOf("trial", "regular") || mode !in setOf("placeholder", "executable") || duration !in 1..60) reject("invalid_lesson")
        if (scope.role == "text") {
            if (kind != "trial" || duration != 15L) reject("not_permitted")
            evidence(connection, scope, args, now)
            if (args.optionalText("timing") != "now" && starts <= now) reject("time_in_past")
        }
        val schedule = args.optionalText("scheduleId")
        val occurrence = args.optionalText("occurrenceKey")
        if ((schedule == null) != (occurrence == null)) reject("invalid_occurrence")
        if (schedule != null) {
            if (connection.document("SELECT to_jsonb(s) FROM schedule s WHERE student_id=? AND id=?", scope.studentId, schedule) == null) reject("not_owned")
            val existing = connection.document("SELECT to_jsonb(l) FROM lesson l WHERE schedule_id=? AND occurrence_key=?", schedule, occurrence)
            if (existing != null) return existing
        }
        var previousLesson: String? = null
        if (kind == "trial") {
            val active = connection.document("SELECT to_jsonb(l) FROM lesson l WHERE student_id=? AND kind='trial' AND status IN ('scheduled','in_progress')", scope.studentId)
            if (active != null) {
                val last = connection.document("SELECT to_jsonb(s) FROM call_session s WHERE lesson_id=? ORDER BY started_at DESC LIMIT 1", active.text("id"))
                if (active.text("status") != "in_progress" || last?.optionalText("end_reason") != "technical_disconnect" || last.text("status") == "connected") reject("active_booking_exists")
                previousLesson = active.text("id")
                connection.update("UPDATE lesson SET status='completed',ended_early=TRUE,outcome_reason='technical_failure_rebooked',version=version+1 WHERE id=?", previousLesson)
                connection.enqueueEvaluation(scope.studentId, previousLesson, now)
                connection.update("UPDATE trial_notice SET status='cancelled' WHERE lesson_id=? AND status IN ('pending','generating','ready')", previousLesson)
            }
        }
        val id = newId()
        connection.update(
            """INSERT INTO lesson(id,student_id,kind,starts_at,duration_minutes,timezone,launch_mode,schedule_id,occurrence_key,timing)
               VALUES (?,?,?,?,?,?,?,?,?,?)""", id, scope.studentId, kind, starts, duration, args.text("timezone"), mode, schedule,
            occurrence, args.optionalText("timing") ?: "scheduled",
        )
        if (previousLesson != null) connection.update("UPDATE lesson SET previous_lesson_id=? WHERE id=?", previousLesson, id)
        if (mode == "executable") connection.enqueueJob(scope.studentId, "lesson.prepare", "$id:initial", id,
            json("goalRevision" to connection.goalRevision(scope.studentId), "learningRevision" to connection.learningRevision(scope.studentId)), now)
        return connection.lesson(scope, id)
    }

    fun change(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant, cancel: Boolean): JsonObject {
        requireRole(scope, "text", "operator")
        args.only("lessonId", "expectedVersion", "startsAt", "timezone", "sourceId", "evidence", "timing", "launchMode")
        val lesson = connection.lesson(scope, args.text("lessonId"))
        if (scope.role == "text") {
            evidence(connection, scope, args, now)
            if (lesson.text("kind") != "trial") reject("not_permitted")
        }
        if (lesson.number("version") != args.number("expectedVersion")) reject("version_conflict")
        if (lesson.text("status") != "scheduled") reject("invalid_state")
        if (cancel) {
            connection.update("UPDATE lesson SET status='cancelled',version=version+1,updated_at=? WHERE id=?", now, lesson.text("id"))
            connection.update("UPDATE foundation_job SET status='cancelled' WHERE target_id=? AND status IN ('queued','running','retry_wait')", lesson.text("id"))
        } else {
            val immediate = args.optionalText("timing") == "now"
            val starts = if (immediate) now else Instant.parse(args.text("startsAt"))
            ZoneId.of(args.text("timezone"))
            if (!immediate && starts <= now) reject("time_in_past")
            val mode = args.optionalText("launchMode") ?: lesson.text("launch_mode")
            if (mode !in setOf("placeholder", "executable")) reject("invalid_lesson")
            connection.update("UPDATE lesson SET launch_mode=? WHERE id=?", mode, lesson.text("id"))
            if (mode == "executable") connection.enqueueJob(scope.studentId, "lesson.prepare", "${lesson.text("id")}:reschedule:${lesson.number("version")}", lesson.text("id"),
                json("goalRevision" to connection.goalRevision(scope.studentId), "learningRevision" to connection.learningRevision(scope.studentId)), now)
            connection.update("UPDATE lesson SET starts_at=?,timezone=?,timing=?,version=version+1,updated_at=? WHERE id=?",
                starts, args.text("timezone"), if (immediate) "now" else "scheduled", now, lesson.text("id"))
        }
        if (lesson.optionalText("schedule_id") != null) connection.update(
            """INSERT INTO schedule_exception(student_id,schedule_id,occurrence_key,lesson_id,kind) VALUES (?,?,?,?,?)
               ON CONFLICT(schedule_id,occurrence_key) DO UPDATE SET kind=EXCLUDED.kind""", scope.studentId,
            lesson.text("schedule_id"), lesson.text("occurrence_key"), lesson.text("id"), if (cancel) "cancelled" else "moved",
        )
        return connection.lesson(scope, lesson.text("id"))
    }

    fun episode(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "voice", "operator")
        args.only("lessonId", "attemptId")
        val lesson = connection.lesson(scope, args.text("lessonId"))
        val existing = connection.document("SELECT to_jsonb(c) FROM call_session c WHERE lesson_id=? AND attempt_id=?",
            lesson.text("id"), args.text("attemptId"))
        if (existing != null) return existing
        if (lesson.text("launch_mode") != "executable" || lesson.text("status") !in setOf("scheduled", "in_progress")) reject("invalid_state")
        if (lesson.text("status") == "scheduled" && !connection.preparation(scope, lesson.text("id")).text("status").equals("ready")) reject("not_ready")
        // Public admission/time/grace policies are deliberately not enabled in SP-00.
        if (scope.role != "operator") reject("admission_not_configured")
        if (connection.document("SELECT to_jsonb(c) FROM call_session c WHERE lesson_id=? AND status='connected'", lesson.text("id")) != null) reject("active_episode")
        val id = newId()
        connection.update("INSERT INTO call_session(id,student_id,lesson_id,attempt_id,status,started_at) VALUES (?,?,?,?,'connected',?)",
            id, scope.studentId, lesson.text("id"), args.text("attemptId"), now)
        connection.update("UPDATE lesson SET status='in_progress',execution_snapshot=COALESCE(execution_snapshot,?::jsonb),version=version+1 WHERE id=?",
            connection.preparation(scope, lesson.text("id")), lesson.text("id"))
        return connection.document("SELECT to_jsonb(c) FROM call_session c WHERE id=?", id) ?: reject("invalid_state")
    }

    fun append(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "voice", "operator")
        args.only("lessonId", "callSessionId", "sourceId", "role", "body", "occurredAt")
        val lesson = connection.lesson(scope, args.text("lessonId"))
        val session = connection.document("SELECT to_jsonb(c) FROM call_session c WHERE student_id=? AND lesson_id=? AND id=?",
            scope.studentId, lesson.text("id"), args.text("callSessionId")) ?: reject("not_owned")
        if (scope.role == "voice" && session.optionalText("scope_digest") != null) connection.activeTrial(scope, args, now)
        if (args.text("role") !in setOf("user", "assistant")) reject("invalid_role")
        val conversation = connection.conversation(scope.studentId, "lesson:${lesson.text("id")}")
        val source = "${session.text("id")}:${args.text("sourceId")}"
        val event = connection.appendEvent(scope.studentId, conversation, source, args.text("role"), args.text("body"), Instant.parse(args.text("occurredAt")), now)
        val inserted = connection.update(
            """INSERT INTO lesson_event(student_id,lesson_id,call_session_id,event_id,source_id,sequence)
               VALUES (?,?,?,?,?,?) ON CONFLICT(call_session_id,source_id) DO NOTHING""", scope.studentId, lesson.text("id"), session.text("id"),
            event.text("id"), args.text("sourceId"), event.number("sequence"),
        )
        if (inserted == 1) {
            connection.update("UPDATE lesson SET transcript_revision=transcript_revision+1 WHERE id=?", lesson.text("id"))
            if (lesson.text("status") == "completed") connection.enqueueEvaluation(scope.studentId, lesson.text("id"), now)
            connection.update("UPDATE lesson_plan SET status='stale' WHERE student_id=?", scope.studentId)
        }
        return json("eventId" to event.text("id"), "sequence" to event.number("sequence"), "duplicate" to (inserted == 0))
    }

    fun endEpisode(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "voice", "operator")
        args.only("lessonId", "callSessionId", "reason")
        val lesson = connection.lesson(scope, args.text("lessonId"))
        val reason = args.text("reason")
        if (reason !in setOf("end_button", "technical_disconnect", "natural_goodbye", "unknown")) reject("invalid_reason")
        val count = connection.update("UPDATE call_session SET status='ended',ended_at=?,end_reason=? WHERE student_id=? AND lesson_id=? AND id=? AND status='connected'",
            now, reason, scope.studentId, lesson.text("id"), args.text("callSessionId"))
        if (count == 0 && connection.document("SELECT to_jsonb(c) FROM call_session c WHERE student_id=? AND lesson_id=? AND id=?",
                scope.studentId, lesson.text("id"), args.text("callSessionId")) == null) reject("not_owned")
        return json("ended" to true)
    }

    fun complete(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("lessonId", "expectedVersion", "reason")
        val lesson = connection.lesson(scope, args.text("lessonId"))
        if (lesson.number("version") != args.number("expectedVersion")) reject("version_conflict")
        if (lesson.text("status") != "in_progress" || lesson.text("launch_mode") != "executable") reject("invalid_state")
        if (connection.document("SELECT to_jsonb(c) FROM call_session c WHERE lesson_id=? AND status='connected'", lesson.text("id")) != null) reject("active_episode")
        connection.update("UPDATE lesson SET status='completed',outcome_reason=?,version=version+1,updated_at=? WHERE id=?", args.text("reason"), now, lesson.text("id"))
        connection.enqueueEvaluation(scope.studentId, lesson.text("id"), now)
        return connection.lesson(scope, lesson.text("id"))
    }
}

internal fun Connection.lesson(scope: ExecutionScope, id: String): JsonObject {
    if (scope.lessonId != null && scope.lessonId != id) reject("not_owned")
    return document("SELECT to_jsonb(l) FROM lesson l WHERE student_id=? AND id=?", scope.studentId, id) ?: reject("not_owned")
}

internal fun evidence(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant) {
    val source = sourceMessage(connection, scope, args.text("sourceId"), now)
    if (args.text("evidence").isBlank() || !source.text("body").contains(args.text("evidence"))) reject("invalid_evidence")
}

internal fun Connection.enqueueEvaluation(student: String, lesson: String, now: Instant) {
    val revision = rows("SELECT transcript_revision FROM lesson WHERE id=?", lesson) { it.getLong(1) }.single()
    enqueueJob(student, "lesson.evaluate", "$lesson:$revision:v1", lesson,
        json("transcriptRevision" to revision, "goalRevision" to goalRevision(student), "analyzerVersion" to "v1"), now)
}
