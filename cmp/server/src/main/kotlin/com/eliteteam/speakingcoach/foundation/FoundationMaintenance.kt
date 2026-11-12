package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject

internal class FoundationMaintenance {
    fun prune(connection: Connection, scope: ExecutionScope, now: Instant): JsonObject {
        requireRole(scope, "operator")
        val student = scope.studentId
        connection.update("UPDATE trial_audio SET status='purge',metadata='{}' WHERE student_id=? AND expires_at<=? AND status<>'deleted'", student, now)
        val events = connection.update("UPDATE conversation_event SET body=NULL,metadata='{}' WHERE student_id=? AND expires_at<=? AND body IS NOT NULL", student, now)
        connection.update("UPDATE tutor_inbox SET response=NULL WHERE student_id=? AND expires_at<=?", student, now)
        connection.update("UPDATE foundation_operation SET result='{}' WHERE student_id=? AND expires_at<=?", student, now)
        connection.update("UPDATE memory_candidate SET value=NULL,status='expired' WHERE student_id=? AND expires_at<=?", student, now)
        connection.update("DELETE FROM tutor_summary WHERE student_id=? AND expires_at<=?", student, now)
        connection.update("UPDATE foundation_job SET status='failed',error_code='source_expired' WHERE student_id=? AND deadline<=? AND status IN ('queued','running','retry_wait')", student, now)
        connection.update("DELETE FROM execution_scope WHERE student_id=? AND expires_at<=?", student, now)
        return json("eventsPruned" to events)
    }

    fun rejectCandidate(connection: Connection, scope: ExecutionScope, args: JsonObject): JsonObject {
        requireRole(scope, "text", "operator")
        args.only("candidateId", "expectedVersion")
        val updated = connection.update("UPDATE memory_candidate SET value=NULL,status='rejected',version=version+1 WHERE student_id=? AND id=? AND version=? AND status='pending'",
            scope.studentId, args.text("candidateId"), args.number("expectedVersion"))
        if (updated != 1) reject("version_conflict")
        return json("rejected" to true)
    }

    fun forget(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("category", "key", "expectedVersion")
        val category = args.text("category")
        val key = args.text("key")
        val revision = if (category == "learning" && key == "goal") {
            connection.goalRevision(scope.studentId).takeIf { it > 0 } ?: reject("not_owned")
        } else {
            connection.document("SELECT to_jsonb(f) FROM memory_fact f WHERE student_id=? AND category=? AND fact_key=?",
                scope.studentId, category, key)?.number("revision") ?: reject("not_owned")
        }
        if (revision != args.number("expectedVersion")) reject("version_conflict")
        connection.update("INSERT INTO foundation_deletion(id,student_id,kind,category,fact_key,deleted_at,resulting_epoch) VALUES (?,?,'forget',?,?,?,?)",
            newId(), scope.studentId, category, key, now, scope.epoch + 1)
        eraseFact(connection, scope.studentId, category, key, now)
        invalidateCopies(connection, scope.studentId, now)
        return json("forgotten" to true)
    }

    fun deleteStudent(connection: Connection, scope: ExecutionScope, now: Instant, recordDeletion: Boolean = true): JsonObject {
        requireRole(scope, "operator")
        val student = scope.studentId
        if (recordDeletion) connection.update("INSERT INTO foundation_deletion(id,student_id,kind,deleted_at,resulting_epoch) VALUES (?,?,'delete_student',?,?)",
            newId(), student, now, scope.epoch + 1)
        invalidateCopies(connection, student, now)
        connection.update("DELETE FROM memory_observation WHERE student_id=?", student)
        connection.update("DELETE FROM memory_candidate WHERE student_id=?", student)
        connection.update("DELETE FROM memory_fact_history WHERE student_id=?", student)
        connection.update("DELETE FROM memory_fact WHERE student_id=?", student)
        connection.update("DELETE FROM roadmap_step WHERE roadmap_id IN (SELECT id FROM roadmap WHERE student_id=?)", student)
        connection.update("DELETE FROM roadmap_revision WHERE student_id=?", student)
        connection.update("DELETE FROM roadmap WHERE student_id=?", student)
        connection.update("DELETE FROM learning_goal WHERE student_id=?", student)
        connection.update("DELETE FROM lesson_plan WHERE student_id=?", student)
        connection.update("DELETE FROM lesson_evaluation WHERE student_id=?", student)
        connection.update("DELETE FROM reminder_delivery WHERE student_id=?", student)
        connection.update("DELETE FROM tutor_delivery WHERE student_id=?", student)
        connection.update("DELETE FROM lesson_telegram_card WHERE student_id=?", student)
        connection.update("DELETE FROM lesson_event WHERE student_id=?", student)
        connection.update("DELETE FROM trial_notice WHERE student_id=?", student)
        connection.update("DELETE FROM trial_demo_receipt WHERE student_id=?", student)
        connection.update("DELETE FROM call_session WHERE student_id=?", student)
        connection.update("DELETE FROM schedule_exception WHERE student_id=?", student)
        connection.update("UPDATE lesson SET previous_lesson_id=NULL WHERE student_id=?", student)
        connection.update("DELETE FROM lesson WHERE student_id=?", student)
        connection.update("DELETE FROM schedule_time_issue WHERE student_id=?", student)
        connection.update("DELETE FROM schedule WHERE student_id=?", student)
        connection.update("DELETE FROM entitlement WHERE student_id=?", student)
        connection.update("DELETE FROM payment_receipt WHERE student_id=?", student)
        connection.update("DELETE FROM subscription WHERE student_id=?", student)
        connection.update("DELETE FROM tutor_inbox WHERE student_id=?", student)
        connection.update("DELETE FROM conversation_event WHERE student_id=?", student)
        connection.update("DELETE FROM tutor_conversation WHERE student_id=?", student)
        connection.update("DELETE FROM student_identity WHERE student_id=?", student)
        connection.update("UPDATE student SET profile='{}',deleted_at=? WHERE id=?", now, student)
        return json("deleted" to true)
    }

