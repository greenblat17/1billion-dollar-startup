package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.RuntimeDiagnostics
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Event
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Outcome
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/** Explicit offline commands; validate needs no database. Import never publishes. */
fun main(args: Array<String>) = runBlocking {
    val action = args.firstOrNull()?.takeIf { it in setOf("validate", "import", "publish") } ?: "unknown"
    var stage = "arguments"
    try {
        when (action) {
            "validate", "import" -> {
                require(args.size == 2)
                stage = "read_manifest"
                val raw = Files.readString(Path.of(args[1]))
                stage = "validate"
                val manifest = LearningCatalogModel.parse(raw)
                if (action == "import") {
                    stage = "import"
                    FoundationDatabase(requireNotNull(System.getenv("DATABASE_URL"))).use { database ->
                        LearningCatalog(database).import(raw)
                    }
                } else {
                    RuntimeDiagnostics.record(Event("catalog_validate", "catalog.validate", "validate", Outcome.COMPLETED,
                        "The catalog passed manifest validation."), mapOf("catalog_version" to manifest.version,
                        "count" to manifest.topics.size.toString()))
                }
                println("$action: ${manifest.version}; ${manifest.skills.size} skills; ${manifest.problems.size} problems; ${manifest.topics.size} topics; content acceptance is separate")
            }
            "publish" -> {
                require(args.size == 4)
                stage = "publish"
                FoundationDatabase(requireNotNull(System.getenv("DATABASE_URL"))).use { database ->
                    LearningCatalog(database).publish(args[1], args[2], args[3])
                }
                println("Published catalog: ${args[1]}")
            }
            else -> throw IllegalArgumentException()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val reason = (error as? FoundationFailure)?.code ?: when (stage) {
            "arguments" -> "invalid_arguments"
            "read_manifest" -> "catalog_manifest_unreadable"
            "validate" -> "invalid_catalog_manifest"
            else -> "catalog_operation_failed"
        }
        val outcome = if (error is FoundationFailure || stage in setOf("arguments", "validate")) Outcome.REJECTED else Outcome.FAILED
        RuntimeDiagnostics.record(Event("catalog_cli_failed", "catalog.$action", stage, outcome,
            "The catalog command stopped at $stage.", reason), error = error)
        if (stage == "arguments") {
            System.err.println("Usage: learningCatalog validate|import <manifest.json> OR publish <version> <reviewer> <review-reference>")
        }
        exitProcess(1)
    }
}
