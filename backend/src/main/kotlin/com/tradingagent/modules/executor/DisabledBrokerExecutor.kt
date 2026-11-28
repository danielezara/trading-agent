package com.tradingagent.modules.executor

import com.tradingagent.kernel.OrderDispatchForbidden

class DisabledBrokerExecutor {
    fun dispatch(intentId: String): Nothing = throw OrderDispatchForbidden(
        "Dispatch rifiutato per intent $intentId: broker assente e stato OBSERVATION.",
    )
}
