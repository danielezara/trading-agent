package com.tradingagent.modules.configuration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ConfigurationServiceTest {
    private val clock = { Instant.parse("2026-11-27T00:00:00Z") }

    @Test
    fun `the first version is active and has no economic parameters`() {
        val service = ConfigurationService(clock)
        val active = service.active()
        assertEquals(1L, active.versionNumber)
        assertEquals("system", active.author)
        assertEquals(null, active.budget.assignedBudgetMinor)
        assertEquals(null, active.budget.baseCurrency)
        assertEquals(64, active.contentSha256.length)
        assertEquals(active.contentSha256, ConfigurationService(clock).active().contentSha256)
    }

    @Test
    fun `recording a version keeps the previous one immutable and inactive change`() {
        val service = ConfigurationService(clock)
        val original = service.active()
        val listed = service.versions()
        val recorded = service.record(
            author = "operator",
            reason = "Proposta di budget, non attivata.",
            budget = BudgetPolicy(baseCurrency = "EUR", assignedBudgetMinor = 1_000_000, cashBufferMinor = 50_000),
        )
        assertEquals(original, service.active())
        assertEquals(1, listed.size)
        assertEquals(2L, recorded.versionNumber)
        assertEquals(original.versionId, recorded.supersedes)
        assertNotEquals(original.contentSha256, recorded.contentSha256)
        assertEquals(2, service.versions().size)
    }

    @Test
    fun `cutover is refused while an intent is in flight`() {
        val service = ConfigurationService(clock)
        val original = service.active()
        val recorded = service.record("operator", "Budget proposto.", BudgetPolicy(baseCurrency = "EUR", assignedBudgetMinor = 1))
        assertThrows(IllegalStateException::class.java) {
            service.activate(recorded.versionId, inFlightIntents = 1)
        }
        assertEquals(original.versionId, service.active().versionId)
        assertEquals(recorded.versionId, service.activate(recorded.versionId, inFlightIntents = 0).versionId)
        assertEquals(recorded.budget, service.active().budget)
    }

    @Test
    fun `a version requires author reason and a currency for amounts`() {
        val service = ConfigurationService(clock)
        assertThrows(IllegalArgumentException::class.java) {
            service.record(" ", "motivo", BudgetPolicy())
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.record("operator", " ", BudgetPolicy())
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.record("operator", "motivo", BudgetPolicy(assignedBudgetMinor = 10))
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.record("operator", "motivo", BudgetPolicy(baseCurrency = "EUR", assignedBudgetMinor = -1))
        }
        assertTrue(service.versions().size == 1)
    }
}
