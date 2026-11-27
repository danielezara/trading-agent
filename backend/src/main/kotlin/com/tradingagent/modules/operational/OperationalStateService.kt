package com.tradingagent.modules.operational

import com.tradingagent.kernel.OperationalState

class OperationalStateService {
    fun current(): OperationalState = OperationalState.OBSERVATION

    fun transition(target: OperationalState): OperationalState {
        if (target != OperationalState.OBSERVATION) {
            throw IllegalStateException(
                "Transizione verso ${target.wire} rifiutata: lo scheletro resta in OBSERVATION.",
            )
        }
        return OperationalState.OBSERVATION
    }
}
