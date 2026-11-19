package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** Durable clock and conversation phase, independent of educational assessment. */
internal class TrialExecution {
    fun state(c: Connection, lesson: JsonObject, now: Instant): JsonObject {
        val milliseconds = c.rows("""SELECT floor(COALESCE(sum(GREATEST(0,1000 * extract(epoch FROM
            ((CASE WHEN status='connected' AND lease_until>? THEN ? ELSE COALESCE(trial_seen_at,trial_ready_at) END)
             - trial_ready_at)))),0))::bigint FROM call_session WHERE lesson_id=? AND trial_ready_at IS NOT NULL""",
            now, now, lesson.text("id")) { it.getLong(1) }.single()
        val base = json("connectedSeconds" to milliseconds / 1000, "phase" to lesson.text("trial_phase"),
            "plannedSeconds" to lesson.number("duration_minutes") * 60, "reconnectBefore" to trialReconnectEnd(lesson).toString(),
            "terminal" to (lesson.text("status") == "completed"))
        if (!trialSp24(lesson)) return base
        val start = lesson.optionalText("trial_conversation_start_ms")?.toLong()
        return JsonObject(base + json("connectedMs" to milliseconds, "conversationStartMs" to start,
            "conversationEndMs" to start?.plus(TRIAL_CONVERSATION_MS),
            "offerSequence" to lesson["trial_offer_sequence"], "regularReaction" to lesson["trial_regular_reaction"]))
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
        val target = args.text("phase")
        var boundary = now
        if (trialSp24(lesson)) {
            val state = state(c, lesson, now)
            if (next == current) return state
            val start = lesson.optionalText("trial_conversation_start_ms")?.toLong()
            val elapsed = state.number("connectedMs")
            val error = sp24PhaseError(lesson.text("trial_phase"), target, start, elapsed)
            if (error != null) reject(error)
            if (target == "conversation") {
                c.update("UPDATE lesson SET trial_conversation_start_ms=? WHERE id=?", elapsed, lesson.text("id"))
            } else {
                boundary = now.minusMillis(elapsed - (requireNotNull(start) + TRIAL_CONVERSATION_MS))
                // When the deadline was reached in the previous episode, resume directly in the Russian close.
                boundary = maxOf(boundary, Instant.parse(session.text("trial_ready_at")))
            }
        }
        c.update("UPDATE lesson SET trial_phase=? WHERE id=?", target, lesson.text("id"))
        TrialCallEvents().lifecycle(c, scope, session, "phase", json("phase" to target), boundary)
        return state(c, c.lesson(scope, lesson.text("id")), now)
    }

    fun expire(c: Connection, lesson: JsonObject, now: Instant): Int {
        val sessions = c.documents("SELECT to_jsonb(s) FROM call_session s WHERE lesson_id=? AND status='connected' AND lease_until<=?", lesson.text("id"), now)
        sessions.forEach { session ->
            val ended = session.optionalText("trial_seen_at")?.let(Instant::parse) ?: now
            c.update("UPDATE call_session SET status='disconnected',ended_at=?,end_reason='technical_disconnect',lease_until=NULL WHERE id=?", ended, session.text("id"))
            if (trialSp24(lesson)) c.extendTrialReconnect(lesson, ended)
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

internal const val TRIAL_SLOT_MINUTES = 20L
internal const val TRIAL_CONVERSATION_MS = 900_000L

internal fun trialSp24(lesson: JsonObject) = trialV55(lesson) && lesson.number("duration_minutes") == TRIAL_SLOT_MINUTES

internal fun sp24PhaseError(current: String, target: String, start: Long?, elapsed: Long): String? = when {
    current == "warmup" && target == "conversation" -> null
    current != "conversation" || target != "wrapup" || start == null -> "invalid_trial_phase"
    elapsed < start + TRIAL_CONVERSATION_MS -> "trial_conversation_in_progress"
    else -> null
}

internal fun trialReconnectEnd(lesson: JsonObject): Instant = maxOf(slotEnd(lesson),
    lesson.optionalText("trial_reconnect_until")?.let(Instant::parse) ?: Instant.MIN)

internal fun Connection.extendTrialReconnect(lesson: JsonObject, disconnectedAt: Instant) {
    update("UPDATE lesson SET trial_reconnect_until=? WHERE id=?", disconnectedAt.plusSeconds(120), lesson.text("id"))
}
