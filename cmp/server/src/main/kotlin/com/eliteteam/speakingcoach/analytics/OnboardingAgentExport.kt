package com.eliteteam.speakingcoach.analytics

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val EXPORT_SCHEMA = "onboarding-analytics.v1"
private val exportJson = Json { prettyPrint = true }

/** Aggregated, content-free snapshot that an analyst agent can consume without parsing HTML. */
internal fun onboardingAgentJson(report: OnboardingReport, generatedAt: Instant): String =
    exportJson.encodeToString(JsonObject.serializer(), onboardingAgentData(report, generatedAt))

internal fun onboardingAgentData(report: OnboardingReport, generatedAt: Instant): JsonObject = buildJsonObject {
    put("schema_version", EXPORT_SCHEMA)
    put("generated_at_utc", generatedAt.toString())
    put("timezone", ONBOARDING_ZONE.id)
    put("filters", buildJsonObject {
        put("start_days", report.filter.days)
        put("onboarding_version", optional(report.filter.version))
        put("start_source", optional(report.filter.source))
        put("trigger", optional(report.filter.trigger))
    })
    put("definitions", buildJsonObject {
        put("cohort_day", "Moscow calendar day of attempt start")
        put("closed_day", "A start day closes at the beginning of the second following Moscow day")
        put("funnel_window", "First 24 hours after each attempt starts")
        put("from_previous", "People with both adjacent steps, divided by people with the previous step")
        put("decision_window", "Selected attempts by start day; decisions may happen after the first 24 hours")
        put("d1", "Recognized ordinary voice on the next Moscow calendar day after primary onboarding")
        put("d7", "Recognized ordinary voice on the seventh Moscow calendar day; null until that day ends")
        put("error_window", "Last seven 24-hour periods among attempts selected by the start filters")
        put("recognition_error_denominator", "Selected onboarding voices in the error window, excluding queue_full")
        put("result_build_error_denominator", "Selected attempts reaching 120 seconds in the error window")
        put("speech_before_stage", "Cumulative recognized speech before the current voice: 0-30, 30-60, 60-90, 90-120, 120+ seconds")
        put("processing_and_delivery_ms", "Clip processing plus Telegram reply delivery for recognized onboarding voices")
        put("reply_gap_seconds", "From a delivered voice reply to the next received voice; negative gaps are excluded")
        put("result_examples", "Availability in a delivered result, not proof that a user opened the Grammar or Vocabulary card")
        put("percentiles", "Discrete percentiles; null when there are no observations")
    })
    put("analysis_guidance", strings(listOf(
        "Report observations and counts before making recommendations; these aggregates do not identify causes.",
        "Compare equivalent closed cohorts and onboarding versions; do not treat open cohorts as drop-off.",
        "Use numerator and denominator for every rate. Null percent means there was no denominator.",
        "Separate no_speech and STT failure from processing, delivery, and queue failures.",
        "Old v1 attempts can lack newer events. Process counters reset on server restart.",
        "No audio, transcripts, profile text, user IDs, or attempt IDs are present in this export.",
    )))
    put("cohorts", buildJsonObject {
        put("closed_primary", JsonArray(report.closedPrimary.map { cohort(it, closed = true, primary = true) }))
        put("open_primary", JsonArray(report.openPrimary.map { cohort(it, closed = false, primary = true) }))
        put("closed_repeats", JsonArray(report.closedRepeats.map { cohort(it, closed = true, primary = false) }))
        put("open_repeat_attempts", report.openRepeatCount)
    })
    put("voice_diagnostics", buildJsonObject {
        val diagnostics = report.diagnostics
        put("outcome_counts", countMap(safeOutcomes(diagnostics.outcomes)))
        put("total_voices", diagnostics.outcomes.values.sum())
        put("outcomes_by_speech_before_stage", JsonObject(diagnostics.outcomesByStage.mapValues {
            countMap(safeOutcomes(it.value))
        }))
        put("processing_and_delivery_ms", percentiles(diagnostics.processingP50Ms, diagnostics.processingP95Ms))
        put("reply_to_next_voice_gap_seconds", percentiles(diagnostics.replyGapP50Sec, diagnostics.replyGapP95Sec))
        put("recognized_voices_per_built_result_p50", nullableNumber(diagnostics.voicesPerCompletedP50))
        put("recognized_voices_per_built_result_distribution", countMap(diagnostics.voicesPerCompleted))
    })
    put("errors_last_7_days", buildJsonObject {
        put("recognition", errorStat(report.recognition))
        put("result_build", errorStat(report.assessment))
    })
    put("decisions", buildJsonObject {
        put("selected_attempts", report.decisions.attempts)
        val primaryAttempts = report.closedPrimary.sumOf { it.size } + report.openPrimary.sumOf { it.size }
        put("selected_primary_attempts", primaryAttempts)
        put("rates", JsonObject(decisionCounts(report.decisions, primaryAttempts).associate {
            it.id to ratio(it.count, it.denominator)
        }))
        put("event_counts", buildJsonObject {
            put("retry_requested", report.decisions.retryRequested)
            put("retry_recovered", report.decisions.retryRecovered)
            put("text_hint", report.decisions.textHint)
            put("pre_begin_voice_hint", report.decisions.preBeginVoiceHint)
        })
    })
    put("data_quality", buildJsonObject {
        put("write_counters_since_process_start", buildJsonObject {
            put("attempted", OnboardingAnalyticsHealth.attemptedWrites())
            put("failed", OnboardingAnalyticsHealth.failedWrites())
            put("missing_attempt", OnboardingAnalyticsHealth.missingAttempts())
        })
        put("event_loss_on_process_crash_possible", true)
    })
}

