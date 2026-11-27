package com.tradingagent.modules.ledger

import com.tradingagent.kernel.Money

data class CashInputs(
    val currency: String? = null,
    val brokerCashMinor: Long? = null,
    val assignedBudgetMinor: Long? = null,
    val externalHoldingsMinor: Long? = null,
    val reservedCashMinor: Long? = null,
    val cashBufferMinor: Long? = null,
) {
    init {
        val currencyCode = currency
        if (currencyCode != null) {
            require(currencyCode.length == 3 && currencyCode.all { it.isLetter() }) {
                "currency must be a 3-letter ISO code"
            }
        }
        listOf(
            brokerCashMinor,
            assignedBudgetMinor,
            externalHoldingsMinor,
            reservedCashMinor,
            cashBufferMinor,
        ).forEach { amount ->
            if (amount != null) {
                require(amount >= 0) { "minor units must be >= 0" }
            }
        }
        val anyAmount = listOf(
            brokerCashMinor,
            assignedBudgetMinor,
            externalHoldingsMinor,
            reservedCashMinor,
            cashBufferMinor,
        ).any { it != null }
        require(!anyAmount || currencyCode != null) { "currency is required when amounts are present" }
    }
}

data class CashQuote(
    val currency: String?,
    val availableMinor: Long?,
    val brokerCashMinor: Long?,
    val assignedBudgetMinor: Long?,
    val externalHoldingsMinor: Long?,
    val reservedCashMinor: Long?,
    val cashBufferMinor: Long?,
    val status: String,
    val shortfall: Boolean,
) {
    fun money(minor: Long?): Money? {
        val code = currency
        if (minor == null || code == null) {
            return null
        }
        return Money(minor, code)
    }
}

class Ledger(
    private val journal: Journal = Journal(),
) {
    fun availableCash(): Money? = quote(CashInputs()).money(null)

    fun journalEntryCount(): Int = journal.entryCount()

    fun journalBalanced(): Boolean = journal.isBalanced()

    fun quote(inputs: CashInputs): CashQuote {
        val complete = inputs.currency != null &&
            inputs.brokerCashMinor != null &&
            inputs.assignedBudgetMinor != null &&
            inputs.externalHoldingsMinor != null &&
            inputs.reservedCashMinor != null &&
            inputs.cashBufferMinor != null
        if (!complete) {
            return CashQuote(
                currency = inputs.currency,
                availableMinor = null,
                brokerCashMinor = inputs.brokerCashMinor,
                assignedBudgetMinor = inputs.assignedBudgetMinor,
                externalHoldingsMinor = inputs.externalHoldingsMinor,
                reservedCashMinor = inputs.reservedCashMinor,
                cashBufferMinor = inputs.cashBufferMinor,
                status = "UNOBSERVED",
                shortfall = false,
            )
        }
        val budgetRoom = Math.subtractExact(inputs.assignedBudgetMinor!!, inputs.externalHoldingsMinor!!)
        val cap = minOf(inputs.brokerCashMinor!!, budgetRoom)
        val afterReserved = Math.subtractExact(cap, inputs.reservedCashMinor!!)
        val available = Math.subtractExact(afterReserved, inputs.cashBufferMinor!!)
        return CashQuote(
            currency = inputs.currency,
            availableMinor = available,
            brokerCashMinor = inputs.brokerCashMinor,
            assignedBudgetMinor = inputs.assignedBudgetMinor,
            externalHoldingsMinor = inputs.externalHoldingsMinor,
            reservedCashMinor = inputs.reservedCashMinor,
            cashBufferMinor = inputs.cashBufferMinor,
            status = "CALCULATED",
            shortfall = available < 0,
        )
    }
}
