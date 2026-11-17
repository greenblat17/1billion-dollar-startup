package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** All writes run under FoundationService's student lock and receipt transaction. */
internal class LearningPlans {
    private fun head(c: Connection, student: String, id: String): JsonObject =
        c.document("SELECT to_jsonb(p) FROM learning_plan p WHERE student_id=? AND id=?", student, id) ?: reject("not_owned")

    private fun version(c: Connection, id: String, revision: Long): JsonObject =
        c.document("SELECT to_jsonb(r) FROM learning_plan_revision r WHERE plan_id=? AND revision=?", id, revision)
            ?: reject("plan_revision_not_found")

    fun read(c: Connection, student: String, id: String, revision: Long? = null): JsonObject {
        val plan = head(c, student, id)
        val selected = version(c, id, revision ?: plan.number("revision"))
        return json("schemaVersion" to 1, "plan" to json("id" to id, "taskId" to plan.text("task_id"),
            "revision" to selected.number("revision"), "definition" to selected.obj("definition"),
            "component" to selected.text("component"), "createdAt" to selected.text("created_at"),
            "currentState" to state(plan)))
    }

    private fun state(plan: JsonObject) = json("planRevision" to plan.number("revision"),
        "stateRevision" to plan.number("state_revision"), "currentStageId" to plan.optionalText("current_stage_id"))

    fun history(c: Connection, student: String, id: String, offset: Int): JsonObject {
        head(c, student, id)
        if (offset !in 0..100000) reject("invalid_offset")
        val rows = c.documents("""SELECT jsonb_build_object('stateRevision',state_revision,'planRevision',plan_revision,
            'currentStageId',current_stage_id,'action',action,'component',component,'reason',reason,'recordedAt',recorded_at)
            FROM learning_plan_history WHERE plan_id=? ORDER BY state_revision DESC LIMIT 51 OFFSET ?""", id, offset)
        return json("schemaVersion" to 1, "items" to JsonArray(rows.take(50)), "nextOffset" to if (rows.size > 50) offset + 50 else null)
    }

    fun resolve(c: Connection, student: String, taskId: String, planId: String, revision: Long, stageId: String): JsonObject {
        val plan = head(c, student, planId)
        if (plan.text("task_id") != taskId) reject("plan_task_mismatch")
        val task = LearningTasks().get(c, student, taskId)
        val stage = LearningPlanModel.stage(version(c, planId, revision).obj("definition"), stageId)
        return json("schemaVersion" to 1,
            "reference" to json("taskId" to taskId, "planId" to planId, "planRevision" to revision, "stageId" to stageId),
            "stage" to stage, "task" to task)
    }

    /** Generic Task patch cannot forge links, cross Tasks or silently roll back the active Plan. */
    fun validateTaskLink(c: Connection, student: String, task: String, ref: JsonObject) {
        val plan = head(c, student, LearningPlanModel.id(ref["id"]))
        if (plan.text("task_id") != task) reject("plan_task_mismatch")
        val revision = LearningPlanModel.revision(ref["revision"])
        version(c, plan.text("id"), revision)
        if (plan.number("revision") != revision) reject("version_conflict")
    }

    fun create(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("taskId", "expectedTaskRevision", "definition", "currentStageId", "component")
        val task = task(c, scope, args)
        val definition = LearningPlanModel.definition(args["definition"])
        val cursor = LearningPlanModel.cursor(args["currentStageId"] ?: JsonNull, definition)
        val component = LearningPlanModel.id(args["component"])
        if (c.document("SELECT to_jsonb(p) FROM learning_plan p WHERE task_id=?", task.text("id")) != null) reject("plan_already_exists")
        val id = newId()
        c.update("""INSERT INTO learning_plan(id,student_id,task_id,revision,state_revision,current_stage_id,created_at,updated_at)
            VALUES (?,?,?,1,1,?,?,?)""", id, scope.studentId, task.text("id"), cursor, now, now)
        saveVersion(c, id, 1, definition, component, now)
        attach(c, scope, task, id, 1, component, now)
        record(c, head(c, scope.studentId, id), "create", component, "definition", now)
        return read(c, scope.studentId, id)
    }

