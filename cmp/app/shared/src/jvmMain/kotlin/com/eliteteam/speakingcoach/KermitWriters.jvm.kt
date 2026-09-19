package com.eliteteam.speakingcoach

import co.touchlab.kermit.LogWriter

private var extraWriters: Array<out LogWriter> = emptyArray()

fun installJvmKermitWriters(vararg writers: LogWriter) {
    extraWriters = writers
}

internal actual fun extraKermitWriters(): Array<out LogWriter> = extraWriters
