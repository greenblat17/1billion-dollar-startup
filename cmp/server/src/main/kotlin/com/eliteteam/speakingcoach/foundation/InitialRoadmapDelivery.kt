package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.RuntimeDiagnostics
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Event
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Outcome
import com.eliteteam.speakingcoach.telegram.TutorTextRequest
import com.eliteteam.speakingcoach.telegram.TutorTextPart
import com.eliteteam.speakingcoach.telegram.telegramRequestJson
import com.eliteteam.speakingcoach.telegram.tutorTextParts
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** One immutable initial snapshot per completed Trial. Unknown Telegram sends are never repeated. */
internal class InitialRoadmapDelivery(private val service: FoundationService, private val http: HttpClient,
    private val aiRoot: String, private val internalToken: String, private val botToken: String,
    private val miniUrl: String) {
    suspend fun tick() {
        val delivery = service.database.transaction { c, now ->
            val expired = c.update("UPDATE initial_roadmap_delivery SET status='pending' WHERE status='generating' AND lease_until<=?", now)
            if (expired > 0) FoundationDatabase.recordAfterCommit(Event("initial_roadmap_delivery", "roadmap.deliver",
                "recover_preparation", Outcome.RETRY_WAIT, "Foundation returned expired Roadmap preparations to the queue.",
                "preparation_lease_expired"), mapOf("count" to expired.toString()))
            val candidate = c.document("""SELECT jsonb_build_object('student',r.student_id,'lesson',j.target_id,
                'revision',r.revision,'epoch',s.data_epoch,'sourceJobId',r.generation_job_id,
                'sourceFence',r.generation_fence) FROM learning_roadmap r
                JOIN learning_roadmap_priority p ON p.student_id=r.student_id AND p.roadmap_revision=r.revision
                    AND p.stale_at IS NULL AND p.document->>'trigger'='initial'
                JOIN student s ON s.id=r.student_id AND s.deleted_at IS NULL
                JOIN foundation_job j ON j.student_id=r.student_id AND j.kind='roadmap.generate'
                    AND j.status='succeeded' AND j.target_id IS NOT NULL AND j.data_epoch=s.data_epoch
                    AND (r.legacy_generation_source OR (j.id=r.generation_job_id AND j.fence=r.generation_fence))
                JOIN lesson l ON l.id=j.target_id AND l.kind='trial' AND l.trial_version='v5.5' AND l.status='completed'
                WHERE EXISTS (SELECT 1 FROM student_identity i WHERE i.student_id=r.student_id AND i.provider='telegram')
                AND NOT EXISTS (SELECT 1 FROM initial_roadmap_delivery d WHERE d.student_id=r.student_id
                    AND d.roadmap_revision=r.revision)
                ORDER BY r.created_at LIMIT 1""")
            if (candidate != null) {
                c.lockStudent(candidate.text("student"))
                c.update("""INSERT INTO initial_roadmap_delivery(id,student_id,lesson_id,roadmap_revision,data_epoch,status,
                    created_at,source_job_id,source_job_fence)
                    SELECT ?,s.id,?,r.revision,s.data_epoch,'pending',?,?,? FROM student s
                    JOIN learning_roadmap r ON r.student_id=s.id
                    WHERE s.id=? AND s.data_epoch=? AND s.deleted_at IS NULL AND r.revision=?
                    ON CONFLICT DO NOTHING""", newId(), candidate.text("lesson"), now,
                    candidate.optionalText("sourceJobId"), candidate.optionalText("sourceFence")?.toLong(),
                    candidate.text("student"), candidate.number("epoch"), candidate.number("revision"))
            }
            val row = c.document("""SELECT to_jsonb(d) FROM initial_roadmap_delivery d JOIN student s ON s.id=d.student_id
                WHERE (d.status='ready' OR (d.status='pending' AND (d.lease_until IS NULL OR d.lease_until<=?)))
                    AND d.data_epoch=s.data_epoch AND s.deleted_at IS NULL
                    AND EXISTS (SELECT 1 FROM student_identity i WHERE i.student_id=d.student_id AND i.provider='telegram')
                ORDER BY d.created_at LIMIT 1""", now) ?: return@transaction null
            c.lockStudent(row.text("student_id"))
            val current = c.document("""SELECT to_jsonb(d) FROM initial_roadmap_delivery d JOIN student s ON s.id=d.student_id
                WHERE d.id=? AND (d.status='ready' OR (d.status='pending' AND (d.lease_until IS NULL OR d.lease_until<=?)))
                AND d.data_epoch=s.data_epoch AND s.deleted_at IS NULL FOR UPDATE OF d""", row.text("id"), now) ?: return@transaction null
            if (current.text("status") == "pending") {
                c.update("UPDATE initial_roadmap_delivery SET status='generating',lease_until=?,attempts=attempts+1 WHERE id=?", now.plusSeconds(90), current.text("id"))
            }
            current
        } ?: return
        val preparing = delivery.text("status") == "pending"
        val context = buildMap {
            put("trace_id", RuntimeDiagnostics.trace(delivery.text("id")))
            put("delivery_id", delivery.text("id"))
            put("lesson_id", delivery.text("lesson_id"))
            put("roadmap_revision", delivery.number("roadmap_revision").toString())
            put("attempt", (delivery.number("attempts") + if (preparing) 1 else 0).toString())
            delivery.optionalText("source_job_id")?.let { put("source_job_id", it) }
            delivery.optionalText("source_job_fence")?.let { put("source_job_fence", it) }
        }
        RuntimeDiagnostics.withContext(context) {
            try {
                if (preparing) {
                    RuntimeDiagnostics.record(Event("initial_roadmap_delivery", "roadmap.deliver", "claim", Outcome.STARTED,
                        "Worker claimed preparation of the initial Roadmap message."))
                    prepare(delivery)
                } else sendNext(delivery)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                RuntimeDiagnostics.record("initial_roadmap_delivery_failed", "worker", error)
                throw error
            }
        }
    }

    private suspend fun prepare(delivery: JsonObject) {
        val student = delivery.text("student_id")
        val attempt = delivery.number("attempts") + 1
        val execution = service.identity.issue(student, "voice", lesson = delivery.text("lesson_id"))
        val response = try {
            http.post("${aiRoot.trimEnd('/')}/internal/trial/initial-roadmap") {
                header("X-Internal-Token", internalToken)
                header("X-Diagnostic-Trace", RuntimeDiagnostics.current()["trace_id"].orEmpty())
                header("X-Diagnostic-Lesson", delivery.text("lesson_id"))
                header("X-Execution-Scope", execution)
                contentType(ContentType.Application.Json)
                setBody(json("roadmapRevision" to delivery.number("roadmap_revision")).toString())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val errorId = RuntimeDiagnostics.record(Event("initial_roadmap_prepare_failed", "roadmap.deliver", "ai_request",
                Outcome.FAILED, "Worker could not request preparation of the Roadmap message.", "ai_transport_error"), error = error)
            retryPreparation(delivery, attempt, errorId)
            return
        }
        val body = try {
            response.bodyAsText()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            RuntimeDiagnostics.record(Event("initial_roadmap_prepare_failed", "roadmap.deliver", "ai_response", Outcome.FAILED,
                "Worker could not read the AI response while preparing the Roadmap message.", "ai_response_unreadable"), error = error)
            throw error
        }
        if (response.status.value == 409) {
            val changed = service.database.transaction { c, _ ->
                c.lockStudent(student)
                c.update("""UPDATE initial_roadmap_delivery SET status='cancelled',body=NULL,lease_until=NULL
                    WHERE id=? AND status='generating' AND attempts=?""", delivery.text("id"), attempt)
            }
            if (changed > 0) RuntimeDiagnostics.record(Event("initial_roadmap_delivery", "roadmap.deliver", "prepare_cancel",
                Outcome.CANCELLED, "Foundation cancelled preparation of the initial Roadmap message after an AI conflict response.",
                "roadmap_preparation_conflict"), RuntimeDiagnostics.httpFields(409, body, response.headers["X-Error-ID"]))
            return
        }
        val reply = if (response.status.value in 200..299) runCatching {
            val result = foundationJson.parseToJsonElement(body).jsonObject
            if (result.number("roadmapRevision") != delivery.number("roadmap_revision")) null else result.text("reply")
        }.getOrNull() else null
        if (reply.isNullOrBlank()) {
            val reason = if (response.status.value in 200..299) "invalid_ai_reply" else "ai_http_error"
            val errorId = RuntimeDiagnostics.record(Event("initial_roadmap_prepare_failed", "roadmap.deliver", "ai_response",
                Outcome.FAILED, "The AI response did not produce a message for this Roadmap revision.", reason),
                RuntimeDiagnostics.httpFields(response.status.value, body, response.headers["X-Error-ID"]))
            retryPreparation(delivery, attempt, errorId)
            return
        }
        val changed = service.database.transaction { c, _ ->
            c.lockStudent(student)
            c.update("""UPDATE initial_roadmap_delivery SET status='ready',body=?,lease_until=NULL
                WHERE id=? AND status='generating' AND attempts=?
                AND data_epoch=(SELECT data_epoch FROM student WHERE id=? AND deleted_at IS NULL)""",
                reply, delivery.text("id"), attempt, student)
        }
        if (changed > 0) RuntimeDiagnostics.record(Event("initial_roadmap_delivery", "roadmap.deliver", "prepare_persist",
            Outcome.COMPLETED, "Foundation saved the prepared Roadmap message."), mapOf("job_status" to "ready"))
        else RuntimeDiagnostics.record(Event("initial_roadmap_delivery", "roadmap.deliver", "prepare_persist",
            Outcome.SUPERSEDED, "Foundation discarded the prepared Roadmap message because the delivery state changed.",
            "delivery_state_changed"))
    }

    private suspend fun retryPreparation(delivery: JsonObject, attempt: Long, errorId: String?) {
        val changed = service.database.transaction { c, now ->
            val delay = 30L * (1L shl minOf(6, delivery.number("attempts").toInt()))
            c.update("""UPDATE initial_roadmap_delivery SET status='pending',lease_until=?
                WHERE id=? AND status='generating' AND attempts=?""",
                now.plusSeconds(delay), delivery.text("id"), attempt)
        }
        if (changed > 0) RuntimeDiagnostics.record(Event("initial_roadmap_delivery", "roadmap.deliver", "prepare_retry",
            Outcome.RETRY_WAIT, "Foundation scheduled another attempt to prepare the Roadmap message.", "roadmap_preparation_failed"),
            mapOf("job_status" to "pending") + (errorId?.let { mapOf("upstream_error_id" to it) }.orEmpty()))
    }

    private suspend fun sendNext(delivery: JsonObject) {
        val body = delivery.optionalText("body") ?: return
        val parts = roadmapParts(body)
        val index = delivery.number("next_part").toInt()
        if (index !in parts.indices) return
        val student = delivery.text("student_id")
        val subject = service.database.transaction { c, _ ->
            c.lockStudent(student)
            val recipient = c.rows("SELECT subject FROM student_identity WHERE student_id=? AND provider='telegram'", student) { it.getString(1) }.singleOrNull()
                ?: return@transaction null
            val claimed = c.update("""UPDATE initial_roadmap_delivery SET status='unknown' WHERE id=? AND status='ready'
                AND next_part=? AND data_epoch=(SELECT data_epoch FROM student WHERE id=? AND deleted_at IS NULL)""",
                delivery.text("id"), index, student)
            if (claimed == 0) return@transaction null
            recipient
        } ?: return
        val partFields = mapOf("provider" to "telegram", "part" to index.toString())
        RuntimeDiagnostics.record("initial_roadmap_delivery", "telegram_request", extra = partFields + ("outcome" to "started"))
        val markup = if (index == parts.lastIndex) json("inline_keyboard" to kotlinx.serialization.json.JsonArray(listOf(
            kotlinx.serialization.json.JsonArray(listOf(json("text" to "Мои встречи", "web_app" to json("url" to miniUrl)))))))
        else null
        val response = try {
            http.post("https://api.telegram.org/bot$botToken/sendMessage") {
                contentType(ContentType.Application.Json)
                setBody(telegramRequestJson.encodeToString(TutorTextRequest(subject.toLong(), parts[index].text,
                    parts[index].entities, markup)))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val timedOut = error is io.ktor.client.plugins.HttpRequestTimeoutException || error is java.net.SocketTimeoutException
            val reason = if (timedOut) "telegram_request_timeout" else "telegram_transport_error"
            val message = if (timedOut) "The Telegram request timed out. Roadmap message delivery is unknown."
                else "The Telegram request failed at the transport boundary. Roadmap message delivery is unknown."
            RuntimeDiagnostics.record(Event("initial_roadmap_delivery_unknown", "roadmap.deliver", "telegram_request",
                Outcome.UNKNOWN, message, reason), partFields, error)
            return
        }
        val sentBody = try {
            response.bodyAsText()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            RuntimeDiagnostics.record(Event("initial_roadmap_delivery_unknown", "roadmap.deliver", "telegram_response",
                Outcome.UNKNOWN, "The Telegram response could not be read. Roadmap message delivery is unknown.", "telegram_response_unreadable"),
                partFields + ("http_status" to response.status.value.toString()), error)
            return
        }
        val ok = runCatching { foundationJson.parseToJsonElement(sentBody).jsonObject.optionalText("ok") }.getOrNull()
        if (ok != "true") {
            val rejected = ok == "false" && response.status.value in 400..499
            val message = if (rejected) "Telegram rejected the Roadmap message request. Foundation left the delivery marked unknown."
                else "The Telegram response did not confirm Roadmap message delivery. The outcome is unknown."
            RuntimeDiagnostics.record(Event("initial_roadmap_delivery_unknown", "roadmap.deliver", "telegram_response",
                if (rejected) Outcome.REJECTED else Outcome.UNKNOWN, message,
                if (rejected) "telegram_request_rejected" else "telegram_response_unconfirmed"),
                partFields + RuntimeDiagnostics.httpFields(response.status.value, sentBody))
            return
        }
        val recorded = service.database.transaction { c, now ->
            c.lockStudent(student)
            val changed = c.update("""UPDATE initial_roadmap_delivery SET status=?,next_part=? WHERE id=? AND status='unknown'
                AND next_part=? AND data_epoch=(SELECT data_epoch FROM student WHERE id=? AND deleted_at IS NULL)""",
                if (index == parts.lastIndex) "sent" else "ready", index + 1, delivery.text("id"), index, student)
            if (changed == 0) return@transaction false
            c.appendEvent(student, c.conversation(student, "telegram"), "roadmap:${delivery.text("id")}:$index",
                "assistant", parts[index].text, now, now)
            true
        }
        if (recorded) RuntimeDiagnostics.record(Event("initial_roadmap_delivery", "roadmap.deliver", "delivery_persist",
            Outcome.COMPLETED, "Telegram accepted the Roadmap message part and Foundation recorded the delivery."),
            partFields + ("job_status" to if (index == parts.lastIndex) "sent" else "ready"))
        else RuntimeDiagnostics.record(Event("initial_roadmap_delivery", "roadmap.deliver", "delivery_persist",
            Outcome.SUPERSEDED, "Telegram accepted the Roadmap message part after its delivery state changed.",
            "delivery_state_changed"), partFields)
    }
}

/** Keep each Task and its reason together when Telegram needs multiple messages. */
internal fun roadmapParts(body: String): List<TutorTextPart> {
    val chunks = mutableListOf<String>()
    var current = ""
    for (paragraph in body.split("\n\n")) {
        val joined = if (current.isEmpty()) paragraph else "$current\n\n$paragraph"
        if (joined.length <= 3000) {
            current = joined
        } else {
            if (current.isNotEmpty()) chunks += current
            if (paragraph.length <= 3000) current = paragraph
            else {
                chunks += tutorTextParts(paragraph, 3000).map { it.text }
                current = ""
            }
        }
    }
    if (current.isNotEmpty()) chunks += current
    return chunks.flatMap { tutorTextParts(it, 3000) }
}
