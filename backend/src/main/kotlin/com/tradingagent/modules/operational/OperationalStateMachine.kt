package com.tradingagent.modules.operational

import com.tradingagent.kernel.OperationalState

enum class OperationalEvent {
    GATES_AND_ADMIN_READY,
    BROKER_DIVERGENCE,
    AUDIT_FAILURE,
    CAPABILITY_MISMATCH,
    BROKER_STALE,
    LOSS_LIMIT,
    KILL_SWITCH,
    SECURITY_INCIDENT,
    FILL_WITHOUT_STOP,
    ADMIN_RESET,
}

/**
 * Pure transition table for RF-049. The running service does not apply it:
 * the process stays in OBSERVATION until the evidence gate is a real PASS.
 */
object OperationalStateMachine {
    fun next(
        state: OperationalState,
        event: OperationalEvent,
        evidenceAllowsActive: Boolean,
    ): OperationalState {
        return when (event) {
            OperationalEvent.KILL_SWITCH,
            OperationalEvent.SECURITY_INCIDENT,
            OperationalEvent.FILL_WITHOUT_STOP,
            -> OperationalState.EMERGENCY

            OperationalEvent.ADMIN_RESET -> when (state) {
                OperationalState.BLOCKED, OperationalState.EMERGENCY -> OperationalState.OBSERVATION
                OperationalState.DEGRADED ->
                    if (evidenceAllowsActive) OperationalState.ACTIVE else OperationalState.DEGRADED
                else -> state
            }

            OperationalEvent.LOSS_LIMIT ->
                if (state == OperationalState.ACTIVE) OperationalState.DEGRADED else state

            OperationalEvent.BROKER_DIVERGENCE,
            OperationalEvent.AUDIT_FAILURE,
            OperationalEvent.CAPABILITY_MISMATCH,
            OperationalEvent.BROKER_STALE,
            -> when (state) {
                OperationalState.EMERGENCY -> OperationalState.EMERGENCY
                OperationalState.BLOCKED -> OperationalState.BLOCKED
                else -> OperationalState.BLOCKED
            }

            OperationalEvent.GATES_AND_ADMIN_READY -> when (state) {
                OperationalState.OBSERVATION ->
                    if (evidenceAllowsActive) OperationalState.ACTIVE else OperationalState.OBSERVATION
                else -> state
            }
        }
    }

    fun permissions(state: OperationalState, brokerFresh: Boolean): OperationalPermissions {
        val newEntries = state == OperationalState.ACTIVE
        val localExits = when (state) {
            OperationalState.ACTIVE -> true
            OperationalState.DEGRADED -> brokerFresh
            OperationalState.EMERGENCY -> true
            else -> false
        }
        val cancel = when (state) {
            OperationalState.ACTIVE, OperationalState.DEGRADED, OperationalState.EMERGENCY -> true
            else -> false
        }
        return OperationalPermissions(
            newEntries = newEntries,
            localExits = localExits,
            cancel = cancel,
            reconcilePermitted = true,
            reconcileWired = false,
        )
    }
}
