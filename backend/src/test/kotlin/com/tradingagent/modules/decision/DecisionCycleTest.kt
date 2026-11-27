package com.tradingagent.modules.decision

import com.tradingagent.kernel.EvidenceStatus
import com.tradingagent.kernel.ReasonCode
import com.tradingagent.modules.audit.AuditLog
import com.tradingagent.modules.brokerread.CapabilityProbe
import com.tradingagent.modules.brokerread.DemoKeyMaterial
import com.tradingagent.modules.brokerread.HttpGet
import com.tradingagent.modules.brokerread.InboundHttp
import com.tradingagent.modules.configuration.ConfigurationService
import com.tradingagent.modules.configuration.EvidenceGate
import com.tradingagent.modules.ingestion.IngestionPipeline
import com.tradingagent.modules.ledger.Ledger
import com.tradingagent.modules.operational.OperationalStateService
import com.tradingagent.modules.risk.PolicyGate
import com.tradingagent.modules.strategy.StrategyEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DecisionCycleTest {
    @Test
    fun `each decision is audited and the order attempt is refused`() {
        val clock = { Instant.parse("2026-11-27T00:00:30Z") }
        val audit = AuditLog()
        val configuration = ConfigurationService(clock)
        val cycle = DecisionCycle(
            strategyEngine = StrategyEngine(),
            ingestionPipeline = IngestionPipeline.fromClasspath(),
            evidenceGate = EvidenceGate(),
            operationalStateService = OperationalStateService(clock = clock),
            configurationService = configuration,
            auditLog = audit,
            policyGate = PolicyGate(),
            clock = clock,
        )
        val first = cycle.record()
        val second = cycle.record()
        assertEquals("NO-TRADE", first.outcome.action)
        assertEquals(ReasonCode.CAPABILITY_UNVERIFIED, first.outcome.reasonCode)
        assertEquals(first.outcome.inputHash, second.outcome.inputHash)
        assertEquals(configuration.active().versionId, first.configuration.versionId)
        assertEquals(configuration.active().contentSha256, first.auditEvent.configContentSha256)
        assertEquals(first.outcome.inputHash, first.auditEvent.correlationId)
        assertFalse(first.outcome.signal.orderable)
        assertEquals("NONE", first.outcome.signal.direction)
        assertNull(first.outcome.signal.instrumentId)
        assertFalse(first.policy.accepted)
        assertFalse(first.policy.brokerCalled)
        assertEquals(PolicyGate.MISSING_INTENT, first.policy.reason)
        assertEquals(2, audit.events().size)
        assertTrue(audit.verify().valid)
        val day = LocalDate.ofInstant(clock(), ZoneOffset.UTC)
        audit.sealDay(day)
        assertEquals(audit.dailyDigest(day), audit.sealedDigest(day))
        assertEquals(second.auditEvent.eventHash, cycle.latest().auditEvent.eventHash)
    }

    @Test
    fun `usd cash adds the fx reason and still cannot send an order`() {
        val gate = EvidenceGate()
        val probe = CapabilityProbe(
            evidenceGate = gate,
            keys = {
                DemoKeyMaterial.fromEnvironment(
                    mapOf("ETORO_DEMO_API_KEY" to "test-api-key", "ETORO_DEMO_USER_KEY" to "test-user-key"),
                )
            },
            transport = HttpGet { _, _ ->
                InboundHttp(200, null, """{"clientPortfolio":{"credit":10.00,"accountCurrencyId":1,"positions":[]}}""")
            },
            clock = { Instant.parse("2026-11-27T00:00:30Z") },
        )
        probe.refresh()
        val cycle = DecisionCycle(
            strategyEngine = StrategyEngine(),
            ingestionPipeline = IngestionPipeline.fromClasspath(),
            evidenceGate = gate,
            operationalStateService = OperationalStateService(clock = { Instant.parse("2026-11-27T00:00:30Z") }),
            configurationService = ConfigurationService(),
            auditLog = AuditLog(),
            policyGate = PolicyGate(),
            clock = { Instant.parse("2026-11-27T00:00:30Z") },
            ledger = Ledger(),
            capabilityProbe = probe,
        )
        val recorded = cycle.record()
        assertEquals("NO-TRADE", recorded.outcome.action)
        assertTrue(recorded.outcome.reasonCodes.contains(ReasonCode.FX_UNAVAILABLE))
        assertFalse(recorded.outcome.signal.orderable)
        assertFalse(recorded.policy.accepted)
        assertFalse(recorded.policy.brokerCalled)
        assertEquals(EvidenceStatus.UNVERIFIED, gate.rows().first { it.controlId == "RF-045" }.status)
        assertEquals(EvidenceStatus.UNVERIFIED, gate.rows().first { it.controlId == "RF-059" }.status)
        assertFalse(gate.allowsActive())
    }
}
