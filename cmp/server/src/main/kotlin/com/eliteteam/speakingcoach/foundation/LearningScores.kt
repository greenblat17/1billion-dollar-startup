package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** SP-19 score state. Observation projections replace their previous revision in the same transaction. */
internal class LearningScores {
    /** Source expiry and privacy erase do not create Observation revisions. Reconcile their stored projections. */
    fun reconcileStored(c: Connection, scope: ExecutionScope, now: Instant) {
        val rows = c.documents("""SELECT to_jsonb(e) FROM learning_score_event e WHERE student_id=?
            AND observation_id IS NOT NULL ORDER BY task_id,plan_revision,stage_id,recorded_at""", scope.studentId)
        val affected = mutableSetOf<String>()
        for (event in rows) {
            val observationId = event.text("observation_id")
            val exists = c.document("SELECT to_jsonb(o) FROM learning_observation o WHERE student_id=? AND id=?",
                scope.studentId, observationId) != null
            val currentPlan = c.document("""SELECT to_jsonb(p) FROM learning_plan p WHERE student_id=? AND task_id=?
                AND id=?""", scope.studentId, event.text("task_id"), event.text("plan_id"))
            val current = currentPlan?.number("revision") == event.number("plan_revision")
            val historicalUsable = if (exists && !current) {
                val projected = LearningObservations(learningPlanObservationStage).read(c, scope.studentId,
                    observationId, now).obj("observation")
                projected["evidenceUsable"]?.jsonPrimitive?.content == "true" &&
                    projected.text("referenceStatus") == "verified"
            } else false
            if (exists && current) reconcileObservation(c, scope, observationId, now)
            else if (!historicalUsable) {
                c.update("DELETE FROM learning_score_event WHERE student_id=? AND observation_id=?", scope.studentId, observationId)
                replayStage(c, scope.studentId, event.text("task_id"), event.text("plan_id"),
                    event.number("plan_revision"), event.text("stage_id"), now)
                affected.add(event.text("task_id"))
            }
        }
        affected.forEach { reconcileProgress(c, scope, it, "source-redaction", now) }
    }

