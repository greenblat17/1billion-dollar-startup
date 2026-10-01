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
    val replyText: String = "",
)

@Serializable
data class ClipResultResponse(
    val notes: List<String> = emptyList(),
    val corrections: List<ClipCorrectionResponse> = emptyList(),
    val transcript: String = "",
    val streak: ClipStreakResponse? = null,
    val audioAvailable: Boolean = true,
    val onboarding: OnboardingStateResponse? = null,
    val call: CallClipResponse? = null,
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
    val llmRequests: Long? = null,
    val llmFailures: Long? = null,
    val llmRequestsByPurpose: Map<String, Long> = emptyMap(),
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
data class LlmRequestPeriod(
    val from: String,
    val to: String,
    val timezone: String,
    val requests: Long,
    val failures: Long,
    val byPurpose: Map<String, Long> = emptyMap(),
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
data class ReminderClockSummary(
    val timezone: String,
    val active: Int,
    val hours: Map<String, Int>,
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
data class ReminderClaimRequest(
    val mode: String,
)

@Serializable
data class ReminderClaimResponse(
    val targets: List<ReminderTarget> = emptyList(),
)

@Serializable
data class ReminderScheduleRequest(
    val sessionId: String,
    val requestId: String,
    val action: String,
    val runId: String = "",
    val text: String = "",
)

@Serializable
data class ReminderScheduleResponse(
    val status: String,
    val time: String? = null,
    val runId: String = "",
)

@Serializable
data class ReminderTimeResponse(
    val time: String? = null,
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

@Serializable
data class OnboardingRequest(
    val sessionId: String,
    val requestId: String,
    val reset: String = "",
    val runId: String = "",
    val action: String = "",
)

@Serializable
data class OnboardingExample(
    val wrong: String = "",
    val better: String = "",
)

@Serializable
data class OnboardingSkill(
    val score: Int? = null,
    val text: String = "",
    val examples: List<OnboardingExample> = emptyList(),
)

@Serializable
data class OnboardingFluency(
    val score: Int? = null,
    val text: String = "",
    val paceWpm: Int? = null,
    val longPauses: Int? = null,
    val fillers: Int? = null,
    val longestStretchSec: Int? = null,
)

@Serializable
data class OnboardingReview(
    val levelText: String = "",
    val grammar: OnboardingSkill = OnboardingSkill(),
    val vocabulary: OnboardingSkill = OnboardingSkill(),
    val fluency: OnboardingFluency = OnboardingFluency(),
)

@Serializable
data class PracticeGoalRequest(
    val sessionId: String,
    val requestId: String,
    val minutes: Int,
)

@Serializable
data class OnboardingVoiceAnalyticsResponse(
    val voiceIndex: Int = 0,
    val telegramDurationSec: Double = 0.0,
    val recognizedDurationSec: Double = 0.0,
    val recognized: Boolean = false,
    val failureReason: String? = null,
    val milestones: List<Int> = emptyList(),
    val completedNow: Boolean = false,
    val assessmentFailed: Boolean = false,
    val cefr: String? = null,
    val overallScore: Int? = null,
    val scoreAvailable: Boolean = false,
    val speechBeforeSec: Double? = null,
    val speechAfterSec: Double? = null,
)

@Serializable
data class OnboardingStateResponse(
    val runId: String = "",
    val status: String,
    val seconds: Double = 0.0,
    val cefr: String? = null,
    val overallScore: Int? = null,
    val nextBand: String? = null,
    val pointsToNext: Int? = null,
    val resultText: String? = null,
    val retryAvailable: Boolean = false,
    val react: Boolean = false,
    val review: OnboardingReview? = null,
    val analytics: OnboardingVoiceAnalyticsResponse? = null,
)

@Serializable
data class CallClipResponse(
    val callId: String = "",
    val todaySeconds: Double = 0.0,
    val goalSeconds: Double = 0.0,
    val goalJustCrossed: Boolean = false,
)

@Serializable
data class CallSessionRequest(
    val sessionId: String,
)

@Serializable
data class OpenCallResponse(
    val callId: String = "",
    val todaySeconds: Double = 0.0,
    val goalSeconds: Double = 0.0,
    val goalJustCrossed: Boolean = false,
    val unseenCallId: String? = null,
)

@Serializable
data class EndCallResponse(
    val callId: String? = null,
)

@Serializable
data class CallReviewRequest(
    val callId: String,
)

@Serializable
data class CallReviewResponse(
    val callId: String = "",
    val retry: Boolean = false,
    val levelText: String = "",
    val recap: String = "",
    val cefr: String? = null,
    val overallScore: Int? = null,
    val previousScore: Int? = null,
    val nextBand: String? = null,
    val pointsToNext: Int? = null,
    val todaySeconds: Double = 0.0,
    val goalSeconds: Double = 0.0,
    val streak: Int = 0,
    val grammar: OnboardingSkill = OnboardingSkill(),
    val vocabulary: OnboardingSkill = OnboardingSkill(),
    val fluency: OnboardingFluency = OnboardingFluency(),
)


@Serializable
data class ProgressAssessment(
    val cefr: String? = null,
    val overallScore: Int? = null,
    val nextBand: String? = null,
    val pointsToNext: Int? = null,
    val grammar: Int? = null,
    val vocabulary: Int? = null,
    val fluency: Int? = null,
)

@Serializable
data class ProgressProfileResponse(
    val assessment: ProgressAssessment? = null,
    val dailyMinutes: Int? = null,
    val currentStreak: Int = 0,
)
