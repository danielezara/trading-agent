package com.tradingagent.modules.readmodel

import com.tradingagent.kernel.EvidenceStatus
import com.tradingagent.modules.brokerread.CapabilityProbe
import com.tradingagent.modules.brokerread.DemoKeyMaterial
import com.tradingagent.modules.brokerread.HttpGet
import com.tradingagent.modules.brokerread.InboundHttp
import com.tradingagent.modules.configuration.ConfigurationService
import com.tradingagent.modules.configuration.EvidenceGate
import com.tradingagent.modules.ledger.Ledger
import com.tradingagent.modules.operational.OperationalStateService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class DashboardReadModelTest {
    @Test
    fun `observed usd cash stays beside the eur budget and available cash stays absent`() {
        val gate = EvidenceGate()
        val probe = CapabilityProbe(
            evidenceGate = gate,
            keys = {
                DemoKeyMaterial.fromEnvironment(
                    mapOf("ETORO_DEMO_API_KEY" to "test-api-key", "ETORO_DEMO_USER_KEY" to "test-user-key"),
                )
            },
            transport = HttpGet { _, _ ->
                InboundHttp(
                    status = 200,
                    retryAfterSeconds = null,
                    body = """{"clientPortfolio":{"credit":280.35,"accountCurrencyId":1,"positions":[]}}""",
                )
            },
            clock = { Instant.parse("2026-11-27T00:00:00Z") },
        )
        probe.refresh()
        val snapshot = DashboardReadModel(
            operationalStateService = OperationalStateService(clock = { Instant.parse("2026-11-27T00:00:00Z") }),
            ledger = Ledger(),
            configurationService = ConfigurationService(),
            capabilityProbe = probe,
        ).portfolio()

        assertEquals("USD", snapshot.brokerCash?.currency)
        assertEquals(28_035L, snapshot.brokerCash?.minorUnits)
        assertEquals("EUR", snapshot.assignedBudget?.currency)
        assertEquals(50_000L, snapshot.assignedBudget?.minorUnits)
        assertEquals(0, snapshot.positionCount)
        assertEquals(emptyList<com.tradingagent.modules.brokerread.ObservedPosition>(), snapshot.positions)
        assertEquals(emptyList<com.tradingagent.modules.brokerread.ObservedOrder>(), snapshot.orders)
        assertEquals(emptyList<com.tradingagent.modules.brokerread.ObservedOrder>(), snapshot.executions)
        assertNull(snapshot.availableCash)
        assertEquals(Ledger.STATUS_FX_UNAVAILABLE, snapshot.ledgerStatus)
        assertEquals(Ledger.FX_UNCONFIGURED, snapshot.fxStatus)
        assertEquals(EvidenceStatus.PASS, gate.rows().first { it.controlId == "RF-044" }.status)
        assertEquals(EvidenceStatus.UNVERIFIED, gate.rows().first { it.controlId == "RF-045" }.status)
        assertEquals(EvidenceStatus.UNVERIFIED, gate.rows().first { it.controlId == "RF-059" }.status)
        assertFalse(gate.allowsActive())
        assertFalse(snapshot.tradingEnabled)
    }
}
