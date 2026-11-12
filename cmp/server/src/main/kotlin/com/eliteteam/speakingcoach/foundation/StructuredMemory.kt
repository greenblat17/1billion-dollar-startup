package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal class StructuredMemory {
    fun record(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "text", "voice", "operator")
        args.only("sourceId", "category", "key", "value", "kind", "evidence", "expectedVersion")
        val source = sourceMessage(connection, scope, args.text("sourceId"), now)
        if (args.text("evidence").isBlank() || !source.text("body").contains(args.text("evidence"))) reject("invalid_evidence")
        val category = args.text("category")
        val key = args.text("key")
        val kind = args.text("kind")
        val value = args.optionalText("value")
        if (category !in setOf("personal", "learning") || !key.matches(Regex("[a-z][a-z_]{0,49}")) ||
            kind !in setOf("user_report", "self_assessment") || (value?.length ?: 0) > 1000
        ) reject("invalid_fact")
        if (key == "goal") {
            if (category != "learning" || kind != "user_report") reject("invalid_goal")
            val revision = connection.goalRevision(scope.studentId)
            if (args.number("expectedVersion") != revision) reject("version_conflict")
            connection.update(
                "INSERT INTO learning_goal(student_id,revision,content,source_event_id,created_at) VALUES (?,?,?::jsonb,?,?)",
                scope.studentId, revision + 1, json("value" to value), source.text("id"), now,
            )
            connection.update("UPDATE lesson_plan SET status='stale' WHERE student_id=? AND status='ready'", scope.studentId)
            connection.invalidatePreparation(scope.studentId, now)
            return json("revision" to revision + 1, "value" to value)
        }
        val old = connection.document(
            "SELECT to_jsonb(f) FROM memory_fact f WHERE student_id=? AND category=? AND fact_key=?",
            scope.studentId, category, key,
        )
        val revision = old?.number("revision") ?: 0
        if (args.number("expectedVersion") != revision) reject("version_conflict")
        connection.update(
            """INSERT INTO memory_fact(student_id,category,fact_key,value,kind,source_event_id,revision,forgotten_at,updated_at)
               VALUES (?,?,?,?,?,?,?,NULL,?) ON CONFLICT(student_id,category,fact_key)
               DO UPDATE SET value=EXCLUDED.value,kind=EXCLUDED.kind,source_event_id=EXCLUDED.source_event_id,
               revision=EXCLUDED.revision,forgotten_at=NULL,updated_at=EXCLUDED.updated_at""",
            scope.studentId, category, key, value, kind, source.text("id"), revision + 1, now,
        )
        if (old?.optionalText("value") != value) connection.invalidatePreparation(scope.studentId, now)
        return json("revision" to revision + 1)
    }

    fun propose(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "learning", "operator")
        args.only("sourceId", "key", "value")
        val source = sourceMessage(connection, scope, args.text("sourceId"), now)
        val key = args.text("key")
        if (!key.matches(Regex("[a-z][a-z_]{0,49}")) || args.text("value").length > 1000) reject("invalid_candidate")
        val forgotten = connection.document(
            "SELECT to_jsonb(f) FROM memory_fact f WHERE student_id=? AND category='personal' AND fact_key=? AND forgotten_at IS NOT NULL",
            scope.studentId, key,
        )
        if (forgotten != null) reject("fact_forgotten")
        if (connection.document("SELECT to_jsonb(c) FROM memory_candidate c WHERE student_id=? AND fact_key=? AND status='rejected' LIMIT 1",
                scope.studentId, key) != null) reject("candidate_rejected")
        val id = newId()
        connection.update(
            """INSERT INTO memory_candidate(id,student_id,fact_key,value,source_event_id,expires_at)
               VALUES (?,?,?,?,?,?) ON CONFLICT(student_id,source_event_id,fact_key) DO NOTHING""",
            id, scope.studentId, key, args.text("value"), source.text("id"), Instant.parse(source.text("expires_at")),
        )
        return connection.document(
            "SELECT to_jsonb(c) FROM memory_candidate c WHERE student_id=? AND source_event_id=? AND fact_key=?",
            scope.studentId, source.text("id"), key,
        ) ?: reject("invalid_candidate")
    }

    fun confirm(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "text", "operator")
        args.only("candidateId", "expectedVersion", "sourceId", "evidence")
        val source = sourceMessage(connection, scope, args.text("sourceId"), now)
        if (args.text("evidence").isBlank() || !source.text("body").contains(args.text("evidence"))) reject("invalid_evidence")
        val candidate = connection.document(
            "SELECT to_jsonb(c) FROM memory_candidate c WHERE student_id=? AND id=? AND expires_at>?",
            scope.studentId, args.text("candidateId"), now,
        ) ?: reject("source_expired")
        if (source.text("id") == candidate.text("source_event_id")) reject("confirmation_required")
        if (candidate.text("status") != "pending" || candidate.number("version") != args.number("expectedVersion")) reject("version_conflict")
        // Never overwrite a fact added while this proposal was waiting for confirmation.
        if (connection.document(
                "SELECT to_jsonb(f) FROM memory_fact f WHERE student_id=? AND category='personal' AND fact_key=?",
                scope.studentId, candidate.text("fact_key"),
            ) != null
        ) reject("version_conflict")
        connection.update(
            """INSERT INTO memory_fact(student_id,category,fact_key,value,kind,source_event_id,updated_at)
               VALUES (?,'personal',?,?,'user_report',?,?)""", scope.studentId, candidate.text("fact_key"),
            candidate.text("value"), source.text("id"), now,
        )
        connection.update("UPDATE memory_candidate SET status='confirmed',version=version+1 WHERE id=?", candidate.text("id"))
        connection.invalidatePreparation(scope.studentId, now)
        return json("confirmed" to true)
    }
}

internal fun Connection.goalRevision(student: String): Long = rows(
    "SELECT COALESCE(max(revision),0) FROM learning_goal WHERE student_id=?", student,
) { it.getLong(1) }.single()

internal fun Connection.learningRevision(student: String): Long = rows(
    "SELECT learning_revision FROM student WHERE id=?", student,
) { it.getLong(1) }.single()

internal fun Connection.invalidatePreparation(student: String, now: Instant, reviewRoadmap: Boolean = true) {
    update("UPDATE student SET learning_revision=learning_revision+1 WHERE id=?", student)
    update("UPDATE foundation_job SET status='superseded' WHERE student_id=? AND kind='lesson.prepare' AND status IN ('queued','running','retry_wait')", student)
    if (reviewRoadmap) {
        update("UPDATE foundation_job SET status='superseded' WHERE student_id=? AND kind='roadmap.prepare' AND status IN ('queued','running','retry_wait')", student)
        RoadmapPreparation().enqueue(this, student, now)
    }
    rows("SELECT id FROM lesson WHERE student_id=? AND status='scheduled' AND launch_mode='executable'", student) {
        it.getString(1)
    }.forEach { lesson ->
        enqueueJob(student, "lesson.prepare", "$lesson:goal:${goalRevision(student)}:learning:${learningRevision(student)}", lesson,
            planInputs(student, lesson), now)
    }
}

internal fun Connection.planInputs(student: String, lesson: String): JsonObject = json(
    "goalRevision" to goalRevision(student), "learningRevision" to learningRevision(student),
    "roadmapRevision" to roadmapRevision(student), "lessonVersion" to
        rows("SELECT version FROM lesson WHERE id=? AND student_id=?", lesson, student) { it.getLong(1) }.single(),
)
