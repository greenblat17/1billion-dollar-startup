package com.eliteteam.speakingcoach.ai

import com.eliteteam.speakingcoach.MetricsSource
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal const val AI_INTERNAL_TOKEN_HEADER = "X-Internal-Token"

class HttpClipClient(
    baseUrl: String,
    private val http: HttpClient,
    private val pollInterval: Duration = 300.milliseconds,
    private val timeout: Duration = 90.seconds,
    private val internalToken: String = "",
) : ClipProcessor {
    private val root = baseUrl.trimEnd('/')

    suspend fun startSession(sessionId: SessionId? = null): SessionGreeting {
        val created = createSession(sessionId)
        val audio = http.get("$root/v1/sessions/${created.sessionId}/greeting/audio") {
            applyInternalToken()
        }
        if (!audio.status.isSuccess()) {
            error("ai-service GET greeting audio returned ${audio.status}")
        }
        val contentType = audio.headers[HttpHeaders.ContentType] ?: "audio/ogg"
        return SessionGreeting(
            sessionId = SessionId(created.sessionId),
            text = created.greeting.text,
            audio = AudioClip(
                bytes = audio.bodyAsBytes(),
                contentType = contentType.substringBefore(';'),
                fileName = "greeting.ogg",
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

    suspend fun streakProfile(sessionId: SessionId): StreakProfileResponse {
        val response = http.get("$root/internal/streak/${sessionId.value}") {
            applyInternalToken()
        }
        if (!response.status.isSuccess()) {
            error("ai-service GET /internal/streak returned ${response.status}")
        }
        return response.body()
    }

    suspend fun claimReminders(): List<ReminderTarget> {
        val response = http.post("$root/internal/reminders/claim") {
            applyInternalToken()
        }
        if (!response.status.isSuccess()) {
            error("ai-service POST /internal/reminders/claim returned ${response.status}")
        }
        return response.body<ReminderClaimResponse>().targets
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
        val jobId = submit(sessionId, clip)
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (deadline.hasNotPassedNow()) {
            when (val status = poll(jobId)) {
                ClipJobStatus.Pending -> delay(pollInterval)
                is ClipJobStatus.Ok -> return ClipReply(
                    corrections = status.corrections,
                    audio = downloadAudio(jobId),
                    transcript = status.transcript,
                    streak = status.streak,
                )
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
        return AudioClip(
            bytes = response.bodyAsBytes(),
            contentType = contentType.substringBefore(';'),
            fileName = "reply.ogg",
        )
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
    data class Ok(val corrections: List<Correction>, val transcript: String, val streak: TurnStreak?) : ClipJobStatus
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
