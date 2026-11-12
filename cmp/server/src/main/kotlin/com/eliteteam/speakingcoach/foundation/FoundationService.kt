package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.RuntimeDiagnostics
import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Authoritative commands. No model/provider call is made in this layer. */
internal class FoundationService(val database: FoundationDatabase) : AutoCloseable {
    val identity = FoundationIdentity(database)
    val history = ConversationHistory(database)
    val telegramIntake = TelegramTextIntake(database)
    private val memory = StructuredMemory()
    private val lessons = LessonManagement()
    private val learning = LearningState()
    private val jobs = FoundationJobs()
    private val maintenance = FoundationMaintenance()
    private val runs = ConversationRuns()
    private val tutorContext = TutorContextOperations(memory)

    suspend fun command(token: String, request: JsonObject): JsonObject = RuntimeDiagnostics.withContext(mapOf(
        "operation_id" to (RuntimeDiagnostics.id(request.optionalText("operationId")) ?: "invalid"),
    )) {
        val action = RuntimeDiagnostics.id(request.optionalText("action")) ?: "invalid"
        RuntimeDiagnostics.record("backend_command", action, extra = mapOf("outcome" to "started"))
        try {
            executeCommand(token, request).also {
                RuntimeDiagnostics.record("backend_command", action, extra = mapOf("outcome" to "completed"))
            }
        } catch (error: Exception) {
            if (error !is kotlinx.coroutines.CancellationException) RuntimeDiagnostics.record("backend_command_failed", action, error)
            throw error
        }
    }

    private suspend fun executeCommand(token: String, request: JsonObject): JsonObject = database.transaction { connection, startedAt ->
        request.only("version", "operationId", "action", "args")
        if (request.number("version") != 1L) reject("unsupported_contract_version")
        val initial = authenticateScope(connection, token, startedAt)
        connection.lockStudent(initial.studentId)
        // Forget/delete may have committed while this transaction waited for the student lock.
        val now = database.clock.instant()
        val scope = authenticateScope(connection, token, now)
        val id = request.text("operationId")
        if (id.isBlank() || id.length > 250) reject("invalid_operation_id")
        val action = request.text("action")
        val args = request.obj("args")
        if (scope.jobId != null && action !in setOf("job.apply", "job.renew", "job.fail")) reject("not_permitted")
        if (scope.role == "text" && action != "run.claim") assertRun(connection, scope, now)
        val fingerprint = digest(canonical(json("action" to action, "args" to args, "role" to scope.role,
            "requestId" to scope.requestId, "jobId" to scope.jobId)))
        val previous = connection.document("SELECT to_jsonb(o) FROM foundation_operation o WHERE student_id=? AND operation_id=?", scope.studentId, id)
        if (previous != null) {
            if (previous.text("input_digest") != fingerprint) reject("operation_conflict")
            if (previous.optionalText("expires_at")?.let { Instant.parse(it) <= now } == true) reject("result_expired")
            previous.obj("result")
        } else {
            val result = dispatch(connection, scope, action, args, now)
            val deadline = connection.rows("SELECT min(expires_at) FROM conversation_event WHERE student_id=? AND body IS NOT NULL AND expires_at>?",
                scope.studentId, now) { it.getTimestamp(1)?.toInstant() }.single() ?: now.plusSeconds(HISTORY_SECONDS)
            connection.update("INSERT INTO foundation_operation(student_id,operation_id,action,request_id,role,input_digest,result,created_at,expires_at) VALUES (?,?,?,?,?,?,?::jsonb,?,?)",
                scope.studentId, id, action, scope.requestId, scope.role, fingerprint, result, now, deadline)
            result
        }
    }

