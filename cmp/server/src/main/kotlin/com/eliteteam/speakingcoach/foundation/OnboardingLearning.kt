package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Narrow text capability; the model never receives operator credentials or assessment write access. */
internal class OnboardingLearning {
    fun update(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "text")
        args.only("sourceId", "expectedRevision", "changes")
        val source = sourceMessage(c, scope, args.text("sourceId"), now)
        val changes = args["changes"] as? JsonArray ?: reject("invalid_onboarding_changes")
        if (changes.isEmpty() || changes.size > 4) reject("invalid_onboarding_changes")
        var patch = json()
        var goal = json()
        val seen = mutableSetOf<String>()
        for (element in changes) {
            val change = StudentLearningModel.objectValue(element)
            change.only("field", "value", "explanation", "evidence")
            val field = change.text("field")
            if (!seen.add(field)) reject("duplicate_onboarding_field")
            val evidence = StudentLearningModel.requiredString(change["evidence"], 2000)
            if (!source.text("body").contains(evidence)) reject("invalid_evidence")
            val value = change["value"] ?: reject("missing_onboarding_value")
            val explanation = StudentLearningModel.stringOrNull(change["explanation"], 2000)
            when (field) {
                "goal" -> goal = json("goal" to value)
                "targetLevel", "deadline" -> patch = JsonObject(patch + json(field to value))
                "selfAssessment" -> patch = JsonObject(patch + json("currentLevels" to json(
                    "selfAssessment" to json("overall" to json("level" to value, "explanation" to explanation)))))
                else -> reject("onboarding_field_not_allowed")
            }
        }
        val command = JsonObject(json("expectedRevision" to args.number("expectedRevision"), "patch" to patch,
            "component" to "telegram-onboarding", "sources" to JsonArray(listOf(json(
                "type" to "event", "id" to source.text("id"), "revision" to source.number("sequence"))))) + goal)
        return StudentLearningState().update(c, scope.copy(role = "operator"), command, now)
    }
}
