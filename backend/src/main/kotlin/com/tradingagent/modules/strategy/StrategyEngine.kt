package com.tradingagent.modules.strategy

import com.tradingagent.kernel.ReasonCode

data class StrategyOutcome(
    val action: String,
    val reasonCode: ReasonCode,
)

class StrategyEngine {
    fun evaluate(): StrategyOutcome = StrategyOutcome(
        action = "NO-TRADE",
        reasonCode = ReasonCode.CAPABILITY_UNVERIFIED,
    )
}
