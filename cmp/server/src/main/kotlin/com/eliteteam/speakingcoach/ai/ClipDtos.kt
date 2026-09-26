package com.eliteteam.speakingcoach.ai

import kotlinx.serialization.Serializable

@Serializable
data class SessionCreateRequest(
    val sessionId: String? = null,
)

@Serializable
data class SessionCreatedResponse(
    val sessionId: String,
    val greeting: GreetingResponse,
)

@Serializable
data class GreetingResponse(
    val text: String,
)

@Serializable
data class ClipAcceptedResponse(
    val jobId: String,
)

@Serializable
data class ClipStatusResponse(
    val jobId: String,
    val status: String,
    val result: ClipResultResponse? = null,
    val error: ClipErrorResponse? = null,
    val transcript: String? = null,
)

@Serializable
data class ClipResultResponse(
    val notes: List<String> = emptyList(),
    val corrections: List<ClipCorrectionResponse> = emptyList(),
    val transcript: String = "",
    val streak: ClipStreakResponse? = null,
)

@Serializable
data class ClipStreakResponse(
    val current: Int = 0,
    val best: Int = 0,
    val firstToday: Boolean = false,
    val firstEver: Boolean = false,
    val newRecord: Boolean = false,
)

@Serializable
data class ClipCorrectionResponse(
    val wrong: String = "",
    val better: String = "",
    val kind: String? = null,
)

@Serializable
data class ClipErrorResponse(
    val code: String,
    val message: String,
)

@Serializable
data class MetricsSnapshot(
    val timezone: String,
    val day: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val tpm: Long,
    val tps: Double,
    val turns: Long,
    val dau: Long,
    val sttSeconds: Double,
    val ttsChars: Long,
    val rubPerTurn: Double? = null,
    val rubPerDau: Double? = null,
    val ratesConfigured: Boolean = false,
    val chats: List<MetricsChat> = emptyList(),
    val activated7: Long = 0,
    val funnelDays: List<FunnelDay> = emptyList(),
    val funnelSources: List<FunnelSource> = emptyList(),
    val reminders: RemindersSnapshot? = null,
    val streaks: StreaksSnapshot? = null,
)

@Serializable
data class RemindersSnapshot(
    val today: ReminderTotals = ReminderTotals(),
    val week: ReminderTotals = ReminderTotals(),
    val days: List<ReminderDay> = emptyList(),
    val segments: List<ReminderSegment> = emptyList(),
    val templates: List<ReminderTemplateStats> = emptyList(),
    val replyMedianSeconds: Long? = null,
    val runs: List<ReminderRun> = emptyList(),
    val autoToday: ReminderRun? = null,
    val forecast: Long = 0,
)

@Serializable
data class ReminderTotals(
    val sent: Long = 0,
    val blocked: Long = 0,
    val failed: Long = 0,
    val returned: Long = 0,
)

@Serializable
data class ReminderDay(
    val day: String,
    val sent: Long = 0,
    val blocked: Long = 0,
    val failed: Long = 0,
    val returned: Long = 0,
)

@Serializable
data class ReminderSegment(
    val segment: String,
    val sent: Long = 0,
    val returned: Long = 0,
)

@Serializable
data class ReminderTemplateStats(
    val templateId: String,
    val sent: Long = 0,
    val returned: Long = 0,
    val blocked: Long = 0,
)

@Serializable
data class ReminderRun(
    val mode: String,
    val day: String = "",
    val startedAt: String = "",
    val finishedAt: String = "",
    val claimed: Long = 0,
    val sent: Long = 0,
    val blocked: Long = 0,
    val failed: Long = 0,
)

@Serializable
data class ReminderReport(
    val mode: String,
    val startedAt: String,
    val finishedAt: String,
    val claimed: Int,
    val results: List<ReminderSendResult>,
)

@Serializable
data class ReminderSendResult(
    val sessionId: String,
    val templateId: String,
    val status: String,
)

@Serializable
data class FunnelDay(
    val day: String,
    val start: Long = 0,
    val activated: Long = 0,
    val engaged: Long = 0,
    val returned: Long = 0,
)

@Serializable
data class FunnelSource(
    val source: String,
    val start: Long = 0,
    val activated: Long = 0,
    val engaged: Long = 0,
    val returned: Long = 0,
)

@Serializable
data class FunnelStartRequest(
    val sessionId: String,
    val source: String? = null,
    val username: String? = null,
    val name: String? = null,
)

@Serializable
data class FunnelVoiceRequest(
    val sessionId: String,
    val username: String? = null,
    val name: String? = null,
)

@Serializable
data class ReminderClaimResponse(
    val targets: List<ReminderTarget> = emptyList(),
)

@Serializable
data class ReminderTarget(
    val sessionId: String,
    val name: String? = null,
    val streak: Int = 0,
)

@Serializable
data class StreakProfileResponse(
    val current: Int = 0,
    val best: Int = 0,
    val last7: List<Boolean> = emptyList(),
)

@Serializable
data class StreakBucket(
    val bucket: String,
    val users: Long = 0,
)

@Serializable
data class StreakReminderBucket(
    val bucket: String,
    val sent: Long = 0,
    val returned: Long = 0,
)

@Serializable
data class RetentionCohort(
    val week: String = "",
    val size: Long = 0,
    val d1: Double? = null,
    val d7: Double? = null,
    val d30: Double? = null,
)

@Serializable
data class RetentionSlice(
    val size: Long = 0,
    val d1: Double? = null,
    val d7: Double? = null,
    val d30: Double? = null,
)

@Serializable
data class RetentionSnapshot(
    val cohorts: List<RetentionCohort> = emptyList(),
    val before: RetentionSlice = RetentionSlice(),
    val after: RetentionSlice = RetentionSlice(),
    val releasedDay: String? = null,
)

@Serializable
data class StreaksSnapshot(
    val buckets: List<StreakBucket> = emptyList(),
    val reminderBuckets: List<StreakReminderBucket> = emptyList(),
    val retention: RetentionSnapshot = RetentionSnapshot(),
)

@Serializable
data class MetricsChat(
    val sessionId: String,
    val turns: Long,
    val lastAt: String,
    val username: String? = null,
    val name: String? = null,
    val lastReminderAt: String? = null,
    val reminderIgnored: Long = 0,
)
