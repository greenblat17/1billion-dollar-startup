package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.RuntimeDiagnostics.Event
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Outcome
import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Applies a one-time Trial diagnosis through SP-03 writers under the job fence and student lock. */
internal class TrialAssessment {
    fun apply(c: Connection, scope: ExecutionScope, lessonId: String, inputs: JsonObject,
              result: JsonObject, now: Instant, attempt: JobAttemptRef): Boolean {
        result.only("schemaVersion", "catalogVersion", "baseLearningRevision", "observationRefs", "levels", "topics", "coverage")
        if (result.number("schemaVersion") != 1L) reject("invalid_result")
        fun superseded(reason: String, detail: String): Boolean {
            FoundationDatabase.recordAfterCommit(Event("trial_assessment_superseded", "trial.assess", "apply", Outcome.SUPERSEDED,
                "Foundation did not apply Trial Assessment because $detail.", reason),
                mapOf("lesson_id" to lessonId, "job_id" to attempt.id, "fence" to attempt.fence.toString()))
            return false
        }
        val lesson = c.lesson(scope, lessonId)
        if (!trialV55(lesson) || lesson.text("status") != "completed")
            return superseded("lesson_state_changed", "the lesson is no longer a completed v5.5 Trial")
        if (lesson.number("transcript_revision") != inputs.number("transcriptRevision"))
            return superseded("transcript_revision_changed", "the transcript revision changed")
        val observedJob = c.document("SELECT to_jsonb(j) FROM foundation_job j WHERE student_id=? AND id=? AND kind='trial.observe'",
            scope.studentId, inputs.text("observationJobId")) ?: reject("invalid_result")
        if (observedJob.text("status") != "succeeded" || observedJob.text("target_id") != lessonId ||
            observedJob.obj("input_refs").number("transcriptRevision") != inputs.number("transcriptRevision"))
            return superseded("observation_job_changed", "the source observation job no longer matches")
        val state = StudentLearningState().read(c, scope.studentId, now)
        if (state.number("revision") != result.number("baseLearningRevision"))
            return superseded("learning_revision_changed", "the learning state revision changed")
        val provenance = state.obj("provenance")
        if (provenance.any { (key, value) -> key.startsWith("currentLevels.speaky.") &&
                (value as? JsonObject)?.optionalText("component") != "post-trial-assessment" })
            return superseded("tutor_levels_replaced", "a newer assessment owns the current levels")
        if (state.obj("profile").obj("currentLevels").obj("speaky").obj("skills").obj("listening").text("level") != "Unknown")
            return superseded("listening_level_already_known", "the listening level is already known")
        val catalogVersion = result.text("catalogVersion")
        if (catalogVersion != inputs.text("catalogVersion")) return superseded("catalog_version_changed", "the catalog version changed")
        if (c.document("SELECT to_jsonb(r) FROM learning_catalog_release r WHERE version=? AND published_at IS NOT NULL", catalogVersion) == null) reject("catalog_not_published")
        val active = c.documents("""SELECT to_jsonb(o) FROM learning_observation o WHERE o.student_id=? AND o.lesson_id=?
            AND o.status='active' AND o.document->'provenance'->>'component'='trial-observation-extractor'""", scope.studentId, lessonId)
        if (active.size > 200) reject("invalid_result")
        if (active.any { it.obj("document").text("catalogVersion") != catalogVersion })
            return superseded("observation_catalog_changed", "the active observations use a different catalog version")
        val actual = active.associate { it.text("id") to it.number("revision") }
        val supplied = refs(result["observationRefs"])
        if (supplied != actual || supplied.size != active.size)
            return superseded("observation_revisions_changed", "the active observation references changed")
        val readable = active.associate { row -> row.text("id") to LearningObservations(learningPlanObservationStage).read(c, scope.studentId, row.text("id"), now).obj("observation") }
        val coverage = result.obj("coverage")
        coverage.only("active", "usableWithoutTopic", "uncertain", "nonIndependent")
        if (coverage.number("active") != active.size.toLong() ||
            coverage.number("usableWithoutTopic") != readable.values.count { it.text("evidenceUsable") == "true" && it.obj("document").optionalText("topicId") == null }.toLong() ||
            coverage.number("uncertain") != readable.values.count { it.text("evidenceUsable") != "true" }.toLong() ||
            coverage.number("nonIndependent") != readable.values.count { it.obj("document").text("support") != "independent" }.toLong()) reject("invalid_assessment_coverage")
        val levels = result.obj("levels")
        levels.only("overall", "speaking", "listening", "grammar", "vocabulary")
        if (levels.keys != setOf("overall", "speaking", "listening", "grammar", "vocabulary")) reject("invalid_result")
        val eligibleSkills = setOf("speaking", "grammar", "vocabulary")
        val selected = mutableMapOf<String, Map<String, Long>>()
        levels.forEach { (skill, element) ->
            val entry = element as? JsonObject ?: reject("invalid_result")
            entry.only("level", "explanation", "observationRefs")
            val level = StudentLearningModel.enum(entry["level"], StudentLearningModel.levels)
            StudentLearningModel.requiredString(entry["explanation"], 1000)
            val evidence = refs(entry["observationRefs"])
            if (skill == "listening" && (level != "Unknown" || evidence.isNotEmpty())) reject("invalid_listening_assessment")
            if (evidence.any { (id, revision) ->
                    val row = readable[id]
                    row == null || row.number("revision") != revision || row.text("evidenceUsable") != "true" ||
                        row.obj("document").text("support") != "independent" ||
                        row.obj("document").text("route") != "assessment_only" ||
                        row.obj("document").text("practice") != "trial" ||
                        row.obj("document").text("skillId") !in eligibleSkills ||
                        (skill != "overall" && row.obj("document").text("skillId") != skill)
                }) reject("invalid_assessment_source")
            if (level != "Unknown" && skill != "overall") {
                val attempts = evidence.keys.map { readable.getValue(it).obj("document").text("attemptId") }.toSet()
                if (skill !in eligibleSkills || attempts.size < 2) reject("unsupported_skill_level")
                val outcomesByTopic = readable.values.filter { it.text("evidenceUsable") == "true" &&
                    it.obj("document").text("skillId") == skill }.groupBy { it.obj("document").optionalText("topicId") }
                if (outcomesByTopic.any { (topic, observations) -> topic != null &&
                        observations.any { it.obj("document").text("outcome") == "independent_correct" } &&
                        observations.any { it.obj("document").text("outcome") == "uncorrected_error" } }) reject("unsupported_skill_level")
            }
            selected[skill] = evidence
        }
        val overall = levels.getValue("overall") as JsonObject
        if (overall.text("level") != "Unknown") {
            val measured = eligibleSkills.map { (levels.getValue(it) as JsonObject).text("level") }.filter { it != "Unknown" }
            if (measured.size < 2 || overall.text("level") !in measured || selected.getValue("overall").isEmpty()) reject("unsupported_overall_level")
        }
        val topics = result["topics"] as? JsonArray ?: reject("invalid_result")
        if (topics.size > 200) reject("invalid_result")
        val topicWrites = mutableListOf<JsonObject>()
        val topicIds = mutableSetOf<String>()
        topics.forEach { element ->
            val item = element as? JsonObject ?: reject("invalid_result")
            item.only("topicId", "status", "explanation", "observationRefs")
            val topicId = item.text("topicId")
            if (!topicIds.add(topicId)) reject("invalid_result")
            val topic = c.document("SELECT content FROM learning_catalog_topic WHERE version=? AND id=?", catalogVersion, topicId) ?: reject("catalog_topic_not_found")
            val status = StudentLearningModel.enum(item["status"], StudentLearningModel.topicStates)
            StudentLearningModel.requiredString(item["explanation"], 2000)
            val evidence = refs(item["observationRefs"])
            val previous = c.document("""SELECT to_jsonb(k) FROM student_topic_knowledge k WHERE student_id=? AND catalog_version=? AND topic_id=?
                ORDER BY revision DESC LIMIT 1""", scope.studentId, catalogVersion, topicId)
            if (evidence.isEmpty() && (status != "Unknown" || previous == null || previous.text("status") == "Unknown" ||
                    previous.text("component") != "post-trial-assessment" ||
                    StudentLearningModel.sources(previous["sources"]).none {
                        StudentLearningModel.objectValue(it).optionalText("lessonId") == lessonId
                    })) reject("invalid_assessment_source")
            val rows = evidence.map { (id, revision) ->
                readable[id]?.takeIf { it.number("revision") == revision && it.text("evidenceUsable") == "true" &&
                    it.obj("document").text("topicId") == topicId && it.obj("document").text("skillId") == topic.text("skillId") &&
                    it.obj("document").text("support") == "independent" } ?: reject("invalid_assessment_source")
            }
            val correct = rows.filter { it.obj("document").text("outcome") == "independent_correct" }
            val errors = rows.filter { it.obj("document").text("outcome") == "uncorrected_error" }
            val corrected = rows.filter { it.obj("document").text("outcome") == "self_corrected_error" }
            val attempts = { matching: List<JsonObject> -> matching.map { it.obj("document").text("attemptId") }.toSet().size }
            if (status == "Known" && (attempts(correct) < 2 || errors.isNotEmpty() || corrected.isNotEmpty()) ||
                status == "Not Known" && (attempts(errors) < 2 || correct.isNotEmpty()) ||
                status == "Presumed Not Known" && errors.isEmpty() && corrected.isEmpty()) reject("unsupported_topic_status")
            if (previous != null && previous.text("component") != "post-trial-assessment") return false
            topicWrites.add(item)
        }
        val operator = scope.copy(role = "operator")
        val levelRefs = selected.values.flatMap { it.entries }.associate { it.key to it.value }
        if (levelRefs.isNotEmpty() || provenance.values.any { (it as? JsonObject)?.optionalText("component") == "post-trial-assessment" }) {
            if (levelRefs.size > 50) reject("invalid_result")
            val patch = json("currentLevels" to json("speaky" to json(
                "overall" to levelAssessment(levels.getValue("overall") as JsonObject),
                "skills" to json(*listOf("speaking", "listening", "grammar", "vocabulary").map {
                    it to levelAssessment(levels.getValue(it) as JsonObject)
                }.toTypedArray()))))
            StudentLearningState().update(c, operator, json("expectedRevision" to state.number("revision"),
                "patch" to patch, "component" to "post-trial-assessment",
                "sources" to sourceArray(levelRefs)), now, allowUnknownSystemReset = levelRefs.isEmpty())
        }
        topicWrites.forEach { item ->
            val topicId = item.text("topicId")
            val revision = c.rows("SELECT COALESCE(max(revision),0) FROM student_topic_knowledge WHERE student_id=? AND catalog_version=? AND topic_id=?",
                scope.studentId, catalogVersion, topicId) { it.getLong(1) }.single()
            StudentTopicKnowledge().record(c, operator, json("catalogVersion" to catalogVersion, "topicId" to topicId,
                "expectedRevision" to revision, "status" to item.text("status"), "explanation" to item.text("explanation"),
                "component" to "post-trial-assessment", "sources" to sourceArray(refs(item["observationRefs"]))), now)
        }
        val assessed = StudentLearningState().read(c, scope.studentId, now)
        val learningRevision = assessed.number("revision")
        FoundationDatabase.recordAfterCommit(Event("trial_assessment_saved", "trial.assess", "apply",
            Outcome.COMPLETED, "Foundation applied the Trial Assessment."),
            mapOf("lesson_id" to lessonId, "job_id" to attempt.id, "fence" to attempt.fence.toString(),
                "source_job_id" to observedJob.text("id"), "learning_revision" to learningRevision.toString(),
                "count" to active.size.toString()))
        val targetLevel = assessed.obj("profile").text("targetLevel")
        val goal = assessed["goal"] as? JsonObject
        val hasTargetLevel = targetLevel in LearningCatalogModel.levels
        val hasGoal = goal?.obj("content")?.optionalText("value")?.isNotBlank() == true
        if (hasTargetLevel && hasGoal) {
            val knowledgeDigest = PersonalizedRoadmap().knowledgeDigest(c, scope.studentId, catalogVersion)
            c.enqueueJob(scope.studentId, "roadmap.generate",
                "$lessonId:${inputs.number("transcriptRevision")}:$learningRevision:$knowledgeDigest:personalized-roadmap-v1", lessonId,
                json("transcriptRevision" to inputs.number("transcriptRevision"),
                    "learningRevision" to learningRevision, "goalRevision" to goal.number("revision"),
                    "catalogVersion" to catalogVersion, "knowledgeDigest" to knowledgeDigest), now, sourceJobId = attempt.id)
        } else {
            val (reason, message) = when {
                !hasTargetLevel && !hasGoal -> "missing_goal_and_target_level" to "the learner's goal and target level are missing"
                !hasGoal -> "missing_goal" to "the learner's goal is missing"
                else -> "missing_target_level" to "the learner's target level is missing"
            }
            FoundationDatabase.recordAfterCommit(Event("trial_roadmap_decision", "roadmap.generate", "enqueue", Outcome.SKIPPED,
                "Roadmap generation was skipped because $message.", reason),
                mapOf("lesson_id" to lessonId, "source_job_id" to attempt.id))
        }
        return true
    }

    private fun levelAssessment(level: JsonObject) = json("level" to level.text("level"), "explanation" to level.text("explanation"))

    private fun sourceArray(refs: Map<String, Long>) = JsonArray(refs.map { (id, revision) ->
        json("type" to "learning_observation", "id" to id, "revision" to revision)
    })

    private fun refs(value: kotlinx.serialization.json.JsonElement?): Map<String, Long> {
        val array = value as? JsonArray ?: reject("invalid_assessment_source")
        if (array.size > 200) reject("invalid_assessment_source")
        val result = mutableMapOf<String, Long>()
        array.forEach { element ->
            val item = element as? JsonObject ?: reject("invalid_assessment_source")
            item.only("id", "revision")
            val id = item.text("id")
            val revision = item.number("revision")
            if (revision <= 0 || result.put(id, revision) != null) reject("invalid_assessment_source")
        }
        return result
    }
}
