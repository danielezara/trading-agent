package com.tradingagent.modules.brokerread

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpotEnabledTickersTest {
    @Test
    fun `bitcoin stays in the universe and a disabled pair does not`() {
        val rows = listOf(row("ETH", false), row("DOGE", true))
        assertEquals(listOf("BTC", "ETH"), SpotEnabledTickers.symbols(listOf("BTC"), rows))
    }

    @Test
    fun `order 397105707 blocks its instrument`() {
        val account = ObservedAccount(
            brokerCash = null,
            equity = null,
            positionCount = 1,
            accountLabel = null,
            executions = listOf(ObservedOrder("397105707", "100000", "buy", "1", null, null)),
        )
        assertEquals(setOf("100000"), DemoClosedBars.blockedEntryIds(account))
    }

    private fun row(ticker: String, blocked: Boolean): SelectionRow = SelectionRow(
        ticker = ticker,
        exchange = "etoro",
        pair = ticker,
        mode = "paper",
        outcome = if (blocked) "excluded" else "enabled",
        reason = "ok",
        observed = "",
        threshold = "",
        entryBlocked = blocked,
        exitBlocked = false,
    )
}