    fun retry(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("jobId")
        val job = connection.document("SELECT to_jsonb(j) FROM foundation_job j WHERE student_id=? AND id=?", scope.studentId, args.text("jobId")) ?: reject("not_owned")
        if (job.text("status") != "failed") reject("invalid_state")
        if (job.optionalText("error_code") == "delivery_unknown") reject("delivery_reconciliation_required")
        if (job.number("data_epoch") != scope.epoch || Instant.parse(job.text("deadline")) <= now) reject("source_expired")
        val inputs = job.obj("input_refs")
        if (job.text("kind") == "lesson.prepare" && (inputs.number("goalRevision") != connection.goalRevision(scope.studentId) ||
                inputs.number("learningRevision") != connection.learningRevision(scope.studentId))) reject("stale_job")
        if (job.text("kind") == "lesson.evaluate" && connection.lesson(scope, job.text("target_id")).number("transcript_revision") != inputs.number("transcriptRevision")) reject("stale_job")
        connection.update("UPDATE foundation_job SET status='queued',due_at=?,max_attempts=attempts+3,error_code=NULL WHERE id=?", now, job.text("id"))
        return json("queued" to true)
    }

    private fun eraseFact(connection: Connection, student: String, category: String, key: String, now: Instant) {
        if (category == "learning" && key == "goal") {
            // Keep revision identities referenced by plans; erase their private contents.
            connection.update("UPDATE learning_goal SET content='{}',source_event_id=NULL WHERE student_id=?", student)
            connection.update("INSERT INTO learning_goal(student_id,revision,content,created_at) VALUES (?,?,'{}',?)",
                student, connection.goalRevision(student) + 1, now)
        } else {
            connection.update("UPDATE memory_fact SET value=NULL,forgotten_at=?,revision=revision+1,source_event_id=NULL WHERE student_id=? AND category=? AND fact_key=?",
                now, student, category, key)
            connection.update("DELETE FROM memory_fact_history WHERE student_id=? AND category=? AND fact_key=?", student, category, key)
        }
        connection.update("UPDATE memory_candidate SET value=NULL,status='rejected',version=version+1 WHERE student_id=? AND fact_key=?", student, key)
        // Observations and roadmap prose can paraphrase a forgotten fact without a complete source graph.
        connection.update("DELETE FROM memory_observation WHERE student_id=?", student)
        connection.update("UPDATE roadmap_revision SET content='{}',reason='',confirmed_source_id=NULL,status='superseded' WHERE student_id=?", student)
        connection.update("DELETE FROM roadmap_step WHERE roadmap_id IN (SELECT id FROM roadmap WHERE student_id=?)", student)
        connection.update("UPDATE roadmap SET active_revision=NULL WHERE student_id=?", student)
    }

    private fun invalidateCopies(connection: Connection, student: String, now: Instant) {
        connection.update("UPDATE trial_audio SET status='purge',metadata='{}',expires_at=? WHERE student_id=? AND status<>'deleted'", now, student)
        connection.update("UPDATE schedule_time_issue SET delivery='cancelled' WHERE student_id=?", student)
        connection.update("UPDATE trial_notice SET status='cancelled',reply=NULL WHERE student_id=?", student)
        connection.update("UPDATE student SET data_epoch=data_epoch+1 WHERE id=?", student)
        connection.update("UPDATE foundation_job SET status='cancelled',input_refs='{}',error_code='data_deleted',lease_until=NULL WHERE student_id=?", student)
        connection.update("DELETE FROM tutor_summary WHERE student_id=?", student)
        connection.update("UPDATE foundation_operation SET result='{}',expires_at=? WHERE student_id=?", now, student)
        connection.update("UPDATE tutor_inbox SET response=NULL,status='failed',lease_until=NULL WHERE student_id=?", student)
        connection.update("UPDATE lesson_plan SET status='stale',content='{}' WHERE student_id=?", student)
        connection.update("UPDATE lesson_evaluation SET result='{}' WHERE student_id=?", student)
        connection.update("UPDATE lesson SET execution_snapshot=NULL WHERE student_id=?", student)
        // Free-form replies/summaries can paraphrase a fact without a machine-verifiable source link.
        // Forget conservatively clears raw conversation content; unrelated confirmed facts remain.
        connection.update("UPDATE conversation_event SET body=NULL,metadata='{}' WHERE student_id=?", student)
        connection.update("""UPDATE telegram_text_intake SET payload=NULL,status='done',lease_until=NULL WHERE chat_id::text IN
            (SELECT subject FROM student_identity WHERE student_id=? AND provider='telegram')""", student)
    }

    fun replayDeletions(connection: Connection, scope: ExecutionScope, now: Instant): JsonObject {
        requireRole(scope, "operator")
        val records = connection.documents("SELECT to_jsonb(d) FROM foundation_deletion d WHERE student_id=? AND resulting_epoch>? ORDER BY resulting_epoch", scope.studentId, scope.epoch)
        records.forEach { record ->
            if (record.text("kind") == "delete_student") {
                deleteStudent(connection, scope, now, recordDeletion = false)
            } else {
                eraseFact(connection, scope.studentId, record.text("category"), record.text("fact_key"),
                    Instant.parse(record.text("deleted_at")))
                invalidateCopies(connection, scope.studentId, now)
            }
            connection.update("UPDATE student SET data_epoch=GREATEST(data_epoch,?) WHERE id=?", record.number("resulting_epoch"), scope.studentId)
        }
        return json("replayed" to records.size)
    }
}
