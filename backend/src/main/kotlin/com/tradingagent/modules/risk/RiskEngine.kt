package com.tradingagent.modules.risk

import com.tradingagent.kernel.ReasonCode

data class RiskDecision(
    val status: String,
    val reasonCode: ReasonCode,
)

class RiskEngine {
    fun evaluate(): RiskDecision = RiskDecision(
        status = "NO_TRADE",
        reasonCode = ReasonCode.CAPABILITY_UNVERIFIED,
    )
}
