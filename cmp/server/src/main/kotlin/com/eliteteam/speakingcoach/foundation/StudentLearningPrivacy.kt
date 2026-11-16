package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Redact dependencies of a forgotten personal fact, not an unrelated student's learning profile. */
internal class StudentLearningPrivacy {
    fun forgetPersonalFact(c: Connection, student: String, category: String, key: String, now: Instant) {
        // Collect every revision before erasing the fact/history, including during deletion-ledger replay.
        val events = c.rows("""SELECT source_event_id FROM memory_fact_history WHERE student_id=? AND category=? AND fact_key=?
            UNION SELECT source_event_id FROM memory_fact WHERE student_id=? AND category=? AND fact_key=?
            UNION SELECT source_event_id FROM memory_candidate WHERE student_id=? AND fact_key=?""",
            student, category, key, student, category, key, student, key) { it.getString(1) }.filterNotNull().toSet()
        val observations = c.rows("SELECT id,source_event_id FROM memory_observation WHERE student_id=?", student) {
            it.getString(1) to it.getString(2)
        }.toMap()
        fun affected(value: JsonElement?): Boolean = (value as? JsonArray).orEmpty().any { element ->
            val ref = StudentLearningModel.objectValue(element)
            val event = ref.optionalText("sourceEventId") ?: if (ref.text("type") == "event") ref.text("id") else observations[ref.text("id")]
            event in events || (ref["sourceEventIds"] as? JsonArray).orEmpty().any {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content in events
            }
        }
        redactProfile(c, student, now, ::affected)
        redactGoals(c, student, now, events, ::affected)
        redactTopics(c, student, now, ::affected)
        // Collect lineage before removing evidence; SP-03 sources persist every event ID, including hints.
        c.update("""DELETE FROM learning_observation WHERE student_id=? AND id IN
            (SELECT observation_id FROM learning_observation_source WHERE student_id=? AND event_id=ANY(?))""",
            student, student, c.createArrayOf("text", events.toTypedArray()))
        // Rationale and component-supplied explanations can paraphrase facts outside declared source links.
        c.update("UPDATE learning_observation SET document=jsonb_set(document,'{rationale}','\"\"') WHERE student_id=?", student)
        c.update("UPDATE learning_observation_revision SET reason='',document=jsonb_set(document,'{rationale}','\"\"') WHERE student_id=?", student)
    }

    private fun redactProfile(c: Connection, student: String, now: Instant, affected: (JsonElement?) -> Boolean) {
        val row = c.document("SELECT to_jsonb(s) FROM student_learning_state s WHERE student_id=?", student) ?: return
        val provenance = row.obj("provenance")
        val paths = provenance.filter { (_, value) -> affected(StudentLearningModel.objectValue(value)["sources"]) }.keys.toMutableSet()
        if ("homework.period" in paths) paths.add("homework.minutes")
        var profile = row.obj("profile")
        val defaults = StudentLearningModel.empty()
        for (path in paths.filter { it != "goal" }) {
            profile = replace(profile, defaults, path.split('.'))
        }
        // Unproven free-text explanations can paraphrase personal data even without a declared source.
        for (kind in listOf("selfAssessment", "speaky")) {
            val prefix = "currentLevels.$kind"
            for (suffix in listOf("overall") + StudentLearningModel.skills.map { "skills.$it" }) {
                val path = "$prefix.$suffix.explanation"
                if (path !in provenance) profile = replace(profile, defaults, path.split('.'))
            }
        }
        if (profile != row.obj("profile") || paths.isNotEmpty()) {
            c.update("UPDATE student_learning_state SET profile=?::jsonb,provenance=?::jsonb,revision=revision+1,updated_at=? WHERE student_id=?",
                profile, JsonObject(provenance - paths), now, student)
        }
    }

    private fun replace(value: JsonObject, defaults: JsonObject, path: List<String>): JsonObject {
        val key = path.first()
        val replacement = if (path.size == 1) defaults[key] ?: JsonNull
            else replace(value.obj(key), defaults.obj(key), path.drop(1))
        return JsonObject(value + (key to replacement))
    }

    private fun redactGoals(c: Connection, student: String, now: Instant, events: Set<String>, affected: (JsonElement?) -> Boolean) {
        val latest = c.goalRevision(student)
        var currentRemoved = false
        c.documents("SELECT to_jsonb(g) FROM learning_goal g WHERE student_id=?", student).forEach { goal ->
            if (goal.optionalText("source_event_id") in events || affected(goal.obj("content")["sources"])) {
                c.update("UPDATE learning_goal SET content='{}',source_event_id=NULL WHERE student_id=? AND revision=?", student, goal.number("revision"))
                if (goal.number("revision") == latest) currentRemoved = true
            }
        }
        if (currentRemoved) c.update("INSERT INTO learning_goal(student_id,revision,content,created_at) VALUES (?,?,'{}',?)", student, latest + 1, now)
    }

    private fun redactTopics(c: Connection, student: String, now: Instant, affected: (JsonElement?) -> Boolean) {
        val rows = c.documents("SELECT to_jsonb(k) FROM student_topic_knowledge k WHERE student_id=? ORDER BY revision DESC", student)
        val latest = rows.groupBy { it.text("catalog_version") to it.text("topic_id") }.mapValues { it.value.first() }
        for (row in rows) {
            val linked = affected(row["sources"])
            val ungrounded = StudentLearningModel.sources(row["sources"]).isEmpty()
            if (!linked && (!ungrounded || row.text("explanation").isEmpty())) continue
            c.update("""UPDATE student_topic_knowledge SET status=?,explanation='',sources='[]',component='privacy-redaction'
                WHERE student_id=? AND catalog_version=? AND topic_id=? AND revision=?""",
                if (linked) "Unknown" else row.text("status"), student, row.text("catalog_version"), row.text("topic_id"), row.number("revision"))
            if (latest[row.text("catalog_version") to row.text("topic_id")] == row) {
                c.update("""INSERT INTO student_topic_knowledge(student_id,catalog_version,topic_id,revision,status,explanation,sources,component,assessed_at)
                    VALUES (?,?,?,?,?,'','[]','privacy-redaction',?)""", student, row.text("catalog_version"), row.text("topic_id"),
                    row.number("revision") + 1, if (linked) "Unknown" else row.text("status"), now)
            }
        }
    }
}