    private fun dispatch(connection: Connection, scope: ExecutionScope, action: String, args: JsonObject, now: Instant): JsonObject = when (action) {
        "voice.heartbeat", "voice.append", "voice.audio", "voice.audio_stored", "voice.playback", "voice.end" -> TrialCommands().execute(connection, scope, action, args, now)
        "schedule.agree" -> TrialSchedule().save(connection, scope, args, now, executable = true)
        "schedule.resolve_time" -> TrialSchedule().resolve(connection, scope, args, now)
        "lesson.get" -> connection.lesson(scope, args.text("lessonId"))
        "lesson.list" -> lessons.list(connection, scope)
        "lesson.next" -> json("lesson" to connection.document("SELECT to_jsonb(l) FROM lesson l WHERE student_id=? AND kind='regular' AND launch_mode='executable' AND status='scheduled' AND starts_at>? AND id IS DISTINCT FROM ? ORDER BY starts_at,id LIMIT 1", scope.studentId, now, scope.lessonId))
        "trial.schedule_cancel" -> TrialSchedule().cancel(connection, scope, args, now)
        "trial.schedule" -> TrialSchedule().save(connection, scope, args, now)
        "trial.schedules" -> TrialSchedule().read(connection, scope)
        "retention.prune" -> maintenance.prune(connection, scope, now)
        "memory.reject" -> maintenance.rejectCandidate(connection, scope, args)
        "memory.forget" -> maintenance.forget(connection, scope, args, now)
        "student.delete" -> maintenance.deleteStudent(connection, scope, now)
        "deletion.replay" -> maintenance.replayDeletions(connection, scope, now)
        "job.retry" -> maintenance.retry(connection, scope, args, now)
        "tutor.update_context" -> tutorContext.update(connection, scope, args, now)
        "run.claim" -> runs.claim(connection, scope, now)
        "run.complete" -> runs.complete(connection, scope, args, now)
        "memory.record" -> memory.record(connection, scope, args, now)
        "memory.propose" -> memory.propose(connection, scope, args, now)
        "memory.confirm" -> memory.confirm(connection, scope, args, now)
        "lesson.create" -> lessons.create(connection, scope, args, now)
        "lesson.reschedule" -> lessons.change(connection, scope, args, now, cancel = false)
        "lesson.cancel" -> lessons.change(connection, scope, args, now, cancel = true)
        "lesson.episode" -> lessons.episode(connection, scope, args, now)
        "lesson.append" -> lessons.append(connection, scope, args, now)
        "lesson.end_episode" -> lessons.endEpisode(connection, scope, args, now)
        "lesson.complete" -> lessons.complete(connection, scope, args, now)
        "schedule.materialize.request" -> BackgroundContracts().request(connection, scope, "schedule.materialize", args, now)
        "reminder.request" -> BackgroundContracts().request(connection, scope, "notification.reminder", args, now)
        "schedule.create" -> learning.schedule(connection, scope, args)
        "access.confirm_payment" -> learning.payment(connection, scope, args, now)
        "roadmap.revise" -> learning.roadmap(connection, scope, args, now)
        "job.claim" -> jobs.claim(connection, scope, args, now)
        "job.renew" -> jobs.renew(connection, scope, args, now)
        "job.fail" -> jobs.fail(connection, scope, args, now)
        "job.apply" -> jobs.apply(connection, scope, args, now)
        else -> reject("unknown_action")
    }

