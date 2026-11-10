package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject

internal const val HISTORY_SECONDS = 30L * 24 * 60 * 60

internal class ConversationHistory(private val database: FoundationDatabase) {
    suspend fun accept(student: String, request: String, text: String, occurredAt: Instant, start: Boolean): JsonObject =
        database.transaction { connection, now ->
            if (request.isBlank() || request.length > 200 || text.length > 12000) reject("invalid_input")
            connection.lockStudent(student)
            val fingerprint = digest(canonical(json("text" to text, "start" to start, "occurredAt" to occurredAt.toString())))
            val previous = connection.document(
                "SELECT to_jsonb(i) FROM tutor_inbox i WHERE student_id=? AND request_id=?", student, request,
            )
            if (previous != null) {
                if (previous.text("input_digest") != fingerprint) reject("operation_conflict")
                previous
            } else {
                val conversation = connection.conversation(student, "telegram")
                val event = connection.appendEvent(student, conversation, request, "user", text, occurredAt, now)
                connection.update(
                    """INSERT INTO tutor_inbox(student_id,request_id,event_id,input_digest,expires_at)
                       VALUES (?,?,?,?,?)""", student, request, event.text("id"), fingerprint, now.plusSeconds(HISTORY_SECONDS),
                )
                json("event_id" to event.text("id"), "request_id" to request, "status" to "pending")
            }
        }
}

internal fun Connection.lockStudent(student: String) {
    if (rows("SELECT id FROM student WHERE id=? AND deleted_at IS NULL FOR UPDATE", student) { it.getString(1) }.isEmpty()) {
        reject("not_owned")
    }
}

internal fun Connection.conversation(student: String, channel: String): String {
    val proposed = newId()
    update("INSERT INTO tutor_conversation(id,student_id,channel) VALUES (?,?,?) ON CONFLICT DO NOTHING", proposed, student, channel)
    return rows("SELECT id FROM tutor_conversation WHERE student_id=? AND channel=?", student, channel) { it.getString(1) }.single()
}

internal fun Connection.appendEvent(
    student: String, conversation: String, source: String, role: String, body: String, occurredAt: Instant, now: Instant,
): JsonObject {
    if (body.length > 12000 || source.isBlank() || source.length > 250) reject("invalid_event")
    val fingerprint = digest(canonical(json("role" to role, "body" to body, "occurredAt" to occurredAt.toString())))
    val previous = document("SELECT to_jsonb(e) FROM conversation_event e WHERE conversation_id=? AND source_id=?", conversation, source)
    if (previous != null) {
        if (previous.text("content_digest") != fingerprint) reject("operation_conflict")
        return previous
    }
    val seq = rows("UPDATE tutor_conversation SET next_sequence=next_sequence+1 WHERE id=? RETURNING next_sequence-1", conversation) {
        it.getLong(1)
    }.single()
    val id = newId()
    update(
        """INSERT INTO conversation_event(id,student_id,conversation_id,sequence,source_id,role,body,
           occurred_at,received_at,expires_at,content_digest) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
        id, student, conversation, seq, source, role, body, occurredAt, now, now.plusSeconds(HISTORY_SECONDS), fingerprint,
    )
    return json("id" to id, "sequence" to seq, "expires_at" to now.plusSeconds(HISTORY_SECONDS).toString())
}
