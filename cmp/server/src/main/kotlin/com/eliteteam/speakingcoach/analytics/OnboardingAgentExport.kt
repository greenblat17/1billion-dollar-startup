package com.eliteteam.speakingcoach.analytics

import java.time.Instant
import com.eliteteam.speakingcoach.ai.ReminderClockSummary
import com.eliteteam.speakingcoach.LlmRange
import com.eliteteam.speakingcoach.ai.LlmRequestPeriod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val EXPORT_SCHEMA = "onboarding-analytics.v7"
private val exportJson = Json { prettyPrint = true }

/** Aggregated, content-free snapshot that an analyst agent can consume without parsing HTML. */
internal fun onboardingAgentJson(report: OnboardingReport, generatedAt: Instant,
                                 reminderSummary: ReminderClockSummary? = null,
                                 llm: LlmRequestPeriod? = null, llmRange: LlmRange? = null): String =
    exportJson.encodeToString(JsonObject.serializer(), onboardingAgentData(report, generatedAt, reminderSummary, llm, llmRange))

internal fun onboardingAgentData(report: OnboardingReport, generatedAt: Instant,
                                 reminderSummary: ReminderClockSummary? = null,
                                 llm: LlmRequestPeriod? = null, llmRange: LlmRange? = null): JsonObject = buildJsonObject {
    put("schema_version", EXPORT_SCHEMA)
    put("generated_at_utc", generatedAt.toString())
    put("timezone", ONBOARDING_ZONE.id)
    put("filters", buildJsonObject {
        put("start_days", report.filter.days)
        put("onboarding_version", optional(report.filter.version))
        put("start_source", optional(report.filter.source))
        put("trigger", optional(report.filter.trigger))
        put("journey_mode", if (report.filter.journeyMode == JourneyMode.PRIMARY) "primary_users" else "repeat_attempts")
        put("llm_from", optional(llmRange?.from?.toString()))
        put("llm_to", optional(llmRange?.to?.toString()))
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
        put("activation_a7", "First eligible /start with delivered result followed by a recognized ordinary voice and delivered bot reply within 7*24 hours; denominator is eligible entries whose full window has elapsed")
        put("practice_two_days", "Successful ordinary replies on two distinct Moscow dates within the same 7*24 hour window")
        put("entry_completeness", "Entries are written after chat action; process crash can lose an entry, so total Telegram traffic is not fully observed")
        put("current_reminders", "Current saved reminder settings across all users, independent of onboarding date/version/source filters; hours are Moscow time")
        put("llm_requests_period", "Application attempts to call the LLM from llm_from through llm_to inclusive (Moscow days), across all users and onboarding versions, including failed attempts and application retries; SDK retries are not counted")
        put("llm_requests_today", "Legacy alias for llm_requests_period only when the selected range is the current Moscow day; otherwise null")
        put("journey_window", "Stages within 24 hours of observed eligible start; open attempts are excluded from stopped counts")
        put("journey_stopped", "Closed attempt reached a stage but not the next required stage, excluding missing or out-of-order facts; a recorded error does not prove causality")
        put("journey_errors", "Errors and unsuccessful outcomes among the filtered starts, grouped by user stage, technical stage, and safe reason; no_speech is not a technical STT failure")
        put("error_recovery", "For each error type, first occurrence per selected attempt inside a closed 24-hour start window; observed retry, next required stage, and full completion after that error within the window. Callback retry denominator includes only attempts with an observed action-attempt event at or before failure.")
        put("post_completion_practice", "Primary users completing every required stage within 24 hours; first recognized ordinary voice with delivered bot reply after final reminder decision. Denominators include only completions tracked after this metric was introduced and mature for 24 hours or 7 days after completion.")
    })
    put("analysis_guidance", strings(listOf(
        "Report observations and counts before making recommendations; these aggregates do not identify causes.",
        "Compare equivalent closed cohorts and onboarding versions; do not treat open cohorts as drop-off.",
        "Use numerator and denominator for every rate. Null percent means there was no denominator.",
        "Separate no_speech and STT failure from processing, delivery, and queue failures.",
        "Old v1 attempts can lack newer events. Process counters reset on server restart.",
        "No audio, transcripts, profile text, user IDs, or attempt IDs are present in this export.",
        "Do not use filtered onboarding cohorts as the denominator for current_reminders.",
        "Do not use filtered onboarding cohorts as the denominator for llm_requests_period.",
        "The recent fifteen usernames and chat IDs are available only on the protected HTML page, not in this export.",
    )))
    put("current_reminders", reminderSummary?.let { summary -> buildJsonObject {
        put("scope", "all_users_current")
        put("timezone", summary.timezone)
        put("active", summary.active)
        put("by_hour", countMap(summary.hours))
    } } ?: JsonNull)
    val llmData = llm?.let { summary -> buildJsonObject {
        put("scope", "all_users_selected_moscow_days")
        put("from", summary.from)
        put("to", summary.to)
        put("timezone", summary.timezone)
        put("requests", summary.requests)
        put("failures", summary.failures)
        put("by_purpose", countMap(summary.byPurpose))
    } }
    put("llm_requests_period", llmData ?: JsonNull)
    val today = generatedAt.atZone(ONBOARDING_ZONE).toLocalDate().toString()
    put("llm_requests_today", if (llm != null && llm.from == today && llm.to == today) buildJsonObject {
        put("scope", "all_users_today")
        put("day", today)
        put("timezone", llm.timezone)
        put("requests", llm.requests)
        put("failures", llm.failures)
        put("by_purpose", countMap(llm.byPurpose))
    } else JsonNull)
    put("cohorts", buildJsonObject {
        put("closed_primary", JsonArray(report.closedPrimary.map { cohort(it, closed = true, primary = true) }))
        put("open_primary", JsonArray(report.openPrimary.map { cohort(it, closed = false, primary = true) }))
        put("closed_repeats", JsonArray(report.closedRepeats.map { cohort(it, closed = true, primary = false) }))
        put("open_repeat_attempts", report.openRepeatCount)
    })
    put("journey", buildJsonObject {
        val journey = report.journey
        put("population", if (report.filter.journeyMode == JourneyMode.PRIMARY) "first_eligible_start_users" else "repeat_attempts")
        put("total", journey.total)
        put("open", journey.open)
        put("closed", journey.closed)
        put("incomplete", journey.incomplete)
        put("stages", JsonArray(journey.steps.map { step -> buildJsonObject {
            put("id", step.stage.id)
            put("label", step.stage.label)
            put("reached", step.reached)
            put("continued", step.continued)
            put("stopped", step.stopped)
            put("stopped_with_unsuccessful_outcome", step.stoppedWithOutcome)
        } }))
        put("errors", JsonArray(journey.errors.map { error -> buildJsonObject {
            put("stage_id", error.stageId)
            put("technical_stage", error.technicalStage)
            put("reason", error.reason)
            put("events", error.events)
            put("affected_users", error.users)
            put("affected_attempts", error.attempts)
            put("recovery_eligible", error.recoveryEligible)
            put("retry_measurable", error.retryMeasurable)
            put("retried", error.retried)
            put("reached_next_stage_after_error", error.reachedNext)
            put("completed_after_error", error.completed)
        } }))
        put("post_completion_practice", journey.practice?.let { practice -> buildJsonObject {
            put("completed", practice.completed)
            put("tracked", practice.tracked)
            put("within_24_hours", ratio(practice.practiced24, practice.mature24))
            put("within_7_days", ratio(practice.practiced7, practice.mature7))
        } } ?: JsonNull)
    })
    put("activation_cohorts", JsonArray(report.activation.map { c -> buildJsonObject {
        put("start_day", c.day.toString())
        put("eligible_entries", c.eligible)
        put("invitations_delivered", ratio(c.invitations, c.eligible))
        put("begin_pressed", ratio(c.beginPressed, c.invitations))
        put("first_question_delivered", ratio(c.firstQuestions, c.beginPressed))
        put("first_voice_received", ratio(c.firstVoices, c.firstQuestions))
        put("first_voice_recognized", ratio(c.recognizedVoices, c.firstQuestions))
        put("result_delivered_within_7_days", ratio(c.results, c.eligible))
        put("mature_entries", c.mature)
        put("a7", ratio(c.activated, c.mature))
        put("practice_two_days", ratio(c.practiceTwoDays, c.mature))
        put("missing_attempt_links", c.missingAttempt)
        put("seconds_to_result_p50", nullableNumber(c.resultP50Sec))
        put("seconds_to_practice_p50", nullableNumber(c.practiceP50Sec))
    } }))
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
        put("by_voice_index", JsonArray(diagnostics.turns.map { t -> buildJsonObject {
            put("index", t.index)
            put("voices", t.voices)
            put("recognized", t.recognized)
            put("no_speech", t.noSpeech)
            put("technical_failures", t.technicalFailures)
            put("next_voice", ratio(t.nextVoice, t.voices))
            put("result_after", ratio(t.resultAfter, t.voices))
        } }))
        put("last_voice_outcome_by_attempt", countMap(safeOutcomes(diagnostics.lastOutcomes)))
        put("attempts_with_consecutive_recognition_failures", diagnostics.attemptsWithConsecutiveRecognitionFailures)
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

private fun countMap(values: Map<String, Number>): JsonObject =
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
