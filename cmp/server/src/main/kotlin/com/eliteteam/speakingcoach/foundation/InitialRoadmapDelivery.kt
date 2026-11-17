package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.RuntimeDiagnostics
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** One immutable initial snapshot per completed Trial. Unknown Telegram sends are never repeated. */
internal class InitialRoadmapDelivery(private val service: FoundationService, private val http: HttpClient,
    private val aiRoot: String, private val internalToken: String, private val botToken: String,
    private val miniUrl: String) {
    suspend fun tick() {
        val delivery = service.database.transaction { c, now ->
            c.update("UPDATE initial_roadmap_delivery SET status='pending' WHERE status='generating' AND lease_until<=?", now)
            val candidate = c.document("""SELECT jsonb_build_object('student',r.student_id,'lesson',j.target_id,
                'revision',r.revision,'epoch',s.data_epoch) FROM learning_roadmap r
                JOIN learning_roadmap_priority p ON p.student_id=r.student_id AND p.roadmap_revision=r.revision
                    AND p.stale_at IS NULL AND p.document->>'trigger'='initial'
                JOIN student s ON s.id=r.student_id AND s.deleted_at IS NULL
                JOIN foundation_job j ON j.student_id=r.student_id AND j.kind='roadmap.generate'
                    AND j.status='succeeded' AND j.target_id IS NOT NULL AND j.data_epoch=s.data_epoch
                JOIN lesson l ON l.id=j.target_id AND l.kind='trial' AND l.trial_version='v5.5' AND l.status='completed'
                WHERE NOT EXISTS (SELECT 1 FROM initial_roadmap_delivery d WHERE d.student_id=r.student_id
                    AND d.roadmap_revision=r.revision)
                ORDER BY r.created_at LIMIT 1""")
            if (candidate != null) {
                c.lockStudent(candidate.text("student"))
                c.update("""INSERT INTO initial_roadmap_delivery(id,student_id,lesson_id,roadmap_revision,data_epoch,status,created_at)
                    VALUES (?,?,?,?,?,'pending',?) ON CONFLICT DO NOTHING""", newId(), candidate.text("student"),
                    candidate.text("lesson"), candidate.number("revision"), candidate.number("epoch"), now)
            }
            val row = c.document("""SELECT to_jsonb(d) FROM initial_roadmap_delivery d JOIN student s ON s.id=d.student_id
                WHERE (d.status='ready' OR (d.status='pending' AND (d.lease_until IS NULL OR d.lease_until<=?)))
                    AND d.data_epoch=s.data_epoch AND s.deleted_at IS NULL
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
        if (delivery.text("status") == "pending") prepare(delivery) else sendNext(delivery)
    }

    private suspend fun prepare(delivery: JsonObject) {
        val student = delivery.text("student_id")
        val execution = service.identity.issue(student, "voice", lesson = delivery.text("lesson_id"))
        val response = runCatching {
            http.post("${aiRoot.trimEnd('/')}/internal/trial/initial-roadmap") {
                header("X-Internal-Token", internalToken)
                header("X-Execution-Scope", execution)
                contentType(ContentType.Application.Json)
                setBody(json("roadmapRevision" to delivery.number("roadmap_revision")).toString())
            }
        }.getOrElse { error ->
            RuntimeDiagnostics.record("initial_roadmap_prepare_failed", "ai_request", error)
            retryPreparation(delivery)
            return
        }
        val body = response.bodyAsText()
        if (response.status.value == 409) {
            service.database.transaction { c, _ ->
                c.lockStudent(student)
                c.update("UPDATE initial_roadmap_delivery SET status='cancelled',body=NULL,lease_until=NULL WHERE id=? AND status='generating'",
                    delivery.text("id"))
            }
            return
        }
        val reply = if (response.status.value in 200..299) runCatching {
            val result = foundationJson.parseToJsonElement(body).jsonObject
            if (result.number("roadmapRevision") != delivery.number("roadmap_revision")) null else result.text("reply")
        }.getOrNull() else null
        if (reply.isNullOrBlank()) {
            RuntimeDiagnostics.record("initial_roadmap_prepare_failed", "ai_response", extra = mapOf(
                "delivery_id" to delivery.text("id"), "http_status" to response.status.value.toString()) +
                RuntimeDiagnostics.httpFields(response.status.value, body))
            retryPreparation(delivery)
            return
        }
        service.database.transaction { c, _ ->
            c.lockStudent(student)
            c.update("""UPDATE initial_roadmap_delivery SET status='ready',body=?,lease_until=NULL
                WHERE id=? AND status='generating' AND data_epoch=(SELECT data_epoch FROM student WHERE id=? AND deleted_at IS NULL)""",
                reply, delivery.text("id"), student)
        }
    }

    private suspend fun retryPreparation(delivery: JsonObject) {
        service.database.transaction { c, now ->
            val delay = 30L * (1L shl minOf(6, delivery.number("attempts").toInt()))
            c.update("UPDATE initial_roadmap_delivery SET status='pending',lease_until=? WHERE id=? AND status='generating'",
                now.plusSeconds(delay), delivery.text("id"))
        }
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
        val markup = if (index == parts.lastIndex) json("inline_keyboard" to kotlinx.serialization.json.JsonArray(listOf(
            kotlinx.serialization.json.JsonArray(listOf(json("text" to "Мои встречи", "web_app" to json("url" to miniUrl)))))))
        else null
        val response = runCatching {
            http.post("https://api.telegram.org/bot$botToken/sendMessage") {
                contentType(ContentType.Application.Json)
                setBody(telegramRequestJson.encodeToString(TutorTextRequest(subject.toLong(), parts[index].text,
                    parts[index].entities, markup)))
            }
        }.getOrElse { error ->
            RuntimeDiagnostics.record("initial_roadmap_delivery_unknown", "telegram_request", error)
            return
        }
        val sentBody = response.bodyAsText()
        val ok = runCatching { foundationJson.parseToJsonElement(sentBody).jsonObject.optionalText("ok") == "true" }.getOrDefault(false)
        if (!ok) {
            RuntimeDiagnostics.record("initial_roadmap_delivery_unknown", "telegram_response", extra = mapOf(
                "delivery_id" to delivery.text("id"), "http_status" to response.status.value.toString(),
                "part" to index.toString()) + RuntimeDiagnostics.httpFields(response.status.value, sentBody))
            return
        }
        service.database.transaction { c, now ->
            c.lockStudent(student)
            val changed = c.update("""UPDATE initial_roadmap_delivery SET status=?,next_part=? WHERE id=? AND status='unknown'
                AND next_part=? AND data_epoch=(SELECT data_epoch FROM student WHERE id=? AND deleted_at IS NULL)""",
                if (index == parts.lastIndex) "sent" else "ready", index + 1, delivery.text("id"), index, student)
            if (changed == 0) return@transaction
            c.appendEvent(student, c.conversation(student, "telegram"), "roadmap:${delivery.text("id")}:$index",
                "assistant", parts[index].text, now, now)
        }
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
