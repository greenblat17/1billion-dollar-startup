package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** A learner-requested replacement within the current Task Stage. */
internal class RequestedTeachingAction {
    fun issue(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "voice")
        args.only("lessonId", "sourceId", "previousContentId", "previousRevision", "expectedPlanStateRevision", "document", "review")
        val lessonId = LearningContentModel.id(args["lessonId"])
        if (scope.lessonId != lessonId) reject("not_owned")
        val lesson = c.lesson(scope, lessonId)
        if (lesson.text("kind") != "regular" || lesson.text("status") != "in_progress") reject("invalid_state")
        if (c.document("""SELECT to_jsonb(s) FROM call_session s WHERE s.student_id=? AND s.lesson_id=?
            AND s.scope_digest=? AND s.status='connected' AND s.lease_until>?""",
            scope.studentId, lessonId, scope.tokenDigest, now) == null) reject("not_owned")
        val sourceId = LearningContentModel.id(args["sourceId"])
        sourceMessage(c, scope, sourceId, now)
        if (c.document("""SELECT to_jsonb(e) FROM lesson_event e JOIN call_session s ON s.id=e.call_session_id
            WHERE e.student_id=? AND e.lesson_id=? AND e.event_id=? AND s.scope_digest=?""",
            scope.studentId, lessonId, sourceId, scope.tokenDigest) == null) reject("not_owned")
        val review = args.obj("review")
        review.only("passed", "checkedAlternatives", "reasons", "rationale")
        if (review["passed"]?.toString() != "true" || review["checkedAlternatives"]?.toString() != "true" ||
            review["reasons"]?.toString() != "[]") reject("content_not_validated")
        val previous = LearningContent().read(c, scope.studentId, LearningContentModel.id(args["previousContentId"])).obj("content")
        if (previous.number("revision") != LearningPlanModel.revision(args["previousRevision"]) ||
            previous.text("status") != "issued" || previous.optionalText("issuedLessonId") != lessonId) reject("version_conflict")
        val old = previous.obj("document")
        val latest = c.rows("""SELECT h.id FROM learning_content h JOIN learning_content_revision v ON v.content_id=h.id
            WHERE h.student_id=? AND v.status='issued' AND v.issued_lesson_id=? ORDER BY v.issued_at DESC,h.id DESC LIMIT 1""",
            scope.studentId, lessonId) { it.getString(1) }.firstOrNull()
        if (latest != previous.text("id")) reject("version_conflict")
        val document = LearningContentModel.validate(args.obj("document"))
        if (document.obj("validation").text("status") != "passed" ||
            document.obj("validation").text("component") != "sp13b.content_generation") reject("content_not_validated")
        if (document.text("taskId") != old.text("taskId") || document.obj("stageRef") != old.obj("stageRef") ||
            document.text("method") != old.text("method") ||
            document.text("actionKind") == old.text("actionKind")) reject("invalid_action_transition")
        val task = LearningTasks().get(c, scope.studentId, document.text("taskId"))
        if (task.obj("details").obj("plan").text("id") != document.obj("stageRef").text("planId") ||
            task.obj("details").obj("plan").number("revision") != document.obj("stageRef").number("planRevision")) reject("stale_content_stage")
        val plan = LearningPlans().read(c, scope.studentId, document.obj("stageRef").text("planId")).obj("plan")
        if (plan.obj("currentState").optionalText("currentStageId") != document.obj("stageRef").text("stageId") ||
            plan.obj("currentState").number("stateRevision") != args.number("expectedPlanStateRevision")) reject("stale_content_stage")
        // create validates catalog, stage ownership, method, document shape, and Task identity.
        val created = LearningContent().create(c, scope.copy(role = "operator"),
            json("document" to document, "component" to "sp13b.content_generation"), now).obj("content")
        return LearningContent().issue(c, scope.copy(role = "operator"),
            json("contentId" to created.text("id"), "expectedRevision" to created.number("revision"),
                "lessonId" to lessonId), now)
    }
}
