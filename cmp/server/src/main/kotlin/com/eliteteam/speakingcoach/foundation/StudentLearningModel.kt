package com.eliteteam.speakingcoach.foundation

import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Storage contract only: no CEFR inference, topic transitions or homework policy. */
internal object StudentLearningModel {
    val skills = setOf("speaking", "listening", "grammar", "vocabulary", "reading", "writing")
    val levels = setOf("A1", "A2", "B1", "B2", "C1", "Unknown")
    val topicStates = setOf("Known", "Not Known", "Presumed Not Known", "Unknown")
    val fields = setOf("currentLevels", "targetLevel", "deadline", "intensity", "homework")
    val memoryKeys = setOf("goal", "goals", "desired_result", "level", "self_assessment", "current_level", "target_level",
        "current_levels", "target_cefr", "current_cefr", "deadline", "intensity", "homework", "homework_preferences",
        "topic_knowledge", "lessons_per_week", "lesson_duration_minutes")

    fun assessment() = json("level" to "Unknown", "explanation" to null)
    private fun levelProfile() = json("overall" to assessment(), "skills" to JsonObject(skills.associateWith { assessment() }))
    fun empty() = json("currentLevels" to json("selfAssessment" to levelProfile(), "speaky" to levelProfile()),
        "targetLevel" to "Unknown", "deadline" to null,
        "intensity" to json("lessonsPerWeek" to null, "lessonDurationMinutes" to null),
        "homework" to json("consent" to "unknown", "minutes" to null, "period" to null))

    fun patch(old: JsonObject, patch: JsonObject): JsonObject {
        patch.only(*fields.toTypedArray())
        // Recursive PATCH: omitted leaves are preserved. Null clears nullable scalar fields only.
        val merged = merge(old, patch)
        validate(merged)
        return merged
    }

    private fun merge(old: JsonObject, patch: JsonObject): JsonObject = JsonObject(old + patch.mapValues { (key, value) ->
        if (value is JsonObject && old[key] is JsonObject) merge(old[key] as JsonObject, value) else value
    })

    fun validate(profile: JsonObject) {
        profile.only(*fields.toTypedArray())
        val current = objectValue(profile["currentLevels"])
        current.only("selfAssessment", "speaky")
        for (kind in listOf("selfAssessment", "speaky")) {
            val group = objectValue(current[kind])
            group.only("overall", "skills")
            validateAssessment(group["overall"])
            val bySkill = objectValue(group["skills"])
            bySkill.only(*skills.toTypedArray())
            skills.forEach { validateAssessment(bySkill[it]) }
        }
        enum(profile["targetLevel"], levels)
        val date = stringOrNull(profile["deadline"])
        if (date != null) try { LocalDate.parse(date) } catch (_: java.time.format.DateTimeParseException) { reject("invalid_deadline") }
        val intensity = objectValue(profile["intensity"])
        intensity.only("lessonsPerWeek", "lessonDurationMinutes")
        positiveOrNull(intensity["lessonsPerWeek"])
        positiveOrNull(intensity["lessonDurationMinutes"])
        val homework = objectValue(profile["homework"])
        homework.only("consent", "minutes", "period")
        enum(homework["consent"], setOf("yes", "no", "unknown"))
        positiveOrNull(homework["minutes"])
        if (homework["period"] != JsonNull) enum(homework["period"], setOf("day", "week"))
        if (homework["minutes"] != JsonNull && homework["period"] == JsonNull) reject("homework_period_required")
    }

    private fun validateAssessment(value: JsonElement?) {
        val assessment = objectValue(value)
        assessment.only("level", "explanation")
        enum(assessment["level"], levels)
        stringOrNull(assessment["explanation"], 2000)
    }

    fun objectValue(value: JsonElement?): JsonObject = value as? JsonObject ?: reject("invalid_learning_object")
    fun stringOrNull(value: JsonElement?, max: Int = 1000): String? {
        if (value == JsonNull) return null
        val primitive = value as? JsonPrimitive ?: reject("invalid_learning_string")
        if (!primitive.isString || primitive.content.length > max) reject("invalid_learning_string")
        return primitive.content
    }
    fun requiredString(value: JsonElement?, max: Int = 1000): String =
        stringOrNull(value, max)?.takeIf { it.isNotBlank() } ?: reject("invalid_learning_string")
    fun enum(value: JsonElement?, allowed: Set<String>): String = requiredString(value).also {
        if (it !in allowed) reject("invalid_learning_enum")
    }
    private fun positiveOrNull(value: JsonElement?) {
        if (value == JsonNull) return
        val primitive = value as? JsonPrimitive ?: reject("invalid_learning_number")
        val number = primitive.doubleOrNull
        if (primitive.isString || number == null || !number.isFinite() || number <= 0) reject("invalid_learning_number")
    }
    fun sources(value: JsonElement?): JsonArray = (value as? JsonArray)?.takeIf { it.size <= 50 }
        ?: reject("invalid_learning_sources")
}
