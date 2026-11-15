package com.eliteteam.speakingcoach.foundation

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/** Explicit offline commands; validate needs no database. Import never publishes. */
fun main(args: Array<String>) = runBlocking {
    require(args.size >= 2) { "Usage: learningCatalog validate|import <manifest.json> OR publish <version> <reviewer> <review-reference>" }
    when (args[0]) {
        "validate", "import" -> {
            require(args.size == 2)
            val raw = Files.readString(Path.of(args[1]))
            val manifest = LearningCatalogModel.parse(raw)
            if (args[0] == "import") FoundationDatabase(requireNotNull(System.getenv("DATABASE_URL"))).use { database ->
                LearningCatalog(database).import(raw)
            }
            println("${args[0]}: ${manifest.version}; ${manifest.skills.size} skills; ${manifest.problems.size} problems; ${manifest.topics.size} topics; content acceptance is separate")
        }
        "publish" -> {
            require(args.size == 4) { "Publication requires reviewer and acceptance reference" }
            FoundationDatabase(requireNotNull(System.getenv("DATABASE_URL"))).use { database ->
                LearningCatalog(database).publish(args[1], args[2], args[3])
            }
            println("Published catalog: ${args[1]}")
        }
        else -> error("Unknown catalog command")
    }
}
