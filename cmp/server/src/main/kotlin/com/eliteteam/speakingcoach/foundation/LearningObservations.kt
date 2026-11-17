package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Authoritative evidence journal. Commands run under the existing student lock and receipt transaction. */
internal class LearningObservations(private val stages: ObservationStageResolver = unresolvedObservationStage) {
    fun write(c: Connection, scope: ExecutionScope, action: String, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator", "voice")
        if (action == "record") args.only("observation") else args.only("observationId", "expectedRevision", "observation", "reason")
        val d = args.obj("observation")
        LearningObservationModel.validate(d)
        LearningObservationSources.validate(c, scope.studentId, d, now)
        validateCatalog(c, d)
        validateTask(c, scope.studentId, d)
        val reference = referenceStatus(c, scope.studentId, d)
        if (scope.role == "voice" && (action != "record" || d.text("practice") != "regular" ||
                d.optionalText("lessonId") != scope.lessonId || d.optionalText("actionId") == null ||
                reference.first != "verified" && !(reference.first == "defective" &&
                    d.text("outcome") == "invalid" && d.obj("quality").text("status") == "invalid"))) reject("not_permitted")
        d.optionalText("repeatOf")?.let {
            if (get(c, scope.studentId, it).obj("document").optionalText("repeatOf") != null) reject("observation_repeat_chain")
        }
        val old = if (action == "record") c.document("""SELECT to_jsonb(o) FROM learning_observation o
            WHERE student_id=? AND anchor_event_id=? AND evidence_slot=?""", scope.studentId, d.text("anchorEventId"), d.text("evidenceSlot"))
            else get(c, scope.studentId, LearningObservationModel.string(args["observationId"]))
        if (action == "record" && old != null) {
            if (old.text("status") != "active" || canonical(old.obj("document")) != canonical(d)) reject("observation_identity_conflict")
            return response(c, old, now, false)
        }
        if (old != null) {
            if (LearningObservationModel.positive(args["expectedRevision"]) != old.number("revision")) reject("version_conflict")
            if (old.text("status") != "active") reject("observation_invalidated")
            for (field in listOf("anchorEventId", "attemptId", "evidenceSlot", "lessonId", "occurredAt", "practice", "repeatOf")) {
                if (d[field] != old.obj("document")[field]) reject("observation_identity_immutable")
            }
        }
        val id = old?.text("id") ?: newId()
        if (d.optionalText("repeatOf") == id) reject("observation_repeat_cycle")
        val duplicate = c.document("""SELECT to_jsonb(o) FROM learning_observation o WHERE student_id=? AND attempt_id=? AND id<>?
            AND status='active' AND document->>'topicId'=? AND document->>'problemId'=?""",
            scope.studentId, d.text("attemptId"), id, d.optionalText("topicId"), d.optionalText("problemId"))
        if (duplicate != null) reject("duplicate_problem_evidence")
        val revision = (old?.number("revision") ?: 0) + 1
        val reason = if (old == null) "record" else LearningObservationModel.string(args["reason"], 2000)
        if (old == null) c.update("""INSERT INTO learning_observation(id,student_id,anchor_event_id,attempt_id,evidence_slot,lesson_id,
            revision,status,document,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'active',?::jsonb,?,?)""",
            id, scope.studentId, d.text("anchorEventId"), d.text("attemptId"), d.text("evidenceSlot"), d.optionalText("lessonId"), revision, d, now, now)
        else c.update("UPDATE learning_observation SET revision=?,document=?::jsonb,updated_at=? WHERE student_id=? AND id=?",
            revision, d, now, scope.studentId, id)
        recordRevision(c, scope.studentId, id, revision, "active", d, d.obj("provenance").text("component"), reason, now)
        return response(c, get(c, scope.studentId, id), now, old == null)
    }

