package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class LegacyMemoryQueue(private val database: FoundationDatabase) {
    suspend fun command(request: JsonObject): JsonObject = database.transaction { c, now ->
        request.only("action", "args")
        val args = request.obj("args")
        // The retained completion marker prevents replay; source retention never slides on retry.
        c.update("DELETE FROM legacy_memory_job WHERE done_until<=?", now)
        c.update("UPDATE legacy_memory_job SET payload=NULL WHERE expires_at<=?", now)
        when (request.text("action")) {
            "enqueue" -> enqueue(c, args, now)
            "sessions" -> {
                args.only()
                json("sessions" to JsonArray(c.rows("SELECT DISTINCT session_id FROM legacy_memory_job WHERE done_until IS NULL ORDER BY session_id") { JsonPrimitive(it.getString(1)) }))
            }
            "pending" -> {
                args.only()
                c.document("SELECT jsonb_build_object('count',count(*),'age',COALESCE(GREATEST(0,extract(epoch FROM (?::timestamptz-min(created_at)))),0)) FROM legacy_memory_job WHERE done_until IS NULL", now) ?: json("count" to 0, "age" to 0)
            }
            "claim", "release", "update", "ack" -> operate(c, request.text("action"), args, now)
            else -> reject("unknown_action")
        }
    }

    private fun enqueue(c: Connection, args: JsonObject, now: Instant): JsonObject {
        args.only("job")
        val job = args.obj("job")
        validate(job)
        val created = Instant.ofEpochMilli((job.double("created_at") * 1000).toLong())
        if (created > now.plusSeconds(60)) reject("invalid_time")
        val count = c.update("INSERT INTO legacy_memory_job(id,session_id,payload,created_at,expires_at) VALUES (?,?,?::jsonb,?,?) ON CONFLICT DO NOTHING",
            job.text("id"), job.text("session"), if (created.plusSeconds(7 * 86400) > now) job else null, created, created.plusSeconds(7 * 86400))
        return json("added" to (count == 1))
    }

    private fun operate(c: Connection, action: String, args: JsonObject, now: Instant): JsonObject {
        args.only("session", "token", "job")
        val session = args.text("session")
        val token = args.text("token")
        if (session.isBlank() || token.isBlank() || session.length > 200 || token.length > 200) reject("invalid_identity")
        c.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "legacy-memory:$session") { Unit }
        val head = c.document("SELECT to_jsonb(j) FROM legacy_memory_job j WHERE session_id=? AND done_until IS NULL ORDER BY position LIMIT 1 FOR UPDATE", session)
        if (action == "claim") {
            if (head == null || head.optionalText("lease_until")?.let { Instant.parse(it) > now } == true) return json("job" to null)
            c.update("UPDATE legacy_memory_job SET lease_token=?,lease_until=? WHERE id=?", token, now.plusSeconds(45), head.text("id"))
            return json("job" to (head["payload"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }
                ?: json("id" to head.text("id"), "session" to session, "transcript" to "", "created_at" to 0)))
        }
        if (head == null || head.optionalText("lease_token") != token || head.optionalText("lease_until")?.let { Instant.parse(it) > now } != true) {
            if (action == "update") reject("stale_job")
            return json("saved" to false)
        }
        if (action == "release") {
            c.update("UPDATE legacy_memory_job SET lease_token=NULL,lease_until=NULL WHERE id=?", head.text("id"))
            return json("saved" to true)
        }
        val job = args.obj("job")
        validate(job)
        if (job.text("id") != head.text("id") || job.text("session") != session) reject("not_owned")
        if (action == "update") {
            if (Instant.parse(head.text("expires_at")) <= now) reject("source_expired")
            // Original input is immutable; only retry state may change.
            val original = head.obj("payload")
            if (job.text("transcript") != original.text("transcript") || job.double("created_at") != original.double("created_at")) reject("operation_conflict")
            c.update("UPDATE legacy_memory_job SET payload=?::jsonb WHERE id=?", job, head.text("id"))
        } else {
            c.update("UPDATE legacy_memory_job SET payload=NULL,done_until=?,lease_token=NULL,lease_until=NULL WHERE id=?", now.plusSeconds(7 * 86400), head.text("id"))
        }
        return json("saved" to true)
    }

    private fun validate(job: JsonObject) {
        job.only("id", "session", "transcript", "created_at", "next_at", "save_attempts", "candidate", "previous_person")
        if (job.text("id").isBlank() || job.text("id").length > 200 || job.text("session").isBlank() || job.text("session").length > 200 ||
            job.text("transcript").length > 16000 || !job.double("created_at").isFinite()) reject("invalid_job")
    }
}
