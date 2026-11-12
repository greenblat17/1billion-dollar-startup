package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.RuntimeDiagnostics

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Durable proactive messages. Unknown Telegram delivery is never retried blindly. */
internal class TrialWorker(private val service: FoundationService, private val http: HttpClient,
    private val aiRoot: String, private val internalToken: String, private val botToken: String, private val miniUrl: String) {
    fun launch(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            try { tick() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { RuntimeDiagnostics.record("trial_worker_unavailable", "followup", error) }
            delay(3000)
        }
    }

    suspend fun tick() {
        sweep()
        val notice = service.database.transaction { c, now ->
            c.update("UPDATE trial_notice SET status='pending' WHERE status='generating' AND lease_until<=?", now)
            val row = c.document("SELECT to_jsonb(n) FROM trial_notice n WHERE status IN ('pending','ready') AND due_at<=? ORDER BY due_at LIMIT 1", now) ?: return@transaction null
            c.lockStudent(row.text("student_id"))
            val current = c.document("SELECT to_jsonb(n) FROM trial_notice n WHERE id=? AND status IN ('pending','ready') FOR UPDATE", row.text("id")) ?: return@transaction null
            val lesson = c.document("SELECT to_jsonb(l) FROM lesson l WHERE id=?", row.text("lesson_id"))
            val student = c.document("SELECT to_jsonb(s) FROM student s WHERE id=? AND deleted_at IS NULL AND data_epoch=?", row.text("student_id"), row.number("data_epoch"))
            val connected = c.document("SELECT to_jsonb(s) FROM call_session s WHERE lesson_id=? AND status='connected'", row.text("lesson_id"))
            if (lesson == null || student == null || connected != null || !noticeApplies(c, row, lesson, now)) {
                c.update("UPDATE trial_notice SET status='cancelled' WHERE id=?", row.text("id")); return@transaction null
            }
            c.update("UPDATE trial_notice SET status='generating',lease_until=?,fence=fence+1 WHERE id=?", now.plusSeconds(90), row.text("id"))
            c.document("SELECT to_jsonb(n) FROM trial_notice n WHERE id=?", current.text("id"))
        } ?: return
        val student = notice.text("student_id")
        val execution = service.identity.issue(student, "voice", lesson = notice.text("lesson_id"))
        val response = http.post("${aiRoot.trimEnd('/')}/internal/trial/followup") {
            header("X-Diagnostic-Trace", RuntimeDiagnostics.trace(notice.text("id")))
            header("X-Diagnostic-Lesson", notice.text("lesson_id"))
            header("X-Internal-Token", internalToken); header("X-Execution-Scope", execution)
            contentType(ContentType.Application.Json); setBody(notice.toString())
        }
        if (response.status.value !in 200..299) {
            RuntimeDiagnostics.record("trial_followup_failed", "ai_response", extra = mapOf(
                "notice_id" to notice.text("id"),
                "trace_id" to RuntimeDiagnostics.trace(notice.text("id"))) + RuntimeDiagnostics.httpFields(response.status.value, response.bodyAsText()))
            service.database.transaction { c, _ -> c.update("UPDATE trial_notice SET status='failed' WHERE id=? AND status='generating' AND fence=?", notice.text("id"), notice.number("fence")) }
            return
        }
        val reply = foundationJson.parseToJsonElement(response.bodyAsText()).jsonObject.text("reply")
        if (reply.isBlank() || reply.length > 3500) return
        val subject = service.database.transaction { c, now ->
            c.lockStudent(student)
            val active = c.document("SELECT to_jsonb(n) FROM trial_notice n JOIN student s ON s.id=n.student_id WHERE n.id=? AND n.status='generating' AND n.fence=? AND n.data_epoch=s.data_epoch AND s.deleted_at IS NULL", notice.text("id"), notice.number("fence")) ?: return@transaction null
            val newer = c.document("SELECT to_jsonb(e) FROM conversation_event e JOIN tutor_conversation t ON t.id=e.conversation_id WHERE e.student_id=? AND t.channel='telegram' AND e.role='user' AND e.received_at>? LIMIT 1", student, java.time.Instant.parse(active.text("due_at")))
            val currentLesson = c.document("SELECT to_jsonb(l) FROM lesson l WHERE id=?", notice.text("lesson_id"))
            if (currentLesson == null || !noticeApplies(c, notice, currentLesson, now) || newer != null || c.document("SELECT to_jsonb(s) FROM call_session s WHERE lesson_id=? AND status='connected'", notice.text("lesson_id")) != null) {
                c.update("UPDATE trial_notice SET status='cancelled' WHERE id=?", notice.text("id")); return@transaction null
            }
            c.update("UPDATE trial_notice SET status='unknown',reply=? WHERE id=?", reply, notice.text("id"))
            c.rows("SELECT subject FROM student_identity WHERE student_id=? AND provider='telegram'", student) { it.getString(1) }.singleOrNull()
        } ?: return
        val markup = json("inline_keyboard" to kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonArray(listOf(
            json("text" to "Мои встречи", "web_app" to json("url" to miniUrl)))))))
        val sent = http.post("https://api.telegram.org/bot$botToken/sendMessage") {
            contentType(ContentType.Application.Json); setBody(json("chat_id" to subject, "text" to reply, "reply_markup" to markup).toString())
        }
        val sentBody = sent.bodyAsText()
        val ok = runCatching { foundationJson.parseToJsonElement(sentBody).jsonObject.optionalText("ok") == "true" }.getOrDefault(false)
        if (!ok) RuntimeDiagnostics.record("trial_delivery_failed", "telegram_response", extra = mapOf(
            "notice_id" to notice.text("id"), "http_status" to sent.status.value.toString(),
            "trace_id" to RuntimeDiagnostics.trace(notice.text("id")), "outcome" to "unknown") + RuntimeDiagnostics.httpFields(sent.status.value, sentBody))
        if (ok) service.database.transaction { c, now ->
            c.lockStudent(student)
            c.update("UPDATE trial_notice SET status='sent' WHERE id=? AND status='unknown'", notice.text("id"))
            c.appendEvent(student, c.conversation(student, "telegram"), "notice:${notice.text("id")}", "assistant", reply, now, now)
        }
    }

    private fun noticeApplies(c: java.sql.Connection, notice: JsonObject, lesson: JsonObject, now: java.time.Instant): Boolean = when (notice.text("kind")) {
        "followup" -> lesson.text("status") == "completed" && lesson.optionalText("outcome_reason") == "natural_goodbye"
        "no_show" -> lesson.text("status") == "scheduled" && now < java.time.Instant.parse(lesson.text("starts_at")).plusSeconds(lesson.number("duration_minutes") * 60)
        else -> {
            val last = c.document("SELECT to_jsonb(s) FROM call_session s WHERE lesson_id=? ORDER BY started_at DESC LIMIT 1", lesson.text("id"))
            last?.optionalText("end_reason") == notice.text("kind") && lesson.text("status") in setOf("in_progress", "completed") && lesson.optionalText("outcome_reason") != "technical_failure_rebooked"
        }
    }

    private suspend fun sweep() = service.database.transaction { c, now ->
        val lessons = c.documents("SELECT to_jsonb(l) FROM lesson l WHERE kind='trial' AND launch_mode='executable' AND status IN ('scheduled','in_progress')")
        for (lesson in lessons) {
            val student = lesson.text("student_id")
            c.lockStudent(student)
            val id = lesson.text("id")
            val epoch = c.rows("SELECT data_epoch FROM student WHERE id=?", student) { it.getLong(1) }.single()
            val expired = c.update("UPDATE call_session SET status='disconnected',end_reason='technical_disconnect',ended_at=? WHERE lesson_id=? AND status='connected' AND lease_until<=?", now, id, now)
            if (expired > 0) c.trialNotice(student, id, epoch, "technical_disconnect", now.plusSeconds(30))
            val start = java.time.Instant.parse(lesson.text("starts_at"))
            val end = start.plusSeconds(lesson.number("duration_minutes") * 60)
            if (lesson.text("status") == "scheduled" && now >= start.plusSeconds(300) && now < end) c.trialNotice(student, id, epoch, "no_show", now)
            if (lesson.text("status") == "scheduled" && now >= end) c.update("UPDATE lesson SET status='missed',version=version+1 WHERE id=? AND status='scheduled'", id)
            val last = c.document("SELECT to_jsonb(s) FROM call_session s WHERE lesson_id=? ORDER BY started_at DESC LIMIT 1", id)
            if (lesson.text("status") == "in_progress" && now >= end && last?.optionalText("end_reason") == "end_button") {
                c.update("UPDATE lesson SET status='completed',ended_early=TRUE,outcome_reason='end_button',version=version+1 WHERE id=?", id)
                c.enqueueEvaluation(student, id, now)
            }
        }
    }
}
