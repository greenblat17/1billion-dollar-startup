package com.eliteteam.speakingcoach.foundation

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Evidence structure only. No language classifier, ASR detector, or score calculator. */
internal object LearningObservationModel {
    val outcomes = setOf("independent_correct", "uncorrected_error", "self_corrected_error", "hinted_correct", "unresolved", "invalid")
    val reasons = setOf("uncertain_asr", "ambiguous_interpretation", "defective_content", "missing_context", "unknown_support", "refusal", "technical_failure")

    fun string(value: JsonElement?, max: Int = 250): String = StudentLearningModel.requiredString(value, max)
    fun positive(value: JsonElement?): Long = LearningTaskModel.positive(value)
    fun nullableString(value: JsonElement?) { if (value != null && value != JsonNull) string(value) }

    fun validate(d: JsonObject) {
        d.only("schemaVersion", "attemptId", "evidenceSlot", "anchorEventId", "lessonId", "occurredAt", "sources",
            "catalogVersion", "skillId", "topicId", "problemId", "practice", "method", "kind", "outcome", "support",
            "initialOutcome", "quality", "provenance", "taskId", "stageRef", "actionId", "repeatOf", "route", "rationale")
        if (positive(d["schemaVersion"]) != 1L) reject("unsupported_observation_schema")
        listOf("attemptId", "evidenceSlot", "anchorEventId", "catalogVersion", "skillId").forEach { string(d[it]) }
        listOf("lessonId", "topicId", "problemId", "taskId", "actionId", "repeatOf").forEach { nullableString(d[it]) }
        if (runCatching { Instant.parse(string(d["occurredAt"])) }.isFailure) reject("invalid_observation_time")
        choice(d, "practice", setOf("trial", "regular", "homework"))
        if (d.text("practice") != "homework" && d.optionalText("lessonId") == null) reject("observation_lesson_required")
        if (d["method"] != null && d["method"] != JsonNull) choice(d, "method", setOf("conversation", "explanation", "exercises", "exposure"))
        choice(d, "kind", setOf("demonstration", "self_report", "feedback"))
        choice(d, "outcome", outcomes)
        choice(d, "support", setOf("independent", "hinted", "unknown"))
        choice(d, "initialOutcome", setOf("correct", "error", "unknown"))
        validateOutcome(d)
        quality(d.obj("quality"))
        val p = d.obj("provenance")
        p.only("component", "analyzerVersion", "model", "promptVersion")
        string(p["component"], 100)
        listOf("analyzerVersion", "model", "promptVersion").forEach { nullableString(p[it]) }
        string(d["rationale"], 2000)
        stage(d)
        sources(d)
        if (isTrialListening(d)) validateTrialListening(d)
    }

    fun isTrialListening(d: JsonObject) = d.text("practice") == "trial" && d.text("skillId") == "listening"

    private fun validateTrialListening(d: JsonObject) {
        val references = (d["sources"] as JsonArray).map { it as JsonObject }.associateBy { it.text("purpose") }
        val answer = references["answer"] ?: reject("invalid_trial_listening")
        val prompt = references["context"] ?: reject("invalid_trial_listening")
        val hint = references["hint"]
        val expected = if (hint == null) setOf("answer", "context") else setOf("answer", "context", "hint")
        val validOutcome = when (d.text("outcome")) {
            "independent_correct" -> hint == null
            "hinted_correct" -> hint != null && d.text("initialOutcome") == "unknown"
            "unresolved" -> hint == null && d.text("support") == "unknown" && d.text("initialOutcome") == "unknown"
            else -> false
        }
        if (!validOutcome || references.keys != expected || (d["sources"] as JsonArray).size != expected.size ||
            answer["span"] !is JsonObject || prompt.text("role") != "assistant" ||
            (hint != null && hint.text("role") != "assistant") ||
            prompt.number("revision") >= (hint ?: answer).number("revision") ||
            (hint != null && hint.number("revision") >= answer.number("revision"))) reject("invalid_trial_listening")
        if (d.text("kind") != "demonstration" || d.text("route") != "assessment_only" ||
            d.optionalText("method") != "conversation" || d.text("attemptId") != d.text("anchorEventId") ||
            d.text("evidenceSlot") != "trial-" + digest("listening||").take(24) ||
            listOf("topicId", "problemId", "taskId", "actionId", "repeatOf").any { d.optionalText(it) != null } ||
            d.obj("provenance").text("component") != "trial-observation-extractor") reject("invalid_trial_listening")
    }

