package com.eliteteam.speakingcoach.ui.home

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class TopicKindTest {
    @Test
    fun everydayMapsToApiName() {
        assertEquals("Everyday", TopicKind.Everyday.toApiTopic())
    }

    @Test
    fun randomResolvesToAConcreteTopic() {
        assertContains(
            listOf("Everyday", "Work", "Travel"),
            TopicKind.Random.toApiTopic(),
        )
    }
}
