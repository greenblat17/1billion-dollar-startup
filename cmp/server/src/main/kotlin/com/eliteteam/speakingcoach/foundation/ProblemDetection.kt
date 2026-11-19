package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** SP-18 evidence score. SP-09 alone applies a learner's decision to the Roadmap. */
internal class ProblemDetection {
    companion object {
        const val PROPOSAL_THRESHOLD = 80.0

        fun next(score: Double, outcome: String): Double {
            require(score.isFinite() && score in 0.0..100.0)
            val result = when (outcome) {
                "uncorrected_error" -> score + (100 - score) * 0.15
                "self_corrected_error" -> score + (100 - score) * 0.07
                "independent_correct" -> score * 0.90
                else -> score
            }
            return result.coerceIn(0.0, 100.0)
        }
    }

    fun refresh(c: Connection, student: String, topic: String?, problem: String?, now: Instant) {
        if (topic == null || problem == null) return
        if (c.document("""SELECT to_jsonb(t) FROM learning_task t WHERE student_id=? AND topic_id=?
            AND problem_id=? AND lifecycle<>'Mastered' LIMIT 1""", student, topic, problem) != null) return
        // One learner attempt can appear in multiple revisions. Read only current active evidence.
        val rows = c.documents("""SELECT to_jsonb(o) FROM learning_observation o WHERE student_id=?
            AND status='active' AND document->>'topicId'=? AND document->>'problemId'=?
            AND document->>'route'='problem_detection' ORDER BY (document->>'occurredAt')::timestamptz,id""",
            student, topic, problem)
        var score = 0.0
        var count = 0
        val attempts = mutableSetOf<String>()
        val evidence = LearningObservations(learningPlanObservationStage)
        for (row in rows) {
            val observation = evidence.read(c, student, row.text("id"), now).obj("observation")
            if (observation.text("scoringEligible") != "true" || !attempts.add(row.text("attempt_id"))) continue
            val outcome = row.obj("document").text("outcome")
            if (outcome !in setOf("uncorrected_error", "self_corrected_error", "independent_correct")) continue
            score = next(score, outcome)
            count++
        }
        c.update("""INSERT INTO learning_problem_score(student_id,topic_id,problem_id,score,evidence_count,updated_at)
            VALUES (?,?,?,?,?,?) ON CONFLICT (student_id,topic_id,problem_id) DO UPDATE SET
            score=EXCLUDED.score,evidence_count=EXCLUDED.evidence_count,updated_at=EXCLUDED.updated_at""",
            student, topic, problem, score, count, now)
    }

    /** Recheck persisted pairs after retention or privacy changes bypass Observation commands. */
    fun reconcileStudent(c: Connection, student: String, now: Instant) {
        val pairs = c.documents("""SELECT jsonb_build_object('topicId',topic_id,'problemId',problem_id)
            FROM learning_problem_score WHERE student_id=?""", student)
        pairs.forEach { refresh(c, student, it.text("topicId"), it.text("problemId"), now) }
    }

    fun read(c: Connection, student: String, topic: String?, problem: String?, offset: Int): JsonObject {
        if (offset !in 0..100000) reject("invalid_offset")
        val rows = c.documents("""SELECT to_jsonb(p) FROM learning_problem_score p WHERE student_id=?
            AND (?::text IS NULL OR topic_id=?) AND (?::text IS NULL OR problem_id=?)
            ORDER BY updated_at DESC,topic_id,problem_id LIMIT 51 OFFSET ?""",
            student, topic, topic, problem, problem, offset)
        val items = rows.take(50).map { row ->
            val active = c.document("""SELECT to_jsonb(t) FROM learning_task t WHERE student_id=?
                AND topic_id=? AND problem_id=? AND lifecycle<>'Mastered' LIMIT 1""",
                student, row.text("topic_id"), row.text("problem_id"))
            val previous = c.document("""SELECT to_jsonb(t) FROM learning_task t WHERE student_id=?
                AND topic_id=? AND problem_id=? AND lifecycle='Mastered' ORDER BY updated_at DESC LIMIT 1""",
                student, row.text("topic_id"), row.text("problem_id"))
            val decision = c.document("""SELECT to_jsonb(d) FROM learning_roadmap_problem_decision d WHERE student_id=?
                AND topic_id=? AND problem_id=?""", student, row.text("topic_id"), row.text("problem_id"))
            val score = row.double("score")
            val proposed = active == null && decision == null && score >= PROPOSAL_THRESHOLD
            JsonObject(row + json("activeTaskId" to active?.text("id"), "previousMasteredTaskId" to previous?.text("id"),
                "learnerDecision" to decision?.text("decision"), "proposal" to if (proposed) json(
                    "topicId" to row.text("topic_id"), "problemId" to row.text("problem_id"),
                    "score" to score, "evidenceCount" to row.number("evidence_count"),
                    "reason" to "Repeated independent evidence reached the experimental Problem Score threshold",
                    "action" to if (previous == null) "consider_task" else "consider_reopening") else null))
        }
        return json("schemaVersion" to 1, "items" to JsonArray(items),
            "nextOffset" to if (rows.size > 50) offset + 50 else null)
    }
}
