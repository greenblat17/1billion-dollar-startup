package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Authoritative student learning profile. Only trusted operators can write until future assessment/onboarding integration. */
internal class StudentLearningState {
    fun read(c: Connection, student: String, now: Instant): JsonObject {
        val row = c.document("SELECT to_jsonb(s) FROM student_learning_state s WHERE student_id=?", student)
        val provenance = row?.obj("provenance") ?: json()
        return json("schemaVersion" to 1, "revision" to (row?.number("revision") ?: 0),
            "profile" to (row?.obj("profile") ?: StudentLearningModel.empty()),
            "goal" to c.document("SELECT to_jsonb(g) FROM learning_goal g WHERE student_id=? ORDER BY revision DESC LIMIT 1", student),
            "provenance" to JsonObject(provenance.mapValues { (_, value) ->
                val item = StudentLearningModel.objectValue(value)
                JsonObject(item + json("sources" to StudentLearningSources.project(c, student,
                    StudentLearningModel.sources(item["sources"]), now)))
            }), "updatedAt" to row?.optionalText("updated_at"))
    }

    private fun changedPaths(patch: JsonObject, prefix: String = ""): List<String> = patch.flatMap { (key, value) ->
        val path = if (prefix.isEmpty()) key else "$prefix.$key"
        if (value is JsonObject) changedPaths(value, path) else listOf(path)
    }

    fun update(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant,
               allowUnknownSystemReset: Boolean = false): JsonObject {
        requireRole(scope, "operator")
        args.only("expectedRevision", "patch", "goal", "component", "sources")
        val current = read(c, scope.studentId, now)
        if (args.number("expectedRevision") != current.number("revision")) reject("version_conflict")
        val patch = StudentLearningModel.objectValue(args["patch"])
        if (patch.isEmpty() && !args.containsKey("goal")) reject("empty_learning_update")
        val profile = StudentLearningModel.patch(current.obj("profile"), patch)
        val component = StudentLearningModel.requiredString(args["component"], 100)
        val sources = StudentLearningSources.validate(c, scope.studentId, args["sources"], now)
        if (sources.isEmpty() && !(allowUnknownSystemReset && isUnknownSystemReset(patch))) reject("learning_source_required")
        val metadata = json("component" to component, "recordedAt" to now.toString(), "sources" to sources)
        // Persist raw refs only, not the dynamic availability projection returned by read().
        val oldProvenance = c.document("SELECT provenance FROM student_learning_state WHERE student_id=?", scope.studentId) ?: json()
        val provenance = JsonObject(oldProvenance + changedPaths(patch).associateWith { metadata } +
            if (args.containsKey("goal")) mapOf("goal" to metadata) else emptyMap())
        if (args.containsKey("goal")) {
            val goal = StudentLearningModel.stringOrNull(args["goal"], 2000)
            if (goal != null && goal.isBlank()) reject("invalid_goal")
            c.update("INSERT INTO learning_goal(student_id,revision,content,source_event_id,created_at) VALUES (?,?,?::jsonb,?,?)",
                scope.studentId, c.goalRevision(scope.studentId) + 1, json("value" to goal, "sources" to sources),
                sources.firstOrNull()?.let { StudentLearningModel.objectValue(it).text("sourceEventId") }, now)
        }
        c.update("""INSERT INTO student_learning_state(student_id,revision,profile,provenance,updated_at) VALUES (?,?,?::jsonb,?::jsonb,?)
            ON CONFLICT(student_id) DO UPDATE SET revision=EXCLUDED.revision,profile=EXCLUDED.profile,
            provenance=EXCLUDED.provenance,updated_at=EXCLUDED.updated_at""",
            scope.studentId, current.number("revision") + 1, profile, provenance, now)
        c.update("UPDATE lesson_plan SET status='stale' WHERE student_id=? AND status='ready'", scope.studentId)
        c.invalidatePreparation(scope.studentId, now)
        return read(c, scope.studentId, now)
    }

    private fun isUnknownSystemReset(patch: JsonObject): Boolean {
        if (patch.keys != setOf("currentLevels")) return false
        val levels = patch.obj("currentLevels")
        if (levels.keys != setOf("speaky")) return false
        val system = levels.obj("speaky")
        if (system.keys != setOf("overall", "skills")) return false
        val skills = system.obj("skills")
        if (skills.keys != setOf("speaking", "listening", "grammar", "vocabulary")) return false
        return (listOf(system.obj("overall")) + skills.values.map { StudentLearningModel.objectValue(it) })
            .all { it.text("level") == "Unknown" }
    }
}

internal fun Connection.goalRevision(student: String): Long = rows(
    "SELECT COALESCE(max(revision),0) FROM learning_goal WHERE student_id=?", student,
) { it.getLong(1) }.single()
