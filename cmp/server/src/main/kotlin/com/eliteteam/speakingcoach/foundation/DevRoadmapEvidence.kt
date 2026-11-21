package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.RuntimeDiagnostics
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private const val bookingRequest =
    "Саша, хочу провести пробный урок сейчас. Если прежнее приглашение уже истекло, перенеси пробный на сейчас, пожалуйста."
private const val probeAttempt = "dev-logging-roadmap-transcript-v1"
private val learnerTurns = listOf(
    "I work as a project manager. Every Monday I explain our progress to my team.",
    "Yesterday I met a new colleague. We discussed the delivery dates and found a solution.",
    "If a customer asks for a deadline, I usually check with the team before I answer.",
    "I want to speak more clearly in meetings because I sometimes need extra time to find the right words.",
)

/** Operator-invoked DEV acceptance for one explicitly requested Telegram Trial. */
fun main(args: Array<String>) = runBlocking {
    require(System.getenv("SPEAKY_ENV") == "dev" && System.getenv("AGENT_TEST_ENABLED") == "true")
    require(args.size == 2 && args[0] in setOf("submit", "inspect"))
    val at = Instant.parse(args[1])
    val url = requireNotNull(System.getenv("DATABASE_URL"))
    FoundationService(FoundationDatabase(url)).use { service ->
        if (args[0] == "submit") println(submitFixture(service, at)) else println(readEvidence(service, at))
    }
}

private suspend fun submitFixture(service: FoundationService, at: Instant): JsonObject =
    service.database.transaction { c, now ->
        if (at < now.minusSeconds(1800) || at > now.plusSeconds(60)) reject("probe_timestamp_expired")
        val target = probeTarget(c, at)
        val student = target.text("student_id")
        val lesson = target.text("lesson_id")
        c.lockStudent(student)
        val previous = c.document("""SELECT jsonb_build_object('id',id) FROM call_session
            WHERE lesson_id=? AND attempt_id=?""", lesson, probeAttempt)
        if (previous != null) return@transaction json("lesson_id" to lesson, "outcome" to "replayed")
        val state = StudentLearningState().read(c, student, now)
        if (state.obj("profile").text("targetLevel") !in LearningCatalogModel.levels ||
            (state["goal"] as? JsonObject)?.obj("content")?.optionalText("value").isNullOrBlank()) reject("probe_learning_goal_missing")
        val opened = TrialAccess(service).open(c, student, target.number("data_epoch"),
            json("lessonId" to lesson, "attemptId" to probeAttempt), now)
        val scope = authenticateScope(c, opened.text("scope"), now)
        val call = opened.text("callSessionId")
        val context = json("lessonId" to lesson, "callSessionId" to call)
        val execution = TrialExecution()
        execution.ready(c, scope, context, now)
        execution.phase(c, scope, JsonObject(context + json("phase" to "conversation")), now)
        val writer = TrialCommands()
        learnerTurns.forEachIndexed { index, body ->
            writer.execute(c, scope, "voice.append", JsonObject(context + json(
                "sourceId" to "synthetic-learner-$index", "role" to "user", "body" to body,
                "occurredAt" to now.minusMillis((learnerTurns.size - index).toLong()).toString(),
                "metadata" to json("finality" to "final"))), now)
        }
        val endReason = if (trialSp24(opened.obj("lesson"))) "learner_requested" else "end_button"
        writer.execute(c, scope, "voice.end", JsonObject(context + json("reason" to endReason)), now)
        c.update("DELETE FROM execution_scope WHERE token_digest=?", scope.tokenDigest)
        FoundationDatabase.recordAfterCommit(RuntimeDiagnostics.Event("dev_trial_fixture", "trial.acceptance", "commit",
            RuntimeDiagnostics.Outcome.COMPLETED, "DEV accepted a synthetic Trial transcript."),
            mapOf("lesson_id" to lesson, "call_session_id" to call, "count" to learnerTurns.size.toString()))
        json("lesson_id" to lesson, "call_session_id" to call, "transcript_events" to learnerTurns.size, "outcome" to "completed")
    }

private fun probeTarget(c: java.sql.Connection, at: Instant): JsonObject {
    val matches = c.documents("""SELECT DISTINCT jsonb_build_object('student_id',s.id,'data_epoch',s.data_epoch,
            'lesson_id',l.id) AS target
        FROM conversation_event e JOIN tutor_conversation v ON v.id=e.conversation_id AND v.channel='telegram'
        JOIN student s ON s.id=e.student_id AND s.deleted_at IS NULL
        JOIN lesson l ON l.student_id=s.id AND l.kind='trial' AND l.trial_version='v5.5'
            AND l.launch_mode='executable' AND l.status IN ('scheduled','in_progress','completed')
            AND l.starts_at BETWEEN ? AND ?
        WHERE e.role='user' AND e.body=? AND e.received_at BETWEEN ? AND ?
            AND EXISTS (SELECT 1 FROM student_identity i WHERE i.student_id=s.id AND i.provider='telegram')
        LIMIT 2""", at.minusSeconds(120), at.plusSeconds(300), bookingRequest, at.minusSeconds(30), at.plusSeconds(30))
    return matches.singleOrNull() ?: reject("probe_booking_not_unique")
}

private suspend fun readEvidence(service: FoundationService, at: Instant): JsonObject =
    service.database.transaction(readOnly = true) { c, _ ->
        val target = probeTarget(c, at)
        val student = target.text("student_id")
        val lesson = target.text("lesson_id")
        val migration = c.rows("SELECT EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='67' AND success)") {
            it.getBoolean(1)
        }.single()
        val jobs = c.documents("""SELECT jsonb_build_object('id',j.id,'kind',j.kind,'status',j.status,
             'fence',j.fence,'error_code',j.error_code,'source_job_id',j.source_job_id,
             'attempt_outcome',a.outcome,'attempt_finished_at',a.finished_at)
            FROM foundation_job j LEFT JOIN foundation_job_attempt a ON a.job_id=j.id AND a.fence=j.fence
            WHERE j.student_id=? AND j.target_id=? AND j.kind IN ('trial.observe','trial.assess','roadmap.generate')
            ORDER BY j.created_at,j.id LIMIT 20""", student, lesson)
        val deliveries = c.documents("""SELECT jsonb_build_object('id',d.id,'status',d.status,'revision',d.roadmap_revision,
            'source_job_id',d.source_job_id,'source_job_fence',d.source_job_fence,'parts',d.next_part,
            'roadmap_source_matches',COALESCE(h.generation_job_id=d.source_job_id AND h.generation_fence=d.source_job_fence,FALSE))
            FROM initial_roadmap_delivery d LEFT JOIN learning_roadmap_history h
            ON h.student_id=d.student_id AND h.revision=d.roadmap_revision
            WHERE d.student_id=? AND d.lesson_id=? ORDER BY d.created_at LIMIT 20""", student, lesson)
        json("lesson_id" to lesson, "migration_67" to migration, "jobs" to JsonArray(jobs), "deliveries" to JsonArray(deliveries))
    }
