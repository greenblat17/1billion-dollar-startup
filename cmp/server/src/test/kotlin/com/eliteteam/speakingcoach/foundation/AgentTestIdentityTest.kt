package com.eliteteam.speakingcoach.foundation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentTestIdentityTest {
    @Test
    fun operatorProbeRequiresExactlyOneMatchingAgentTestIdentity() {
        val id = "00000000-0000-0000-0000-000000000001"
        val agent = "agent_test" to id
        assertTrue(agentTestOnlyIdentity(listOf(agent), id))
        assertFalse(agentTestOnlyIdentity(listOf(agent, "telegram" to "123456"), id))
        assertFalse(agentTestOnlyIdentity(listOf("telegram" to "123456"), id))
        assertFalse(agentTestOnlyIdentity(listOf(agent), "00000000-0000-0000-0000-000000000002"))
        assertFalse(agentTestOnlyIdentity(emptyList(), id))
    }
}
