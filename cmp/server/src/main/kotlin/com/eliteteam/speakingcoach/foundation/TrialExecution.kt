package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** Durable clock and conversation phase, independent of educational assessment. */
internal class TrialExecution {
    fun state(c: Connection, lesson: JsonObject, now: Instant): JsonObject {
        val seconds = c.rows("""SELECT COALESCE(sum(GREATEST(0,extract(epoch FROM
            ((CASE WHEN status='connected' AND lease_until>? THEN ? ELSE COALESCE(trial_seen_at,trial_ready_at) END)
             - trial_ready_at)))),0)::bigint FROM call_session WHERE lesson_id=? AND trial_ready_at IS NOT NULL""",
            now, now, lesson.text("id")) { it.getLong(1) }.single()
        return json("connectedSeconds" to seconds, "phase" to lesson.text("trial_phase"),
            "plannedSeconds" to 1800, "reconnectBefore" to slotEnd(lesson).toString(),
            "terminal" to (lesson.text("status") == "completed"))
    }

    fun ready(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        args.only("lessonId", "callSessionId")
        val session = c.activeTrial(scope, args, now)
        val lesson = c.lesson(scope, args.text("lessonId"))
        requireV55(lesson)
        c.update("UPDATE call_session SET trial_ready_at=COALESCE(trial_ready_at,?),trial_seen_at=? WHERE id=?", now, now, session.text("id"))
        TrialCallEvents().lifecycle(c, scope, session, "connected", json(), now)
        return state(c, lesson, now)
    }

    fun phase(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        args.only("lessonId", "callSessionId", "phase")
        val session = c.activeTrial(scope, args, now)
        val lesson = c.lesson(scope, args.text("lessonId"))
        requireV55(lesson)
        val phases = listOf("warmup", "conversation", "wrapup")
        val next = phases.indexOf(args.text("phase"))
        val current = phases.indexOf(lesson.text("trial_phase"))
        if (next < current || next < 0) reject("invalid_trial_phase")
        c.update("UPDATE lesson SET trial_phase=? WHERE id=?", args.text("phase"), lesson.text("id"))
        TrialCallEvents().lifecycle(c, scope, session, "phase", json("phase" to args.text("phase")), now)
        return state(c, c.lesson(scope, lesson.text("id")), now)
    }

    fun expire(c: Connection, lesson: JsonObject, now: Instant): Int {
        val sessions = c.documents("SELECT to_jsonb(s) FROM call_session s WHERE lesson_id=? AND status='connected' AND lease_until<=?", lesson.text("id"), now)
        sessions.forEach { session ->
            val ended = session.optionalText("trial_seen_at")?.let(Instant::parse) ?: now
            c.update("UPDATE call_session SET status='disconnected',ended_at=?,end_reason='technical_disconnect',lease_until=NULL WHERE id=?", ended, session.text("id"))
            if (trialV55(lesson)) TrialCallEvents().lifecycle(c, ExecutionScope(lesson.text("student_id"), "operator", 0),
                session, "ended", json("reason" to "technical_disconnect", "detectedBy" to "lease_expiry"), ended)
        }
        return sessions.size
    }

    fun complete(c: Connection, lesson: JsonObject, reason: String, now: Instant) {
        c.update("UPDATE lesson SET status='completed',ended_early=?,outcome_reason=?,version=version+1 WHERE id=? AND status<>'completed'",
            reason != "natural_goodbye", reason, lesson.text("id"))
        c.enqueueEvaluation(lesson.text("student_id"), lesson.text("id"), now)
    }
}

internal fun trialV55(lesson: JsonObject) = lesson.optionalText("trial_version") == "v5.5"
internal fun requireV55(lesson: JsonObject) { if (!trialV55(lesson)) reject("not_v55_trial") }
internal fun slotEnd(lesson: JsonObject): Instant = Instant.parse(lesson.text("starts_at")).plusSeconds(lesson.number("duration_minutes") * 60)
