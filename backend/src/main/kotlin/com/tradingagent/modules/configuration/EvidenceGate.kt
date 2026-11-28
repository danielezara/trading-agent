package com.tradingagent.modules.configuration

import com.tradingagent.kernel.EvidenceStatus

data class EvidenceRow(
    val controlId: String,
    val scope: String,
    val ownerRole: String,
    val status: EvidenceStatus,
)

class EvidenceGate(initial: List<EvidenceRow> = ROWS) {
    private val lock = Any()
    private val rows = initial.map { it.copy() }.toMutableList()

    fun rows(): List<EvidenceRow> = synchronized(lock) { rows.toList() }

    fun recordBrokerRead(status: EvidenceStatus) = synchronized(lock) {
        val index = rows.indexOfFirst { it.controlId == "RF-044" }
        rows[index] = rows[index].copy(status = status)
    }

    fun allowsActive(): Boolean {
        val byId = rows().associateBy { it.controlId }
        return ACTIVE_REQUIRED.all { id -> byId[id]?.status == EvidenceStatus.PASS }
    }

    companion object {
        val ACTIVE_REQUIRED: Set<String> = setOf("RF-044")
        val ROWS: List<EvidenceRow> = listOf(
            EvidenceRow("RF-044", "broker-demo", "API Capability Owner", EvidenceStatus.UNVERIFIED),
            EvidenceRow("RF-045", "data-providers", "Data & Licensing Owner", EvidenceStatus.UNVERIFIED),
            EvidenceRow("RF-059", "compliance", "Compliance Owner", EvidenceStatus.UNVERIFIED),
        )
    }
}