    fun revise(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("taskId", "planId", "expectedTaskRevision", "expectedPlanRevision", "expectedStateRevision",
            "definition", "currentStageId", "component")
        val task = task(c, scope, args)
        val plan = checkedHead(c, scope, args)
        if (plan.text("task_id") != task.text("id")) reject("plan_task_mismatch")
        val id = plan.text("id")
        val definition = LearningPlanModel.definition(args["definition"])
        // Includes retired IDs: they cannot be reused later for a different meaning.
        c.documents("SELECT definition FROM learning_plan_revision WHERE plan_id=?", id).forEach {
            LearningPlanModel.preserveIdentity(it, definition)
        }
        val cursor = LearningPlanModel.cursor(args["currentStageId"] ?: plan["current_stage_id"] ?: JsonNull, definition)
        val component = LearningPlanModel.id(args["component"])
        val revision = plan.number("revision") + 1
        saveVersion(c, id, revision, definition, component, now)
        c.update("UPDATE learning_plan SET revision=?,state_revision=state_revision+1,current_stage_id=?,updated_at=? WHERE id=?",
            revision, cursor, now, id)
        attach(c, scope, task, id, revision, component, now)
        LearningScores().reconcilePlanRevision(c, scope, task.text("id"), now)
        record(c, head(c, scope.studentId, id), "revise", component, "definition", now)
        refreshTeachingIfSelected(c, scope.studentId, plan, now)
        return read(c, scope.studentId, id)
    }

    fun select(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("planId", "expectedPlanRevision", "expectedStateRevision", "currentStageId", "reason", "component")
        val plan = checkedHead(c, scope, args)
        val definition = version(c, plan.text("id"), plan.number("revision")).obj("definition")
        val cursor = LearningPlanModel.cursor(args["currentStageId"], definition)
        val component = LearningPlanModel.id(args["component"])
        val reason = StudentLearningModel.enum(args["reason"], setOf("plan_transition", "student_request"))
        LearningPlanModel.transition(definition, plan.optionalText("current_stage_id"), cursor, reason)
        if (cursor != plan.optionalText("current_stage_id")) {
            c.update("UPDATE learning_plan SET current_stage_id=?,state_revision=state_revision+1,updated_at=? WHERE id=?",
                cursor, now, plan.text("id"))
            record(c, head(c, scope.studentId, plan.text("id")), "select-stage", component, reason, now)
            refreshTeachingIfSelected(c, scope.studentId, plan, now)
        }
        return read(c, scope.studentId, plan.text("id"))
    }

    private fun refreshTeachingIfSelected(c: Connection, student: String, previous: JsonObject, now: Instant) {
        val taskId = c.currentTeachingPriority(student)?.obj("document")?.optionalText("nextTaskId")
        if (taskId == previous.text("task_id")) {
            c.refreshRegularTeachingPreparation(student, "plan:${previous.text("id")}:${previous.number("state_revision") + 1}", now)
        }
    }

    private fun task(c: Connection, scope: ExecutionScope, args: JsonObject): JsonObject {
        val task = LearningTasks().get(c, scope.studentId, LearningPlanModel.id(args["taskId"]))
        if (LearningPlanModel.revision(args["expectedTaskRevision"]) != task.number("revision")) reject("version_conflict")
        return task
    }

    private fun checkedHead(c: Connection, scope: ExecutionScope, args: JsonObject): JsonObject {
        val plan = head(c, scope.studentId, LearningPlanModel.id(args["planId"]))
        if (LearningPlanModel.revision(args["expectedPlanRevision"]) != plan.number("revision") ||
            LearningPlanModel.revision(args["expectedStateRevision"]) != plan.number("state_revision")) reject("version_conflict")
        return plan
    }

    private fun saveVersion(c: Connection, id: String, revision: Long, definition: JsonObject, component: String, now: Instant) {
        c.update("INSERT INTO learning_plan_revision(plan_id,revision,definition,component,created_at) VALUES (?,?,?::jsonb,?,?)",
            id, revision, definition, component, now)
    }

    private fun attach(c: Connection, scope: ExecutionScope, task: JsonObject, id: String, revision: Long, component: String, now: Instant) {
        LearningTasks().change(c, scope, "update", json("taskId" to task.text("id"), "expectedRevision" to task.number("revision"),
            "component" to component, "patch" to json("plan" to json("id" to id, "revision" to revision))), now)
    }

    private fun record(c: Connection, plan: JsonObject, action: String, component: String, reason: String, now: Instant) {
        c.update("""INSERT INTO learning_plan_history(plan_id,state_revision,plan_revision,current_stage_id,action,component,reason,recorded_at)
            VALUES (?,?,?,?,?,?,?,?)""", plan.text("id"), plan.number("state_revision"), plan.number("revision"),
            plan.optionalText("current_stage_id"), action, component, reason, now)
    }
}
