package com.tradingagent.modules.operational

import com.tradingagent.kernel.OperationalState
import com.tradingagent.modules.configuration.EvidenceGate
import java.time.Instant

data class OperationalPermissions(
    val newEntries: Boolean,
    val localExits: Boolean,
    val cancel: Boolean,
    val reconcilePermitted: Boolean,
    val reconcileWired: Boolean,
)

data class OperationalSnapshot(
    val state: OperationalState,
    val since: Instant,
    val reason: String,
    val generation: Long,
    val permissions: OperationalPermissions,
)

class OperationalStateService(
    private val evidenceGate: EvidenceGate = EvidenceGate(),
    private val clock: () -> Instant = { Instant.parse("2026-11-27T00:00:00Z") },
) {
    private val snapshot = OperationalSnapshot(
        state = OperationalState.OBSERVATION,
        since = clock(),
        reason = "Avvio in OBSERVATION: gate non verificato e broker assente.",
        generation = 1,
        permissions = OBSERVATION_PERMISSIONS,
    )

    fun current(): OperationalState = snapshot.state

    fun snapshot(): OperationalSnapshot = snapshot

    fun transition(target: OperationalState): OperationalState {
        if (target == OperationalState.ACTIVE && !evidenceGate.allowsActive()) {
            throw IllegalStateException(
                "Transizione verso ACTIVE rifiutata: il gate evidenze non è PASS e lo stato resta OBSERVATION.",
            )
        }
        if (target != OperationalState.OBSERVATION) {
            throw IllegalStateException(
                "Transizione verso ${target.wire} rifiutata: lo scheletro resta in OBSERVATION.",
            )
        }
        return snapshot.state
    }

    companion object {
        val OBSERVATION_PERMISSIONS = OperationalPermissions(
            newEntries = false,
            localExits = false,
            cancel = false,
            reconcilePermitted = true,
            reconcileWired = false,
        )
    }
}
