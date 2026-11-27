package com.tradingagent.modules.risk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class PolicyGateTest {
    @Test
    fun `every fixture attempt is rejected without a broker call`() {
        val gate = PolicyGate()
        val rows = PolicyGate.loadFixture()
        assertEquals(5, rows.size)
        rows.forEach { (preconditions, reason) ->
            val verdict = gate.evaluate(preconditions)
            assertFalse(verdict.accepted)
            assertFalse(verdict.brokerCalled)
            assertEquals(reason, verdict.reason)
        }
    }
}
