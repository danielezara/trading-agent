package com.tradingagent.modules.configuration

import com.tradingagent.kernel.EvidenceStatus

data class EvidenceRow(
    val controlId: String,
    val scope: String,
    val ownerRole: String,
    val status: EvidenceStatus,
)

class EvidenceGate {
    fun rows(): List<EvidenceRow> = ROWS

    fun allowsActive(): Boolean = false

    companion object {
        val ROWS: List<EvidenceRow> = listOf(
            EvidenceRow("RF-044", "broker-demo", "API Capability Owner", EvidenceStatus.UNVERIFIED),
            EvidenceRow("RF-045", "data-providers", "Data & Licensing Owner", EvidenceStatus.UNVERIFIED),
            EvidenceRow("RF-059", "compliance", "Compliance Owner", EvidenceStatus.UNVERIFIED),
        )
    }
}