    suspend fun snapshot(token: String, purpose: String, lessonId: String? = null): JsonObject = database.transaction(readOnly = true) { connection, now ->
        val scope = authenticateScope(connection, token, now)
        if (purpose !in setOf("text", "voice", "learning")) reject("invalid_purpose")
        if (scope.role != "operator" && scope.role != purpose) reject("not_permitted")
        if (scope.lessonId != null && scope.lessonId != lessonId) reject("not_owned")
        if (purpose == "voice" && lessonId == null) reject("lesson_required")
        val student = connection.document("SELECT to_jsonb(s) FROM student s WHERE id=?", scope.studentId) ?: reject("not_owned")
        val goal = connection.document("SELECT to_jsonb(g) FROM learning_goal g WHERE student_id=? ORDER BY revision DESC LIMIT 1", scope.studentId)
        val facts = connection.documents("""SELECT to_jsonb(f) || jsonb_build_object('source_content_expired',e.body IS NULL OR e.expires_at<=?)
            FROM memory_fact f LEFT JOIN conversation_event e ON e.id=f.source_event_id
            WHERE f.student_id=? AND forgotten_at IS NULL AND value IS NOT NULL ORDER BY category,fact_key""", now, scope.studentId)
        val observations = connection.documents("""SELECT to_jsonb(o) || jsonb_build_object('source_content_expired',e.body IS NULL OR e.expires_at<=?, 'occurred_at',e.occurred_at)
            FROM memory_observation o LEFT JOIN conversation_event e ON e.id=o.source_event_id
            WHERE o.student_id=? ORDER BY e.occurred_at DESC,o.id LIMIT 40""", now, scope.studentId)
        val channel = if (lessonId != null) "lesson:$lessonId" else "telegram"
        val through = if (scope.role == "text" && scope.requestId != null) connection.rows(
            "SELECT e.sequence FROM tutor_inbox i JOIN conversation_event e ON e.id=i.event_id WHERE i.student_id=? AND i.request_id=?",
            scope.studentId, scope.requestId) { it.getLong(1) }.single() else Long.MAX_VALUE
        val events = connection.documents(
            """SELECT to_jsonb(e) FROM conversation_event e JOIN tutor_conversation c ON c.id=e.conversation_id
               WHERE e.student_id=? AND c.channel=? AND e.body IS NOT NULL AND e.expires_at>? AND e.sequence<=? ORDER BY e.sequence DESC LIMIT 40""", scope.studentId, channel, now, through,
        ).reversed()
        val lesson = lessonId?.let { connection.lesson(scope, it) }
        val workSource = scope.jobId?.let { jobId ->
            val job = connection.document("SELECT to_jsonb(j) FROM foundation_job j WHERE id=? AND student_id=?", jobId, scope.studentId) ?: reject("not_owned")
            job.obj("input_refs").optionalText("sourceId")?.let { sourceMessage(connection, scope, it, now) }
        }
        val roadmap = connection.document("SELECT to_jsonb(v) FROM roadmap r JOIN roadmap_revision v ON v.roadmap_id=r.id AND v.revision=r.active_revision WHERE r.student_id=? AND v.status='active'", scope.studentId)
        val booking = connection.document("SELECT to_jsonb(l) FROM lesson l WHERE student_id=? AND kind='trial' AND status IN ('scheduled','in_progress') ORDER BY created_at DESC LIMIT 1", scope.studentId)
        json("version" to 1, "student" to student, "goal" to goal, "summary" to connection.document("SELECT to_jsonb(s) FROM tutor_summary s WHERE student_id=? AND expires_at>?", scope.studentId, now), "facts" to JsonArray(facts), "observations" to JsonArray(observations),
            "trialResult" to connection.document("SELECT to_jsonb(e) FROM lesson_evaluation e WHERE student_id=? ORDER BY created_at DESC LIMIT 1", scope.studentId),
            "roadmap" to roadmap, "events" to JsonArray(events), "workSource" to workSource, "lesson" to lesson, "booking" to booking,
            "preparation" to lessonId?.let { connection.preparation(scope, it) })
    }

    suspend fun receipt(token: String, id: String): JsonObject = database.transaction(readOnly = true) { connection, now ->
        val scope = authenticateScope(connection, token, now)
        val receipt = connection.document("SELECT to_jsonb(o) FROM foundation_operation o WHERE student_id=? AND operation_id=? AND role=? AND request_id IS NOT DISTINCT FROM ? AND expires_at>?",
            scope.studentId, id, scope.role, scope.requestId, now)
        json("result" to receipt?.obj("result"))
    }

    suspend fun inspectJobs(token: String): JsonObject = database.transaction(readOnly = true) { connection, now ->
        val scope = authenticateScope(connection, token, now)
        requireRole(scope, "operator", "learning")
        json("jobs" to JsonArray(connection.documents("SELECT to_jsonb(j) FROM foundation_job j WHERE student_id=? ORDER BY created_at DESC LIMIT 100", scope.studentId)))
    }

    suspend fun diagnostics(token: String): JsonObject = database.transaction(readOnly = true) { connection, now ->
        val scope = authenticateScope(connection, token, now)
        requireRole(scope, "operator")
        json("jobs" to JsonArray(connection.documents("""SELECT jsonb_build_object('status',status,'count',count(*),'oldestDueAt',min(due_at))
            FROM foundation_job WHERE student_id=? GROUP BY status ORDER BY status""", scope.studentId)),
            "deliveries" to JsonArray(connection.documents("SELECT to_jsonb(d) FROM tutor_delivery d WHERE student_id=? AND outcome<>'sent' ORDER BY request_id LIMIT 100", scope.studentId)),
            "runs" to JsonArray(connection.documents("""SELECT jsonb_build_object('requestId',request_id,'status',status,'leaseUntil',lease_until,'fence',fence,'createdAt',created_at)
                FROM tutor_inbox WHERE student_id=? AND status<>'ready' ORDER BY created_at LIMIT 100""", scope.studentId)),
            "attempts" to JsonArray(connection.documents("""SELECT to_jsonb(a) FROM foundation_job_attempt a JOIN foundation_job j ON j.id=a.job_id
                WHERE j.student_id=? ORDER BY a.started_at DESC LIMIT 100""", scope.studentId)))
    }

    override fun close() = database.close()
}