    fun reconcileObservation(c: Connection, scope: ExecutionScope, observationId: String, now: Instant,
        stages: ObservationStageResolver = learningPlanObservationStage) {
        val observation = LearningObservations(learningPlanObservationStage).get(c, scope.studentId, observationId)
        val d = observation.obj("document")
        val previous = c.document("SELECT to_jsonb(e) FROM learning_score_event e WHERE student_id=? AND observation_id=?",
            scope.studentId, observationId)
        val taskId = d.optionalText("taskId")
        val candidate = taskId?.let { task ->
            val projected = LearningObservations(stages).read(c, scope.studentId, observationId, now).obj("observation")
            val ref = d["stageRef"] as? JsonObject
            val plan = c.document("SELECT to_jsonb(p) FROM learning_plan p WHERE student_id=? AND task_id=?",
                scope.studentId, task)
            val definition = plan?.let { c.document("SELECT definition FROM learning_plan_revision WHERE plan_id=? AND revision=?",
                it.text("id"), it.number("revision")) }
            val stage = if (ref != null && plan != null && definition != null &&
                ref.text("planId") == plan.text("id") && ref.number("planRevision") == plan.number("revision"))
                LearningPlanModel.stages(definition).find { it.text("id") == ref.text("stageId") } else null
            if (stage != null && d.text("route") == "stage_progress" &&
                projected["evidenceUsable"]?.jsonPrimitive?.content == "true" && projected.text("referenceStatus") == "verified" &&
                (stage.text("kind") == "numeric" && projected["scoringEligible"]?.jsonPrimitive?.content == "true" ||
                    stage.text("kind") == "binary" && d.text("outcome") == "independent_correct"))
                Triple(plan!!, stage, task) else null
        }
        if (previous != null && (candidate == null || previous.text("plan_id") != candidate.first.text("id") ||
                previous.number("plan_revision") != candidate.first.number("revision") ||
                previous.text("stage_id") != candidate.second.text("id"))) {
            c.update("DELETE FROM learning_score_event WHERE student_id=? AND observation_id=?", scope.studentId, observationId)
            replayStage(c, scope.studentId, previous.text("task_id"), previous.text("plan_id"),
                previous.number("plan_revision"), previous.text("stage_id"), now)
        }
        if (candidate != null) {
            val (plan, stage, task) = candidate
            val planId = plan.text("id")
            val revision = plan.number("revision")
            val stageId = stage.text("id")
            val kind = stage.text("kind")
            c.update("""INSERT INTO learning_score_stage(student_id,task_id,plan_id,plan_revision,stage_id,stage_attempt_id,
                kind,score,score_source,updated_at) VALUES (?,?,?,?,?,?,?,?, 'observation',?) ON CONFLICT DO NOTHING""",
                scope.studentId, task, planId, revision, stageId, newId(), kind,
                if (kind == "numeric") LearningScoreModel.START else null, now)
            val state = c.document("""SELECT to_jsonb(s) FROM learning_score_stage s WHERE student_id=? AND task_id=?
                AND plan_id=? AND plan_revision=? AND stage_id=?""", scope.studentId, task, planId, revision, stageId)!!
            if (state.text("score_source") != "observation") {
                c.update("""UPDATE learning_score_stage SET score_source='observation',stage_attempt_id=?,attempt_number=1
                    WHERE student_id=? AND task_id=? AND plan_id=? AND plan_revision=? AND stage_id=?""",
                    newId(), scope.studentId, task, planId, revision, stageId)
            }
            val current = c.document("""SELECT to_jsonb(e) FROM learning_score_event e WHERE student_id=? AND observation_id=?""",
                scope.studentId, observationId)
            val eventKind = if (kind == "binary") "binary" else "outcome"
            val outcome = if (kind == "numeric") d.text("outcome") else null
            if (current == null) {
                val active = c.document("""SELECT to_jsonb(s) FROM learning_score_stage s WHERE student_id=? AND task_id=?
                    AND plan_id=? AND plan_revision=? AND stage_id=?""", scope.studentId, task, planId, revision, stageId)!!
                c.update("""INSERT INTO learning_score_event(student_id,task_id,plan_id,plan_revision,stage_id,fixture_event_id,
                    stage_attempt_id,attempt_number,event_kind,outcome,score_before,score_after,completed_after,recorded_at,
                    observation_id,observation_revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    scope.studentId, task, planId, revision, stageId, "observation:$observationId",
                    active.text("stage_attempt_id"), active.number("attempt_number"), eventKind, outcome,
                    if (kind == "numeric") LearningScoreModel.START else null,
                    if (kind == "numeric") LearningScoreModel.START else null, false,
                    Instant.parse(observation.text("created_at")), observationId, observation.number("revision"))
            } else if (current.number("observation_revision") != observation.number("revision") ||
                current.text("event_kind") != eventKind || current.optionalText("outcome") != outcome) {
                c.update("""UPDATE learning_score_event SET observation_revision=?,event_kind=?,outcome=?
                    WHERE student_id=? AND observation_id=?""", observation.number("revision"), eventKind, outcome,
                    scope.studentId, observationId)
            } else if (previous != null) return
            replayStage(c, scope.studentId, task, planId, revision, stageId, now)
        }
        setOfNotNull(previous?.text("task_id"), candidate?.third).forEach {
            reconcileProgress(c, scope, it, observationId, now)
        }
    }

    fun reconcilePlanRevision(c: Connection, scope: ExecutionScope, taskId: String, now: Instant) {
        reconcileProgress(c, scope, taskId, "plan-revision", now)
    }

    private fun reconcileProgress(c: Connection, scope: ExecutionScope, taskId: String, decisionId: String, now: Instant) {
        val task = LearningTasks().get(c, scope.studentId, taskId)
        val progress = read(c, scope.studentId, taskId)["progress"]!!.jsonPrimitive.doubleOrNull!!
        if (task["progress"]!!.jsonPrimitive.doubleOrNull != progress) {
            LearningTasks().change(c, scope.copy(role = "operator"), "progress", json("taskId" to taskId,
                "expectedRevision" to task.number("revision"), "component" to "sp19b_observation",
                "decisionId" to decisionId, "reason" to "score_reconciliation", "progress" to progress), now)
        }
    }

    private fun replayStage(c: Connection, student: String, taskId: String, planId: String, revision: Long, stageId: String, now: Instant) {
        val old = c.document("""SELECT to_jsonb(s) FROM learning_score_stage s WHERE student_id=? AND task_id=?
            AND plan_id=? AND plan_revision=? AND stage_id=?""", student, taskId, planId, revision, stageId) ?: return
        val kind = old.text("kind")
        val source = old.text("score_source")
        val events = c.documents("""SELECT to_jsonb(e) FROM learning_score_event e WHERE student_id=? AND task_id=?
            AND plan_id=? AND plan_revision=? AND stage_id=? AND ((observation_id IS NOT NULL)=? OR event_kind='reopen')
            ORDER BY recorded_at,fixture_event_id""", student, taskId, planId, revision, stageId, source == "observation")
        var score = if (kind == "numeric") LearningScoreModel.START else null
        var completed = false
        var attempt = if (source == "observation") 1L else old.number("attempt_number")
        var attemptId = old.text("stage_attempt_id")
        for (event in events) {
            val before = score
            when (event.text("event_kind")) {
                "reopen" -> {
                    score = LearningScoreModel.START
                    completed = false
                    attempt = event.number("attempt_number")
                    attemptId = event.text("stage_attempt_id")
                }
                "binary" -> completed = true
                "outcome" -> if (!completed) {
                    score = LearningScoreModel.next(score!!, event.text("outcome"))
                    completed = score >= LearningScoreModel.UPPER
                }
            }
            if (event["score_before"]?.jsonPrimitive?.doubleOrNull != before ||
                event["score_after"]?.jsonPrimitive?.doubleOrNull != score ||
                event["completed_after"]?.jsonPrimitive?.content != completed.toString()) {
                c.update("""UPDATE learning_score_event SET score_before=?,score_after=?,completed_after=?
                    WHERE student_id=? AND task_id=? AND plan_id=? AND plan_revision=? AND stage_id=? AND fixture_event_id=?""",
                    before, score, completed, student, taskId, planId, revision, stageId, event.text("fixture_event_id"))
            }
        }
        if (old["score"]?.jsonPrimitive?.doubleOrNull != score ||
            old["completed"]?.jsonPrimitive?.content != completed.toString() ||
            old.number("attempt_number") != attempt || old.text("stage_attempt_id") != attemptId) {
            c.update("""UPDATE learning_score_stage SET score=?,completed=?,attempt_number=?,stage_attempt_id=?,
                state_revision=state_revision+1,updated_at=? WHERE student_id=? AND task_id=? AND plan_id=?
                AND plan_revision=? AND stage_id=?""", score, completed, attempt, attemptId, now,
                student, taskId, planId, revision, stageId)
        }
    }
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
        if (old.text("score_source") != "fixture" && eventKind != "reopen") reject("score_fixture_mixed_with_observation")
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
