package com.tradingagent.modules.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.tradingagent.kernel.OrderIntentState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ConfigurationServiceTest {
    private val clock = { Instant.parse("2026-11-27T00:00:00Z") }

    @Test
    fun `the first version is the file and stays stable for the same clock`() {
        val service = ConfigurationService(clock)
        val active = service.active()
        assertEquals(1L, active.versionNumber)
        assertEquals("daniele", active.author)
        assertEquals(50_000L, active.budget.assignedBudgetMinor)
        assertEquals("EUR", active.budget.baseCurrency)
        assertEquals("days", active.parameters.economic.horizon)
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
    fun `cutover happens only at the scheduled bar boundary without in-flight intents`() {
        val service = ConfigurationService(clock)
        val original = service.active()
        val recorded = service.record(
            "operator",
            "Budget proposto.",
            BudgetPolicy(baseCurrency = "EUR", assignedBudgetMinor = 1),
        )
        assertEquals(Instant.parse("2026-11-27T00:05:00Z"), recorded.validFromBarOpen)
        val filled = BoundIntent("done", OrderIntentState.FILLED, original.versionId)
        val pending = BoundIntent("open", OrderIntentState.DISPATCH_PENDING, original.versionId)
        assertThrows(IllegalStateException::class.java) {
            service.activate(recorded.versionId, listOf(pending, filled), recorded.validFromBarOpen)
        }
        assertEquals(original.versionId, service.active().versionId)
        assertEquals(original.versionId, pending.configVersionId)
        assertEquals(original.versionId, filled.configVersionId)
        assertThrows(IllegalStateException::class.java) {
            service.activate(recorded.versionId, emptyList(), Instant.parse("2026-11-27T00:03:00Z"))
        }
        val activated = service.activate(recorded.versionId, listOf(filled), recorded.validFromBarOpen)
        assertEquals(recorded.versionId, activated.versionId)
        assertEquals(recorded.budget, service.active().budget)
        assertEquals(original.versionId, filled.configVersionId)
    }

    @Test
    fun `a mid bar change is scheduled for the next boundary`() {
        val service = ConfigurationService { Instant.parse("2026-11-27T00:03:10Z") }
        assertEquals(Instant.parse("2026-11-27T00:00:00Z"), service.active().validFromBarOpen)
        val recorded = service.record("operator", "Dopo l'apertura della barra.", BudgetPolicy())
        assertEquals(Instant.parse("2026-11-27T00:05:00Z"), recorded.validFromBarOpen)
    }

    @Test
    fun `sent and unknown block cutover and terminal states do not`() {
        val blocking = listOf(
            OrderIntentState.PROPOSED,
            OrderIntentState.RISK_RESERVED,
            OrderIntentState.DISPATCH_PENDING,
            OrderIntentState.SENT,
            OrderIntentState.UNKNOWN,
        )
        val open = listOf(OrderIntentState.FILLED, OrderIntentState.CANCELLED, OrderIntentState.ACKNOWLEDGED)
        blocking.forEach { state -> assertTrue(state in ConfigurationService.BLOCKING_STATES) }
        open.forEach { state -> assertTrue(state !in ConfigurationService.BLOCKING_STATES) }
    }

    @Test
    fun `fixture intents keep the configuration they were created with`() {
        val service = ConfigurationService(clock)
        val original = service.active()
        val recorded = service.record("operator", "Cutover di fixture.", BudgetPolicy(baseCurrency = "EUR", assignedBudgetMinor = 1))
        val fixture = ObjectMapper().readTree(
            javaClass.getResourceAsStream("/fixtures/configuration/cutover-intents.json"),
        )
        val bound = fixture.get("intents").map { node ->
            BoundIntent(
                intentId = node.get("intent_id").asText(),
                state = OrderIntentState.values().first { it.wire == node.get("state").asText() },
                configVersionId = original.versionId,
            )
        }
        assertEquals(recorded.validFromBarOpen.toString(), fixture.get("scheduled_bar_open").asText())
        assertThrows(IllegalStateException::class.java) {
            service.activate(recorded.versionId, bound, recorded.validFromBarOpen)
        }
        assertEquals(original.versionId, service.active().versionId)
        assertTrue(bound.all { it.configVersionId == original.versionId })
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
