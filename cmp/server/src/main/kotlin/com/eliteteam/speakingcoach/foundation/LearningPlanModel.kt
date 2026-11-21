package com.eliteteam.speakingcoach.foundation

import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Structural contract only. Method selection and scoring belong to SP-12B and SP-19. */
internal object LearningPlanModel {
    val methods = setOf("Conversation", "Explanation", "Exercises", "Exposure")

    fun id(value: JsonElement?): String {
        val p = value as? JsonPrimitive ?: reject("invalid_plan_id")
        if (!p.isString || !p.content.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,119}"))) reject("invalid_plan_id")
        return p.content
    }

    fun revision(value: JsonElement?): Long {
        val p = value as? JsonPrimitive ?: reject("invalid_plan_revision")
        return p.longOrNull?.takeIf { !p.isString && it > 0 } ?: reject("invalid_plan_revision")
    }

    fun definition(value: JsonElement?): JsonObject {
        val d = value as? JsonObject ?: reject("invalid_plan_definition")
        d.only("schemaVersion", "stages", "order", "transitions")
        if (revision(d["schemaVersion"]) != 1L) reject("unsupported_plan_schema")
        val stages = d["stages"] as? JsonArray ?: reject("invalid_plan_stages")
        if (stages.size !in 1..4) reject("invalid_plan_stages")
        val ids = mutableSetOf<String>()
        val usedMethods = mutableSetOf<String>()
        var weight = BigDecimal.ZERO
        var numeric = false
        for (element in stages) {
            val stage = element as? JsonObject ?: reject("invalid_plan_stage")
            stage.only("id", "method", "kind", "required", "weight", "condition")
            if (!ids.add(id(stage["id"]))) reject("duplicate_stage_id")
            val method = StudentLearningModel.enum(stage["method"], methods)
            if (!usedMethods.add(method)) reject("duplicate_stage_method")
            // Unneeded methods are omitted, not stored as optional weighted stages (v5.5 §13.1).
            val required = stage["required"] as? JsonPrimitive
            if (required == null || required.isString || required.booleanOrNull != true) reject("stage_required")
            when (StudentLearningModel.enum(stage["kind"], setOf("numeric", "binary"))) {
                "numeric" -> {
                    if (stage.containsKey("condition")) reject("numeric_stage_condition")
                    val p = stage["weight"] as? JsonPrimitive ?: reject("invalid_stage_weight")
                    val w = if (!p.isString && p.content.length <= 64) p.content.toBigDecimalOrNull() else null
                    if (w == null || w < BigDecimal.ZERO || w > BigDecimal.ONE || w.scale() !in -100..100) {
                        reject("invalid_stage_weight")
                    }
                    weight += w
                    numeric = true
                }
                "binary" -> {
                    if (stage.containsKey("weight")) reject("binary_stage_weight")
                    // A structured requirement avoids copying personal context or content into a definition.
                    StudentLearningModel.enum(stage["condition"], setOf("understanding_demonstrated"))
                }
            }
        }
        if (numeric && weight.compareTo(BigDecimal.ONE) != 0) reject("invalid_plan_weight_sum")
        val order = d["order"] as? JsonArray ?: reject("invalid_stage_order")
        if (order.size != ids.size || order.map { id(it) }.toSet() != ids) reject("invalid_stage_order")
        val transitions = d["transitions"] as? JsonArray ?: reject("invalid_plan_transitions")
        if (transitions.size > 16) reject("invalid_plan_transitions")
        val edges = transitions.map { element ->
            val edge = element as? JsonObject ?: reject("invalid_plan_transition")
            edge.only("from", "to")
            val pair = id(edge["from"]) to id(edge["to"])
            if (pair.first !in ids || pair.second !in ids) reject("invalid_plan_transition")
            pair
        }
        if (edges.toSet().size != edges.size) reject("duplicate_plan_transition")
        return d
    }

    fun stages(definition: JsonObject): List<JsonObject> = (definition["stages"] as JsonArray).map { it as JsonObject }

    fun stage(definition: JsonObject, id: String): JsonObject =
        stages(definition).find { it.text("id") == id } ?: reject("stage_not_in_revision")

    fun cursor(value: JsonElement?, definition: JsonObject): String? =
        if (value == JsonNull) null else id(value).also { stage(definition, it) }

    fun preserveIdentity(old: JsonObject, next: JsonObject) {
        for (previous in stages(old)) {
            val same = stages(next).find { it.text("id") == previous.text("id") } ?: continue
            if (listOf("method", "kind", "condition").any { same[it] != previous[it] }) reject("stage_identity_changed")
        }
    }

    fun transition(definition: JsonObject, from: String?, to: String?, reason: String) {
        if (reason !in setOf("plan_transition", "student_request")) reject("invalid_stage_selection_reason")
        if (to != null) stage(definition, to)
        if (from == null || to == null || from == to || reason == "student_request") return
        val edges = definition["transitions"] as JsonArray
        if (edges.none { (it as JsonObject).text("from") == from && it.text("to") == to }) reject("stage_transition_not_allowed")
    }
}
