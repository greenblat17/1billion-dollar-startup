package com.eliteteam.speakingcoach

import co.touchlab.kermit.Logger
import co.touchlab.kermit.loggerConfigInit

internal fun testLogger(tag: String = "test"): Logger = Logger(loggerConfigInit(), tag)
