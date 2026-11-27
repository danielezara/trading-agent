package com.tradingagent.modules.strategy

import com.tradingagent.modules.configuration.EconomicParameters
import java.math.BigDecimal
import java.math.RoundingMode

data class PayloadRate(
    val currency: String,
    val displayCurrency: String,
    val rate: String,
)

object BudgetLoss {
    /**
     * EUR minor units for the entry check.
     * A loss already in EUR is used as reported. Another currency is converted only by a rate on the payload.
     * Zero means the EUR figure is not available, so the entry check does not invent a breach.
     */
    fun entryLossMinor(
        lossMinor: Long?,
        currency: String?,
        economic: EconomicParameters,
        rates: List<PayloadRate> = emptyList(),
    ): Long = eurMinor(lossMinor, currency, economic.baseCurrency, rates) ?: 0

    fun blocksNewEntries(
        lossMinor: Long?,
        currency: String?,
        economic: EconomicParameters,
        rates: List<PayloadRate> = emptyList(),
    ): Boolean {
        val limit = economic.maxLossMinor() ?: return false
        val comparable = eurMinor(lossMinor, currency, economic.baseCurrency, rates) ?: return false
        return comparable >= limit
    }

    private fun eurMinor(
        lossMinor: Long?,
        currency: String?,
        budgetCurrency: String?,
        rates: List<PayloadRate>,
    ): Long? {
        if (lossMinor == null || lossMinor < 0 || currency == null || budgetCurrency == null) {
            return null
        }
        if (currency == budgetCurrency) {
            return lossMinor
        }
        val direct = rates.firstOrNull { it.currency == currency && it.displayCurrency == budgetCurrency }
        val inverse = rates.firstOrNull { it.currency == budgetCurrency && it.displayCurrency == currency }
        val rate = when {
            direct != null -> decimal(direct.rate)?.let { lossMinor to it }
            inverse != null -> decimal(inverse.rate)?.let { lossMinor to it }
            else -> null
        } ?: return null
        val (amount, quote) = rate
        if (quote.signum() <= 0) {
            return null
        }
        val money = BigDecimal.valueOf(amount).movePointLeft(2)
        val eur = if (direct != null) {
            money.multiply(quote)
        } else {
            money.divide(quote, 8, RoundingMode.DOWN)
        }
        return eur.setScale(2, RoundingMode.DOWN).movePointRight(2).longValueExact()
    }

    private fun decimal(raw: String): BigDecimal? = runCatching { BigDecimal(raw) }.getOrNull()
}
