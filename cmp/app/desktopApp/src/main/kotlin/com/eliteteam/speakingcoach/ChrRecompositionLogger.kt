@file:OptIn(InternalHotReloadApi::class)

package com.eliteteam.speakingcoach

import com.skydoves.compose.stability.runtime.DefaultRecompositionLogger
import com.skydoves.compose.stability.runtime.RecompositionEvent
import com.skydoves.compose.stability.runtime.RecompositionLogger
import org.jetbrains.compose.reload.InternalHotReloadApi
import org.jetbrains.compose.reload.core.Logger
import org.jetbrains.compose.reload.core.createLogger
import org.jetbrains.compose.reload.core.info
import java.util.Locale
import java.util.ServiceLoader

/**
 * Bridge from compose-stability-analyzer into Compose Hot Reload's log file.
 *
 * Analyzer's JVM default is `println`. MCP `get_logs` does not read stdout: it tails
 * `build/run/main/main.chr.log`, which the CHR agent writes from its `Logger.Log` queue.
 * There is no public CHR logging API (`@InternalHotReloadApi`). Events must go through
 * `createLogger` so `AgentLoggerDispatch` owns the file. Do not open a second writer
 * on `main.chr.log` — the agent already buffers it.
 *
 * The agent `Logger.Dispatch` is often missing from the app TCCL. Load it from the
 * system classloader. If dispatch is empty (process not started via `hotRun`), fall
 * back to [DefaultRecompositionLogger] / stdout.
 */
internal class ChrRecompositionLogger(
    private val fallback: RecompositionLogger = DefaultRecompositionLogger(),
) : RecompositionLogger {
    private val chr: Logger? = createChrLogger()

    override fun log(event: RecompositionEvent) {
        val logger = chr
        if (logger == null) {
            fallback.log(event)
            return
        }
        format(event).forEach { line -> logger.info(line) }
    }
}

private fun createChrLogger(): Logger? {
    val dispatch = loadChrDispatch()
    if (dispatch.isEmpty()) return null
    return createLogger(name = "TraceRecomposition", dispatch = dispatch)
}

private fun loadChrDispatch(): List<Logger.Dispatch> {
    val loaders = listOfNotNull(
        ClassLoader.getSystemClassLoader(),
        Thread.currentThread().contextClassLoader,
        Logger.Dispatch::class.java.classLoader,
    ).distinct()
    return loaders
        .flatMap { loader -> ServiceLoader.load(Logger.Dispatch::class.java, loader).toList() }
        .distinctBy { it.javaClass }
}

private fun format(event: RecompositionEvent): List<String> {
    val tagSuffix = if (event.tag.isNotEmpty()) " (tag: ${event.tag})" else ""
    val durationStr = if (event.durationNanos > 0) {
        " (%.2fms)".format(Locale.ROOT, event.durationNanos / 1_000_000.0)
    } else {
        ""
    }
    val fqSuffix = if (event.fqName.isNotEmpty()) " (fq: ${event.fqName})" else ""
    val autoSuffix = if (event.isAutoTraced) " (auto)" else ""
    val header =
        "[Recomposition #${event.recompositionCount}] " +
            "${event.composableName}$tagSuffix$durationStr$fqSuffix$autoSuffix"
    val tree = buildTreeLines(event)
    if (tree.isEmpty()) return listOf(header)
    return listOf(header) + tree.mapIndexed { index, line ->
        val prefix = if (index == tree.lastIndex) "  └─" else "  ├─"
        "$prefix $line"
    }
}

private fun buildTreeLines(event: RecompositionEvent): List<String> {
    val lines = mutableListOf<String>()
    event.parameterChanges.forEach { change ->
        val status = when {
            change.changed -> "changed (${safeToString(change.oldValue)} → ${safeToString(change.newValue)})"
            change.referenceChanged -> {
                val token = if (change.stable) "stable" else "unstable"
                "$token (${safeToString(change.oldValue)} → ${safeToString(change.newValue)})"
            }
            change.stable -> "stable (${safeToString(change.newValue)})"
            else -> "unstable (${safeToString(change.newValue)})"
        }
        lines.add("[param] ${change.name}: ${change.type} $status")
    }
    event.stateChanges.filter { it.changed }.forEach { change ->
        val site = change.writeSite?.let { " ← $it" } ?: ""
        lines.add(
            "[state] ${change.name}: ${change.type} changed " +
                "(${safeToString(change.oldValue)} → ${safeToString(change.newValue)})$site",
        )
    }
    if (event.unstableParameters.isNotEmpty()) {
        lines.add("Unstable parameters: ${event.unstableParameters}")
    }
    val changedStates = event.stateChanges.filter { it.changed }.map { it.name }
    if (changedStates.isNotEmpty()) {
        lines.add("State changes: $changedStates")
    }
    return lines
}

private fun safeToString(value: Any?): String {
    if (value == null) return "null"
    return try {
        value.toString()
    } catch (_: Throwable) {
        "${value.javaClass.simpleName}@${value.hashCode().toString(16)}"
    }
}