    fun invalidate(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("observationId", "expectedRevision", "reason", "component")
        val old = get(c, scope.studentId, LearningObservationModel.string(args["observationId"]))
        if (LearningObservationModel.positive(args["expectedRevision"]) != old.number("revision")) reject("version_conflict")
        val reason = LearningObservationModel.string(args["reason"], 2000)
        val component = LearningObservationModel.string(args["component"], 100)
        if (old.text("status") == "invalidated") return response(c, old, now, false)
        val revision = old.number("revision") + 1
        c.update("UPDATE learning_observation SET revision=?,status='invalidated',updated_at=? WHERE student_id=? AND id=?", revision, now, scope.studentId, old.text("id"))
        recordRevision(c, scope.studentId, old.text("id"), revision, "invalidated", old.obj("document"), component, reason, now)
        return response(c, get(c, scope.studentId, old.text("id")), now, false)
    }

    private fun recordRevision(c: Connection, student: String, id: String, revision: Long, status: String, d: JsonObject, component: String, reason: String, now: Instant) {
        c.update("""INSERT INTO learning_observation_revision(student_id,observation_id,revision,status,document,component,reason,recorded_at)
            VALUES (?,?,?,?,?::jsonb,?,?,?)""", student, id, revision, status, d, component, reason, now)
        LearningObservationSources.store(c, student, id, revision, d)
    }

    fun get(c: Connection, student: String, id: String): JsonObject = c.document(
        "SELECT to_jsonb(o) FROM learning_observation o WHERE student_id=? AND id=?", student, id) ?: reject("not_owned")

    fun read(c: Connection, student: String, id: String, now: Instant): JsonObject = json("schemaVersion" to 1, "observation" to project(c, get(c, student, id), now))

    fun list(c: Connection, student: String, filters: Map<String, String>, offset: Int, now: Instant): JsonObject {
        offset(offset)
        val allowed = mapOf("lessonId" to "lesson_id", "taskId" to "document->>'taskId'", "skillId" to "document->>'skillId'",
            "topicId" to "document->>'topicId'", "problemId" to "document->>'problemId'")
        if (filters.keys.any { it !in allowed }) reject("invalid_observation_query")
        val predicates = filters.keys.joinToString("") { " AND ${allowed.getValue(it)}=?" }
        val values: List<Any> = listOf(student) + filters.values + offset
        val rows = c.documents("SELECT to_jsonb(o) FROM learning_observation o WHERE student_id=?$predicates ORDER BY created_at,id LIMIT 51 OFFSET ?", *values.toTypedArray())
        return page(rows.take(50).map { project(c, it, now) }, rows.size, offset)
    }

    fun history(c: Connection, student: String, id: String, offset: Int, now: Instant): JsonObject {
        offset(offset)
        val current = get(c, student, id)
        val rows = c.documents("SELECT to_jsonb(r) FROM learning_observation_revision r WHERE student_id=? AND observation_id=? ORDER BY revision DESC LIMIT 51 OFFSET ?", student, id, offset)
        return page(rows.take(50).map { row ->
            JsonObject(row + json("isCurrent" to (row.number("revision") == current.number("revision")),
                "sources" to LearningObservationSources.project(c, student, id, row.number("revision"), now)))
        }, rows.size, offset)
    }

    private fun project(c: Connection, row: JsonObject, now: Instant): JsonObject {
        val d = row.obj("document")
        val sources = LearningObservationSources.project(c, row.text("student_id"), row.text("id"), row.number("revision"), now)
        val available = sources.isNotEmpty() && sources.all { (it as JsonObject).text("sourceContentExpired") == "false" && it.text("sourceRevisionCurrent") == "true" }
        val usable = row.text("status") == "active" && available && d.obj("quality").text("status") == "usable" &&
            d.text("kind") == "demonstration" && d.text("outcome") !in setOf("unresolved", "invalid") &&
            d.text("support") != "unknown" && d.optionalText("repeatOf") == null
        // No fabricated resolution against a Plan table that is not part of this delivery.
        val (referenceStatus, stageKind) = referenceStatus(c, row.text("student_id"), d)
        return JsonObject(row + json("sources" to sources, "evidenceUsable" to usable, "referenceStatus" to referenceStatus,
            "stageKind" to stageKind,
            "scoringEligible" to (usable && (referenceStatus == "not_applicable" && d.text("route") == "problem_detection" ||
                referenceStatus == "verified" && stageKind == "numeric" && d.text("route") == "stage_progress"))))
    }

