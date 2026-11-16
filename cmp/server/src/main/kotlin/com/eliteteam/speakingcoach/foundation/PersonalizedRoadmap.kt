package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Persists one initial proposal. SP-09 owns subsequent priority changes. */
internal class PersonalizedRoadmap {
    fun knowledgeDigest(c: Connection, student: String, version: String): String = digest(canonical(JsonArray(
        c.documents("""SELECT jsonb_build_object('topicId',topic_id,'revision',revision,'status',status)
            FROM (SELECT DISTINCT ON(topic_id) topic_id,revision,status FROM student_topic_knowledge
            WHERE student_id=? AND catalog_version=? ORDER BY topic_id,revision DESC) latest ORDER BY topic_id""",
            student, version))))

    fun read(c: Connection, student: String): JsonObject =
        c.document("SELECT to_jsonb(r) FROM learning_roadmap r WHERE student_id=?", student)
            ?: reject("roadmap_not_found")

    fun history(c: Connection, student: String, offset: Int): JsonObject {
        if (offset !in 0..100000) reject("invalid_offset")
        read(c, student)
        val rows = c.documents("""SELECT to_jsonb(h) FROM learning_roadmap_history h WHERE student_id=?
            ORDER BY revision DESC LIMIT 51 OFFSET ?""", student, offset)
        return json("schemaVersion" to 1, "items" to JsonArray(rows.take(50)),
            "nextOffset" to if (rows.size > 50) offset + 50 else null)
    }

    fun save(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("learningRevision", "roadmap")
        val expected = args.number("learningRevision")
        val roadmap = args.obj("roadmap")
        roadmap.only("schemaVersion", "catalogVersion", "goalRevision", "targetLevel", "goal",
            "provisional", "sequence", "taskCount", "assumptions", "history", "scope", "timeline")
        if (roadmap.number("schemaVersion") != 1L || roadmap.text("scope") != "initial_roadmap" ||
            roadmap.text("targetLevel") !in LearningCatalogModel.levels) reject("invalid_roadmap")
        val state = StudentLearningState().read(c, scope.studentId, now)
        if (state.number("revision") != expected || state.obj("profile").text("targetLevel") != roadmap.text("targetLevel")) {
            reject("version_conflict")
        }
        val goal = state["goal"] as? JsonObject ?: reject("roadmap_goal_required")
        if (goal.number("revision") != roadmap.number("goalRevision") ||
            goal.obj("content").text("value") != roadmap.text("goal")) reject("version_conflict")
        val version = roadmap.text("catalogVersion")
        val knowledgeDigest = knowledgeDigest(c, scope.studentId, version)
        if (c.document("SELECT to_jsonb(r) FROM learning_catalog_release r WHERE version=? AND published_at IS NOT NULL", version) == null) {
            reject("catalog_not_published")
        }
        val sequence = roadmap["sequence"] as? JsonArray ?: reject("invalid_roadmap")
        if (sequence.size > 500 || roadmap.number("taskCount") != sequence.size.toLong()) reject("invalid_roadmap")
        val ids = mutableSetOf<String>()
        sequence.forEachIndexed { index, value ->
            val entry = value as? JsonObject ?: reject("invalid_roadmap")
            entry.only("topicId", "skillId", "cefr", "problemId", "knowledge", "provisional", "reason",
                "orderReason", "goalFocus", "taskId", "order")
            if (entry.number("order") != (index + 1).toLong() ||
                entry.text("knowledge") !in StudentLearningModel.topicStates ||
                entry.text("cefr") !in LearningCatalogModel.levels ||
                entry.text("reason").isBlank() || entry.text("orderReason").isBlank()) reject("invalid_roadmap")
            val provisional = entry["provisional"]?.jsonPrimitive?.booleanOrNull ?: reject("invalid_roadmap")
            if (entry["goalFocus"]?.jsonPrimitive?.booleanOrNull == null) reject("invalid_roadmap")
            val topic = c.document("SELECT content FROM learning_catalog_topic WHERE version=? AND id=?", version,
                entry.text("topicId")) ?: reject("invalid_roadmap_topic")
            if (topic.text("skillId") != entry.text("skillId") || topic.text("cefr") != entry.text("cefr")) {
                reject("invalid_roadmap_topic")
            }
            val actualKnowledge = c.document("""SELECT to_jsonb(k) FROM student_topic_knowledge k WHERE student_id=?
                AND catalog_version=? AND topic_id=? ORDER BY revision DESC LIMIT 1""", scope.studentId, version,
                entry.text("topicId"))?.text("status") ?: "Unknown"
            if (actualKnowledge != entry.text("knowledge") ||
                actualKnowledge in setOf("Unknown", "Presumed Not Known") && !provisional) reject("version_conflict")
            val taskId = entry.text("taskId")
            if (!ids.add(taskId)) reject("duplicate_roadmap_task")
            val task = LearningTasks().get(c, scope.studentId, taskId)
            if (task.text("topic_id") != entry.text("topicId") || task.text("skill_id") != entry.text("skillId") ||
                task.text("problem_id") != entry.text("problemId") ||
                task.number("goal_revision") != roadmap.number("goalRevision")) reject("invalid_roadmap_task")
            if (actualKnowledge == "Known" && task.text("lifecycle") != "Reopened") reject("known_topic_in_roadmap")
        }
        val previous = c.document("SELECT to_jsonb(r) FROM learning_roadmap r WHERE student_id=?", scope.studentId)
        if (previous != null) {
            if (previous.obj("document") == roadmap) return json("schemaVersion" to 1, "created" to false, "roadmap" to previous)
            if (previous.number("learning_revision") > expected || previous.number("learning_revision") == expected &&
                previous.text("knowledge_digest") == knowledgeDigest) reject("roadmap_already_exists")
            c.update("""INSERT INTO learning_roadmap_history(student_id,revision,goal_revision,learning_revision,
                knowledge_digest,catalog_version,document,recorded_at) VALUES (?,?,?,?,?,?,?::jsonb,?)""", scope.studentId,
                previous.number("revision"), previous.number("goal_revision"), previous.number("learning_revision"),
                previous.text("knowledge_digest"), previous.text("catalog_version"), previous.obj("document"), now)
            c.update("""UPDATE learning_roadmap SET revision=revision+1,goal_revision=?,learning_revision=?,
                knowledge_digest=?,catalog_version=?,document=?::jsonb,created_at=? WHERE student_id=?""",
                roadmap.number("goalRevision"), expected, knowledgeDigest, version, roadmap, now, scope.studentId)
        } else {
            c.update("""INSERT INTO learning_roadmap(student_id,revision,goal_revision,learning_revision,knowledge_digest,catalog_version,document,created_at)
                VALUES (?,1,?,?,?,?,?::jsonb,?)""", scope.studentId, roadmap.number("goalRevision"), expected,
                knowledgeDigest, version, roadmap, now)
        }
        return json("schemaVersion" to 1, "created" to (previous == null), "roadmap" to read(c, scope.studentId))
    }

