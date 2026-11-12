package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal class TrialAccess(private val service: FoundationService) {
    suspend fun access(args: JsonObject, botToken: String): JsonObject {
        val subject = telegramMiniSubject(args.text("initData"), botToken, service.database.clock.instant())
        val student = service.identity.resolve("telegram", subject)
        return service.database.transaction { c, now ->
            c.lockStudent(student)
            val epoch = c.rows("SELECT data_epoch FROM student WHERE id=?", student) { it.getLong(1) }.single()
            when (args.text("action")) {
                "list" -> json("lessons" to JsonArray(c.documents("SELECT to_jsonb(l) FROM lesson l WHERE student_id=? ORDER BY starts_at DESC LIMIT 40", student)),
                    "schedules" to JsonArray(c.documents("SELECT to_jsonb(s) FROM schedule s WHERE student_id=? ORDER BY effective_from DESC", student)),
                    "demoReceipts" to JsonArray(c.documents("SELECT to_jsonb(r) FROM trial_demo_receipt r WHERE student_id=?", student)))
                "audio" -> {
                    val lesson = c.lesson(ExecutionScope(student, "voice", epoch), args.text("lessonId"))
                    json("objects" to JsonArray(c.documents("SELECT to_jsonb(a) FROM trial_audio a WHERE student_id=? AND lesson_id=? AND data_epoch=? AND status='stored' AND expires_at>? ORDER BY created_at,sequence LIMIT 2000", student, lesson.text("id"), epoch, now)))
                }
                "demo_pay" -> {
                    val schedule = c.document("SELECT to_jsonb(s) FROM schedule s WHERE student_id=? AND id=?", student, args.text("scheduleId")) ?: reject("not_owned")
                    if (schedule.obj("agreement").optionalText("demo") != "true" || schedule.obj("agreement").optionalText("cancelled") == "true") reject("not_permitted")
                    c.update("INSERT INTO trial_demo_receipt(student_id,schedule_id,created_at) VALUES (?,?,?) ON CONFLICT DO NOTHING", student, schedule.text("id"), now)
                    json("demo" to true, "charged" to false, "subscriptionActive" to false)
                }
                "open" -> open(c, student, epoch, args, now)
                else -> reject("unknown_action")
            }
        }
    }

    private fun open(c: Connection, student: String, epoch: Long, args: JsonObject, now: Instant): JsonObject {
        val lesson = c.lesson(ExecutionScope(student, "voice", epoch), args.text("lessonId"))
        if (lesson.text("kind") != "trial" || lesson.text("launch_mode") != "executable" || lesson.text("status") !in setOf("scheduled", "in_progress")) reject("lesson_unavailable")
        val start = Instant.parse(lesson.text("starts_at"))
        if (now < start) reject("too_early")
        if (lesson.text("status") == "scheduled" && now >= start.plusSeconds(lesson.number("duration_minutes") * 60)) reject("lesson_expired")
        val attempt = args.text("attemptId")
        if (attempt.length !in 1..100) reject("invalid_attempt")
        // An expired transport is recoverable; its Lesson is not automatically completed.
        c.update("UPDATE call_session SET status='disconnected',ended_at=?,end_reason='technical_disconnect' WHERE lesson_id=? AND status='connected' AND lease_until<=?", now, lesson.text("id"), now)
        if (c.document("SELECT to_jsonb(s) FROM call_session s WHERE lesson_id=? AND status='connected'", lesson.text("id")) != null) reject("active_episode")
        if (c.document("SELECT to_jsonb(s) FROM call_session s WHERE lesson_id=? AND attempt_id=?", lesson.text("id"), attempt) != null) reject("attempt_already_used")
        val id = newId()
        val token = newId() + newId()
        c.update("INSERT INTO execution_scope(token_digest,student_id,role,lesson_id,data_epoch,expires_at) VALUES (?,?,'voice',?,?,?)", digest(token), student, lesson.text("id"), epoch, now.plusSeconds(120))
        c.update("INSERT INTO call_session(id,student_id,lesson_id,attempt_id,status,started_at,lease_until,scope_digest) VALUES (?,?,?,?,'connected',?,?,?)",
            id, student, lesson.text("id"), attempt, now, now.plusSeconds(45), digest(token))
        val briefing = json("kind" to "trial", "objective" to "Diagnose and demonstrate through a natural conversation; oral feedback and comfort self-report.")
        c.update("UPDATE lesson SET status='in_progress',execution_snapshot=COALESCE(execution_snapshot,?::jsonb),version=version+1 WHERE id=?", briefing, lesson.text("id"))
        c.update("UPDATE trial_notice SET status='cancelled' WHERE lesson_id=? AND status IN ('pending','generating','ready')", lesson.text("id"))
        return json("scope" to token, "callSessionId" to id, "lesson" to c.lesson(ExecutionScope(student, "voice", epoch), lesson.text("id")))
    }
}

internal fun Connection.activeTrial(scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
    requireRole(scope, "voice")
    if (scope.lessonId != args.text("lessonId")) reject("not_owned")
    return document("SELECT to_jsonb(s) FROM call_session s WHERE student_id=? AND lesson_id=? AND id=? AND scope_digest=? AND status='connected' AND lease_until>?",
        scope.studentId, scope.lessonId, args.text("callSessionId"), scope.tokenDigest, now) ?: reject("stale_episode")
}
