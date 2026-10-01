package com.eliteteam.speakingcoach.ai

import com.eliteteam.speakingcoach.MetricsSource
import com.eliteteam.speakingcoach.speaking.CallProgress
import com.eliteteam.speakingcoach.speaking.OnboardingStatus
import com.eliteteam.speakingcoach.speaking.AudioClip
import com.eliteteam.speakingcoach.speaking.ClipProcessor
import com.eliteteam.speakingcoach.speaking.ClipReply
import com.eliteteam.speakingcoach.speaking.Correction
import com.eliteteam.speakingcoach.speaking.CorrectionKind
import com.eliteteam.speakingcoach.speaking.parseCorrections
import com.eliteteam.speakingcoach.speaking.SessionGreeting
import com.eliteteam.speakingcoach.speaking.SessionId
import com.eliteteam.speakingcoach.speaking.TurnStreak
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import java.util.Base64

internal const val AI_INTERNAL_TOKEN_HEADER = "X-Internal-Token"

class HttpClipClient(
    baseUrl: String,
    private val http: HttpClient,
    private val pollInterval: Duration = 300.milliseconds,
    private val timeout: Duration = 90.seconds,
    private val internalToken: String = "",
) : ClipProcessor {
    private val root = baseUrl.trimEnd('/')
    private val log = LoggerFactory.getLogger(HttpClipClient::class.java)

    suspend fun startSession(sessionId: SessionId? = null): SessionGreeting {
        val created = createSession(sessionId)
        val audio = http.get("$root/v1/sessions/${created.sessionId}/greeting/audio") {
            applyInternalToken()
        }
        if (!audio.status.isSuccess()) {
            error("ai-service GET greeting audio returned ${audio.status}")
        }
        val contentType = audio.headers[HttpHeaders.ContentType] ?: "audio/ogg"
        val mediaType = contentType.substringBefore(';').trim().lowercase()
        return SessionGreeting(
            sessionId = SessionId(created.sessionId),
            text = created.greeting.text,
            audio = AudioClip(
                bytes = audio.bodyAsBytes(),
                contentType = mediaType,
                fileName = "greeting.${voiceExtension(mediaType)}",
            ),
        )
    }

    suspend fun recordFunnelStart(sessionId: SessionId, source: String?, profile: ChatProfile) {
        val response = http.post("$root/internal/funnel/start") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(FunnelStartRequest(sessionId.value, source, profile.username, profile.name))
        }
        if (!response.status.isSuccess()) {
            error("ai-service POST /internal/funnel/start returned ${response.status}")
        }
    }

    suspend fun recordFunnelVoice(sessionId: SessionId, profile: ChatProfile) {
        val response = http.post("$root/internal/funnel/voice") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(FunnelVoiceRequest(sessionId.value, profile.username, profile.name))
        }
        if (!response.status.isSuccess()) {
            error("ai-service POST /internal/funnel/voice returned ${response.status}")
        }
    }

    suspend fun progressProfile(sessionId: SessionId): ProgressProfileResponse {
        val response = http.get("$root/internal/profile/${sessionId.value}") { applyInternalToken() }
        check(response.status.isSuccess()) { "ai-service progress profile returned ${response.status}" }
        return response.body()
    }

    suspend fun speechSpeed(sessionId: SessionId): Double {
        val response = http.get("$root/internal/speech-speed/${sessionId.value}") { applyInternalToken() }
        check(response.status.isSuccess()) { "ai-service speech speed returned ${response.status}" }
        return response.body<SpeechSpeedResponse>().speed
    }

    suspend fun setSpeechSpeed(sessionId: SessionId, speed: Double): Double {
        val response = http.post("$root/internal/speech-speed") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(SpeechSpeedRequest(sessionId.value, speed))
        }
        check(response.status.isSuccess()) { "ai-service speech speed update returned ${response.status}" }
        return response.body<SpeechSpeedResponse>().speed
    }

    suspend fun streakProfile(sessionId: SessionId): StreakProfileResponse {
        val response = http.get("$root/internal/streak/${sessionId.value}") {
            applyInternalToken()
        }
        if (!response.status.isSuccess()) {
            error("ai-service GET /internal/streak returned ${response.status}")
        }
        return response.body()
    }

    suspend fun claimReminders(mode: String): List<ReminderTarget> {
        val response = http.post("$root/internal/reminders/claim") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(ReminderClaimRequest(mode))
        }
        if (!response.status.isSuccess()) {
            error("ai-service POST /internal/reminders/claim returned ${response.status}")
        }
        return response.body<ReminderClaimResponse>().targets
    }

    suspend fun legacyCampaignStatus(): LegacyCampaignStatus {
        val response = http.get("$root/internal/campaign/legacy-onboarding") { applyInternalToken() }
        check(response.status.isSuccess()) { "ai-service campaign status returned ${response.status}" }
        return response.body()
    }

    suspend fun claimLegacyCampaign(): List<Long> {
        val response = http.post("$root/internal/campaign/legacy-onboarding/claim") { applyInternalToken() }
        check(response.status.isSuccess()) { "ai-service campaign claim returned ${response.status}" }
        return response.body<LegacyCampaignClaim>().chatIds
    }

    suspend fun reportLegacyCampaign(chatId: Long, status: String) {
        val response = http.post("$root/internal/campaign/legacy-onboarding/report") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(LegacyCampaignReport(chatId, status))
        }
        check(response.status.isSuccess()) { "ai-service campaign report returned ${response.status}" }
    }

    suspend fun reminderTime(sessionId: SessionId): String? {
        val response = http.get("$root/internal/reminders/${sessionId.value}") { applyInternalToken() }
        check(response.status.isSuccess()) { "ai-service reminder time returned ${response.status}" }
        return response.body<ReminderTimeResponse>().time?.takeIf { it.isNotBlank() }
    }

    suspend fun scheduleReminder(
        sessionId: SessionId,
        requestId: String,
        action: String,
        runId: String = "",
        text: String = "",
    ): ReminderScheduleResponse {
        val response = http.post("$root/internal/reminders/schedule") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(ReminderScheduleRequest(sessionId.value, requestId, action, runId, text))
        }
        check(response.status.isSuccess()) { "ai-service reminder schedule returned ${response.status}" }
        return response.body()
    }

    suspend fun reportReminders(report: ReminderReport) {
        val response = http.post("$root/internal/reminders/report") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(report)
        }
        if (!response.status.isSuccess()) {
            error("ai-service POST /internal/reminders/report returned ${response.status}")
        }
    }

    suspend fun ensureSession(sessionId: SessionId): SessionId {
        return SessionId(createSession(sessionId).sessionId)
    }

    suspend fun onboardingState(sessionId: SessionId, requestId: String, reset: String = ""): OnboardingStateResponse {
        val response = http.post("$root/internal/onboarding/state") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(OnboardingRequest(sessionId.value, requestId, reset = reset))
        }
        check(response.status.isSuccess()) { "ai-service onboarding state returned ${response.status}" }
        return response.body()
    }

    suspend fun savePracticeGoal(sessionId: SessionId, requestId: String, minutes: Int) {
        val response = http.post("$root/internal/onboarding/goal") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(PracticeGoalRequest(sessionId.value, requestId, minutes))
        }
        check(response.status.isSuccess()) { "ai-service practice goal returned ${response.status}" }
    }

    suspend fun openCall(sessionId: SessionId): OpenCallResponse {
        val response = http.post("$root/internal/calls/open") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(CallSessionRequest(sessionId.value))
        }
        check(response.status.isSuccess()) { "ai-service open call returned ${response.status}" }
        return response.body()
    }

    suspend fun callStatus(sessionId: SessionId): CallStatusResponse {
        val response = http.post("$root/internal/calls/status") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(CallSessionRequest(sessionId.value))
        }
        check(response.status.isSuccess()) { "ai-service call status returned ${response.status}" }
        return response.body()
    }

    suspend fun startCall(sessionId: SessionId): StartCallResponse {
        val response = http.post("$root/internal/calls/start") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(CallSessionRequest(sessionId.value))
        }
        check(response.status.isSuccess()) { "ai-service start call returned ${response.status}" }
        return response.body()
    }

    suspend fun markCallStarterDelivered(callId: String) {
        val response = http.post("$root/internal/calls/starter-delivered") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(CallReviewRequest(callId))
        }
        check(response.status.isSuccess()) { "ai-service call starter delivery returned ${response.status}" }
    }

    suspend fun endCall(sessionId: SessionId): EndCallResponse {
        val response = http.post("$root/internal/calls/end") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(CallSessionRequest(sessionId.value))
        }
        check(response.status.isSuccess()) { "ai-service end call returned ${response.status}" }
        return response.body()
    }

    suspend fun reviewCall(callId: String): CallReviewResponse {
        val response = http.post("$root/internal/calls/review") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(CallReviewRequest(callId))
        }
        check(response.status.isSuccess()) { "ai-service call review returned ${response.status}" }
        return response.body()
    }

    suspend fun onboardingAction(sessionId: SessionId, requestId: String, runId: String, action: String): ClipReply {
        val response = http.post("$root/internal/onboarding/actions") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(OnboardingRequest(sessionId.value, requestId, runId = runId, action = action))
        }
        check(response.status == HttpStatusCode.Accepted) { "ai-service onboarding action returned ${response.status}" }
        return awaitJob(response.body<ClipAcceptedResponse>().jobId)
    }

    suspend fun loadMetrics(): MetricsSnapshot {
        val response = http.get("$root/internal/metrics") {
            applyInternalToken()
        }
        if (!response.status.isSuccess()) {
            error("ai-service GET /internal/metrics returned ${response.status}")
        }
        return response.body()
    }

    private suspend fun createSession(sessionId: SessionId?): SessionCreatedResponse {
        val response = http.post("$root/v1/sessions") {
            applyInternalToken()
            if (sessionId != null) {
                contentType(ContentType.Application.Json)
                setBody(SessionCreateRequest(sessionId.value))
            }
        }
        if (response.status != HttpStatusCode.Created && !response.status.isSuccess()) {
            error("ai-service POST /v1/sessions returned ${response.status}")
        }
        return response.body()
    }

    override suspend fun process(sessionId: SessionId, clip: AudioClip): ClipReply {
        val started = TimeSource.Monotonic.markNow()
        val jobId = submit(sessionId, clip)
        val submitMs = started.elapsedNow().inWholeMilliseconds
        val reply = awaitJob(jobId)
        log.info(
            "AI clip client session={} job={} submit_ms={} wait_and_audio_ms={} total_ms={}",
            sessionId.value, jobId, submitMs, started.elapsedNow().inWholeMilliseconds - submitMs,
            started.elapsedNow().inWholeMilliseconds,
        )
        return reply
    }

    private suspend fun awaitJob(jobId: String): ClipReply {
        val started = TimeSource.Monotonic.markNow()
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (deadline.hasNotPassedNow()) {
            when (val status = poll(jobId)) {
                ClipJobStatus.Pending -> delay(pollInterval)
                is ClipJobStatus.Ok -> {
                    val pollMs = started.elapsedNow().inWholeMilliseconds
                    val audioStarted = TimeSource.Monotonic.markNow()
                    val audio = if (status.audioAvailable) downloadAudio(jobId) else null
                    log.info(
                        "AI clip result job={} poll_ms={} audio_download_ms={}",
                        jobId, pollMs, audioStarted.elapsedNow().inWholeMilliseconds,
                    )
                    return ClipReply(
                        corrections = status.corrections,
                        audio = audio,
                        text = status.text,
                        onboarding = status.onboarding?.let {
                            OnboardingStatus(
                                it.runId, it.status, it.seconds, it.cefr, it.review,
                                it.overallScore, it.nextBand, it.pointsToNext,
                            )
                        },
                        transcript = status.transcript,
                        streak = status.streak,
                        call = status.call?.let {
                            CallProgress(it.callId, it.todaySeconds, it.goalSeconds, it.goalJustCrossed)
                        },
                    )
                }
                is ClipJobStatus.Failed -> error("ai-service job $jobId failed: ${status.message}")
            }
        }
        error("ai-service job $jobId timed out after $timeout")
    }

    private suspend fun submit(sessionId: SessionId, clip: AudioClip): String {
        val response = http.submitFormWithBinaryData(
            url = "$root/v1/clips",
            formData = formData {
                append("sessionId", sessionId.value)
                clip.onboardingRunId?.let { append("onboardingRunId", it) }
                clip.requestId?.let { append("requestId", it) }
                append("durationSeconds", clip.durationSeconds.toString())
                append(
                    "audio",
                    clip.bytes,
                    Headers.build {
                        append(HttpHeaders.ContentType, clip.contentType)
                        append(HttpHeaders.ContentDisposition, "filename=\"${clip.fileName}\"")
                    },
                )
            },
        ) {
            applyInternalToken()
        }
        if (response.status != HttpStatusCode.Accepted) {
            error("ai-service POST /v1/clips returned ${response.status}")
        }
        return response.body<ClipAcceptedResponse>().jobId
    }

    private suspend fun poll(jobId: String): ClipJobStatus {
        val response = http.get("$root/v1/clips/$jobId") {
            applyInternalToken()
        }
        if (response.status == HttpStatusCode.NotFound) {
            return ClipJobStatus.Failed("unknown job")
        }
        if (!response.status.isSuccess()) {
            error("ai-service GET /v1/clips/$jobId returned ${response.status}")
        }
        val body = response.body<ClipStatusResponse>()
        return when (body.status) {
            "pending" -> ClipJobStatus.Pending
            "ok" -> ClipJobStatus.Ok(
                corrections = body.result?.let(::corrections).orEmpty(),
                transcript = body.result?.transcript?.ifBlank { null } ?: body.transcript.orEmpty(),
                streak = body.result?.streak?.let(::turnStreak),
                text = body.replyText,
                audioAvailable = body.result?.audioAvailable ?: true,
                onboarding = body.result?.onboarding,
                call = body.result?.call,
            )
            "error" -> ClipJobStatus.Failed(body.error?.message ?: "unknown error")
            else -> ClipJobStatus.Failed("unexpected status ${body.status}")
        }
    }

    private suspend fun downloadAudio(jobId: String): AudioClip {
        val response = http.get("$root/v1/clips/$jobId/audio") {
            applyInternalToken()
        }
        if (!response.status.isSuccess()) {
            error("ai-service GET /v1/clips/$jobId/audio returned ${response.status}")
        }
        val contentType = response.headers[HttpHeaders.ContentType] ?: "audio/ogg"
        val mediaType = contentType.substringBefore(';').trim().lowercase()
        return AudioClip(
            bytes = response.bodyAsBytes(),
            contentType = mediaType,
            fileName = "reply.${voiceExtension(mediaType)}",
        )
    }

    private fun voiceExtension(contentType: String): String = when (contentType) {
        "audio/ogg" -> "ogg"
        "audio/mpeg" -> "mp3"
        else -> error("unsupported reply audio type $contentType")
    }

    private fun HttpRequestBuilder.applyInternalToken() {
        if (internalToken.isNotBlank()) {
            header(AI_INTERNAL_TOKEN_HEADER, internalToken)
        }
    }
}

