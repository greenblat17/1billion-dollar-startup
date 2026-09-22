package com.eliteteam.speakingcoach.data

import kotlin.test.Test
import kotlin.test.assertEquals

class WebRtcStunTest {
    @Test
    fun usesPublicGoogleStun() {
        assertEquals(
            listOf(
                "stun:stun.l.google.com:19302",
                "stun:stun1.l.google.com:19302",
            ),
            WebRtcStun.urls,
        )
    }
}
