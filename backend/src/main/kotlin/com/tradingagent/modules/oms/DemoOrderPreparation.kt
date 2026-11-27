package com.tradingagent.modules.oms

import com.tradingagent.modules.risk.DispatchPreconditions
import com.tradingagent.modules.risk.PolicyGate
import com.tradingagent.modules.risk.PolicyVerdict

data class DemoOrderDraft(
    val clientOrderId: String,
    val instrumentId: String,
    val side: String,
    val notionalMinor: Long,
    val currency: String,
    val orderable: Boolean,
)

sealed interface DraftOutcome {
    data class Accepted(val draft: DemoOrderDraft) : DraftOutcome
    data class Replay(val draft: DemoOrderDraft) : DraftOutcome
    data class Conflict(val existing: DemoOrderDraft) : DraftOutcome
    data class Rejected(val reason: String) : DraftOutcome
}

class DemoOrderBook {
    private val drafts = linkedMapOf<String, DemoOrderDraft>()

    fun size(): Int = drafts.size

    fun prepare(
        clientOrderId: String,
        instrumentId: String,
        side: String,
        notionalMinor: Long,
        currency: String,
    ): DraftOutcome {
        val reason = rejectReason(clientOrderId, instrumentId, side, notionalMinor, currency)
        if (reason != null) {
            return DraftOutcome.Rejected(reason)
        }
        val draft = DemoOrderDraft(
            clientOrderId = clientOrderId,
            instrumentId = instrumentId,
            side = side,
            notionalMinor = notionalMinor,
            currency = currency,
            orderable = false,
        )
        val existing = drafts[clientOrderId]
        if (existing != null) {
            return if (existing == draft) DraftOutcome.Replay(existing) else DraftOutcome.Conflict(existing)
        }
        drafts[clientOrderId] = draft
        return DraftOutcome.Accepted(draft)
    }

    private fun rejectReason(
        clientOrderId: String,
        instrumentId: String,
        side: String,
        notionalMinor: Long,
        currency: String,
    ): String? = when {
        clientOrderId.isBlank() || clientOrderId.length > 64 || clientOrderId.any { it.isWhitespace() } -> "IDEMPOTENCY_KEY"
        !instrumentId.matches(instrument) -> "INSTRUMENT"
        side != "BUY" -> "SIDE"
        currency.length != 3 || currency.any { !it.isLetter() } -> "CURRENCY"
        notionalMinor < MIN_NOTIONAL_MINOR -> "MIN_SIZE"
        else -> null
    }

    companion object {
        const val MIN_NOTIONAL_MINOR: Long = 100
        private val instrument = Regex("[1-9][0-9]{0,9}")
    }
}

class UnsentDemoOrders(
    private val gate: PolicyGate = PolicyGate(),
) {
    fun submit(draft: DemoOrderDraft): PolicyVerdict {
        val verdict = gate.evaluate(DispatchPreconditions(intentId = draft.clientOrderId))
        check(!verdict.accepted && !verdict.brokerCalled) { "il percorso Demo non invia" }
        return verdict
    }
}
