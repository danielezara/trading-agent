package com.tradingagent.modules.ledger

import com.tradingagent.kernel.Money
import java.time.Instant

/**
 * Integer quote of how many quote-currency minor units equal one base-currency minor unit.
 * Both currencies use the same minor scale. There is no floating point and no implied market rate.
 */
data class FxQuote(
    val source: String,
    val version: String,
    val observedAt: Instant,
    val baseCurrency: String,
    val quoteCurrency: String,
    val quoteMinorPerBaseMinorNumerator: Long,
    val quoteMinorPerBaseMinorDenominator: Long,
    val haircutBps: Int,
) {
    init {
        require(source.isNotBlank() && !source.contains('\n')) { "fx source is required" }
        require(version.isNotBlank() && !version.contains('\n')) { "fx version is required" }
        require(iso(baseCurrency) && iso(quoteCurrency)) { "fx currencies must be 3-letter ISO codes" }
        require(!baseCurrency.equals(quoteCurrency, ignoreCase = true)) { "fx quote is for two different currencies" }
        require(quoteMinorPerBaseMinorNumerator > 0 && quoteMinorPerBaseMinorDenominator > 0) {
            "fx rate terms must be positive"
        }
        require(haircutBps in 0..10_000) { "fx haircut must be between 0 and 10000 bps" }
    }

    fun applies(base: String, quote: String): Boolean =
        baseCurrency.equals(base, ignoreCase = true) && quoteCurrency.equals(quote, ignoreCase = true)

    fun toBaseMinor(quoteMinor: Long): Long {
        require(quoteMinor >= 0) { "quote minor units must be >= 0" }
        val converted = Math.multiplyExact(quoteMinor, quoteMinorPerBaseMinorDenominator) / quoteMinorPerBaseMinorNumerator
        return Math.multiplyExact(converted, (10_000 - haircutBps).toLong()) / 10_000L
    }

    private fun iso(code: String): Boolean = code.length == 3 && code.all { it.isLetter() }
}

data class CashInputs(
    val currency: String? = null,
    val brokerCashMinor: Long? = null,
    val brokerCurrency: String? = null,
    val assignedBudgetMinor: Long? = null,
    val externalHoldingsMinor: Long? = null,
    val reservedCashMinor: Long? = null,
    val cashBufferMinor: Long? = null,
    val fx: FxQuote? = null,
) {
    init {
        val currencyCode = currency
        if (currencyCode != null) {
            require(currencyCode.length == 3 && currencyCode.all { it.isLetter() }) {
                "currency must be a 3-letter ISO code"
            }
        }
        if (brokerCurrency != null) {
            require(brokerCurrency.length == 3 && brokerCurrency.all { it.isLetter() }) {
                "broker currency must be a 3-letter ISO code"
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
    val brokerCurrency: String?,
    val brokerCashInBaseMinor: Long?,
    val assignedBudgetMinor: Long?,
    val externalHoldingsMinor: Long?,
    val reservedCashMinor: Long?,
    val cashBufferMinor: Long?,
    val status: String,
    val fxStatus: String,
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
        val base = inputs.currency?.uppercase()
        val brokerCurrency = when {
            inputs.brokerCurrency != null -> inputs.brokerCurrency.uppercase()
            inputs.brokerCashMinor != null -> base
            else -> null
        }
        val fxStatus = when {
            inputs.brokerCashMinor == null || brokerCurrency == null || base == null -> FX_UNOBSERVED
            brokerCurrency == base -> FX_NOT_REQUIRED
            inputs.fx != null && inputs.fx.applies(base, brokerCurrency) -> FX_APPLIED
            else -> FX_UNCONFIGURED
        }
        val brokerInBase = when (fxStatus) {
            FX_NOT_REQUIRED -> inputs.brokerCashMinor
            FX_APPLIED -> inputs.fx!!.toBaseMinor(inputs.brokerCashMinor!!)
            else -> null
        }
        val complete = brokerInBase != null &&
            inputs.assignedBudgetMinor != null &&
            inputs.externalHoldingsMinor != null &&
            inputs.reservedCashMinor != null &&
            inputs.cashBufferMinor != null
        if (!complete) {
            return CashQuote(
                currency = base,
                availableMinor = null,
                brokerCashMinor = inputs.brokerCashMinor,
                brokerCurrency = brokerCurrency,
                brokerCashInBaseMinor = brokerInBase,
                assignedBudgetMinor = inputs.assignedBudgetMinor,
                externalHoldingsMinor = inputs.externalHoldingsMinor,
                reservedCashMinor = inputs.reservedCashMinor,
                cashBufferMinor = inputs.cashBufferMinor,
                status = if (fxStatus == FX_UNCONFIGURED) STATUS_FX_UNAVAILABLE else STATUS_UNOBSERVED,
                fxStatus = fxStatus,
                shortfall = false,
            )
        }
        val budgetRoom = Math.subtractExact(inputs.assignedBudgetMinor!!, inputs.externalHoldingsMinor!!)
        val cap = minOf(brokerInBase!!, budgetRoom)
        val afterReserved = Math.subtractExact(cap, inputs.reservedCashMinor!!)
        val available = Math.subtractExact(afterReserved, inputs.cashBufferMinor!!)
        return CashQuote(
            currency = base,
            availableMinor = available,
            brokerCashMinor = inputs.brokerCashMinor,
            brokerCurrency = brokerCurrency,
            brokerCashInBaseMinor = brokerInBase,
            assignedBudgetMinor = inputs.assignedBudgetMinor,
            externalHoldingsMinor = inputs.externalHoldingsMinor,
            reservedCashMinor = inputs.reservedCashMinor,
            cashBufferMinor = inputs.cashBufferMinor,
            status = STATUS_CALCULATED,
            fxStatus = fxStatus,
            shortfall = available < 0,
        )
    }

    companion object {
        const val STATUS_UNOBSERVED: String = "UNOBSERVED"
        const val STATUS_CALCULATED: String = "CALCULATED"
        const val STATUS_FX_UNAVAILABLE: String = "FX_UNAVAILABLE"
        const val FX_UNOBSERVED: String = "UNOBSERVED"
        const val FX_NOT_REQUIRED: String = "NOT_REQUIRED"
        const val FX_UNCONFIGURED: String = "UNCONFIGURED"
        const val FX_APPLIED: String = "APPLIED"
    }
}
