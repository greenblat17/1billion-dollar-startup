package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** Select only an existing SP-09 Task and verified SP-13 material for this regular call. */
internal class RegularLessonAdmission {
    /** A saved teaching attempt, not admission alone, starts the existing Task. */
    fun markPracticed(c: Connection, scope: ExecutionScope, observation: JsonObject, now: Instant) {
        if (scope.role != "voice" || observation.text("practice") != "regular" ||
            observation.optionalText("lessonId") != scope.lessonId ||
            observation.optionalText("actionId") == null) return
        val taskId = observation.optionalText("taskId") ?: return
        val task = LearningTasks().get(c, scope.studentId, taskId)
        if (task.text("lifecycle") !in setOf("Identified", "Reopened")) return
        LearningTasks().change(c, scope.copy(role = "operator"), "transition", json(
            "taskId" to taskId, "expectedRevision" to task.number("revision"),
            "component" to "sp16.regular_lesson", "reason" to "saved_teaching_attempt",
            "decisionId" to observation.text("anchorEventId"), "lifecycle" to "In Progress"), now)
    }

    fun prepare(c: Connection, student: String, lessonId: String, now: Instant): JsonObject {
        val priority = c.currentTeachingPriority(student)
        val previouslyIssued = c.document("""SELECT jsonb_build_object('id',h.id,'revision',v.revision,
            'status',v.status,'document',v.document,'issuedLessonId',v.issued_lesson_id)
            FROM learning_content h JOIN learning_content_revision v ON v.content_id=h.id
            WHERE h.student_id=? AND v.status='issued' AND v.issued_lesson_id=?
            ORDER BY v.issued_at DESC LIMIT 1""", student, lessonId)
        val nextId = previouslyIssued?.obj("document")?.optionalText("taskId")
            ?: priority?.obj("document")?.optionalText("nextTaskId")
        val task = nextId?.let { c.document("SELECT to_jsonb(t) FROM learning_task t WHERE student_id=? AND id=? AND lifecycle<>'Mastered'", student, it) }
        val plan = task?.obj("details")?.get("plan") as? JsonObject
        val planId = plan?.optionalText("id")
        val current = planId?.let { c.document("SELECT to_jsonb(p) FROM learning_plan p WHERE student_id=? AND id=? AND task_id=?", student, it, nextId) }
        val stageId = current?.optionalText("current_stage_id")
        val content = if (task == null) null else previouslyIssued ?: if (current != null && stageId != null) {
            val prepared = c.document("""SELECT jsonb_build_object('id',h.id,'revision',v.revision,'status',v.status,
                'document',v.document,'issuedLessonId',v.issued_lesson_id) FROM learning_content h
                JOIN learning_content_revision v ON v.content_id=h.id AND v.revision=h.current_revision
                WHERE h.student_id=? AND h.task_id=? AND v.document->'stageRef'->>'planId'=?
                AND (v.document->'stageRef'->>'planRevision')::bigint=? AND v.document->'stageRef'->>'stageId'=?
                AND v.document->'validation'->>'status'='passed'
                AND (v.status='prepared' OR v.status='issued' AND v.issued_lesson_id=?)
                ORDER BY CASE WHEN v.issued_lesson_id=? THEN 0 ELSE 1 END,v.created_at DESC LIMIT 1""",
                student, nextId, planId, current.number("revision"), stageId, lessonId, lessonId)
            if (prepared != null && prepared.text("status") == "prepared") {
                LearningContent().issue(c, ExecutionScope(student, "operator", 0),
                    json("contentId" to prepared.text("id"), "expectedRevision" to prepared.number("revision"), "lessonId" to lessonId), now).obj("content")
            } else prepared
        } else null
        // A selected Task Stage must not silently become generic conversation while
        // its validated material is still being generated or has failed validation.
        if (task != null && current != null && stageId != null && content == null) reject("teaching_content_not_ready")
        return json("task" to task, "plan" to current?.let { LearningPlans().read(c, student, it.text("id")).obj("plan") },
            "score" to if (current != null) LearningScores().read(c, student, task.text("id")) else null,
            "issuedTeachingContent" to content)
    }
}
