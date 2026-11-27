package com.tradingagent.modules.decision

import com.tradingagent.kernel.ReasonCode
import com.tradingagent.modules.audit.AuditLog
import com.tradingagent.modules.configuration.ConfigurationService
import com.tradingagent.modules.configuration.EvidenceGate
import com.tradingagent.modules.ingestion.IngestionPipeline
import com.tradingagent.modules.operational.OperationalStateService
import com.tradingagent.modules.risk.PolicyGate
import com.tradingagent.modules.strategy.StrategyEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
}
