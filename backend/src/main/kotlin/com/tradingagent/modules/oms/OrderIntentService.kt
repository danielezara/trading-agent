package com.tradingagent.modules.oms

import com.tradingagent.kernel.OrderCreationForbidden
import com.tradingagent.kernel.OrderIntentState

class OrderIntentService {
    fun supportedStates(): List<String> = OrderIntentState.values().map { it.wire }

    fun create(): Nothing = throw OrderCreationForbidden(
        "Creazione intent rifiutata: stato OBSERVATION, nessuna outbox e nessun broker.",
    )
}
