package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal class TrialAccess(private val service: FoundationService) {
    suspend fun access(args: JsonObject, botToken: String): JsonObject {
        val grant = args.optionalText("agentGrant")
        if (grant != null && (System.getenv("SPEAKY_ENV") != "dev" || System.getenv("AGENT_TEST_ENABLED") != "true" || args.text("action") != "open")) reject("not_permitted")
        val student = if (grant == null) {
            val subject = telegramMiniSubject(args.text("initData"), botToken, service.database.clock.instant())
            service.identity.resolve("telegram", subject)
        } else null
        return service.database.transaction { c, _ ->
            val owner = if (grant == null) requireNotNull(student) else {
                if (grant.length !in 32..200 || args.optionalText("initData") != null) reject("invalid_agent_grant")
                val scope = authenticateScope(c, grant, service.database.clock.instant())
                if (scope.role != "agent_voice_grant" || scope.lessonId != args.text("lessonId") ||
                    c.document("SELECT to_jsonb(i) FROM student_identity i WHERE i.student_id=? AND i.provider='agent_test' AND NOT EXISTS (SELECT 1 FROM student_identity t WHERE t.student_id=i.student_id AND t.provider='telegram')", scope.studentId) == null) reject("not_owned")
                c.document("DELETE FROM execution_scope WHERE token_digest=? AND role='agent_voice_grant' RETURNING to_jsonb(execution_scope)", digest(grant)) ?: reject("invalid_agent_grant")
                scope.studentId
            }
            c.lockStudent(owner)
            val now = service.database.clock.instant()
            val epoch = c.rows("SELECT data_epoch FROM student WHERE id=?", owner) { it.getLong(1) }.single()
            when (args.text("action")) {
                "progress" -> LearningProgress().read(c, owner)
                "list" -> json("lessons" to JsonArray(c.documents("SELECT to_jsonb(l) FROM lesson l WHERE student_id=? ORDER BY starts_at DESC LIMIT 40", owner)),
                    "schedules" to JsonArray(c.documents("SELECT to_jsonb(s) FROM schedule s WHERE student_id=? ORDER BY effective_from DESC", owner)),
                    "demoReceipts" to JsonArray(c.documents("SELECT to_jsonb(r) FROM trial_demo_receipt r WHERE student_id=?", owner)))
                "lesson_status" -> json("lesson" to c.lesson(ExecutionScope(owner, "voice", epoch), args.text("lessonId")), "serverNow" to now.toString())
                "calendar" -> json("lesson" to c.lesson(ExecutionScope(owner, "voice", epoch), args.text("lessonId")))
                "audio" -> {
                    val lesson = c.lesson(ExecutionScope(owner, "voice", epoch), args.text("lessonId"))
                    val cursor = args.optionalText("cursor") ?: ""
                    if (cursor.length > 512) reject("invalid_cursor")
                    val objects = c.documents("SELECT to_jsonb(a) FROM trial_audio a WHERE student_id=? AND lesson_id=? AND data_epoch=? AND status='stored' AND expires_at>? AND object_key>? ORDER BY object_key LIMIT 201", owner, lesson.text("id"), epoch, now, cursor)
                    json("objects" to JsonArray(objects.take(200)), "nextCursor" to if (objects.size > 200) objects[199].text("object_key") else null)
                }
                "demo_pay" -> {
                    val schedule = c.document("SELECT to_jsonb(s) FROM schedule s WHERE student_id=? AND id=?", owner, args.text("scheduleId")) ?: reject("not_owned")
                    if (schedule.obj("agreement").optionalText("demo") != "true" || schedule.obj("agreement").optionalText("cancelled") == "true") reject("not_permitted")
                    c.update("INSERT INTO trial_demo_receipt(student_id,schedule_id,created_at) VALUES (?,?,?) ON CONFLICT DO NOTHING", owner, schedule.text("id"), now)
                    json("demo" to true, "charged" to false, "subscriptionActive" to false)
                }
                "open" -> open(c, owner, epoch, args, now)
                else -> reject("unknown_action")
            }
        }
    }

    private fun open(c: Connection, student: String, epoch: Long, args: JsonObject, now: Instant): JsonObject {
        val lesson = c.lesson(ExecutionScope(student, "voice", epoch), args.text("lessonId"))
        if (lesson.text("kind") !in setOf("trial", "regular") || lesson.text("launch_mode") != "executable" || lesson.text("status") !in setOf("scheduled", "in_progress")) reject("lesson_unavailable")
        val start = Instant.parse(lesson.text("starts_at"))
        if (now < start) reject("too_early")
        TrialExecution().expire(c, lesson, now)
        val current = c.lesson(ExecutionScope(student, "voice", epoch), args.text("lessonId"))
        if ((lesson.text("status") == "scheduled" || trialV55(lesson)) && now >= trialReconnectEnd(current)) reject("lesson_expired")
        if (lesson.text("kind") == "regular" && lesson.number("duration_minutes") !in 15..60) reject("invalid_lesson")
        val entitlement = if (lesson.text("kind") == "regular") {
            val previous = lesson.optionalText("admission_entitlement_id")
            if (lesson.text("status") == "in_progress" && previous != null) {
                c.document("SELECT to_jsonb(e) FROM entitlement e WHERE id=? AND student_id=?", previous, student) ?: reject("subscription_required")
            } else c.activeEntitlement(student, now) ?: reject("subscription_required")
        } else null
        val briefing = if (lesson.text("kind") == "trial") {
            json("kind" to "trial", "objective" to "${lesson.number("duration_minutes")}-minute friendly Trial: Russian warm-up, English conversation, Russian wrap-up. No CEFR, scores or timelines.")
        } else if (lesson.text("status") == "in_progress") {
            lesson["execution_snapshot"] as? JsonObject ?: reject("not_ready")
        } else {
            val preparation = c.preparation(ExecutionScope(student, "voice", epoch), lesson.text("id"))
            if (preparation.text("status") != "ready") reject(preparation.text("reason"))
            preparation
        }
        val attempt = args.text("attemptId")
        if (attempt.length !in 1..100) reject("invalid_attempt")
        // An expired transport is recoverable; its Lesson is not automatically completed.
        if (c.document("SELECT to_jsonb(s) FROM call_session s WHERE lesson_id=? AND status='connected'", lesson.text("id")) != null) reject("active_episode")
        if (c.document("SELECT to_jsonb(s) FROM call_session s WHERE lesson_id=? AND attempt_id=?", lesson.text("id"), attempt) != null) reject("attempt_already_used")
        val id = newId()
        val token = newId() + newId()
        c.update("INSERT INTO execution_scope(token_digest,student_id,role,lesson_id,data_epoch,expires_at) VALUES (?,?,'voice',?,?,?)", digest(token), student, lesson.text("id"), epoch, now.plusSeconds(120))
        c.update("INSERT INTO call_session(id,student_id,lesson_id,attempt_id,status,started_at,lease_until,scope_digest) VALUES (?,?,?,?,'connected',?,?,?)",
            id, student, lesson.text("id"), attempt, now, now.plusSeconds(45), digest(token))
        if (entitlement != null) c.update("UPDATE lesson SET admission_entitlement_id=? WHERE id=?", entitlement.text("id"), lesson.text("id"))
        c.update("UPDATE lesson SET status='in_progress',execution_snapshot=COALESCE(execution_snapshot,?::jsonb),version=version+1 WHERE id=?", briefing, lesson.text("id"))
        c.update("UPDATE trial_notice SET status='cancelled' WHERE lesson_id=? AND status IN ('pending','generating','ready')", lesson.text("id"))
        val regularExecution = if (lesson.text("kind") == "regular") RegularLessonAdmission().prepare(c, student, lesson.text("id"), now) else null
        return json("scope" to token, "callSessionId" to id, "lesson" to c.lesson(ExecutionScope(student, "voice", epoch), lesson.text("id")),
            "regularExecution" to regularExecution,
            "issuedTeachingContent" to regularExecution?.get("issuedTeachingContent"),
            "trialExecution" to if (trialV55(lesson)) TrialExecution().state(c, current, now) else null,
            "trialDialogue" to if (trialSp24(lesson)) TrialDialogue().checkpoints(c, current, now) else null)
    }
}

internal fun Connection.activeTrial(scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
    requireRole(scope, "voice")
    if (scope.lessonId != args.text("lessonId")) reject("not_owned")
    return document("SELECT to_jsonb(s) FROM call_session s WHERE student_id=? AND lesson_id=? AND id=? AND scope_digest=? AND status='connected' AND lease_until>?",
        scope.studentId, scope.lessonId, args.text("callSessionId"), scope.tokenDigest, now) ?: reject("stale_episode")
}
