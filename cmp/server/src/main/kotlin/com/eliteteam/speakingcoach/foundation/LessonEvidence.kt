package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class LessonEvidence {
    fun validate(observation: JsonObject, source: JsonObject) {
        val evidence = observation["evidence"]?.takeUnless { it == JsonNull }?.jsonObject ?: return
        evidence.only("kind", "signal", "support", "situation", "quote")
        if (evidence.text("kind") !in setOf("self_report", "ai_observation") ||
            evidence.text("signal") !in setOf("strength", "difficulty", "neutral") ||
            evidence.text("support") !in setOf("independent", "prompted", "unknown") ||
            evidence.text("situation").length !in 1..500 || evidence.text("quote").length !in 1..1000 ||
            !source.text("body").contains(evidence.text("quote")) || observation.text("dimension") == "fluency" ||
            (observation.text("dimension") == "confidence" && evidence.text("kind") != "self_report")
        ) reject("invalid_evidence")
    }

    fun notes(c: Connection, scope: ExecutionScope, lesson: String, result: JsonObject, now: Instant) {
        val notes = result["notes"] as? JsonArray ?: return
        if (notes.size > 30) reject("invalid_result")
        notes.forEach { item ->
            val note = item.jsonObject
            note.only("kind", "content", "sourceIds")
            if (note.text("kind") !in setOf("topic", "skill", "strength", "difficulty", "feedback", "recommendation") ||
                note.text("content").length !in 1..1000) reject("invalid_result")
            val ids = note["sourceIds"] as? JsonArray ?: reject("invalid_result")
            if (ids.size !in 1..10 || ids.distinct().size != ids.size) reject("invalid_evidence")
            ids.forEach { id ->
                val source = sourceMessage(c, scope, id.jsonPrimitive.content, now)
                if (c.document("SELECT to_jsonb(e) FROM lesson_event e WHERE lesson_id=? AND event_id=?", lesson, source.text("id")) == null) reject("invalid_evidence")
            }
        }
    }
}
