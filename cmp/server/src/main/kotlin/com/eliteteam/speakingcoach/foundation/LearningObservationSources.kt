package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** V1 source adapter: durable conversation_event, revision = its persisted sequence.
 * Raw call-event revisions are a separate SP-17B adapter, never confused with this revision. */
internal object LearningObservationSources {
    fun validate(c: Connection, student: String, d: JsonObject, now: Instant) {
        val lesson = d.optionalText("lessonId")
        if (lesson != null) {
            val row = c.document("SELECT to_jsonb(l) FROM lesson l WHERE student_id=? AND id=?", student, lesson) ?: reject("not_owned")
            if (d.text("practice") != "homework" && row.text("kind") != d.text("practice")) reject("observation_lesson_kind")
        }
        for (element in d["sources"] as JsonArray) {
            val ref = element as JsonObject
            val e = c.document("SELECT to_jsonb(e) FROM conversation_event e WHERE student_id=? AND id=?", student, ref.text("eventId"))
                ?: reject("observation_source_not_owned")
            if (e.number("sequence") != ref.number("revision") || e.text("role") != ref.text("role")) reject("stale_observation_source")
            val body = e.optionalText("body") ?: reject("source_expired")
            if (Instant.parse(e.text("expires_at")) <= now) reject("source_expired")
            if (lesson != null && c.document("SELECT to_jsonb(e) FROM lesson_event e WHERE student_id=? AND lesson_id=? AND event_id=?",
                student, lesson, ref.text("eventId")) == null) reject("observation_source_lesson")
            ref["span"]?.takeUnless { it == JsonNull }?.let {
                // Unicode code point offsets, shared with Python; no UTF-16 indexing ambiguity.
                if ((it as JsonObject).number("end") > body.codePointCount(0, body.length)) reject("invalid_source_span")
            }
        }
        if (LearningObservationModel.isTrialListening(d)) validateTrialListening(c, student, d, now)
    }

    private fun validateTrialListening(c: Connection, student: String, d: JsonObject, now: Instant) {
        val refs = (d["sources"] as JsonArray).map { it as JsonObject }
        val raw = refs.associate { ref ->
            ref.text("purpose") to (c.document("""SELECT to_jsonb(r) FROM trial_call_event r
                WHERE r.student_id=? AND r.lesson_id=? AND r.source_event_id=? AND r.kind='transcript' AND r.expires_at>?""",
                student, d.text("lessonId"), ref.text("eventId"), now) ?: reject("invalid_trial_listening"))
        }
        val answer = raw.getValue("answer")
        val prompt = raw["hint"] ?: raw.getValue("context")
        for ((purpose, row) in raw) {
            val metadata = row.obj("metadata")
            if (row.text("call_session_id") != answer.text("call_session_id") || metadata.text("finality") != "final" ||
                (purpose != "answer" && (metadata.optionalText("playbackComplete") != "true" ||
                    (metadata.optionalText("playedMs")?.toDoubleOrNull() ?: 0.0) <= 0.0))) reject("invalid_trial_listening")
        }
        val heardAt = Instant.parse(prompt.text("occurred_at"))
        val firstPromptAt = Instant.parse(raw.getValue("context").text("occurred_at"))
        val answeredAt = Instant.parse(answer.text("occurred_at"))
        val phase = c.document("""SELECT metadata FROM trial_call_event WHERE student_id=? AND lesson_id=?
            AND kind='phase' AND occurred_at<=? ORDER BY occurred_at DESC,id LIMIT 1""",
            student, d.text("lessonId"), answeredAt)
        val started = c.document("""SELECT metadata FROM trial_call_event WHERE student_id=? AND lesson_id=?
            AND kind='phase' AND metadata->>'phase'='conversation' AND occurred_at<=? LIMIT 1""",
            student, d.text("lessonId"), firstPromptAt)
        val answerRef = refs.first { it.text("purpose") == "answer" }
        val previous = c.document("""SELECT to_jsonb(r) FROM trial_call_event r JOIN conversation_event e ON e.id=r.source_event_id
            WHERE r.student_id=? AND r.lesson_id=? AND r.kind='transcript' AND e.sequence<?
            ORDER BY e.sequence DESC LIMIT 1""", student, d.text("lessonId"), answerRef.number("revision"))
        if (firstPromptAt > heardAt || heardAt > answeredAt || phase?.text("phase") != "conversation" ||
            started == null || previous?.text("id") != prompt.text("id")) reject("invalid_trial_listening")
    }

    fun store(c: Connection, student: String, id: String, revision: Long, d: JsonObject) {
        for (element in d["sources"] as JsonArray) {
            val ref = element as JsonObject
            val span = ref["span"] as? JsonObject
            c.update("""INSERT INTO learning_observation_source(student_id,observation_id,revision,event_id,source_revision,role,purpose,span_start,span_end)
                VALUES (?,?,?,?,?,?,?,?,?)""", student, id, revision, ref.text("eventId"), ref.number("revision"), ref.text("role"),
                ref.text("purpose"), span?.number("start"), span?.number("end"))
        }
    }

    fun project(c: Connection, student: String, id: String, revision: Long, now: Instant): JsonArray = JsonArray(c.documents(
        """SELECT jsonb_build_object('eventId',s.event_id,'revision',s.source_revision,'role',s.role,'purpose',s.purpose,
            'sourceContentExpired',e.body IS NULL OR e.expires_at<=?,'sourceRevisionCurrent',e.sequence=s.source_revision,
            'span',CASE WHEN s.span_start IS NULL THEN NULL ELSE jsonb_build_object('start',s.span_start,'end',s.span_end) END)
            FROM learning_observation_source s LEFT JOIN conversation_event e ON e.student_id=s.student_id AND e.id=s.event_id
            WHERE s.student_id=? AND s.observation_id=? AND s.revision=? ORDER BY s.event_id""", now, student, id, revision))

    fun summary(c: Connection, student: String, id: String, now: Instant): JsonObject? {
        val row = c.document("SELECT to_jsonb(o) FROM learning_observation o WHERE student_id=? AND id=?", student, id) ?: return null
        val sources = project(c, student, id, row.number("revision"), now)
        val available = sources.isNotEmpty() && sources.all { (it as JsonObject).text("sourceContentExpired") == "false" && it.text("sourceRevisionCurrent") == "true" }
        val current = sources.isNotEmpty() && sources.all { (it as JsonObject).text("sourceRevisionCurrent") == "true" }
        val expiry = c.document("""SELECT jsonb_build_object('expiresAt',min(e.expires_at)) FROM learning_observation_source s
            JOIN conversation_event e ON e.id=s.event_id AND e.student_id=s.student_id
            WHERE s.student_id=? AND s.observation_id=? AND s.revision=?""", student, id, row.number("revision"))
        return json("event_id" to row.text("anchor_event_id"), "lesson_id" to row.optionalText("lesson_id"),
            "revision" to row.number("revision"), "current_revision" to if (current) row.number("revision") else 0,
            "body" to if (available && row.text("status") == "active") "available" else null,
            "expires_at" to (expiry?.optionalText("expiresAt") ?: Instant.EPOCH.toString()),
            "sourceEventIds" to JsonArray(sources.map { (it as JsonObject).getValue("eventId") }))
    }
}
