@file:OptIn(InternalHotReloadApi::class)

package com.eliteteam.speakingcoach

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import org.jetbrains.compose.reload.InternalHotReloadApi
import org.jetbrains.compose.reload.core.info

/**
 * Forwards Kermit into CHR `get_logs` (same `main.chr.log` as recomposition traces).
 * No-op when not launched via `hotRun`. Does not replace platform stdout/Logcat.
 */
internal class ChrKermitLogWriter : LogWriter() {
    private val chr = createChrLogger("Kermit")

    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        val logger = chr ?: return
        val letter = severity.name.first()
        val cause = throwable?.let { " ${it::class.simpleName}" }.orEmpty()
        logger.info("$letter/$tag: $message$cause")
    }
}
