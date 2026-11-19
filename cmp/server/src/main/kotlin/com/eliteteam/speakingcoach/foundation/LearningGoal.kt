package com.eliteteam.speakingcoach.foundation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val goalIds = (1..8).map { "G0$it" }.toSet()

internal fun learningGoalContent(value: JsonElement?, previous: JsonObject?, sources: JsonArray): JsonObject {
    if (value !is JsonObject) {
        val legacy = StudentLearningModel.stringOrNull(value, 2000)
        if (legacy != null && legacy.isBlank()) reject("invalid_goal")
        return json("value" to legacy, "sources" to sources)
    }

    value.only("goals", "mode", "personal_goal")
    val goals = value["goals"] as? JsonArray ?: reject("invalid_goal_ids")
    if (goals.size !in 1..goalIds.size || goals.toSet().size != goals.size) reject("invalid_goal_ids")
    goals.forEach { StudentLearningModel.enum(it, goalIds) }
    val mode = StudentLearningModel.enum(value["mode"], setOf("replace", "extend"))
    val quote = StudentLearningModel.requiredString(value["personal_goal"], 2000)
    val previousQuote = previous?.optionalText("personal_goal")?.takeIf { mode == "extend" }
    // Keep the original value field for consumers of existing goal revisions.
    if (previous == null || previousQuote == null)
        return json("value" to quote, "personal_goal" to quote, "goals" to goals, "sources" to sources)
    val personalGoal = "$previousQuote\n$quote"
    val previousGoals = previous["goals"] as? JsonArray ?: reject("invalid_goal_ids")
    val mergedGoals = JsonArray((previousGoals + goals).distinct())
    val mergedSources = StudentLearningModel.sources(
        JsonArray((StudentLearningModel.sources(previous["sources"]) + sources).distinct()),
    )
    return json("value" to personalGoal, "personal_goal" to personalGoal, "goals" to mergedGoals, "sources" to mergedSources)
}
