package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal class LessonMemory {
    fun apply(c: Connection, scope: ExecutionScope, lesson: String, result: JsonObject, now: Instant) {
        val facts = result["facts"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
        if (facts.size > 20) reject("invalid_result")
        val keys = mutableSetOf<String>()
        facts.forEach { item ->
            val fact = item.jsonObject
            fact.only("key", "value", "sourceId", "evidence", "expectedVersion")
            val key = fact.text("key")
            if (!keys.add(key) || key in setOf("goal", "goals", "self_assessment", "level", "confidence", "timezone")) reject("invalid_fact")
            val source = sourceMessage(c, scope, fact.text("sourceId"), now)
            if (c.document("SELECT to_jsonb(e) FROM lesson_event e WHERE lesson_id=? AND event_id=?", lesson, source.text("id")) == null ||
                fact.text("evidence").isBlank() || !source.text("body").contains(fact.text("evidence"))) reject("invalid_evidence")
            val old = c.document("SELECT to_jsonb(f) FROM memory_fact f WHERE student_id=? AND category='personal' AND fact_key=?", scope.studentId, key)
            val oldSource = old?.optionalText("source_event_id")?.let { id -> c.document("SELECT to_jsonb(e) FROM conversation_event e WHERE id=?", id) }
            // A concurrent correction wins, including an explicit restriction/version change.
            if (memoryVersionMatches(old, fact.number("expectedVersion"), source, now) &&
                (oldSource == null || !sourceIsNewer(oldSource, source))) {
                StructuredMemory().record(c, scope.copy(role = "operator"), json("sourceId" to source.text("id"),
                    "category" to "personal", "key" to key, "value" to fact.text("value"), "kind" to "user_report",
                    "evidence" to fact.text("evidence"), "expectedVersion" to fact.number("expectedVersion")), now)
            }
        }
        val candidates = result["candidates"]?.jsonArray ?: return
        if (candidates.size > 10) reject("invalid_result")
        candidates.forEach { item ->
            val candidate = item.jsonObject
            candidate.only("key", "value", "sourceId")
            val source = sourceMessage(c, scope, candidate.text("sourceId"), now)
            if (c.document("SELECT to_jsonb(e) FROM lesson_event e WHERE lesson_id=? AND event_id=?", lesson, source.text("id")) == null) reject("invalid_evidence")
            val known = c.rows("SELECT count(*) FROM memory_fact WHERE student_id=? AND category='personal' AND fact_key=?",
                scope.studentId, candidate.text("key")) { it.getLong(1) }.single() > 0
            val rejected = c.rows("SELECT count(*) FROM memory_candidate WHERE student_id=? AND fact_key=? AND status='rejected'",
                scope.studentId, candidate.text("key")) { it.getLong(1) }.single() > 0
            if (!known && !rejected) StructuredMemory().propose(c, scope, candidate, now)
        }
    }
}