    private fun validateOutcome(d: JsonObject) {
        val outcome = d.text("outcome")
        val initial = d.text("initialOutcome")
        val support = d.text("support")
        if ((outcome in setOf("independent_correct", "self_corrected_error") && support != "independent") ||
            (outcome == "hinted_correct" && support != "hinted") ||
            (outcome == "independent_correct" && initial != "correct") ||
            (outcome in setOf("uncorrected_error", "self_corrected_error") && initial != "error") ||
            (d.text("kind") != "demonstration" && outcome !in setOf("unresolved", "invalid"))) reject("invalid_observation_outcome")
    }

    private fun quality(q: JsonObject) {
        q.only("status", "signals", "confidence")
        choice(q, "status", setOf("usable", "uncertain", "invalid"))
        val signals = q["signals"] as? JsonArray ?: reject("invalid_quality_signals")
        if (signals.size > 20 || (q.text("status") != "usable" && signals.isEmpty())) reject("invalid_quality_signals")
        signals.forEach {
            val s = it as? JsonObject ?: reject("invalid_quality_signals")
            s.only("reason", "component", "sourceEventId")
            choice(s, "reason", reasons)
            string(s["component"], 100)
            string(s["sourceEventId"])
        }
        if (signals.isNotEmpty() && q.text("status") == "usable") reject("invalid_quality_status")
        q["confidence"]?.takeUnless { it == JsonNull }?.let {
            val c = it as? JsonObject ?: reject("invalid_observation_confidence")
            c.only("value", "component", "kind")
            string(c["component"], 100)
            choice(c, "kind", setOf("interpretation", "asr"))
            val v = c["value"] as? JsonPrimitive ?: reject("invalid_observation_confidence")
            val n = v.doubleOrNull
            if (v.isString || n == null || !n.isFinite() || n !in 0.0..1.0) reject("invalid_observation_confidence")
        }
    }

    private fun stage(d: JsonObject) {
        choice(d, "route", setOf("assessment_only", "problem_detection", "stage_progress", "unresolved"))
        d["stageRef"]?.takeUnless { it == JsonNull }?.let {
            val s = it as? JsonObject ?: reject("invalid_stage_reference")
            s.only("planId", "planRevision", "stageId", "stageAttemptId")
            string(s["planId"]); positive(s["planRevision"]); string(s["stageId"])
            nullableString(s["stageAttemptId"])
            if (d.optionalText("taskId") == null) reject("stage_task_required")
        }
        if (d.text("route") == "stage_progress" && (d["stageRef"] == null || d["stageRef"] == JsonNull)) reject("stage_reference_required")
        if (d.text("route") == "problem_detection" && d.optionalText("taskId") != null) reject("observation_route_conflict")
        if (d.text("route") in setOf("problem_detection", "stage_progress") &&
            (d.optionalText("topicId") == null || d.optionalText("problemId") == null)) reject("observation_classification_required")
    }

    private fun sources(d: JsonObject) {
        val sources = d["sources"] as? JsonArray ?: reject("invalid_observation_sources")
        if (sources.size !in 1..20) reject("invalid_observation_sources")
        val ids = sources.map { element ->
            val s = element as? JsonObject ?: reject("invalid_observation_sources")
            s.only("eventId", "revision", "role", "purpose", "span")
            positive(s["revision"])
            choice(s, "role", setOf("user", "assistant"))
            choice(s, "purpose", setOf("answer", "context", "hint", "correction"))
            if (s.text("purpose") in setOf("answer", "correction") && s.text("role") != "user") reject("invalid_answer_source")
            s["span"]?.takeUnless { it == JsonNull }?.let {
                val span = it as? JsonObject ?: reject("invalid_source_span")
                span.only("start", "end")
                val start = integer(span["start"])
                val end = integer(span["end"])
                if (start < 0 || end <= start || end > 1000000) reject("invalid_source_span")
            }
            string(s["eventId"])
        }
        if (ids.distinct().size != ids.size) reject("duplicate_observation_source")
        if (sources.none { (it as JsonObject).text("eventId") == d.text("anchorEventId") && it.text("purpose") == "answer" }) reject("observation_anchor_required")
        d.obj("quality")["signals"]!!.let { it as JsonArray }.forEach {
            if ((it as JsonObject).text("sourceEventId") !in ids) reject("quality_source_required")
        }
        if (d.text("support") == "hinted" && sources.none { (it as JsonObject).text("purpose") == "hint" }) reject("hint_source_required")
    }

    private fun integer(v: JsonElement?): Long {
        val p = v as? JsonPrimitive ?: reject("invalid_source_span")
        return p.longOrNull?.takeUnless { p.isString } ?: reject("invalid_source_span")
    }

    private fun choice(d: JsonObject, key: String, values: Set<String>) {
        if (string(d[key]) !in values) reject("invalid_observation_$key")
    }
}
