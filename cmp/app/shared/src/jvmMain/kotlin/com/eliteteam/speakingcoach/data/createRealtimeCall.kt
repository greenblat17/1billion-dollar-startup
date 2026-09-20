package com.eliteteam.speakingcoach.data

import co.touchlab.kermit.Logger

actual fun createRealtimeCall(logger: Logger): RealtimeCall = JvmRealtimeCall(logger)
