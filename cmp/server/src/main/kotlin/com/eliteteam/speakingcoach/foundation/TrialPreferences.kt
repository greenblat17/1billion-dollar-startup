package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Only learner-reported workload. Never grants the voice model general learning write access. */
internal class TrialPreferences {
    fun update(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        args.only("lessonId", "callSessionId", "sourceId", "expectedRevision", "changes")
        c.activeTrial(scope, args, now)
        requireV55(c.lesson(scope, args.text("lessonId")))
        val source = sourceMessage(c, scope, args.text("sourceId"), now)
        if (source.text("role") != "user" || c.document("SELECT to_jsonb(e) FROM lesson_event e WHERE lesson_id=? AND event_id=?",
                scope.lessonId, source.text("id")) == null) reject("invalid_evidence")
        val changes = args["changes"] as? JsonArray ?: reject("invalid_preferences")
        if (changes.size !in 1..5) reject("invalid_preferences")
        var intensity = json()
        var homework = json()
        val seen = mutableSetOf<String>()
        val provenance = c.document("SELECT provenance FROM student_learning_state WHERE student_id=?", scope.studentId) ?: json()
        for (element in changes) {
            val change = StudentLearningModel.objectValue(element)
            change.only("field", "value", "evidence")
            val field = change.text("field")
            if (!seen.add(field)) reject("duplicate_preference")
            val quote = StudentLearningModel.requiredString(change["evidence"], 2000)
            if (!source.text("body").contains(quote)) reject("invalid_evidence")
            val value = change["value"] ?: reject("missing_preference")
            val path = if (field in setOf("lessonsPerWeek", "lessonDurationMinutes")) "intensity.$field" else "homework.$field"
            val previous = provenance[path] as? JsonObject
            (previous?.get("sources") as? JsonArray)?.forEach { item ->
                val ref = StudentLearningModel.objectValue(item)
                val old = c.document("SELECT to_jsonb(e) FROM conversation_event e WHERE student_id=? AND id=?",
                    scope.studentId, ref.text("sourceEventId"))
                if (old != null && sourceIsNewer(old, source)) reject("preference_source_superseded")
            }
            when (field) {
                "lessonsPerWeek", "lessonDurationMinutes" -> intensity = JsonObject(intensity + json(field to value))
                "consent", "minutes", "period" -> homework = JsonObject(homework + json(field to value))
                else -> reject("preference_not_allowed")
            }
        }
        var patch = json()
        if (intensity.isNotEmpty()) patch = JsonObject(patch + json("intensity" to intensity))
        if (homework.isNotEmpty()) patch = JsonObject(patch + json("homework" to homework))
        return StudentLearningState().update(c, scope.copy(role = "operator"), json(
            "expectedRevision" to args.number("expectedRevision"), "patch" to patch, "component" to "trial-v55",
            "sources" to JsonArray(listOf(json("type" to "event", "id" to source.text("id"), "revision" to source.number("sequence"))))), now)
    }
}