private fun cohort(value: CohortFunnel, closed: Boolean, primary: Boolean): JsonObject = buildJsonObject {
    put("start_day", value.day.toString())
    put("closed", closed)
    put("primary", primary)
    put("started_attempts", value.size)
    put("steps", JsonArray(value.steps.mapIndexed { index, step ->
        buildJsonObject {
            put("id", step.id)
            put("label", step.name)
            put("count", step.count)
            put("from_start", ratio(step.count, value.size))
            put("from_previous", if (index == 0) JsonNull else ratio(step.withPreviousCount, value.steps[index - 1].count))
        }
    }))
    put("levels_among_built_results", countMap(safeLevels(value.levels)))
    put("selected_goal_minutes", countMap(value.goals.mapKeys { it.key.toString() }))
    put("step_marks_without_previous", value.skippedPrevious)
    put("first_practice", if (primary) ratio(value.firstPractice, value.size) else JsonNull)
    put("d1_from_start", if (primary && closed) ratio(value.returnedNextDay, value.size) else JsonNull)
    put("d1_from_completed_by_end_of_d1", if (primary && closed) ratio(value.returnedNextDay, value.completedByD1) else JsonNull)
    put("d7_from_start", if (primary && value.d7Eligible > 0) ratio(value.returnedDay7, value.d7Eligible) else JsonNull)
    put("d7_from_completed_by_end_of_d7", if (primary && value.d7Eligible > 0) ratio(value.returnedDay7, value.completedByD7) else JsonNull)
}

private fun ratio(numerator: Int, denominator: Int): JsonObject = buildJsonObject {
    put("numerator", numerator)
    put("denominator", denominator)
    put("percent", if (denominator == 0) JsonNull else JsonPrimitive(percentOf(numerator, denominator)))
}

private fun percentiles(p50: Int?, p95: Int?): JsonObject = buildJsonObject {
    put("p50", nullableNumber(p50))
    put("p95", nullableNumber(p95))
}

private fun errorStat(value: ErrorStat): JsonObject = buildJsonObject {
    put("events", ratio(value.errors, value.denominator))
    put("affected_people", value.people)
}

private fun countMap(values: Map<String, Int>): JsonObject =
    JsonObject(values.toSortedMap().mapValues { JsonPrimitive(it.value) })

private fun safeLevels(values: Map<String, Int>): Map<String, Int> {
    val allowed = setOf("A1", "A2", "B1", "B2", "C1", "C2", "нет уровня")
    return values.entries.groupBy { if (it.key in allowed) it.key else "other" }
        .mapValues { (_, entries) -> entries.sumOf { it.value } }
}

private fun safeOutcomes(values: Map<String, Int>): Map<String, Int> {
    val allowed = setOf("recognized", "no_speech", "stt_failure", "processing_failure", "delivery_failure", "queue_full", "unknown")
    return values.entries.groupBy { if (it.key in allowed) it.key else "unknown" }
        .mapValues { (_, entries) -> entries.sumOf { it.value } }
}

private fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

private fun optional(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

private fun nullableNumber(value: Int?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
