package com.eliteteam.speakingcoach.foundation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** SP-13A wire shape. This checks structure, never language quality or a learner's answer. */
internal object LearningContentModel {
    private val actions = mapOf(
        "Explanation" to setOf("direct", "comparison", "learner_example"),
        "Exercises" to setOf("sentence_construction", "sentence_transformation", "sentence_correction",
            "translation", "word_recall", "meaning_recognition", "meaning_matching", "listening_reconstruction",
            "listening_comprehension", "rapid_response", "sentence_expansion", "paraphrasing"),
        "Exposure" to setOf("listening_reconstruction", "listening_comprehension", "dialogue"),
        "Conversation" to setOf("personalized", "situational", "topic_driven"),
    )

    fun id(value: JsonElement?): String {
        val p = value as? JsonPrimitive ?: reject("invalid_content_id")
        if (!p.isString || !p.content.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,119}"))) reject("invalid_content_id")
        return p.content
    }

    private fun text(value: JsonElement?, max: Int = 1000): String {
        val p = value as? JsonPrimitive ?: reject("invalid_content_text")
        if (!p.isString || p.content.isBlank() || p.content.length > max) reject("invalid_content_text")
        return p.content
    }

    private fun choice(value: JsonElement?, allowed: Set<String>): String = text(value, 120).also {
        if (it !in allowed) reject("invalid_content_choice")
    }

    private fun strings(value: JsonElement?, min: Int, max: Int, itemMax: Int): List<String> {
        val array = value as? JsonArray ?: reject("invalid_content_list")
        if (array.size !in min..max) reject("invalid_content_list")
        return array.map { text(it, itemMax) }.also { if (it.toSet().size != it.size) reject("duplicate_content_item") }
    }

    fun validate(d: JsonObject): JsonObject {
        if (d.toString().length > 16_000) reject("content_too_large")
        d.only("schemaVersion", "taskId", "stageRef", "catalogVersion", "skillId", "topicId", "problemId",
            "method", "actionKind", "cefr", "objective", "spokenInstruction", "payload", "answerGuide", "validation")
        if (LearningTaskModel.positive(d["schemaVersion"]) != 1L) reject("unsupported_content_schema")
        id(d["taskId"])
        val ref = d["stageRef"] as? JsonObject ?: reject("invalid_content_stage")
        ref.only("taskId", "planId", "planRevision", "stageId")
        if (id(ref["taskId"]) != d.text("taskId")) reject("content_task_mismatch")
        id(ref["planId"])
        LearningPlanModel.revision(ref["planRevision"])
        id(ref["stageId"])
        text(d["catalogVersion"], 250)
        text(d["skillId"], 120)
        text(d["topicId"], 120)
        text(d["problemId"], 120)
        val method = choice(d["method"], actions.keys)
        choice(d["actionKind"], actions.getValue(method))
        choice(d["cefr"], StudentLearningModel.levels)
        text(d["objective"], 1000)
        text(d["spokenInstruction"], 400)
        payload(method, d.obj("payload"))
        val guide = d.obj("answerGuide")
        guide.only("criteria", "alternatives", "unacceptable")
        strings(guide["criteria"], 1, 8, 300)
        strings(guide["alternatives"], 0, 8, 300)
        strings(guide["unacceptable"], 0, 8, 300)
        val validation = d.obj("validation")
        validation.only("status", "component", "reasons")
        val status = choice(validation["status"], setOf("pending", "passed", "rejected"))
        id(validation["component"])
        strings(validation["reasons"], if (status == "rejected") 1 else 0, 8, 250).also {
            if (status != "rejected" && it.isNotEmpty()) reject("invalid_content_validation")
        }
        return d
    }

    private fun payload(method: String, p: JsonObject) {
        when (method) {
            "Explanation" -> {
                p.only("explanation", "examples")
                text(p["explanation"], 1200)
                strings(p["examples"], 1, 4, 300)
            }
            "Exercises" -> {
                p.only("stimulus", "targetOperation")
                text(p["stimulus"], 300)
                text(p["targetOperation"], 300)
            }
            "Exposure" -> {
                p.only("segments", "checks")
                strings(p["segments"], 1, 8, 250)
                val checks = strings(p["checks"], 1, 2, 40)
                if (checks.any { it !in setOf("reconstruction", "meaning") }) reject("invalid_content_check")
            }
            "Conversation" -> {
                p.only("openingPrompt", "targetOpportunities")
                text(p["openingPrompt"], 400)
                strings(p["targetOpportunities"], 1, 6, 250)
            }
        }
    }
}
