package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** SP-09 decision state. SP-04 owns Task lifecycle; SP-10 owns timeline. */
internal class RoadmapPriority {
    fun read(c: Connection, student: String): JsonObject {
        val roadmap = PersonalizedRoadmap().read(c, student)
        val current = c.document("SELECT to_jsonb(p) FROM learning_roadmap_priority p WHERE student_id=?", student)
        val decisions = c.documents("""SELECT jsonb_build_object('topicId',topic_id,'problemId',problem_id,
            'decision',decision,'taskId',task_id,'reason',reason,'decidedAt',decided_at)
            FROM learning_roadmap_problem_decision WHERE student_id=? ORDER BY decided_at,topic_id,problem_id""", student)
        val nextId = current?.obj("document")?.optionalText("nextTaskId")
        val masteredNext = nextId != null && c.document("SELECT to_jsonb(t) FROM learning_task t WHERE student_id=? AND id=?",
            student, nextId)?.text("lifecycle") == "Mastered"
        return json("schemaVersion" to 1, "roadmapRevision" to roadmap.number("revision"),
            "priority" to current, "stale" to (current != null && (current.number("roadmap_revision") != roadmap.number("revision") ||
                current.optionalText("stale_at") != null || masteredNext)),
            "problemDecisions" to JsonArray(decisions))
    }

    fun decide(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("topicId", "problemId", "skillId", "decision", "source", "reason", "decisionId")
        val source = args.text("source")
        if (source !in setOf("learner_request", "learner_response")) reject("invalid_priority_source")
        val decision = args.text("decision")
        if (decision !in setOf("accepted", "declined")) reject("invalid_priority_decision")
        val reason = StudentLearningModel.requiredString(args["reason"], 2000)
        val roadmap = PersonalizedRoadmap().read(c, scope.studentId)
        val version = roadmap.text("catalog_version")
        val topic = StudentLearningModel.requiredString(args["topicId"], 120)
        val problem = StudentLearningModel.requiredString(args["problemId"], 120)
        val skill = StudentLearningModel.requiredString(args["skillId"], 120)
        val topicRow = c.document("SELECT to_jsonb(t) FROM learning_catalog_topic t WHERE version=? AND id=?",
            version, topic) ?: reject("invalid_priority_catalog_links")
        val problemRow = c.document("SELECT to_jsonb(p) FROM learning_catalog_problem p WHERE version=? AND id=?",
            version, problem) ?: reject("invalid_priority_catalog_links")
        if (topicRow.text("skill_id") != skill || problemRow.text("skill_id") != skill) reject("invalid_priority_catalog_links")
        val existing = c.document("""SELECT to_jsonb(d) FROM learning_roadmap_problem_decision d
            WHERE student_id=? AND topic_id=? AND problem_id=?""", scope.studentId, topic, problem)
        if (existing != null && existing.text("decision") == decision) return read(c, scope.studentId)
        val task = if (decision == "accepted") {
            val operator = scope.copy(role = "operator")
            val result = LearningTasks().create(c, operator, json("goalRevision" to roadmap.number("goal_revision"),
                "catalogVersion" to version, "skillId" to skill, "topicId" to topic, "problemId" to problem,
                "component" to "roadmap-priority", "reason" to reason), now).obj("task")
            if (result.text("lifecycle") == "Mastered") {
                LearningTasks().change(c, operator, "transition", json("taskId" to result.text("id"),
                    "expectedRevision" to result.number("revision"), "component" to "roadmap-priority",
                    "reason" to reason, "decisionId" to StudentLearningModel.requiredString(args["decisionId"], 250),
                    "lifecycle" to "Reopened"), now).obj("task")
            } else result
        } else {
            // The SP-18 proposal is only a suggestion. Refusal never creates a Task.
            null
        }
        c.update("""INSERT INTO learning_roadmap_problem_decision
            (student_id,topic_id,problem_id,decision,task_id,reason,decided_at)
            VALUES (?,?,?,?,?,?,?) ON CONFLICT (student_id,topic_id,problem_id) DO UPDATE SET
            decision=EXCLUDED.decision,task_id=EXCLUDED.task_id,reason=EXCLUDED.reason,decided_at=EXCLUDED.decided_at""",
            scope.studentId, topic, problem, decision, task?.text("id"), reason, now)
        c.update("UPDATE learning_roadmap_priority SET stale_at=? WHERE student_id=?", now, scope.studentId)
        return read(c, scope.studentId)
    }

