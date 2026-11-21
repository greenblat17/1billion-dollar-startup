package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** Records the played invitation and the learner's response. The learning jobs do not read this state. */
internal class TrialClosing {
    fun react(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        args.only("lessonId", "callSessionId", "sourceId", "reaction", "evidence")
        c.activeTrial(scope, args, now)
        val lesson = c.lesson(scope, args.text("lessonId"))
        val offer = lesson.optionalText("trial_offer_sequence")?.toLong()
        if (!trialSp24(lesson) || lesson.text("trial_phase") != "wrapup" || offer == null) reject("trial_offer_required")
        val reaction = args.text("reaction")
        if (reaction !in setOf("yes", "no", "undecided")) reject("invalid_reaction")
        val source = sourceMessage(c, scope, args.text("sourceId"), now)
        val quote = StudentLearningModel.requiredString(args["evidence"], 2000)
        if (source.number("sequence") <= offer || !source.text("body").contains(quote)) reject("invalid_evidence")
        val prior = lesson.optionalText("trial_reaction_source_id")?.let {
            c.document("SELECT to_jsonb(e) FROM conversation_event e WHERE student_id=? AND id=?", scope.studentId, it)
        }
        if (prior != null && sourceIsNewer(prior, source)) reject("stale_source")
        c.update("UPDATE lesson SET trial_regular_reaction=?,trial_reaction_source_id=? WHERE id=?",
            reaction, source.text("id"), lesson.text("id"))
        return json("regularReaction" to reaction)
    }
}
