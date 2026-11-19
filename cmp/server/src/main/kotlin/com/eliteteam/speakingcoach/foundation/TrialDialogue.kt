package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject

internal val trialDialoguePlaybackStages = setOf(
    "greeting", "get_name", "explain_format", "roadmap_info", "regular_offer", "goodbye",
)

/** Restore dialogue checkpoints from the same journal as the learner's speech and playback. */
internal class TrialDialogue {
    fun checkpoints(c: Connection, lesson: JsonObject, now: Instant): JsonObject = c.document(
        """SELECT COALESCE(jsonb_object_agg(checkpoint, sequence), '{}'::jsonb)
            FROM (
                SELECT checkpoint, MAX(e.sequence) AS sequence
                FROM trial_call_event r JOIN conversation_event e ON e.id=r.source_event_id
                CROSS JOIN LATERAL (VALUES
                    (r.metadata->>'trialDialogueStage'),
                    ('interrupted_' || (r.metadata->>'trialDialogueInterrupted')),
                    (r.metadata->>'trialDialogueDecision')
                ) AS marks(checkpoint)
                WHERE r.student_id=? AND r.lesson_id=? AND r.kind='transcript'
                    AND r.expires_at>? AND checkpoint IS NOT NULL
                GROUP BY checkpoint
            ) AS latest""",
        lesson.text("student_id"), lesson.text("id"), now,
    ) ?: json()

    fun decide(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        args.only("lessonId", "callSessionId", "decision", "sourceId", "evidence")
        c.activeTrial(scope, args, now)
        val lesson = c.lesson(scope, args.text("lessonId"))
        val decision = args.text("decision")
        if (!trialSp24(lesson) || decision !in setOf("name_refused", "early_exit") ||
            (decision == "name_refused" && lesson.text("trial_phase") != "warmup")) reject("invalid_trial_dialogue_decision")
        val source = sourceMessage(c, scope, args.text("sourceId"), now)
        val evidence = StudentLearningModel.requiredString(args["evidence"], 2000)
        if (!source.text("body").contains(evidence)) reject("invalid_evidence")
        val updated = c.update(
            """UPDATE trial_call_event SET metadata=metadata || ?::jsonb
                WHERE student_id=? AND lesson_id=? AND source_event_id=? AND kind='transcript'
                    AND metadata->>'finality'='final' AND expires_at>?""",
            json("trialDialogueDecision" to decision), scope.studentId, scope.lessonId, source.text("id"), now,
        )
        if (updated != 1) reject("invalid_evidence")
        return json("recorded" to true)
    }
}
