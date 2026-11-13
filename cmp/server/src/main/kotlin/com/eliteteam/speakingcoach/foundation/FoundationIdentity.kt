package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject

internal data class ExecutionScope(
    val studentId: String,
    val role: String,
    val epoch: Long,
    val requestId: String? = null,
    val lessonId: String? = null,
    val jobId: String? = null,
    val fence: Long? = null,
    val tokenDigest: String = "",
)

internal class FoundationIdentity(private val database: FoundationDatabase) {
    /** Called only after a channel adapter authenticates the subject; never exposed as an AI tool. */
    suspend fun resolve(provider: String, subject: String): String = database.transaction { connection, _ ->
        if (provider != "telegram" || subject.toLongOrNull()?.let { it > 0 } != true) reject("invalid_identity")
        connection.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "$provider:$subject") { Unit }
        val existing = connection.rows(
            "SELECT student_id FROM student_identity WHERE provider=? AND subject=?", provider, subject,
        ) { it.getString(1) }.singleOrNull()
        existing ?: newId().also { id ->
            connection.update("INSERT INTO student(id) VALUES (?)", id)
            connection.update("INSERT INTO student_identity(provider,subject,student_id) VALUES (?,?,?)", provider, subject, id)
        }
    }

    suspend fun issueJob(job: JsonObject): String = database.transaction { connection, now ->
        val current = connection.document("SELECT to_jsonb(j) FROM foundation_job j WHERE id=? AND status='running' AND fence=? AND lease_until>?",
            job.text("id"), job.number("fence"), now) ?: reject("stale_job")
        val token = newId() + newId()
        connection.update("INSERT INTO execution_scope(token_digest,student_id,role,lesson_id,job_id,fence,data_epoch,expires_at) VALUES (?,?,'learning',?,?,?,?,?)",
            digest(token), current.text("student_id"), current.optionalText("target_id"), current.text("id"), current.number("fence"),
            current.number("data_epoch"), Instant.parse(current.text("lease_until")))
        token
    }

    suspend fun issue(student: String, role: String, request: String? = null, lesson: String? = null): String =
        database.transaction { connection, now ->
            if (role !in setOf("text", "voice", "learning", "operator")) reject("invalid_role")
            val epoch = connection.rows("SELECT data_epoch FROM student WHERE id=? AND deleted_at IS NULL", student) {
                it.getLong(1)
            }.singleOrNull() ?: reject("not_owned")
            if (request != null && connection.document(
                    "SELECT to_jsonb(i) FROM tutor_inbox i WHERE student_id=? AND request_id=?", student, request,
                ) == null
            ) reject("not_owned")
            if (lesson != null && connection.document(
                    "SELECT to_jsonb(l) FROM lesson l WHERE student_id=? AND id=?", student, lesson,
                ) == null
            ) reject("not_owned")
            val token = newId() + newId()
            connection.update(
                """INSERT INTO execution_scope(token_digest,student_id,role,request_id,lesson_id,data_epoch,expires_at)
                   VALUES (?,?,?,?,?,?,?)""", digest(token), student, role, request, lesson, epoch, now.plusSeconds(120),
            )
            token
        }
}

internal fun authenticateScope(connection: Connection, token: String, now: Instant): ExecutionScope {
    val row = connection.document(
        """SELECT to_jsonb(e) FROM execution_scope e JOIN student s ON s.id=e.student_id
           WHERE e.token_digest=? AND e.expires_at>? AND e.data_epoch=s.data_epoch AND s.deleted_at IS NULL""",
        digest(token), now,
    ) ?: reject("scope_expired")
    return ExecutionScope(row.text("student_id"), row.text("role"), row.number("data_epoch"),
        row.optionalText("request_id"), row.optionalText("lesson_id"), row.optionalText("job_id"),
        row.optionalText("fence")?.toLong(), row.text("token_digest"))
}

internal fun requireRole(scope: ExecutionScope, vararg roles: String) {
    if (scope.role !in roles) reject("not_permitted")
}

internal fun sourceMessage(connection: Connection, scope: ExecutionScope, sourceId: String, now: Instant): JsonObject {
    val source = connection.document(
        """SELECT to_jsonb(e) FROM conversation_event e WHERE student_id=? AND id=? AND role='user'
           AND body IS NOT NULL AND expires_at>?""", scope.studentId, sourceId, now,
    ) ?: reject("source_expired")
    if (scope.role == "voice" && (scope.lessonId == null || connection.document(
            "SELECT to_jsonb(e) FROM lesson_event e WHERE lesson_id=? AND event_id=?", scope.lessonId, sourceId) == null)) reject("not_owned")
    if (scope.role == "text") {
        val input = connection.document(
            "SELECT to_jsonb(i) FROM tutor_inbox i WHERE student_id=? AND request_id=?",
            scope.studentId, scope.requestId,
        ) ?: reject("not_owned")
        if (input.text("event_id") != sourceId) reject("source_not_current_input")
    }
    return source
}
