package com.tradingagent.config

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkeletonSafetyCheckTest {
    private val safe = TradingProperties()

    @Test
    fun `defaults are observation without a broker`() {
        SkeletonSafetyCheck.assertConfiguration(safe, emptyMap())
    }

    @Test
    fun `active state is rejected`() {
        val error = assertThrows(IllegalStateException::class.java) {
            SkeletonSafetyCheck.assertConfiguration(safe.copy(operationalState = "ACTIVE"), emptyMap())
        }
        assertTrue(error.message!!.contains("OBSERVATION"))
    }

    @Test
    fun `non demo environment is rejected`() {
        assertThrows(IllegalStateException::class.java) {
            SkeletonSafetyCheck.assertConfiguration(safe.copy(environment = "REAL"), emptyMap())
        }
    }

    @Test
    fun `broker enablement and url are rejected`() {
        assertThrows(IllegalStateException::class.java) {
            SkeletonSafetyCheck.assertConfiguration(
                safe.copy(broker = TradingProperties.Broker(enabled = true, baseUrl = "")),
                emptyMap(),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            SkeletonSafetyCheck.assertConfiguration(
                safe.copy(broker = TradingProperties.Broker(enabled = false, baseUrl = "https://broker.example")),
                emptyMap(),
            )
        }
    }

    @Test
    fun `secret env names are rejected without echoing values`() {
        val error = assertThrows(IllegalStateException::class.java) {
            SkeletonSafetyCheck.assertConfiguration(safe, mapOf("ETORO_API_KEY" to "super-secret-value"))
        }
        assertTrue(error.message!!.contains("ETORO_API_KEY"))
        assertTrue(!error.message!!.contains("super-secret-value"))
    }
}
