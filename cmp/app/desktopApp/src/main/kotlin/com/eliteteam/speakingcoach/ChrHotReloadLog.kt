@file:OptIn(InternalHotReloadApi::class)

package com.eliteteam.speakingcoach

import org.jetbrains.compose.reload.InternalHotReloadApi
import org.jetbrains.compose.reload.core.Logger
import org.jetbrains.compose.reload.core.createLogger
import java.util.ServiceLoader

/**
 * CHR `get_logs` tails `main.chr.log` via `AgentLoggerDispatch`, not stdout.
 * Load dispatch from the system classloader: the agent is often missing from TCCL.
 */
internal fun createChrLogger(name: String): Logger? {
    val dispatch = loadChrDispatch()
    if (dispatch.isEmpty()) return null
    return createLogger(name = name, dispatch = dispatch)
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
