package com.eliteteam.speakingcoach.foundation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Storage invariants only; pedagogical mastery and score mathematics belong to SP-19. */
internal object LearningTaskModel {
    val states = setOf("Identified", "In Progress", "Mastered", "Reopened")
    private val transitions = mapOf("Identified" to "In Progress", "In Progress" to "Mastered",
        "Mastered" to "Reopened", "Reopened" to "In Progress")

    fun transition(previous: String, next: String) {
        if (next !in states || (previous != next && transitions[previous] != next)) reject("invalid_task_transition")
    }

    fun positive(value: JsonElement?): Long {
        val number = value as? JsonPrimitive ?: reject("invalid_task_revision")
        return number.longOrNull?.takeIf { !number.isString && it > 0 } ?: reject("invalid_task_revision")
    }

    fun progress(value: JsonElement?): Double {
        val number = value as? JsonPrimitive ?: reject("invalid_task_progress")
        return number.doubleOrNull?.takeIf { !number.isString && it.isFinite() && it in 0.0..100.0 }
            ?: reject("invalid_task_progress")
    }

    fun details(current: JsonObject, patch: JsonObject): JsonObject {
        patch.only("priority", "plan", "actions", "homework")
        patch["priority"]?.let { value ->
            if (value != JsonNull) {
                val number = value as? JsonPrimitive ?: reject("invalid_task_priority")
                if (number.isString || number.longOrNull == null) reject("invalid_task_priority")
            }
        }
        patch["plan"]?.let { if (it != JsonNull) reference(it) }
        for (key in listOf("actions", "homework")) patch[key]?.let { value ->
            val list = value as? JsonArray ?: reject("invalid_task_links")
            if (list.size > 100) reject("too_many_task_links")
            list.forEach { reference(it) }
            if (list.map { (it as JsonObject).text("id") }.toSet().size != list.size) reject("duplicate_task_link")
        }
        return JsonObject(current + patch)
    }

    private fun reference(value: JsonElement) {
        val ref = value as? JsonObject ?: reject("invalid_task_link")
        ref.only("id", "revision")
        StudentLearningModel.requiredString(ref["id"], 250)
        ref["revision"]?.let { positive(it) }
    }

    fun emptyDetails() = json("priority" to null, "plan" to null, "actions" to JsonArray(emptyList()), "homework" to JsonArray(emptyList()))
}
