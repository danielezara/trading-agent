package com.tradingagent.modules.strategy

import com.tradingagent.modules.configuration.EconomicParameters

object BudgetLoss {
    /** EUR minor units for the entry check. A loss reported in another currency is not converted. */
    fun entryLossMinor(lossMinor: Long?, currency: String?, economic: EconomicParameters): Long {
        if (lossMinor == null || lossMinor < 0 || currency == null) {
            return 0
        }
        if (currency != economic.baseCurrency) {
            return 0
        }
        return lossMinor
    }

    fun blocksNewEntries(lossMinor: Long?, currency: String?, economic: EconomicParameters): Boolean {
        val limit = economic.maxLossMinor() ?: return false
        val comparable = entryLossMinor(lossMinor, currency, economic)
        if (currency != economic.baseCurrency) {
            return false
        }
        return comparable >= limit
    }
}