    fun save(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("expectedRevision", "decision")
        val decision = args.obj("decision")
        decision.only("schemaVersion", "roadmapRevision", "trigger", "orderedTasks", "nextTaskId",
            "focusTaskIds", "deferredTaskIds", "declinedProblems", "needsRoadmapReview", "explanation", "scope")
        if (decision.number("schemaVersion") != 1L || decision.text("scope") != "operational_priority" ||
            decision.text("trigger") !in setOf("initial", "post_lesson_review", "goal_change", "learner_request",
                "task_mastered", "proposal_accepted")) reject("invalid_priority_decision")
        val roadmap = PersonalizedRoadmap().read(c, scope.studentId)
        if (decision.number("roadmapRevision") != roadmap.number("revision")) reject("version_conflict")
        val previous = c.document("SELECT to_jsonb(p) FROM learning_roadmap_priority p WHERE student_id=?", scope.studentId)
        if (args.number("expectedRevision") != (previous?.number("revision") ?: 0L)) reject("version_conflict")
        val base = roadmap.obj("document")["sequence"] as? JsonArray ?: reject("invalid_roadmap")
        val baseEntries = base.associate { value ->
            val item = value as? JsonObject ?: reject("invalid_roadmap")
            item.text("taskId") to item
        }
        val baseIds = baseEntries.keys
        val accepted = c.documents("""SELECT to_jsonb(d) FROM learning_roadmap_problem_decision d
            WHERE student_id=? AND decision='accepted' AND task_id IS NOT NULL""", scope.studentId)
            .map { it.text("task_id") }.toSet()
        val ordered = decision["orderedTasks"] as? JsonArray ?: reject("invalid_priority_decision")
        if (ordered.size > 500) reject("invalid_priority_decision")
        val ids = mutableListOf<String>()
        ordered.forEachIndexed { index, value ->
            val entry = value as? JsonObject ?: reject("invalid_priority_decision")
            entry.only("taskId", "priority", "reason", "evidence", "prerequisiteTaskIds")
            val id = entry.text("taskId")
            if (entry.number("priority") != (index + 1).toLong() || entry.text("reason").isBlank() ||
                entry.text("evidence") !in setOf("confirmed", "hypothesis", "topic_confirmed_problem_hypothesis", "known") ||
                id !in baseIds && id !in accepted || id in ids) reject("invalid_priority_decision")
            val task = LearningTasks().get(c, scope.studentId, id)
            if (task.text("lifecycle") == "Mastered") reject("stale_priority_task")
            val baseEntry = baseEntries[id]
            val currentKnowledge = c.document("""SELECT to_jsonb(k) FROM student_topic_knowledge k
                WHERE student_id=? AND catalog_version=? AND topic_id=? ORDER BY revision DESC LIMIT 1""",
                scope.studentId, roadmap.text("catalog_version"), task.text("topic_id"))?.text("status")
                ?: baseEntry?.text("knowledge") ?: "Unknown"
            val expectedEvidence = when {
                currentKnowledge == "Known" && task.text("lifecycle") != "Reopened" -> "known"
                currentKnowledge != "Not Known" -> "hypothesis"
                baseEntry == null -> "topic_confirmed_problem_hypothesis"
                baseEntry["provisional"]?.jsonPrimitive?.booleanOrNull == true -> "topic_confirmed_problem_hypothesis"
                else -> "confirmed"
            }
            if (entry.text("evidence") != expectedEvidence) reject("invalid_priority_evidence")
            val deps = entry["prerequisiteTaskIds"] as? JsonArray ?: reject("invalid_priority_decision")
            if (deps.size > 100 || deps.any { (it as? JsonPrimitive)?.content !in baseIds + accepted } ||
                deps.map { (it as JsonPrimitive).content }.toSet().size != deps.size ||
                deps.any { (it as JsonPrimitive).content == id }) reject("invalid_priority_prerequisite")
            ids.add(id)
        }
        val required = baseIds.filter { id -> LearningTasks().get(c, scope.studentId, id).text("lifecycle") != "Mastered" }.toSet()
        if (!ids.containsAll(required) || !ids.containsAll(accepted.filter { id ->
                LearningTasks().get(c, scope.studentId, id).text("lifecycle") != "Mastered" })) reject("missing_priority_task")
        val next = decision["nextTaskId"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
        val focus = decision["focusTaskIds"] as? JsonArray ?: reject("invalid_priority_decision")
        val deferred = decision["deferredTaskIds"] as? JsonArray ?: reject("invalid_priority_decision")
        val focusIds = focus.map { (it as? JsonPrimitive)?.content ?: reject("invalid_priority_decision") }
        val deferredIds = deferred.map { (it as? JsonPrimitive)?.content ?: reject("invalid_priority_decision") }
        if (next != null && next !in ids || focusIds.any { it !in ids } || deferredIds.any { it !in ids } ||
            focusIds.size != focusIds.toSet().size || deferredIds.size != deferredIds.toSet().size ||
            next in deferredIds || focusIds.any { it in deferredIds }) reject("invalid_priority_decision")
        val declinedRows = c.documents("""SELECT jsonb_build_object('topicId',topic_id,'problemId',problem_id)
            FROM learning_roadmap_problem_decision WHERE student_id=? AND decision='declined'""", scope.studentId)
        val declined = decision["declinedProblems"] as? JsonArray ?: reject("invalid_priority_decision")
        if (declined.toSet() != declinedRows.toSet()) reject("stale_priority_decision")
        if (next != null) {
            val nextTask = LearningTasks().get(c, scope.studentId, next)
            if (declinedRows.any { it.text("topicId") == nextTask.text("topic_id") &&
                    it.text("problemId") == nextTask.text("problem_id") }) reject("declined_priority_task")
            val nextEntry = ordered.first { (it as JsonObject).text("taskId") == next } as JsonObject
            if (nextEntry.text("evidence") == "known" && decision.text("trigger") != "learner_request") reject("known_priority_task")
            val deps = nextEntry["prerequisiteTaskIds"] as JsonArray
            if (deps.any { dependency ->
                    val dep = (dependency as JsonPrimitive).content
                    LearningTasks().get(c, scope.studentId, dep).text("lifecycle") != "Mastered"
                }) reject("priority_prerequisite_unmet")
        }
        if (decision["needsRoadmapReview"]?.jsonPrimitive?.booleanOrNull != (next == null)) reject("invalid_priority_decision")
        if (decision.text("explanation").isBlank()) reject("invalid_priority_decision")
        if (previous?.obj("document") == decision && previous.optionalText("stale_at") == null) return read(c, scope.studentId)
        ordered.forEach { value ->
            val entry = value as JsonObject
            val task = LearningTasks().get(c, scope.studentId, entry.text("taskId"))
            LearningTasks().change(c, scope, "update", json("taskId" to task.text("id"),
                "expectedRevision" to task.number("revision"), "component" to "roadmap-priority",
                "reason" to entry.text("reason"), "patch" to json("priority" to entry.number("priority"))), now)
        }
        if (previous != null) {
            c.update("""INSERT INTO learning_roadmap_priority_history
                (student_id,revision,roadmap_revision,document,recorded_at) VALUES (?,?,?,?::jsonb,?)""",
                scope.studentId, previous.number("revision"), previous.number("roadmap_revision"),
                previous.obj("document"), now)
            c.update("""UPDATE learning_roadmap_priority SET revision=revision+1,roadmap_revision=?,
                document=?::jsonb,stale_at=NULL,updated_at=? WHERE student_id=?""", roadmap.number("revision"), decision, now, scope.studentId)
        } else {
            c.update("""INSERT INTO learning_roadmap_priority(student_id,revision,roadmap_revision,document,updated_at)
                VALUES (?,1,?,?::jsonb,?)""", scope.studentId, roadmap.number("revision"), decision, now)
        }
        return read(c, scope.studentId)
    }
}
