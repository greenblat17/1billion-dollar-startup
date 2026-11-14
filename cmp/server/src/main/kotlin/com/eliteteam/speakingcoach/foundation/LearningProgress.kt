package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A projection of canonical observations and roadmap reviews, shared by API and tutor context. */
internal class LearningProgress {
    fun evidence(c: Connection, student: String): JsonObject {
        val rows = c.documents("""SELECT to_jsonb(o) || jsonb_build_object('review',to_jsonb(r),'occurred_at',e.occurred_at,'reviewOccurredAt',re.occurred_at)
            FROM memory_observation o JOIN lesson l ON l.id=o.lesson_id AND l.transcript_revision=o.source_revision
            JOIN conversation_event e ON e.id=o.source_event_id
            LEFT JOIN memory_observation_review r ON r.student_id=o.student_id AND r.observation_key=o.observation_key
            LEFT JOIN conversation_event re ON re.id=r.source_event_id
            WHERE o.student_id=? ORDER BY e.occurred_at DESC,o.id LIMIT 1001""", student)
        var size = 0
        val selected = rows.take(1000).takeWhile { size += it.toString().length; size <= 120000 }
        return json("rows" to JsonArray(selected), "partial" to (selected.size < rows.size))
    }

    fun apply(c: Connection, scope: ExecutionScope, inputs: JsonObject, result: JsonObject, now: Instant) {
        if (result["progress"] == null && result["dimensions"] == null) return // Pre-SP08 result.
        val catalog = evidence(c, scope.studentId)
        val rows = catalog["rows"]!!.jsonArray.map { it.jsonObject }.associateBy { it.text("id") }
        val complete = catalog.optionalText("partial") == "false"
        val roadmap = c.currentRoadmap(scope.studentId)
        val steps = (roadmap?.obj("content")?.get("steps") as? JsonArray).orEmpty().map { it.jsonObject }
        val assessments = (result["progress"] as? JsonArray).orEmpty()
        val dimensions = (result["dimensions"] as? JsonArray).orEmpty()
        if (assessments.size > 20 || dimensions.size > 5) reject("invalid_result")
        val seen = mutableSetOf<String>()
        val storedSteps = assessments.map { item ->
            val a = item.jsonObject
            a.only("stepId", "outcome", "decision", "reason", "transferReason", "observationIds", "dimensions")
            val step = steps.singleOrNull { it.text("id") == a.text("stepId") && it.text("outcome") == a.text("outcome") }
                ?: reject("invalid_progress_step")
            if (!seen.add(step.text("id"))) reject("invalid_result")
            checkText(a, "reason")
            val dims = a["dimensions"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
            if (dims.isEmpty() || !progressDimensions.containsAll(dims)) reject("invalid_result")
            val refs = references(a, rows)
            if (a.text("decision") !in setOf("demonstrated", "insufficient_evidence")) reject("invalid_result")
            if (a.text("decision") == "demonstrated") {
                checkText(a, "transferReason")
                if (!complete || "fluency" in dims || refs.isEmpty() || rows.values.any { disputed(it) } ||
                    refs.any { it.text("uncertainty") != "supported" || metadata(it)?.optionalText("signal") != "strength" || predatesReview(it) } ||
                    refs.map { it.text("lesson_id") }.distinct().size < 2 ||
                    refs.map { it.text("source_event_id") }.distinct().size < 2 ||
                    refs.mapNotNull { metadata(it)?.optionalText("situation")?.trim()?.lowercase() }.distinct().size < 2
                ) reject("insufficient_progress_evidence")
                if (refs.any { it.text("dimension") !in dims }) reject("invalid_evidence")
                val keys = refs.map { it.text("observation_key") }.toSet()
                val difficulties = rows.values.filter { it.text("observation_key") in keys && metadata(it)?.optionalText("signal") == "difficulty" }
                val latestDifficulty = difficulties.maxOfOrNull { Instant.parse(it.text("occurred_at")) }
                if (difficulties.map { it.text("lesson_id") }.distinct().size >= 2 &&
                    refs.any { Instant.parse(it.text("occurred_at")) <= latestDifficulty!! }) {
                    reject("contradictory_progress_evidence")
                }
                dims.forEach { dimension ->
                    val matching = refs.filter { it.text("dimension") == dimension }
                    if (matching.map { it.text("lesson_id") }.distinct().size < 2 || matching.any {
                        if (dimension == "confidence") metadata(it)?.optionalText("kind") != "self_report"
                        else metadata(it)?.optionalText("kind") != "ai_observation" || metadata(it)?.optionalText("support") != "independent"
                    }) reject("insufficient_progress_evidence")
                }
            }
            JsonObject(a + ("evidence" to JsonArray(refs.map { provenance(it) })))
        }
        val seenDimensions = mutableSetOf<String>()
        val storedDimensions = dimensions.map { item ->
            val a = item.jsonObject
            a.only("dimension", "trend", "reason", "comparison", "observationIds")
            val dimension = a.text("dimension")
            if (dimension !in progressDimensions || !seenDimensions.add(dimension) ||
                a.text("trend") !in setOf("insufficient_evidence", "observed", "improving", "stable", "mixed")) reject("invalid_result")
            checkText(a, "reason")
            val refs = references(a, rows)
            if (refs.any { it.text("dimension") != dimension }) reject("invalid_evidence")
            if (a.text("trend") != "insufficient_evidence") {
                if (!complete || dimension == "fluency" || refs.isEmpty() || refs.any { disputed(it) || predatesReview(it) || metadata(it) == null } ||
                    (dimension == "confidence" && refs.any { metadata(it)?.optionalText("kind") != "self_report" })) reject("insufficient_progress_evidence")
                if (a.text("trend") != "observed") {
                    checkText(a, "comparison")
                    if (refs.map { it.text("lesson_id") }.distinct().size < 2 ||
                        refs.map { it.text("source_event_id") }.distinct().size < 2) reject("insufficient_progress_evidence")
                }
            }
            JsonObject(a + ("evidence" to JsonArray(refs.map { provenance(it) })))
        }
        if (result["dimensions"] != null && seenDimensions != progressDimensions.toSet()) reject("invalid_result")
        c.update("""INSERT INTO progress_review(student_id,job_id,goal_revision,roadmap_revision,learning_revision,result,created_at)
            VALUES (?,?,?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING""", scope.studentId, scope.jobId,
            inputs.number("goalRevision"), inputs.number("roadmapRevision"), inputs.number("learningRevision"),
            json("steps" to JsonArray(storedSteps), "dimensions" to JsonArray(storedDimensions)), now)
    }

    fun read(c: Connection, student: String): JsonObject {
        val roadmap = c.currentRoadmap(student)
        val goal = c.document("SELECT to_jsonb(g) FROM learning_goal g WHERE student_id=? ORDER BY revision DESC LIMIT 1", student)
        val preparation = RoadmapPreparation().state(c, student)
        val history = c.documents("""SELECT DISTINCT ON (a->>'stepId',a->>'outcome') a
            FROM progress_review p CROSS JOIN LATERAL jsonb_array_elements(p.result->'steps') a
            WHERE p.student_id=? AND p.goal_revision=? AND a->>'decision'='demonstrated'
            ORDER BY a->>'stepId',a->>'outcome',p.created_at DESC,p.job_id DESC""", student, c.goalRevision(student))
        val latestDimensions = c.documents("""SELECT DISTINCT ON (a->>'dimension') a
            FROM progress_review p CROSS JOIN LATERAL jsonb_array_elements(p.result->'dimensions') a
            WHERE p.student_id=? AND p.goal_revision=? ORDER BY a->>'dimension',p.created_at DESC,p.job_id DESC""", student, c.goalRevision(student))
        val ids = (history + latestDimensions).flatMap { a -> a["evidence"]!!.jsonArray.map { it.jsonObject.text("id") } }.distinct()
        val rows = c.documents("""SELECT to_jsonb(o) || jsonb_build_object('review',to_jsonb(r))
            FROM memory_observation o JOIN lesson l ON l.id=o.lesson_id AND l.transcript_revision=o.source_revision
            LEFT JOIN memory_observation_review r ON r.student_id=o.student_id AND r.observation_key=o.observation_key
            WHERE o.student_id=? AND o.id IN (SELECT jsonb_array_elements_text(?::jsonb))""", student,
            JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) })).associateBy { it.text("id") }
        var currentAssigned = false
        val steps = (roadmap?.obj("content")?.get("steps") as? JsonArray).orEmpty().map { item ->
            val step = item.jsonObject
            val achievement = history.firstOrNull { it.text("stepId") == step.text("id") && it.text("outcome") == step.text("outcome") && it.text("decision") == "demonstrated" }
            val state = when {
                achievement != null && valid(achievement, rows) -> "completed"
                achievement != null -> "needs_review"
                !currentAssigned -> "current"
                else -> "upcoming"
            }
            if (state == "current" || state == "needs_review") currentAssigned = true
            json("id" to step.text("id"), "outcome" to step.text("outcome"), "tactic" to step.text("tactic"), "status" to state)
        }
        val dimensions = progressDimensions.map { dimension ->
            val assessment = latestDimensions.firstOrNull { it.text("dimension") == dimension }
            val usable = assessment != null && valid(assessment, rows) && dimension != "fluency"
            json("dimension" to dimension, "trend" to if (usable) assessment.text("trend") else "insufficient_evidence",
                "reason" to if (usable) assessment.text("reason") else "Недостаточно достоверных данных")
        }
        val state = when {
            roadmap != null -> "ready"
            preparation?.optionalText("status") in setOf("queued", "running", "retry_wait") -> "preparing"
            preparation?.optionalText("status") == "failed" -> "error"
            preparation?.optionalText("decision") == "insufficient_evidence" -> "insufficient_evidence"
            else -> "no_roadmap"
        }
        return json("status" to state, "goal" to goal?.get("content"), "roadmapRevision" to roadmap?.get("revision"),
            "steps" to JsonArray(steps), "dimensions" to JsonArray(dimensions),
            "evidenceStatus" to if (dimensions.all { it.text("trend") == "insufficient_evidence" }) "insufficient_evidence" else "reviewed",
            "preparation" to preparation)
    }

    private fun references(a: JsonObject, rows: Map<String, JsonObject>): List<JsonObject> {
        val ids = a["observationIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: reject("invalid_result")
        if (ids.size > 40 || ids.distinct().size != ids.size) reject("invalid_evidence")
        return ids.map { rows[it] ?: reject("invalid_evidence") }
    }

    private fun provenance(o: JsonObject) = json("id" to o.text("id"), "reviewRevision" to ((o["review"] as? JsonObject)?.number("revision") ?: 0))
    private fun valid(a: JsonObject, rows: Map<String, JsonObject>): Boolean = a["evidence"]!!.jsonArray.isNotEmpty() && a["evidence"]!!.jsonArray.all {
        val ref = it.jsonObject
        val observation = rows[ref.text("id")]
        observation != null && !disputed(observation) &&
            ((observation["review"] as? JsonObject)?.number("revision") ?: 0) == ref.number("reviewRevision")
    }
    private fun predatesReview(o: JsonObject): Boolean {
        val reviewed = o.optionalText("reviewOccurredAt") ?: return false
        return Instant.parse(o.text("occurred_at")) <= Instant.parse(reviewed)
    }
    private fun disputed(o: JsonObject) = (o["review"] as? JsonObject)?.optionalText("status") == "disputed"
    private fun metadata(o: JsonObject) = o["evidence"]?.takeUnless { it == JsonNull } as? JsonObject
    private fun checkText(a: JsonObject, key: String) {
        if (a.text(key).isBlank() || a.text(key).length > 1000) reject("invalid_result")
    }
}

internal val progressDimensions = listOf("fluency", "confidence", "autonomy", "grammar", "vocabulary")
