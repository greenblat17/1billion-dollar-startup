package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Operator-only fixture adapter for SP-19A. Real SP-17 consumption belongs to SP-19B. */
internal class LearningScores {
    fun read(c: Connection, student: String, taskId: String): JsonObject {
        val task = LearningTasks().get(c, student, taskId)
        val plan = c.document("SELECT to_jsonb(p) FROM learning_plan p WHERE student_id=? AND task_id=?", student, taskId)
            ?: reject("score_plan_required")
        val revision = plan.number("revision")
        val definition = c.document("SELECT definition FROM learning_plan_revision WHERE plan_id=? AND revision=?",
            plan.text("id"), revision) ?: reject("plan_revision_not_found")
        val rows = c.documents("""SELECT to_jsonb(s) FROM learning_score_stage s WHERE student_id=? AND task_id=?
            AND plan_id=? AND plan_revision=?""", student, taskId, plan.text("id"), revision)
        val byId = rows.associateBy { it.text("stage_id") }
        val scores = mutableMapOf<String, Double>()
        val binary = mutableSetOf<String>()
        val stages = LearningPlanModel.stages(definition).map { stage ->
            val id = stage.text("id")
            val row = byId[id]
            val kind = stage.text("kind")
            val completed = row?.get("completed")?.jsonPrimitive?.content == "true"
            if (kind == "binary" && completed) binary.add(id)
            if (kind == "numeric") scores[id] = row?.get("score")?.jsonPrimitive?.doubleOrNull ?: LearningScoreModel.START
            json("stageId" to id, "kind" to kind, "method" to stage.text("method"),
                "score" to if (kind == "numeric") scores[id] else null,
                "completed" to completed, "signal" to if (kind == "numeric") LearningScoreModel.signal(scores[id]!!) else null,
                "stageAttemptId" to row?.optionalText("stage_attempt_id"),
                "attemptNumber" to (row?.number("attempt_number") ?: 1), "stateRevision" to row?.number("state_revision"))
        }
        return json("schemaVersion" to 1, "taskId" to taskId, "planId" to plan.text("id"),
            "planRevision" to revision, "stages" to JsonArray(stages),
            "progress" to LearningScoreModel.progress(definition, scores),
            "masteryReady" to LearningScoreModel.masteryReady(definition, scores, binary),
            "taskRevision" to task.number("revision"), "taskLifecycle" to task.text("lifecycle"))
    }

    fun history(c: Connection, student: String, taskId: String, offset: Int): JsonObject {
        LearningTasks().get(c, student, taskId)
        if (offset !in 0..100000) reject("invalid_offset")
        val rows = c.documents("""SELECT to_jsonb(e) FROM learning_score_event e WHERE student_id=? AND task_id=?
            ORDER BY recorded_at DESC,fixture_event_id DESC LIMIT 51 OFFSET ?""", student, taskId, offset)
        return json("schemaVersion" to 1, "items" to JsonArray(rows.take(50)),
            "nextOffset" to if (rows.size > 50) offset + 50 else null)
    }

