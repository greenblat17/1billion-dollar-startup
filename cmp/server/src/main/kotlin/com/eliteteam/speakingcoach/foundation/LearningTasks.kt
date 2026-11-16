package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Called inside FoundationService's student lock/receipt transaction. Never chooses tasks or computes scores. */
internal class LearningTasks {
    fun get(c: Connection, student: String, id: String): JsonObject =
        c.document("SELECT to_jsonb(t) FROM learning_task t WHERE student_id=? AND id=?", student, id) ?: reject("not_owned")

    fun list(c: Connection, student: String, topic: String?, problem: String?, state: String?, offset: Int): JsonObject {
        if (offset !in 0..100000) reject("invalid_offset")
        if (state != null && state !in LearningTaskModel.states) reject("invalid_task_state")
        val rows = c.documents("""SELECT to_jsonb(t) FROM learning_task t WHERE student_id=?
            AND (?::text IS NULL OR topic_id=?) AND (?::text IS NULL OR problem_id=?)
            AND (?::text IS NULL OR lifecycle=?) ORDER BY created_at,id LIMIT 51 OFFSET ?""",
            student, topic, topic, problem, problem, state, state, offset)
        return page(rows, offset)
    }

    fun history(c: Connection, student: String, id: String, offset: Int, now: Instant): JsonObject {
        get(c, student, id)
        if (offset !in 0..100000) reject("invalid_offset")
        val rows = c.documents("""SELECT to_jsonb(h) FROM learning_task_history h
            WHERE student_id=? AND task_id=? ORDER BY revision DESC LIMIT 51 OFFSET ?""", student, id, offset)
        return page(rows.map { row -> JsonObject(row + json("sources" to StudentLearningSources.project(c, student,
            StudentLearningModel.sources(row["sources"]), now))) }, offset)
    }

    private fun page(rows: List<JsonObject>, offset: Int) = json("schemaVersion" to 1,
        "items" to JsonArray(rows.take(50)), "nextOffset" to if (rows.size > 50) offset + 50 else null)

    fun create(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("goalRevision", "catalogVersion", "skillId", "topicId", "problemId", "component", "reason", "sources")
        val goal = LearningTaskModel.positive(args["goalRevision"])
        validateGoal(c, scope.studentId, goal)
        val version = StudentLearningModel.requiredString(args["catalogVersion"], 250)
        val skill = StudentLearningModel.requiredString(args["skillId"], 120)
        val topic = StudentLearningModel.requiredString(args["topicId"], 120)
        val problem = StudentLearningModel.requiredString(args["problemId"], 120)
        validateCatalog(c, version, skill, topic, problem)
        val metadata = metadata(c, scope.studentId, args, now)
        // Search across published versions: SP-02 guarantees stable semantic identity of published IDs.
        val existing = c.document("""SELECT to_jsonb(t) FROM learning_task t WHERE student_id=? AND topic_id=? AND problem_id=?
            ORDER BY (lifecycle <> 'Mastered') DESC,created_at DESC,id LIMIT 1""", scope.studentId, topic, problem)
        if (existing != null) return json("schemaVersion" to 1, "created" to false, "task" to existing)
        val id = newId()
        c.update("""INSERT INTO learning_task(id,student_id,goal_revision,catalog_version,skill_id,topic_id,problem_id,
            revision,lifecycle,progress,details,created_at,updated_at) VALUES (?,?,?,?,?,?,?,1,'Identified',0,?::jsonb,?,?)""",
            id, scope.studentId, goal, version, skill, topic, problem, LearningTaskModel.emptyDetails(), now, now)
        val task = get(c, scope.studentId, id)
        record(c, scope.studentId, "create", null, task, metadata, now)
        return json("schemaVersion" to 1, "created" to true, "task" to task)
    }

