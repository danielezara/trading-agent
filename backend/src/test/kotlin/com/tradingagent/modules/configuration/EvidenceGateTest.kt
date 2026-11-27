package com.tradingagent.modules.configuration

import com.tradingagent.kernel.EvidenceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EvidenceGateTest {
    @Test
    fun `active requires the broker row and ignores the license and the removed authorization`() {
        val ready = gate(EvidenceStatus.PASS, EvidenceStatus.UNVERIFIED, EvidenceStatus.UNVERIFIED)
        assertTrue(ready.allowsActive())
        assertEquals(EvidenceStatus.UNVERIFIED, ready.rows().first { it.controlId == "RF-045" }.status)
        assertEquals(EvidenceStatus.UNVERIFIED, ready.rows().first { it.controlId == "RF-059" }.status)

        val licenseOpen = gate(EvidenceStatus.PASS, EvidenceStatus.FAIL, EvidenceStatus.PASS)
        assertTrue(licenseOpen.allowsActive())
        assertEquals(EvidenceStatus.FAIL, licenseOpen.rows().first { it.controlId == "RF-045" }.status)

        val brokerOpen = gate(EvidenceStatus.UNVERIFIED, EvidenceStatus.PASS, EvidenceStatus.PASS)
        assertFalse(brokerOpen.allowsActive())
        assertEquals(EvidenceStatus.UNVERIFIED, brokerOpen.rows().first { it.controlId == "RF-044" }.status)
    }

    @Test
    fun `the default gate does not pass either required row`() {
        val gate = EvidenceGate()
        assertEquals(EvidenceStatus.UNVERIFIED, gate.rows().first { it.controlId == "RF-044" }.status)
        assertEquals(EvidenceStatus.UNVERIFIED, gate.rows().first { it.controlId == "RF-045" }.status)
        assertEquals(EvidenceStatus.UNVERIFIED, gate.rows().first { it.controlId == "RF-059" }.status)
        assertFalse(gate.allowsActive())
    }

    private fun gate(broker: EvidenceStatus, license: EvidenceStatus, authorization: EvidenceStatus) = EvidenceGate(
        listOf(
            EvidenceRow("RF-044", "broker-demo", "API Capability Owner", broker),
            EvidenceRow("RF-045", "data-providers", "Data & Licensing Owner", license),
            EvidenceRow("RF-059", "compliance", "Compliance Owner", authorization),
        ),
    )
}