    fun apply(c: Connection, scope: ExecutionScope, lessonId: String, inputs: JsonObject, proposal: JsonObject, now: Instant): Boolean {
        val lesson = c.lesson(scope, lessonId)
        if (lesson.text("status") != "completed" || lesson.number("transcript_revision") != inputs.number("transcriptRevision")) return false
        val state = StudentLearningState().read(c, scope.studentId, now)
        if (state.number("revision") != inputs.number("learningRevision") ||
            c.goalRevision(scope.studentId) != inputs.number("goalRevision") ||
            knowledgeDigest(c, scope.studentId, inputs.text("catalogVersion")) != inputs.text("knowledgeDigest") ||
            proposal.number("goalRevision") != inputs.number("goalRevision") ||
            proposal.text("catalogVersion") != inputs.text("catalogVersion")) return false
        val entries = proposal["sequence"] as? JsonArray ?: reject("invalid_roadmap")
        if (entries.size > 500) reject("invalid_roadmap")
        val operator = scope.copy(role = "operator")
        val completed = entries.map { value ->
            val candidate = value as? JsonObject ?: reject("invalid_roadmap")
            val task = candidate.optionalText("taskId")?.let { LearningTasks().get(c, scope.studentId, it) }
                ?: LearningTasks().create(c, operator, json(
                    "goalRevision" to inputs.number("goalRevision"), "catalogVersion" to inputs.text("catalogVersion"),
                    "skillId" to candidate.text("skillId"), "topicId" to candidate.text("topicId"),
                    "problemId" to candidate.text("problemId"), "component" to "personalized-roadmap",
                    "reason" to candidate.text("reason")), now).obj("task")
            if (task.text("lifecycle") == "Mastered") reject("stale_roadmap_task")
            val current = if (task.number("goal_revision") != inputs.number("goalRevision")) {
                LearningTasks().change(c, operator, "update", json("taskId" to task.text("id"),
                    "expectedRevision" to task.number("revision"), "component" to "personalized-roadmap",
                    "goalRevision" to inputs.number("goalRevision")), now).obj("task")
            } else task
            JsonObject(candidate + ("taskId" to kotlinx.serialization.json.JsonPrimitive(current.text("id"))))
        }
        val roadmap = JsonObject(proposal + ("sequence" to JsonArray(completed)))
        save(c, operator, json("learningRevision" to inputs.number("learningRevision"), "roadmap" to roadmap), now)
        return true
    }
}
