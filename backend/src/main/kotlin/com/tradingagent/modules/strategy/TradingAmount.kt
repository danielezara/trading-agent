package com.tradingagent.modules.strategy

import java.math.BigDecimal
import java.math.RoundingMode

object TradingAmount {
    val MIN_USD: BigDecimal = BigDecimal("1.00")
    val MAX_USD: BigDecimal = BigDecimal("150.00")

    /**
     * USD cash for one new Demo open.
     * Null when the balances payload has no positive EUR to USD rate.
     */
    fun usd(eurMinor: Long, rates: List<PayloadRate>): BigDecimal? {
        if (eurMinor <= 0) {
            return null
        }
        val quote = rates.firstOrNull { it.currency == "EUR" && it.displayCurrency == "USD" } ?: return null
        val rate = runCatching { BigDecimal(quote.rate) }.getOrNull() ?: return null
        if (rate.signum() <= 0) {
            return null
        }
        val eur = BigDecimal.valueOf(eurMinor).movePointLeft(2)
        return eur.multiply(rate).setScale(2, RoundingMode.DOWN)
    }

    fun acceptable(amount: BigDecimal): Boolean = amount >= MIN_USD && amount <= MAX_USD
}