    fun fixture(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("taskId", "planId", "planRevision", "stageId", "fixtureEventId", "outcome", "binaryMet", "reopen", "reason")
        val taskId = LearningPlanModel.id(args["taskId"])
        val planId = LearningPlanModel.id(args["planId"])
        val revision = LearningPlanModel.revision(args["planRevision"])
        val stageId = LearningPlanModel.id(args["stageId"])
        val eventId = LearningPlanModel.id(args["fixtureEventId"])
        val reason = args.optionalText("reason")
        if (reason != null && reason !in setOf("pedagogical_review", "manual_correction")) reject("invalid_score_reason")
        val task = LearningTasks().get(c, scope.studentId, taskId)
        if (task.text("lifecycle") == "Mastered") reject("score_task_mastered")
        val plan = c.document("SELECT to_jsonb(p) FROM learning_plan p WHERE student_id=? AND task_id=? AND id=?",
            scope.studentId, taskId, planId) ?: reject("not_owned")
        if (plan.number("revision") != revision) reject("score_plan_revision_conflict")
        val definition = c.document("SELECT definition FROM learning_plan_revision WHERE plan_id=? AND revision=?",
            planId, revision) ?: reject("plan_revision_not_found")
        val stage = LearningPlanModel.stage(definition, stageId)
        val kind = stage.text("kind")
        fun flag(key: String): Boolean? {
            val value = args[key] ?: return null
            val primitive = value as? JsonPrimitive ?: reject("invalid_score_fixture")
            return primitive.booleanOrNull?.takeUnless { primitive.isString } ?: reject("invalid_score_fixture")
        }
        val reopen = flag("reopen")
        val binaryMet = flag("binaryMet")
        val outcome = args.optionalText("outcome")
        val eventKind = when {
            reopen == true && kind == "numeric" && outcome == null && binaryMet == null && !reason.isNullOrBlank() -> "reopen"
            kind == "binary" && binaryMet == true && outcome == null && reopen == null && reason == null -> "binary"
            kind == "numeric" && outcome in LearningScoreModel.outcomes && reopen == null && binaryMet == null && reason == null -> "outcome"
            else -> reject("invalid_score_fixture")
        }
        c.update("""INSERT INTO learning_score_stage(student_id,task_id,plan_id,plan_revision,stage_id,stage_attempt_id,kind,score,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING""", scope.studentId, taskId, planId, revision,
            stageId, newId(), kind, if (kind == "numeric") LearningScoreModel.START else null, now)
        val old = c.document("""SELECT to_jsonb(s) FROM learning_score_stage s WHERE student_id=? AND task_id=?
            AND plan_id=? AND plan_revision=? AND stage_id=?""", scope.studentId, taskId, planId, revision, stageId)!!
        val duplicate = c.document("""SELECT to_jsonb(e) FROM learning_score_event e WHERE student_id=? AND task_id=?
            AND plan_id=? AND plan_revision=? AND stage_id=? AND fixture_event_id=?""",
            scope.studentId, taskId, planId, revision, stageId, eventId)
        if (duplicate != null) {
            if (duplicate.text("event_kind") != eventKind || duplicate.optionalText("outcome") != outcome ||
                duplicate.optionalText("reason") != reason) reject("score_event_conflict")
            return read(c, scope.studentId, taskId)
        }
        val wasCompleted = old["completed"]?.jsonPrimitive?.content == "true"
        if (eventKind == "reopen" && !wasCompleted) reject("score_stage_not_completed")
        val before = if (kind == "numeric") old["score"]!!.jsonPrimitive.doubleOrNull!! else null
        val after = when {
            eventKind == "reopen" -> LearningScoreModel.START
            eventKind == "outcome" && !wasCompleted -> LearningScoreModel.next(before!!, outcome!!)
            else -> before
        }
        val completed = when (eventKind) {
            "reopen" -> false
            "binary" -> true
            else -> wasCompleted || (after != null && after >= LearningScoreModel.UPPER)
        }
        val attempt = old.number("attempt_number") + if (eventKind == "reopen") 1 else 0
        val attemptId = if (eventKind == "reopen") newId() else old.text("stage_attempt_id")
        c.update("""UPDATE learning_score_stage SET score=?,completed=?,attempt_number=?,state_revision=state_revision+1,
            stage_attempt_id=?,updated_at=? WHERE student_id=? AND task_id=? AND plan_id=? AND plan_revision=? AND stage_id=?""",
            after, completed, attempt, attemptId, now, scope.studentId, taskId, planId, revision, stageId)
        c.update("""INSERT INTO learning_score_event(student_id,task_id,plan_id,plan_revision,stage_id,fixture_event_id,
            stage_attempt_id,attempt_number,event_kind,outcome,reason,score_before,score_after,completed_after,recorded_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", scope.studentId, taskId, planId, revision, stageId, eventId,
            attemptId, attempt, eventKind, outcome, reason, before, after, completed, now)
        val result = read(c, scope.studentId, taskId)
        val progress = result["progress"]!!.jsonPrimitive.doubleOrNull!!
        LearningTasks().change(c, scope, "progress", json("taskId" to taskId,
            "expectedRevision" to task.number("revision"), "component" to "sp19a_fixture",
            "decisionId" to eventId, "reason" to "fixture_score", "progress" to progress), now)
        return read(c, scope.studentId, taskId)
    }
}
