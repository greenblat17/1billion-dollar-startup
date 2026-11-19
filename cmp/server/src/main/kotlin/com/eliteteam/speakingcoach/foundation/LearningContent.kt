package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Versioned material for a verified Task Stage. All writes use FoundationService's student lock. */
internal class LearningContent {
    private fun head(c: Connection, student: String, id: String): JsonObject =
        c.document("SELECT to_jsonb(h) FROM learning_content h WHERE student_id=? AND id=?", student, id)
            ?: reject("not_owned")

    private fun version(c: Connection, id: String, revision: Long): JsonObject =
        c.document("SELECT to_jsonb(v) FROM learning_content_revision v WHERE content_id=? AND revision=?", id, revision)
            ?: reject("content_revision_not_found")

    fun read(c: Connection, student: String, id: String, revision: Long? = null): JsonObject {
        val h = head(c, student, id)
        val v = version(c, id, revision ?: h.number("current_revision"))
        return json("schemaVersion" to 1, "content" to json("id" to id, "revision" to v.number("revision"),
            "currentRevision" to h.number("current_revision"), "status" to v.text("status"),
            "document" to v.obj("document"), "component" to v.text("component"), "reason" to v.text("reason"),
            "createdAt" to v.text("created_at"), "issuedAt" to v.optionalText("issued_at"),
            "issuedLessonId" to v.optionalText("issued_lesson_id"), "defectReason" to v.optionalText("defect_reason"),
            "defectComponent" to v.optionalText("defect_component"), "defectAt" to v.optionalText("defect_at")))
    }

    fun history(c: Connection, student: String, id: String, offset: Int): JsonObject {
        head(c, student, id)
        if (offset !in 0..100000) reject("invalid_offset")
        val rows = c.documents("SELECT to_jsonb(v) FROM learning_content_revision v WHERE content_id=? ORDER BY revision DESC LIMIT 51 OFFSET ?", id, offset)
        return json("schemaVersion" to 1, "items" to JsonArray(rows.take(50).map { v ->
            json("revision" to v.number("revision"), "status" to v.text("status"), "document" to v.obj("document"),
                "createdAt" to v.text("created_at"), "issuedAt" to v.optionalText("issued_at"),
                "issuedLessonId" to v.optionalText("issued_lesson_id"), "defectReason" to v.optionalText("defect_reason"),
                "defectComponent" to v.optionalText("defect_component"), "defectAt" to v.optionalText("defect_at"))
        }), "nextOffset" to if (rows.size > 50) offset + 50 else null)
    }

    fun create(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("document", "component")
        val d = LearningContentModel.validate(args.obj("document"))
        validateReferences(c, scope.studentId, d)
        val component = LearningContentModel.id(args["component"])
        val id = newId()
        c.update("INSERT INTO learning_content(id,student_id,task_id,current_revision,created_at,updated_at) VALUES (?,?,?,1,?,?)",
            id, scope.studentId, d.text("taskId"), now, now)
        save(c, id, 1, d, component, "create", now)
        return read(c, scope.studentId, id)
    }

    fun revise(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("contentId", "expectedRevision", "document", "component", "reason")
        val id = LearningContentModel.id(args["contentId"])
        val h = head(c, scope.studentId, id)
        val expected = LearningPlanModel.revision(args["expectedRevision"])
        if (expected != h.number("current_revision")) reject("version_conflict")
        val old = version(c, id, expected).obj("document")
        val d = LearningContentModel.validate(args.obj("document"))
        for (key in listOf("taskId", "stageRef", "method", "actionKind", "catalogVersion", "skillId", "topicId", "problemId")) {
            if (d[key] != old[key]) reject("content_identity_changed")
        }
        validateReferences(c, scope.studentId, d)
        val component = LearningContentModel.id(args["component"])
        val reason = StudentLearningModel.enum(args["reason"], setOf("rephrase", "simplify", "replace_defect", "validate"))
        val revision = expected + 1
        save(c, id, revision, d, component, reason, now)
        c.update("UPDATE learning_content SET current_revision=?,updated_at=? WHERE id=?", revision, now, id)
        return read(c, scope.studentId, id)
    }

    fun issue(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("contentId", "expectedRevision", "lessonId")
        val id = LearningContentModel.id(args["contentId"])
        val h = head(c, scope.studentId, id)
        val revision = LearningPlanModel.revision(args["expectedRevision"])
        if (revision != h.number("current_revision")) reject("version_conflict")
        val v = version(c, id, revision)
        val d = v.obj("document")
        validateReferences(c, scope.studentId, d)
        if (d.obj("validation").text("status") != "passed") reject("content_not_validated")
        val lessonId = LearningContentModel.id(args["lessonId"])
        val lesson = c.document("SELECT to_jsonb(l) FROM lesson l WHERE student_id=? AND id=?", scope.studentId, lessonId)
            ?: reject("not_owned")
        if (lesson.text("kind") != "regular") reject("content_regular_lesson_required")
        if (v.text("status") == "issued" && v.optionalText("issued_lesson_id") == lessonId) return read(c, scope.studentId, id)
        if (v.text("status") != "prepared") reject("content_not_prepared")
        c.update("UPDATE learning_content_revision SET status='issued',issued_at=?,issued_lesson_id=? WHERE content_id=? AND revision=?",
            now, lessonId, id, revision)
        return read(c, scope.studentId, id)
    }

    fun markDefective(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("contentId", "revision", "reason", "component")
        val id = LearningContentModel.id(args["contentId"])
        head(c, scope.studentId, id)
        val revision = LearningPlanModel.revision(args["revision"])
        val v = version(c, id, revision)
        val reason = StudentLearningModel.enum(args["reason"], setOf("ambiguous_condition", "incorrect_example",
            "level_mismatch", "other_content_error"))
        val component = LearningContentModel.id(args["component"])
        if (v.text("status") == "defective" && v.optionalText("defect_reason") == reason) return read(c, scope.studentId, id, revision)
        if (v.text("status") != "issued") reject("content_not_issued")
        c.update("""UPDATE learning_content_revision SET status='defective',defect_reason=?,defect_component=?,defect_at=?
            WHERE content_id=? AND revision=?""", reason, component, now, id, revision)
        c.update("UPDATE learning_content SET updated_at=? WHERE id=?", now, id)
        return read(c, scope.studentId, id, revision)
    }

    private fun save(c: Connection, id: String, revision: Long, d: JsonObject, component: String, reason: String, now: Instant) {
        c.update("""INSERT INTO learning_content_revision(content_id,revision,document,status,component,reason,created_at)
            VALUES (?,?,?::jsonb,'prepared',?,?,?)""", id, revision, d, component, reason, now)
    }

    private fun validateReferences(c: Connection, student: String, d: JsonObject) {
        val ref = d.obj("stageRef")
        val taskId = d.text("taskId")
        val resolved = LearningPlans().resolve(c, student, taskId, ref.text("planId"),
            ref.number("planRevision"), ref.text("stageId"))
        val task = resolved.obj("task")
        val currentPlan = task.obj("details").obj("plan")
        if (currentPlan.text("id") != ref.text("planId") || currentPlan.number("revision") != ref.number("planRevision")) {
            reject("stale_content_stage")
        }
        if (resolved.obj("stage").text("method") != d.text("method")) reject("content_method_mismatch")
        for ((wire, column) in listOf("catalogVersion" to "catalog_version", "skillId" to "skill_id",
            "topicId" to "topic_id", "problemId" to "problem_id")) {
            if (d.text(wire) != task.text(column)) reject("content_task_mismatch")
        }
    }
}
