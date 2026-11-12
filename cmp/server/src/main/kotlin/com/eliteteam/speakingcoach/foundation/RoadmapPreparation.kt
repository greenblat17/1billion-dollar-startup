package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Content is computed by AI; this boundary owns evidence, revisions and activation. */
internal class RoadmapPreparation {
    fun enqueue(c: Connection, student: String, now: Instant) {
        if (c.goalRevision(student) == 0L || c.document("""SELECT to_jsonb(e) FROM lesson_evaluation e JOIN lesson l ON l.id=e.lesson_id
            WHERE e.student_id=? AND l.kind='trial' AND l.status='completed' AND e.source_revision=l.transcript_revision LIMIT 1""", student) == null) return
        val inputs = json("goalRevision" to c.goalRevision(student), "learningRevision" to c.learningRevision(student),
            "roadmapRevision" to c.roadmapRevision(student))
        c.enqueueJob(student, "roadmap.prepare", digest(canonical(inputs)), null, inputs, now)
    }

    fun state(c: Connection, student: String): JsonObject? = c.document("""SELECT jsonb_build_object('status',status,'error',error_code,'decision',result->>'decision','reason',result->>'reason')
        FROM foundation_job WHERE student_id=? AND kind='roadmap.prepare'
        AND (input_refs->>'goalRevision')::bigint=? AND (input_refs->>'learningRevision')::bigint=?
        ORDER BY created_at DESC,id DESC LIMIT 1""", student, c.goalRevision(student), c.learningRevision(student))

    fun apply(c: Connection, scope: ExecutionScope, inputs: JsonObject, result: JsonObject, now: Instant): Boolean {
        if (inputs.number("goalRevision") != c.goalRevision(scope.studentId) ||
            inputs.number("learningRevision") != c.learningRevision(scope.studentId) ||
            inputs.number("roadmapRevision") != c.roadmapRevision(scope.studentId)) return false
        result.only("decision", "reason", "steps", "sourceIds", "progress", "dimensions")
        val decision = result.text("decision")
        if (decision !in setOf("revise", "keep", "insufficient_evidence") || result.text("reason").isBlank() || result.text("reason").length > 1000) reject("invalid_result")
        val sources = result["sourceIds"]?.jsonArray ?: reject("invalid_result")
        val steps = result["steps"]?.jsonArray ?: reject("invalid_result")
        if (sources.size > 40 || sources.distinct().size != sources.size) reject("invalid_result")
        val refs = sources.map { source ->
            c.document("""SELECT to_jsonb(o) FROM memory_observation o JOIN lesson l ON l.id=o.lesson_id
                WHERE o.student_id=? AND o.source_event_id=? AND o.source_revision=l.transcript_revision
                ORDER BY o.created_at DESC LIMIT 1""", scope.studentId, source.jsonPrimitive.content) ?: reject("invalid_evidence")
        }
        val active = c.currentRoadmap(scope.studentId)
        if (decision == "keep" && active == null) reject("invalid_result")
        LearningProgress().apply(c, scope, inputs, result, now)
        if (decision != "revise") {
            if (steps.isNotEmpty()) reject("invalid_result")
            return true
        }
        if (refs.isEmpty()) reject("invalid_evidence")
        // Corroboration floor, not a mastery score: two distinct lessons, never two errors in one lesson.
        if (active != null && refs.map { it.text("lesson_id") }.distinct().size < 2) reject("insufficient_evidence")
        if (active != null) {
            val previousSources = (active.obj("content")["sources"] as? JsonArray).orEmpty().map { it.jsonObject.text("sourceId") }.toSet()
            if (refs.all { it.text("source_event_id") in previousSources }) reject("insufficient_evidence")
        }
        val provenance = JsonArray(refs.map { json("lessonId" to it.text("lesson_id"), "sourceId" to it.text("source_event_id"),
            "sourceRevision" to it.number("source_revision"), "analyzerVersion" to it.text("analyzer_version")) })
        LearningState().roadmap(c, scope, json("goalRevision" to inputs.number("goalRevision"),
            "expectedRevision" to inputs.number("roadmapRevision"), "reason" to result.text("reason"), "steps" to steps,
            "mode" to if (active == null) "initialize" else "adapt", "sources" to provenance), now)
        return true
    }
}

internal fun Connection.roadmapRevision(student: String): Long = rows(
    "SELECT COALESCE(active_revision,0) FROM roadmap WHERE student_id=?", student,
) { it.getLong(1) }.singleOrNull() ?: 0

internal fun Connection.currentRoadmap(student: String): JsonObject? = document("""SELECT to_jsonb(v)
    FROM roadmap r JOIN roadmap_revision v ON v.roadmap_id=r.id AND v.revision=r.active_revision
    WHERE r.student_id=? AND v.status='active' AND v.goal_revision=?""", student, goalRevision(student))
