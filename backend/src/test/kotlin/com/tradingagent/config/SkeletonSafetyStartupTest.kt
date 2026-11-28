package com.tradingagent.config

import com.tradingagent.TradingAgentApplication
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication

class SkeletonSafetyStartupTest {
    @Test
    fun `startup fails closed when the state is not observation`() {
        val application = SpringApplication(TradingAgentApplication::class.java)
        val failure = assertThrows(Exception::class.java) {
            application.run(
                "--trading.operational-state=ACTIVE",
                "--spring.main.web-application-type=none",
                "--spring.main.banner-mode=off",
            )
        }
        assertTrue(failure.containsMessage("OBSERVATION"))
    }
}

private fun Throwable.containsMessage(fragment: String): Boolean {
    if (message?.contains(fragment) == true) return true
    val cause = cause ?: return false
    if (cause === this) return false
    return cause.containsMessage(fragment)
}