    private fun referenceStatus(c: Connection, student: String, d: JsonObject): Pair<String, String?> {
        val ref = d["stageRef"] as? JsonObject
        if (ref == null) return (if (d.optionalText("actionId") == null) "not_applicable" else "unresolved") to null
        val stage = stages.resolve(c, student, d.text("taskId"), ref) ?: return "unresolved" to null
        val methods = mapOf("Conversation" to "conversation", "Explanation" to "explanation", "Exercises" to "exercises", "Exposure" to "exposure")
        val method = methods[stage.text("method")] ?: reject("observation_stage_method")
        if (d.optionalText("method") != null && method != d.text("method")) reject("observation_stage_method")
        val kind = stage.text("kind")
        if (kind !in setOf("numeric", "binary")) reject("observation_stage_kind")
        val action = d.optionalText("actionId") ?: return "verified" to kind
        val contentId = action.substringBeforeLast(':', "")
        val revision = action.substringAfterLast(':').toLongOrNull()
        if (contentId.isBlank() || revision == null || revision < 1) return "unresolved" to kind
        val issued = c.document("""SELECT to_jsonb(v) FROM learning_content_revision v
            JOIN learning_content h ON h.id=v.content_id
            WHERE h.student_id=? AND h.id=? AND v.revision=?""", student, contentId, revision)
            ?: return "unresolved" to kind
        val content = issued.obj("document")
        val contentStage = content.obj("stageRef")
        val sameStage = contentStage.text("planId") == ref.text("planId") &&
            contentStage.number("planRevision") == ref.number("planRevision") &&
            contentStage.text("stageId") == ref.text("stageId")
        val sameEvidence = content.text("taskId") == d.text("taskId") &&
            content.text("catalogVersion") == d.text("catalogVersion") &&
            content.text("skillId") == d.text("skillId") &&
            content.text("topicId") == d.text("topicId") &&
            content.text("problemId") == d.text("problemId") &&
            methods[content.text("method")] == d.text("method")
        if (!sameStage || !sameEvidence || issued.optionalText("issued_lesson_id") != d.optionalText("lessonId"))
            reject("observation_action_mismatch")
        return (when (issued.text("status")) {
            "issued" -> "verified"
            "defective" -> "defective"
            else -> "unresolved"
        }) to kind
    }

    private fun response(c: Connection, row: JsonObject, now: Instant, created: Boolean) =
        json("schemaVersion" to 1, "created" to created, "observation" to project(c, row, now))

    private fun page(items: List<JsonObject>, count: Int, offset: Int) = json("schemaVersion" to 1, "items" to JsonArray(items), "nextOffset" to if (count > 50) offset + 50 else null)
    private fun offset(offset: Int) { if (offset !in 0..100000) reject("invalid_offset") }

    private fun validateCatalog(c: Connection, d: JsonObject) {
        val v = d.text("catalogVersion")
        if (c.document("SELECT to_jsonb(r) FROM learning_catalog_release r WHERE version=? AND published_at IS NOT NULL", v) == null) reject("catalog_not_published")
        if (c.document("SELECT content FROM learning_catalog_skill WHERE version=? AND id=?", v, d.text("skillId")) == null) reject("invalid_observation_catalog")
        for ((field, table) in listOf("topicId" to "learning_catalog_topic", "problemId" to "learning_catalog_problem")) {
            d.optionalText(field)?.let { id ->
                val row = c.document("SELECT to_jsonb(t) FROM $table t WHERE version=? AND id=?", v, id) ?: reject("invalid_observation_catalog")
                if (row.text("skill_id") != d.text("skillId")) reject("invalid_observation_catalog")
            }
        }
    }

    private fun validateTask(c: Connection, student: String, d: JsonObject) {
        d.optionalText("taskId")?.let { id ->
            val task = LearningTasks().get(c, student, id)
            for ((field, column) in listOf("skillId" to "skill_id", "topicId" to "topic_id", "problemId" to "problem_id")) {
                if (d.optionalText(field) != task.text(column)) reject("observation_task_mismatch")
            }
        }
        if (d.text("route") == "problem_detection" && c.document("""SELECT to_jsonb(t) FROM learning_task t
            WHERE student_id=? AND topic_id=? AND problem_id=? AND lifecycle<>'Mastered' LIMIT 1""",
            student, d.text("topicId"), d.text("problemId")) != null) reject("observation_active_task")
    }
}