data class ChatProfile(
    val username: String?,
    val name: String?,
)

internal class HttpMetricsSource(
    private val clips: HttpClipClient,
) : MetricsSource {
    override suspend fun load(): MetricsSnapshot = clips.loadMetrics()
}

private sealed interface ClipJobStatus {
    data object Pending : ClipJobStatus
    data class Ok(
        val corrections: List<Correction>,
        val transcript: String,
        val streak: TurnStreak?,
        val text: String,
        val audioAvailable: Boolean,
        val onboarding: OnboardingStateResponse?,
        val call: CallClipResponse?,
    ) : ClipJobStatus
    data class Failed(val message: String) : ClipJobStatus
}

private fun turnStreak(streak: ClipStreakResponse): TurnStreak = TurnStreak(
    current = streak.current,
    best = streak.best,
    firstToday = streak.firstToday,
    firstEver = streak.firstEver,
    newRecord = streak.newRecord,
)

private fun corrections(result: ClipResultResponse): List<Correction> {
    if (result.corrections.isEmpty()) {
        return parseCorrections(result.notes)
    }
    return result.corrections.mapNotNull { item ->
        val wrong = item.wrong.trim()
        val better = item.better.trim()
        if (wrong.isEmpty() || better.isEmpty()) {
            null
        } else {
            Correction(wrong, better, CorrectionKind.fromWire(item.kind))
        }
    }
}
