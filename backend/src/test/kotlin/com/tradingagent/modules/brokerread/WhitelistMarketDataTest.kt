package com.tradingagent.modules.brokerread

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class WhitelistMarketDataTest {
    @Test
    fun `keeps the forming five minute candle`() {
        val now = Instant.parse("2026-11-28T06:06:00Z")
        val candles = WhitelistMarketData.parseCandles(
            """
            {"interval":"FiveMinutes","candles":[{"candles":[
              {"fromDate":"2026-11-28T06:05:00Z","open":3,"high":4,"low":2,"close":3.5},
              {"fromDate":"2026-11-28T06:00:00Z","open":1,"high":2,"low":0.5,"close":1.5},
              {"fromDate":"2026-11-28T06:10:00Z","open":4,"high":5,"low":3.5,"close":4.2}
            ]}]}
            """.trimIndent(),
            now,
        )
        assertEquals(2, candles.size)
        assertEquals("2026-11-28T06:00:00Z", candles[0].start)
        assertEquals(false, candles[0].forming)
        assertEquals("2026-11-28T06:05:00Z", candles[1].start)
        assertEquals(true, candles[1].forming)
    }

    @Test
    fun `picks the euro crypto pair`() {
        val page = CatalogPage(
            totalItems = 2,
            rows = listOf(
                CatalogRow("100000", "BTC", "Bitcoin", "Crypto", "eToro", false, false, true, true, true),
                CatalogRow("100109", "BTCEUR", "Bitcoin", "Crypto", "eToro", false, false, true, true, true),
            ),
        )
        assertEquals("BTCEUR" to "100109", WhitelistMarketData.euroPair("BTC", page))
    }

    @Test
    fun `without keys every card stays unavailable`() {
        val board = WhitelistMarketData(keys = { null }, transport = HttpGet { _, _ -> InboundHttp(500, null, "") })
        val cards = board.snapshot()
        assertEquals(20, cards.size)
        assertTrue(cards.all { it.reason == "Chiavi non disponibili" })
    }
}
