package com.eliteteam.speakingcoach.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApiBaseUrlTest {
    @Test
    fun overrideWinsAndStripsSlash() {
        assertEquals("https://example.test", resolveApiBaseUrl("https://example.test/"))
    }

    @Test
    fun missingUrlFailsWhenNothingBaked() {
        if (ApiConfig.BAKED_API_BASE_URL.isNotBlank()) return
        assertFailsWith<IllegalArgumentException> { resolveApiBaseUrl(null) }
    }
}
