package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import kotlinx.serialization.json.JsonObject

/** Integration port for SP-12A's historical resolver. Null means unavailable, never validated.
 * Implementations must verify student/task/plan ownership and membership in the exact revision.
 * Return the historical stage definition (including method); never retarget to the current Plan. */
internal fun interface ObservationStageResolver {
    fun resolve(connection: Connection, student: String, task: String, reference: JsonObject): JsonObject?
}

internal val unresolvedObservationStage = ObservationStageResolver { _, _, _, _ -> null }

/** The integrated SP-12A resolver keeps Stage references tied to their exact Plan revision. */
internal val learningPlanObservationStage = ObservationStageResolver { connection, student, task, reference ->
    LearningPlans().resolve(connection, student, task, reference.text("planId"),
        reference.number("planRevision"), reference.text("stageId")).obj("stage")
}
