package com.tradingagent.kernel

enum class OperationalState(val wire: String) {
    OBSERVATION("OBSERVATION"),
    ACTIVE("ACTIVE"),
    DEGRADED("DEGRADED"),
    BLOCKED("BLOCKED"),
    EMERGENCY("EMERGENCY"),
}

enum class OrderIntentState(val wire: String) {
    PROPOSED("proposed"),
    RISK_RESERVED("risk_reserved"),
    DISPATCH_PENDING("dispatch_pending"),
    SENT("sent"),
    ACKNOWLEDGED("acknowledged"),
    PARTIALLY_FILLED("partially_filled"),
    FILLED("filled"),
    CANCEL_PENDING("cancel_pending"),
    CANCELLED("cancelled"),
    EXPIRED("expired"),
    REJECTED("rejected"),
    UNKNOWN("unknown"),
    RECONCILED("reconciled"),
}

enum class ReasonCode {
    DATA_STALE,
    DATA_MISSING,
    DATA_CONFLICT,
    MARKET_CLOSED,
    INSTRUMENT_HALTED,
    LIQUIDITY_LOW,
    SCORE_BELOW_THRESHOLD,
    CONFIDENCE_LOW,
    EDGE_INSUFFICIENT,
    EVENT_BLACKOUT,
    WARMUP_INCOMPLETE,
    BUDGET_LIMIT,
    EXPOSURE_LIMIT,
    LOSS_LIMIT,
    DRAWDOWN_LIMIT,
    ORDER_UNKNOWN,
    RECONCILIATION_DIVERGENCE,
    CAPABILITY_UNVERIFIED,
    LLM_OUTPUT_INVALID,
    KILL_SWITCH_ACTIVE,
    SECURITY_OR_AUDIT_FAILURE,
}

enum class EvidenceStatus {
    UNVERIFIED,
    PASS,
    FAIL,
    EXPIRED,
}

data class Money(
    val minorUnits: Long,
    val currency: String,
) {
    init {
        require(currency.length == 3 && currency.all { it.isLetter() }) {
            "currency must be a 3-letter ISO code"
        }
    }
}

class OrderCreationForbidden(message: String) : IllegalStateException(message)

class OrderDispatchForbidden(message: String) : IllegalStateException(message)
