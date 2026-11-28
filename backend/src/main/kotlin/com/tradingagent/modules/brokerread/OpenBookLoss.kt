package com.tradingagent.modules.brokerread

import com.tradingagent.kernel.Money

object OpenBookLoss {
    const val CURRENCY: String = "USD"

    /** Net loss of open positions from the dollar amounts eToro already reports. Null when a position has no dollar pair. */
    fun of(positions: List<ObservedPosition>?): Money? {
        if (positions == null) {
            return null
        }
        if (positions.isEmpty()) {
            return Money(0, CURRENCY)
        }
        var opened = 0L
        var current = 0L
        for (position in positions) {
            val initial = position.initialDollarsMinor ?: return null
            val value = position.value ?: return null
            if (value.currency != CURRENCY) {
                return null
            }
            opened = Math.addExact(opened, initial)
            current = Math.addExact(current, value.minorUnits)
        }
        val loss = Math.subtractExact(opened, current)
        return Money(if (loss > 0) loss else 0L, CURRENCY)
    }
}
