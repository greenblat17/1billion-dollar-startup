package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal class LearningState {
    fun schedule(connection: Connection, scope: ExecutionScope, args: JsonObject): JsonObject {
        requireRole(scope, "operator")
        args.only("timezone", "effectiveFrom", "agreement")
        ZoneId.of(args.text("timezone"))
        val id = newId()
        connection.update("INSERT INTO schedule(id,student_id,timezone,effective_from,agreement) VALUES (?,?,?,?,?::jsonb)",
            id, scope.studentId, args.text("timezone"), Instant.parse(args.text("effectiveFrom")), args.obj("agreement"))
        return json("id" to id, "version" to 1)
    }

    fun payment(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("provider", "externalId", "validFrom", "validUntil")
        val from = Instant.parse(args.text("validFrom"))
        val until = Instant.parse(args.text("validUntil"))
        if (until <= from) reject("invalid_period")
        val fingerprint = digest(canonical(args))
        val previous = connection.document("SELECT to_jsonb(p) FROM payment_receipt p WHERE provider=? AND external_id=?", args.text("provider"), args.text("externalId"))
        if (previous != null) {
            if (previous.text("student_id") != scope.studentId || previous.text("input_digest") != fingerprint) reject("operation_conflict")
            return json("subscriptionId" to previous.text("subscription_id"))
        }
        val id = newId()
        connection.update("INSERT INTO subscription(id,student_id,status) VALUES (?,?,'active')", id, scope.studentId)
        connection.update("INSERT INTO payment_receipt(provider,external_id,student_id,subscription_id,input_digest,confirmed_at) VALUES (?,?,?,?,?,?)",
            args.text("provider"), args.text("externalId"), scope.studentId, id, fingerprint, now)
        connection.update("INSERT INTO entitlement(id,student_id,subscription_id,valid_from,valid_until,source_provider,source_external_id) VALUES (?,?,?,?,?,?,?)",
            newId(), scope.studentId, id, from, until, args.text("provider"), args.text("externalId"))
        return json("subscriptionId" to id)
    }

    fun roadmap(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator", "learning")
        args.only("goalRevision", "expectedRevision", "reason", "steps", "mode", "sourceId", "evidence", "sources")
        if (connection.goalRevision(scope.studentId) != args.number("goalRevision")) reject("version_conflict")
        var roadmap = connection.document("SELECT to_jsonb(r) FROM roadmap r WHERE student_id=?", scope.studentId)
        if (roadmap == null) {
            connection.update("INSERT INTO roadmap(id,student_id) VALUES (?,?)", newId(), scope.studentId)
            roadmap = connection.document("SELECT to_jsonb(r) FROM roadmap r WHERE student_id=?", scope.studentId) ?: reject("invalid_state")
        }
        val previous = roadmap.optionalText("active_revision")?.toLong() ?: 0
        if (args.number("expectedRevision") != previous) reject("version_conflict")
        if (args.text("reason").isBlank() || args.text("reason").length > 1000) reject("invalid_reason")
        val mode = args.text("mode")
        if (mode !in setOf("propose", "adapt", "confirm", "initialize")) reject("invalid_mode")
        if (mode == "initialize") {
            if (scope.jobId == null || connection.currentRoadmap(scope.studentId) != null) reject("not_permitted")
            val job = connection.document("SELECT to_jsonb(j) FROM foundation_job j WHERE id=? AND kind='roadmap.prepare' AND status='running'", scope.jobId)
                ?: reject("not_permitted")
            if (job.number("fence") != scope.fence) reject("stale_job")
        }
        if (mode == "confirm") {
            requireRole(scope, "operator")
            evidence(connection, scope, args, now)
        }
        val steps = args["steps"]?.jsonArray ?: reject("invalid_steps")
        if (steps.isEmpty() || steps.size > 20) reject("invalid_steps")
        val ids = steps.map { step ->
            val value = step.jsonObject
            value.only("id", "outcome", "tactic")
            if (value.text("outcome").isBlank() || value.text("outcome").length > 1000 || value.text("tactic").length > 2000) reject("invalid_steps")
            value.text("id")
        }
        if (ids.toSet().size != ids.size) reject("invalid_steps")
        if (mode == "adapt") {
            if (previous == 0L) reject("confirmation_required")
            val old = connection.document("SELECT to_jsonb(r) FROM roadmap_revision r WHERE roadmap_id=? AND revision=?", roadmap.text("id"), previous) ?: reject("invalid_state")
            val outcomes = old.obj("content")["steps"]!!.jsonArray.map { it.jsonObject }.associate { it.text("id") to it.text("outcome") }
            // Autonomous adaptation may change tactics/order, never invent or replace a learning outcome.
            if (outcomes != steps.map { it.jsonObject }.associate { it.text("id") to it.text("outcome") }) reject("confirmation_required")
        }
        val revision = connection.rows("SELECT COALESCE(max(revision),0)+1 FROM roadmap_revision WHERE roadmap_id=?", roadmap.text("id")) { it.getLong(1) }.single()
        val active = mode != "propose"
        connection.update("INSERT INTO roadmap_revision(student_id,roadmap_id,revision,goal_revision,content,reason,confirmed_source_id,status) VALUES (?,?,?,?,?::jsonb,?,?,?)",
            scope.studentId, roadmap.text("id"), revision, args.number("goalRevision"), json("steps" to steps, "sources" to args["sources"]), args.text("reason"), args.optionalText("sourceId"), if (active) "active" else "proposed")
        steps.forEachIndexed { index, step ->
            connection.update("INSERT INTO roadmap_step(roadmap_id,roadmap_revision,step_id,position,outcome) VALUES (?,?,?,?,?)",
                roadmap.text("id"), revision, step.jsonObject.text("id"), index, step.jsonObject.text("outcome"))
        }
        if (active) {
            connection.update("UPDATE roadmap_revision SET status='superseded' WHERE roadmap_id=? AND revision<>? AND status='active'", roadmap.text("id"), revision)
            connection.update("UPDATE roadmap SET active_revision=? WHERE id=?", revision, roadmap.text("id"))
            connection.update("UPDATE lesson_plan SET status='stale' WHERE student_id=?", scope.studentId)
            connection.invalidatePreparation(scope.studentId, now, reviewRoadmap = false)
        }
        return json("id" to roadmap.text("id"), "revision" to revision, "status" to if (active) "active" else "proposed")
    }
}
