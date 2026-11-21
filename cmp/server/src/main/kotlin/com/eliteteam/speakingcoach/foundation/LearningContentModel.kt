package com.eliteteam.speakingcoach.foundation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** SP-13A wire shape. This checks structure, never language quality or a learner's answer. */
internal object LearningContentModel {
    private val actions: Map<String, Set<String>> = checkNotNull(
        LearningContentModel::class.java.getResourceAsStream("/learning-content-action-kinds-v1.json"),
    ).bufferedReader().use { foundationJson.decodeFromString(it.readText()) }

    @Serializable
    private data class ListLimit(val minItems: Int, val maxItems: Int, val maxUnits: Int)

    @Serializable
    private data class PayloadLimits(
        val text: Map<String, Int> = emptyMap(),
        val lists: Map<String, ListLimit> = emptyMap(),
    )

    @Serializable
    private data class ContentLimits(
        val document: Int,
        val text: Map<String, Int>,
        val answerGuide: Map<String, ListLimit>,
        val payload: Map<String, PayloadLimits>,
    )

    private val limits: ContentLimits = checkNotNull(
        LearningContentModel::class.java.getResourceAsStream("/learning-content-limits-v1.json"),
    ).bufferedReader().use { foundationJson.decodeFromString(it.readText()) }

    fun id(value: JsonElement?): String {
        val p = value as? JsonPrimitive ?: reject("invalid_content_id")
        if (!p.isString || !p.content.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,119}"))) reject("invalid_content_id")
        return p.content
    }

    private fun text(value: JsonElement?, max: Int): String {
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
        if (d.toString().length > limits.document) reject("content_too_large")
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
        for ((field, max) in limits.text) text(d[field], max)
        payload(method, d.obj("payload"))
        val guide = d.obj("answerGuide")
        guide.only(*limits.answerGuide.keys.toTypedArray())
        for ((field, limit) in limits.answerGuide) {
            strings(guide[field], limit.minItems, limit.maxItems, limit.maxUnits)
        }
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
        val fields = limits.payload.getValue(method)
        p.only(*(fields.text.keys + fields.lists.keys).toTypedArray())
        for ((field, max) in fields.text) text(p[field], max)
        for ((field, limit) in fields.lists) {
            val items = strings(p[field], limit.minItems, limit.maxItems, limit.maxUnits)
            if (method == "Exposure" && field == "checks" && items.any { it != "reconstruction" && it != "meaning" }) {
                reject("invalid_content_check")
            }
        }
    }
}
