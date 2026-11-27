package com.tradingagent.modules.configuration

import com.tradingagent.kernel.EvidenceStatus

data class EvidenceRow(
    val controlId: String,
    val scope: String,
    val ownerRole: String,
    val status: EvidenceStatus,
)

class EvidenceGate {
    private val lock = Any()
    private val rows = ROWS.map { it.copy() }.toMutableList()

    fun rows(): List<EvidenceRow> = synchronized(lock) { rows.toList() }

    fun recordBrokerRead(status: EvidenceStatus) = synchronized(lock) {
        val index = rows.indexOfFirst { it.controlId == "RF-044" }
        rows[index] = rows[index].copy(status = status)
    }

    fun allowsActive(): Boolean = rows().all { it.status == EvidenceStatus.PASS }

    companion object {
        val ROWS: List<EvidenceRow> = listOf(
            EvidenceRow("RF-044", "broker-demo", "API Capability Owner", EvidenceStatus.UNVERIFIED),
            EvidenceRow("RF-045", "data-providers", "Data & Licensing Owner", EvidenceStatus.UNVERIFIED),
            EvidenceRow("RF-059", "compliance", "Compliance Owner", EvidenceStatus.UNVERIFIED),
        )
    }
}