    fun change(c: Connection, scope: ExecutionScope, action: String, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        val fields = when (action) {
            "update" -> arrayOf("patch", "goalRevision")
            "transition" -> arrayOf("lifecycle", "progress", "decisionId")
            "progress" -> arrayOf("progress", "decisionId")
            else -> reject("unknown_action")
        }
        args.only("taskId", "expectedRevision", "component", "reason", "sources", *fields)
        val old = get(c, scope.studentId, StudentLearningModel.requiredString(args["taskId"], 250))
        if (LearningTaskModel.positive(args["expectedRevision"]) != old.number("revision")) reject("version_conflict")
        val metadata = metadata(c, scope.studentId, args, now)
        var state = old.text("lifecycle")
        if (action == "transition") {
            state = StudentLearningModel.enum(args["lifecycle"], LearningTaskModel.states)
            LearningTaskModel.transition(old.text("lifecycle"), state)
        }
        if (action != "update") StudentLearningModel.requiredString(args["decisionId"], 250)
        val progress = if (args.containsKey("progress")) LearningTaskModel.progress(args["progress"])
            else if (action == "progress") reject("invalid_task_progress") else LearningTaskModel.progress(old["progress"])
        val goal = args["goalRevision"]?.let { LearningTaskModel.positive(it).also { goal -> validateGoal(c, scope.studentId, goal) } }
            ?: old.number("goal_revision")
        val details = if (args.containsKey("patch")) LearningTaskModel.details(old.obj("details"),
            args["patch"] as? JsonObject ?: reject("invalid_task_patch")) else old.obj("details")
        if (state == old.text("lifecycle") && progress == LearningTaskModel.progress(old["progress"]) &&
            goal == old.number("goal_revision") && details == old.obj("details")) return json("schemaVersion" to 1, "task" to old)
        c.update("""UPDATE learning_task SET goal_revision=?,lifecycle=?,progress=?,details=?::jsonb,revision=revision+1,updated_at=?
            WHERE student_id=? AND id=?""", goal, state, progress, details, now, scope.studentId, old.text("id"))
        val task = get(c, scope.studentId, old.text("id"))
        record(c, scope.studentId, action, old, task, metadata, now)
        return json("schemaVersion" to 1, "task" to task)
    }

    private fun metadata(c: Connection, student: String, args: JsonObject, now: Instant): JsonObject = json(
        "component" to StudentLearningModel.requiredString(args["component"], 100),
        "reason" to (args["reason"]?.let { StudentLearningModel.requiredString(it, 2000) } ?: ""),
        "decisionId" to args.optionalText("decisionId"),
        "sources" to StudentLearningSources.validate(c, student, args["sources"] ?: JsonArray(emptyList()), now))

    private fun record(c: Connection, student: String, action: String, old: JsonObject?, task: JsonObject, metadata: JsonObject, now: Instant) {
        c.update("""INSERT INTO learning_task_history(student_id,task_id,revision,action,component,decision_id,reason,sources,
            previous_state,state,recorded_at) VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?)""", student,
            task.text("id"), task.number("revision"), action, metadata.text("component"), metadata.optionalText("decisionId"),
            metadata.text("reason"), metadata["sources"], old, task, now)
    }

    private fun validateGoal(c: Connection, student: String, revision: Long) {
        val goal = c.document("SELECT content FROM learning_goal WHERE student_id=? AND revision=?", student, revision)
        if (goal?.optionalText("value").isNullOrBlank()) reject("task_goal_required")
    }

    private fun validateCatalog(c: Connection, version: String, skill: String, topic: String, problem: String) {
        if (c.document("SELECT to_jsonb(r) FROM learning_catalog_release r WHERE version=? AND published_at IS NOT NULL", version) == null) {
            reject("catalog_not_published")
        }
        val topicRow = c.document("SELECT to_jsonb(t) FROM learning_catalog_topic t WHERE version=? AND id=?", version, topic)
        val problemRow = c.document("SELECT to_jsonb(p) FROM learning_catalog_problem p WHERE version=? AND id=?", version, problem)
        if (topicRow == null || problemRow == null || topicRow.text("skill_id") != skill || problemRow.text("skill_id") != skill) {
            reject("invalid_task_catalog_links")
        }
    }
}
