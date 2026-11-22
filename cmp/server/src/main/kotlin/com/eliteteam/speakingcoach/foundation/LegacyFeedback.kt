package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Survey invariants live here; no read-modify-write cycle crosses the HTTP boundary. */
internal class LegacyFeedback(private val database: FoundationDatabase) {
    suspend fun command(request: JsonObject): JsonObject = database.transaction { connection, now ->
        request.only("action", "family", "sessionId", "args")
        val family = request.text("family")
        if (family !in setOf("first", "mode")) reject("invalid_family")
        val action = request.text("action")
        val args = request.obj("args")
        if (action == "list") return@transaction list(connection, family, args)
        val session = request.text("sessionId")
        if (session.isBlank() || session.length > 200) reject("invalid_identity")
        connection.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "feedback:$session") { Unit }
        when (action) {
            "offer" -> offer(connection, family, session, args, now)
            "rate" -> rate(connection, family, session, args, now)
            "answer" -> answer(connection, family, session, args, now)
            "read" -> {
                args.only("callId")
                json("row" to (find(connection, family, session, args.optionalText("callId")) ?: json()))
            }
            "confirm", "release" -> {
                args.only("callId")
                if (family != "mode") reject("unknown_action")
                val id = "mode:${args.text("callId")}"
                val count = if (action == "confirm") connection.update("UPDATE legacy_feedback SET offered_at=COALESCE(offered_at,?) WHERE id=? AND session_id=?", now, id, session)
                    else connection.update("DELETE FROM legacy_feedback WHERE id=? AND session_id=? AND rating IS NULL", id, session)
                json("status" to if (count == 1) "saved" else "ignored")
            }
            "migrate" -> migrate(connection, session, args, now)
            else -> reject("unknown_action")
        }
    }

    private fun offer(c: Connection, family: String, session: String, args: JsonObject, now: Instant): JsonObject {
        args.only("callId", "mode", "username", "character")
        val call = args.text("callId")
        val character = args.text("character")
        if (call.isBlank() || call.length > 200 || character !in setOf("speaky", "kora_h")) reject("invalid_feedback")
        val mode = args.optionalText("mode")
        if (family == "mode") {
            if (mode !in setOf("onboarding", "free", "situation")) reject("invalid_mode")
            if (c.document("SELECT to_jsonb(r) FROM legacy_feedback_mode_rating r WHERE session_id=? AND mode=?", session, mode) != null) return json("status" to "ignored", "reason" to "already_rated")
        }
        val id = if (family == "mode") "mode:$call" else if (character == "kora_h") "kora:$call" else "first:$session"
        val username = args.text("username").removePrefix("@").trim().split(Regex("\\s+")).joinToString(" ").take(64)
        val count = c.update("INSERT INTO legacy_feedback(id,family,session_id,call_id,mode,character,username,offered_at) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
            id, family, session, call, mode, character, username, if (family == "first") now else null)
        if (count == 0) return json("status" to "ignored", "reason" to "already_offered")
        return if (family == "mode") json("status" to "offered", "character" to character, "mode" to mode)
            else json("status" to "offered", "character" to character)
    }

    private fun find(c: Connection, family: String, session: String, call: String?): JsonObject? = if (call == null) {
        c.document("SELECT to_jsonb(f) FROM legacy_feedback f WHERE id=? AND session_id=?", "first:$session", session)
    } else c.document("SELECT to_jsonb(f) FROM legacy_feedback f WHERE family=? AND session_id=? AND call_id=? ORDER BY (character='kora_h') DESC LIMIT 1", family, session, call)

    private fun rate(c: Connection, family: String, session: String, args: JsonObject, now: Instant): JsonObject {
        args.only("callId", "choice")
        val choice = args.text("choice")
        if (choice !in setOf("liked", "neutral", "disliked")) reject("invalid_choice")
        val row = find(c, family, session, args.text("callId")) ?: return if (family == "mode")
            JsonObject(rate(c, "first", session, args, now) + json("_legacy" to true)) else json("status" to "ignored")
        if (row.optionalText("rating") != null) return json("status" to "ignored")
        if (family == "mode" && c.update("INSERT INTO legacy_feedback_mode_rating(session_id,mode,instance_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                session, row.text("mode"), row.text("call_id")) == 0) return json("status" to "ignored")
        c.update("UPDATE legacy_feedback SET rating=?,rated_at=? WHERE id=?", choice, now, row.text("id"))
        if (family == "mode" && row.text("mode") != "onboarding") {
            offer(c, "first", session, json("callId" to row.text("call_id"), "character" to row.text("character"), "username" to row.text("username")), now)
            rate(c, "first", session, args, now)
            if (find(c, "first", session, row.text("call_id"))?.optionalText("rating") != null) {
                c.update("UPDATE legacy_feedback SET mirrored=TRUE WHERE id=?", row.text("id"))
            }
        }
        c.update("INSERT INTO legacy_feedback_pending(session_id,feedback_id,expires_at) VALUES (?,?,?) ON CONFLICT(session_id) DO UPDATE SET feedback_id=EXCLUDED.feedback_id,expires_at=EXCLUDED.expires_at", session, row.text("id"), now.plusSeconds(86400))
        return json("status" to "rated")
    }

    private fun answer(c: Connection, family: String, session: String, args: JsonObject, now: Instant): JsonObject {
        args.only("callId", "text")
        val call = args.optionalText("callId")
        val row = if (call != null) find(c, family, session, call) ?: find(c, "first", session, call)
            else c.document("SELECT to_jsonb(f) FROM legacy_feedback_pending p JOIN legacy_feedback f ON f.id=p.feedback_id WHERE p.session_id=? AND p.expires_at>?", session, now)
                ?: find(c, "first", session, null)
        if (row == null || row.optionalText("rating") == null || row.optionalText("answer") != null ||
            Instant.parse(row.text("rated_at")).plusSeconds(86400) < now) return json("status" to "ignored")
        val text = args.optionalText("text")?.trim()
        if (text != null && text.isEmpty()) return json("status" to "ignored")
        if (text != null && text.length > 500) return json("status" to "too_long")
        c.update("UPDATE legacy_feedback SET answer=? WHERE id=?", if (text == null) "skip" else "text:$text", row.text("id"))
        return json("status" to "saved")
    }

    private fun list(c: Connection, family: String, args: JsonObject): JsonObject {
        args.only("offset", "limit", "includeRatedAt")
        val offset = args.number("offset").coerceAtLeast(0)
        val limit = args.number("limit").coerceIn(0, 500)
        val filter = if (family == "first") "f.family='first'" else "(f.family='mode' OR NOT EXISTS (SELECT 1 FROM legacy_feedback m WHERE m.family='mode' AND m.call_id=f.call_id AND m.session_id=f.session_id AND m.rating IS NOT NULL))"
        val total = c.rows("SELECT count(*) FROM legacy_feedback f WHERE rating IS NOT NULL AND $filter") { it.getLong(1) }.single()
        val rows = c.documents("SELECT to_jsonb(f) FROM legacy_feedback f WHERE rating IS NOT NULL AND $filter ORDER BY rated_at DESC,id LIMIT ? OFFSET ?", limit, offset)
        val items = rows.map { row ->
            val base = json("sessionId" to row.text("session_id"), "callId" to row.text("call_id"), "username" to row.text("username"),
                "character" to row.text("character"), "choice" to row.text("rating"), "message" to row.optionalText("answer")?.takeIf { it.startsWith("text:") }?.removePrefix("text:"))
            val mode = if (row.text("family") == "mode") json("mode" to row.text("mode")) else json()
            val rated = if (args.optionalText("includeRatedAt") == "true") json("ratedAt" to Instant.parse(row.text("rated_at")).unix()) else json()
            JsonObject(base + mode + rated)
        }
        return json("total" to total, "items" to JsonArray(items))
    }

    private fun migrate(c: Connection, session: String, args: JsonObject, now: Instant): JsonObject {
        args.only("force")
        val created = c.update("INSERT INTO legacy_feedback_migration(session_id) VALUES (?) ON CONFLICT DO NOTHING", session)
        if (created == 0 && args.optionalText("force") != "true") return json("saved" to true)
        // Infer only from retained calls owned by this session, never from ambiguous old ratings.
        val rows = c.documents("SELECT to_jsonb(c) FROM legacy_call c JOIN legacy_feedback f ON f.call_id=c.id AND f.session_id=c.session_id WHERE f.family='first' AND f.rating IS NOT NULL AND c.session_id=? AND (c.payload->>'startedUnix')::double precision>?", session, now.minusSeconds(7 * 86400).unix())
        rows.forEach { row ->
            val mode = if (row.obj("payload")["scenario"].let { it == null || it == kotlinx.serialization.json.JsonNull || it.toString() == "\"\"" }) "free" else "situation"
            c.update("INSERT INTO legacy_feedback_mode_rating(session_id,mode,instance_id) VALUES (?,?,?) ON CONFLICT DO NOTHING", session, mode, "legacy:${row.text("id")}")
        }
        return json("saved" to true)
    }
}
