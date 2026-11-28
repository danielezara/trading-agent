package com.tradingagent.modules.outbox

import com.tradingagent.kernel.OrderDispatchForbidden

class OutboxDispatcher {
    fun dispatchNext(): Nothing = throw OrderDispatchForbidden(
        "Outbox non consegna intent: dispatch disabilitato.",
    )
}
