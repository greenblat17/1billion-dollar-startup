package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.RuntimeDiagnostics
import com.eliteteam.speakingcoach.telegram.TutorTextRequest
import com.eliteteam.speakingcoach.telegram.telegramRequestJson
import com.eliteteam.speakingcoach.telegram.tutorTextParts
import com.eliteteam.speakingcoach.telegram.tutorBookingMarkup
import kotlinx.serialization.encodeToString

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
        clarifyTime()
        val notice = service.database.transaction { c, now ->
            val row = c.document("""SELECT to_jsonb(n) FROM trial_notice n WHERE status IN ('pending','ready') AND due_at<=?
                AND NOT (kind='followup' AND EXISTS (SELECT 1 FROM lesson l WHERE l.id=n.lesson_id AND n.due_at>?
                    AND EXISTS (SELECT 1 FROM foundation_job j WHERE j.student_id=n.student_id
                        AND j.status IN ('queued','running','retry_wait')
                        AND (j.kind='roadmap.prepare' OR (j.kind='lesson.evaluate' AND j.target_id=l.id)))))
                ORDER BY due_at LIMIT 1""", now, now.minusSeconds(300)) ?: return@transaction null
            c.lockStudent(row.text("student_id"))
            val current = c.document("SELECT to_jsonb(n) FROM trial_notice n WHERE id=? AND status IN ('pending','ready') FOR UPDATE", row.text("id")) ?: return@transaction null
            val lesson = c.document("SELECT to_jsonb(l) FROM lesson l WHERE id=?", current.text("lesson_id"))
            val student = c.document("SELECT to_jsonb(s) FROM student s WHERE id=? AND deleted_at IS NULL AND data_epoch=?", current.text("student_id"), current.number("data_epoch"))
            val connected = c.document("SELECT to_jsonb(s) FROM call_session s WHERE lesson_id=? AND status='connected'", current.text("lesson_id"))
            if (lesson == null || student == null || connected != null || !noticeApplies(c, current, lesson, now)) {
                c.update("UPDATE trial_notice SET status='cancelled' WHERE id=?", current.text("id")); return@transaction null
            }
            c.update("UPDATE trial_notice SET status='generating',lease_until=?,fence=fence+1 WHERE id=?", now.plusSeconds(90), current.text("id"))
            c.document("SELECT to_jsonb(n) FROM trial_notice n WHERE id=?", current.text("id"))
        } ?: return
        val student = notice.text("student_id")
        val scheduledTrial = if (notice.text("kind") in scheduledNoticeKinds) service.database.transaction(readOnly = true) { c, _ ->
            c.document("SELECT to_jsonb(l) FROM lesson l WHERE id=? AND kind='trial'", notice.text("lesson_id"))
        } else null
        val reply = if (scheduledTrial != null) {
            scheduledNoticeText(notice.text("kind"), scheduledTrial)
        } else {
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
            foundationJson.parseToJsonElement(response.bodyAsText()).jsonObject.text("reply")
        }
        if (reply.isBlank() || reply.length > 3500) {
            service.database.transaction { c, _ -> c.update("UPDATE trial_notice SET status='cancelled' WHERE id=? AND status='generating' AND fence=?", notice.text("id"), notice.number("fence")) }
            return
        }
        val subject = service.database.transaction { c, now ->
            c.lockStudent(student)
            val active = c.document("SELECT to_jsonb(n) FROM trial_notice n JOIN student s ON s.id=n.student_id WHERE n.id=? AND n.status='generating' AND n.fence=? AND n.data_epoch=s.data_epoch AND s.deleted_at IS NULL", notice.text("id"), notice.number("fence")) ?: return@transaction null
            val newer = c.document("SELECT to_jsonb(e) FROM conversation_event e JOIN tutor_conversation t ON t.id=e.conversation_id WHERE e.student_id=? AND t.channel='telegram' AND e.role='user' AND e.received_at>? LIMIT 1", student, java.time.Instant.parse(active.text("due_at")))
            val currentLesson = c.document("SELECT to_jsonb(l) FROM lesson l WHERE id=?", notice.text("lesson_id"))
            if (currentLesson == null || !noticeApplies(c, notice, currentLesson, now) || (newer != null && scheduledTrial == null) || c.document("SELECT to_jsonb(s) FROM call_session s WHERE lesson_id=? AND status='connected'", notice.text("lesson_id")) != null) {
                c.update("UPDATE trial_notice SET status='cancelled' WHERE id=?", notice.text("id")); return@transaction null
            }
            c.update("UPDATE trial_notice SET status='unknown',reply=? WHERE id=?", reply, notice.text("id"))
            c.rows("SELECT subject FROM student_identity WHERE student_id=? AND provider='telegram'", student) { it.getString(1) }.singleOrNull()
        } ?: return
        val markup = if (notice.text("kind") == "invitation") tutorBookingMarkup(true, notice.text("lesson_id"), miniUrl)
        else if (notice.text("kind") in setOf("morning", "reminder")) tutorBookingMarkup(false)
        else json("inline_keyboard" to kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonArray(listOf(
            json("text" to "Мои встречи", "web_app" to json("url" to miniUrl)))))))
        for (part in tutorTextParts(reply)) {
            val sent = http.post("https://api.telegram.org/bot$botToken/sendMessage") {
                contentType(ContentType.Application.Json)
                setBody(telegramRequestJson.encodeToString(TutorTextRequest(subject.toLong(), part.text, part.entities, markup)))
            }
            val sentBody = sent.bodyAsText()
            val ok = runCatching { foundationJson.parseToJsonElement(sentBody).jsonObject.optionalText("ok") == "true" }.getOrDefault(false)
            if (!ok) {
                RuntimeDiagnostics.record("trial_delivery_failed", "telegram_response", extra = mapOf(
                    "notice_id" to notice.text("id"), "http_status" to sent.status.value.toString(),
                    "trace_id" to RuntimeDiagnostics.trace(notice.text("id")), "outcome" to "unknown") + RuntimeDiagnostics.httpFields(sent.status.value, sentBody))
                return
            }
        }
        service.database.transaction { c, now ->
            c.lockStudent(student)
            val updated = c.update("UPDATE trial_notice SET status='sent' WHERE id=? AND status='unknown' AND fence=? AND data_epoch=(SELECT data_epoch FROM student WHERE id=? AND deleted_at IS NULL)", notice.text("id"), notice.number("fence"), student)
            if (updated == 0) return@transaction
            c.appendEvent(student, c.conversation(student, "telegram"), "notice:${notice.text("id")}:${notice.number("fence")}", "assistant", reply, now, now)
        }
    }

    private fun noticeApplies(c: java.sql.Connection, notice: JsonObject, lesson: JsonObject, now: java.time.Instant): Boolean {
        if (notice.optionalText("lesson_version")?.toLong() != null && notice.number("lesson_version") != lesson.number("version")) return false
        val start = java.time.Instant.parse(lesson.text("starts_at"))
        return when (notice.text("kind")) {
            in scheduledNoticeKinds -> lesson.text("status") == "scheduled" &&
                scheduledNoticeApplies(notice.text("kind"), java.time.Instant.parse(notice.text("due_at")), start, now)
            "followup" -> lesson.text("status") == "completed" && lesson.optionalText("outcome_reason") == "natural_goodbye"
            "no_show" -> lesson.text("status") == "scheduled" && now < java.time.Instant.parse(lesson.text("starts_at")).plusSeconds(lesson.number("duration_minutes") * 60)
            else -> {
                val last = c.document("SELECT to_jsonb(s) FROM call_session s WHERE lesson_id=? ORDER BY started_at DESC LIMIT 1", lesson.text("id"))
                last?.optionalText("end_reason") == notice.text("kind") && lesson.text("status") in setOf("in_progress", "completed") && lesson.optionalText("outcome_reason") != "technical_failure_rebooked"
            }
        }
    }

    internal suspend fun sweep() {
        val students = service.database.transaction { c, _ -> c.rows("SELECT id FROM student WHERE deleted_at IS NULL ORDER BY id") { it.getString(1) } }
        for (studentId in students) service.database.transaction { c, now ->
            c.lockStudent(studentId)
            c.update("UPDATE trial_notice SET status='pending' WHERE student_id=? AND status='generating' AND lease_until<=?", studentId, now)
            if (c.document("SELECT to_jsonb(s) FROM student s WHERE id=? AND deleted_at IS NULL", studentId) == null) return@transaction
            c.documents("SELECT to_jsonb(s) FROM schedule s WHERE student_id=? AND agreement->>'executable'='true' AND agreement->>'cancelled' IS DISTINCT FROM 'true'", studentId).forEach { TrialSchedule().materialize(c, it, now) }
            val lessons = c.documents("SELECT to_jsonb(l) FROM lesson l WHERE student_id=? AND launch_mode='executable' AND status IN ('scheduled','in_progress')", studentId)
            for (lesson in lessons) {
                val student = lesson.text("student_id")
                val id = lesson.text("id")
                val epoch = c.rows("SELECT data_epoch FROM student WHERE id=?", student) { it.getLong(1) }.single()
                val expired = c.update("UPDATE call_session SET status='disconnected',end_reason='technical_disconnect',ended_at=? WHERE lesson_id=? AND status='connected' AND lease_until<=?", now, id, now)
                if (expired > 0 && lesson.text("kind") == "trial") c.trialNotice(student, id, epoch, "technical_disconnect", now.plusSeconds(30))
                val start = java.time.Instant.parse(lesson.text("starts_at"))
                val end = start.plusSeconds(lesson.number("duration_minutes") * 60)
                if (lesson.text("kind") == "trial" && lesson.text("status") == "scheduled") {
                    for ((kind, due) in trialReminderTimes(lesson)) {
                        if (scheduledNoticeApplies(kind, due, start, now)) schedulingNotice(c, lesson, epoch, kind, due)
                    }
                }
                if (lesson.text("kind") == "regular" && lesson.text("status") == "scheduled") {
                    if (now >= start.minusSeconds(900) && now < start) schedulingNotice(c, lesson, epoch, "reminder", now)
                    if (now >= start && now < start.plusSeconds(300)) schedulingNotice(c, lesson, epoch, "invitation", now)
                }
                if (lesson.text("status") == "scheduled" && now >= start.plusSeconds(600) && now < end) schedulingNotice(c, lesson, epoch, "no_show", now)
                if (lesson.text("status") == "scheduled" && now >= end) c.update("UPDATE lesson SET status='missed',version=version+1 WHERE id=? AND status='scheduled'", id)
                val last = c.document("SELECT to_jsonb(s) FROM call_session s WHERE lesson_id=? ORDER BY started_at DESC LIMIT 1", id)
                if (lesson.text("status") == "in_progress" && now >= end && last?.optionalText("end_reason") == "end_button") {
                    c.update("UPDATE lesson SET status='completed',ended_early=TRUE,outcome_reason='end_button',version=version+1 WHERE id=?", id)
                    c.enqueueEvaluation(student, id, now)
                }
            }
        }
    }

    private fun schedulingNotice(c: java.sql.Connection, lesson: JsonObject, epoch: Long, kind: String, now: java.time.Instant) {
        c.update("INSERT INTO trial_notice(id,student_id,lesson_id,kind,due_at,data_epoch,lesson_version) VALUES (?,?,?,?,?,?,?) ON CONFLICT(lesson_id,kind) DO UPDATE SET status='pending',due_at=EXCLUDED.due_at,lesson_version=EXCLUDED.lesson_version,fence=trial_notice.fence+1 WHERE trial_notice.lesson_version IS DISTINCT FROM EXCLUDED.lesson_version",
            newId(), lesson.text("student_id"), lesson.text("id"), kind, now, epoch, lesson.number("version"))
    }

    private suspend fun clarifyTime() {
        val issue = service.database.transaction { c, _ ->
            val candidate = c.document("SELECT to_jsonb(i) FROM schedule_time_issue i JOIN student s ON s.id=i.student_id JOIN schedule sc ON sc.id=i.schedule_id WHERE i.status='pending' AND i.delivery='pending' AND s.deleted_at IS NULL AND s.data_epoch=i.data_epoch AND sc.version=i.schedule_version ORDER BY i.local_date LIMIT 1") ?: return@transaction null
            c.lockStudent(candidate.text("student_id"))
            val current = c.document("SELECT to_jsonb(i) FROM schedule_time_issue i JOIN student s ON s.id=i.student_id JOIN schedule sc ON sc.id=i.schedule_id WHERE i.schedule_id=? AND i.occurrence_key=? AND i.status='pending' AND i.delivery='pending' AND s.deleted_at IS NULL AND s.data_epoch=i.data_epoch AND sc.version=i.schedule_version", candidate.text("schedule_id"), candidate.text("occurrence_key")) ?: return@transaction null
            c.update("UPDATE schedule_time_issue SET delivery='unknown' WHERE schedule_id=? AND occurrence_key=?", current.text("schedule_id"), current.text("occurrence_key"))
            current
        } ?: return
        val reply = "На ${issue.text("local_date")} время ${issue.text("local_time")} (${issue.text("timezone")}) неоднозначно или отсутствует из-за перевода часов. На какое другое местное время договоримся в этот день? Остальное расписание сохраняется."
        val subject = service.database.transaction { c, _ -> c.rows("SELECT subject FROM student_identity WHERE student_id=? AND provider='telegram'", issue.text("student_id")) { it.getString(1) }.singleOrNull() } ?: return
        val response = http.post("https://api.telegram.org/bot$botToken/sendMessage") {
            contentType(ContentType.Application.Json); setBody(json("chat_id" to subject, "text" to reply).toString())
        }
        val ok = runCatching { foundationJson.parseToJsonElement(response.bodyAsText()).jsonObject.optionalText("ok") == "true" }.getOrDefault(false)
        if (ok) service.database.transaction { c, now ->
            c.lockStudent(issue.text("student_id"))
            val changed = c.update("UPDATE schedule_time_issue SET delivery='sent' WHERE schedule_id=? AND occurrence_key=? AND delivery='unknown' AND data_epoch=(SELECT data_epoch FROM student WHERE id=? AND deleted_at IS NULL)", issue.text("schedule_id"), issue.text("occurrence_key"), issue.text("student_id"))
            if (changed == 0) return@transaction
            c.appendEvent(issue.text("student_id"), c.conversation(issue.text("student_id"), "telegram"), "dst:${issue.text("schedule_id")}:${issue.text("occurrence_key")}", "assistant", reply, now, now)
        }
    }
}
