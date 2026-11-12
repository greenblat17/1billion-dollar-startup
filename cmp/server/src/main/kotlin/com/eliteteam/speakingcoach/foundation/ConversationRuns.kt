package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray

internal class ConversationRuns {
    fun claim(connection: Connection, scope: ExecutionScope, now: Instant): JsonObject {
        requireRole(scope, "text")
        val request = scope.requestId ?: reject("request_required")
        val input = connection.document("SELECT to_jsonb(i) FROM tutor_inbox i WHERE student_id=? AND request_id=?", scope.studentId, request)
            ?: reject("not_owned")
        if (Instant.parse(input.text("expires_at")) <= now) reject("source_expired")
        if (input.text("status") == "ready") return json("status" to "ready", "response" to input.obj("response"))
        if (connection.document("""SELECT to_jsonb(i) FROM tutor_inbox i JOIN conversation_event e ON e.id=i.event_id
            WHERE i.student_id=? AND i.status IN ('pending','running') AND i.expires_at>?
            AND e.sequence<(SELECT sequence FROM conversation_event WHERE id=?) LIMIT 1""",
                scope.studentId, now, input.text("event_id")) != null) reject("conversation_busy")
        if (connection.document("SELECT to_jsonb(i) FROM tutor_inbox i WHERE student_id=? AND status='running' AND lease_until>? LIMIT 1", scope.studentId, now) != null) reject("conversation_busy")
        connection.update("UPDATE tutor_inbox SET status='running',fence=fence+1,lease_until=? WHERE student_id=? AND request_id=?", now.plusSeconds(90), scope.studentId, request)
        val claimed = connection.document("SELECT to_jsonb(i) FROM tutor_inbox i WHERE student_id=? AND request_id=?", scope.studentId, request) ?: reject("not_owned")
        connection.update("UPDATE execution_scope SET fence=? WHERE token_digest=?", claimed.number("fence"), scope.tokenDigest)
        val source = sourceMessage(connection, scope, claimed.text("event_id"), now)
        val committed = connection.documents("""SELECT jsonb_build_object('action',action,'result',result) FROM foundation_operation
            WHERE student_id=? AND request_id=? AND role='text' AND action NOT IN ('run.claim','run.complete','lesson.list','lesson.next','lesson.get','trial.schedules','subscription.get')
            AND expires_at>? ORDER BY created_at,operation_id""", scope.studentId, request, now)
        val recovered = committed.map { operation ->
            if (operation.text("action") in setOf("lesson.create", "lesson.reschedule", "lesson.cancel")) {
                json("action" to operation.text("action"), "result" to connection.lesson(scope, operation.obj("result").text("id")))
            } else operation
        }
        return json("status" to "running", "input" to source, "fence" to claimed.number("fence"), "committed" to JsonArray(recovered))
    }

    fun complete(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "text")
        args.only("reply", "bookingId", "degraded")
        assertRun(connection, scope, now)
        val reply = args.text("reply")
        if (reply.isBlank() || reply.length > 12000) reject("invalid_reply")
        val request = scope.requestId ?: reject("request_required")
        val conversation = connection.conversation(scope.studentId, "telegram")
        val deadline = connection.rows("SELECT min(expires_at) FROM conversation_event WHERE student_id=? AND body IS NOT NULL AND expires_at>?",
            scope.studentId, now) { it.getTimestamp(1)?.toInstant() }.single() ?: now.plusSeconds(HISTORY_SECONDS)
        val event = connection.appendEvent(scope.studentId, conversation, "reply:$request", "assistant", reply, now, now)
        connection.update("UPDATE conversation_event SET expires_at=? WHERE id=?", deadline, event.text("id"))
        val booking = args.optionalText("bookingId")?.let { connection.lesson(scope, it) }
        val response = json("conversationId" to conversation, "reply" to reply, "booking" to booking, "degraded" to args["degraded"])
        connection.update("UPDATE tutor_inbox SET status='ready',response=?::jsonb,lease_until=NULL,expires_at=LEAST(expires_at,?) WHERE student_id=? AND request_id=?", response, deadline, scope.studentId, request)
        val input = connection.document("SELECT to_jsonb(i) FROM tutor_inbox i WHERE student_id=? AND request_id=?", scope.studentId, request) ?: reject("not_owned")
        val job = connection.enqueueJob(scope.studentId, "memory.extract", input.text("event_id"), null,
            json("sourceId" to input.text("event_id"), "extractorVersion" to "v1"), now)
        connection.update("UPDATE foundation_job SET deadline=LEAST(deadline,?) WHERE id=?", deadline, job)
        return response
    }
}

internal fun assertRun(connection: Connection, scope: ExecutionScope, now: Instant) {
    if (scope.role != "text") return
    if (scope.requestId == null) reject("request_required")
    val input = connection.document("SELECT to_jsonb(i) FROM tutor_inbox i WHERE student_id=? AND request_id=?", scope.studentId, scope.requestId)
        ?: reject("not_owned")
    if (scope.fence == null || input.number("fence") != scope.fence || input.text("status") != "running" ||
        Instant.parse(input.text("lease_until")) <= now
    ) reject("stale_run")
}
