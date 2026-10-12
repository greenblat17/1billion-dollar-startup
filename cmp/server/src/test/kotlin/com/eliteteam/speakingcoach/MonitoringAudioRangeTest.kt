package com.eliteteam.speakingcoach

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MonitoringAudioRangeTest {
    @Test
    fun parsesPlayerByteRanges() {
        assertEquals(0..9, parseAudioRange("bytes=0-9", 100))
        assertEquals(90..99, parseAudioRange("bytes=-10", 100))
        assertEquals(90..99, parseAudioRange("bytes=90-", 100))
        assertEquals(0..99, parseAudioRange("bytes=0-999", 100))
        assertNull(parseAudioRange("bytes=100-", 100))
        assertNull(parseAudioRange("bytes=0-1,3-4", 100))
        assertNull(parseAudioRange("bytes=-0", 100))
    }
}
