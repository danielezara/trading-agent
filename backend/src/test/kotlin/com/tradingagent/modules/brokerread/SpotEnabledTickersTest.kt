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
    fun `a pair without an eur market stays excluded in the report`() {
        val enabled = row("BTC", false).copy(pair = "BTCEUR#100001")
        val missing = row("DOGE", true).copy(reason = "quote_absent", pair = "-")
        assertEquals("100001", SpotEnabledTickers.instrumentId(enabled))
        assertEquals(null, SpotEnabledTickers.instrumentId(missing))
        assertEquals(
            "spot_evaluated=2 enabled=BTC excluded=DOGE:quote_absent",
            SpotEnabledTickers.report(listOf(enabled, missing)),
        )
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
