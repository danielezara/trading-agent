package com.tradingagent.modules.brokerread

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration
import java.time.Instant

class DemoClosedTradesTest {
    private val apiKey = "test-api-key"
    private val userKey = "test-user-key"
    private val now: Instant = Instant.parse("2026-11-27T00:00:00Z")

    @Test
    fun `a closed trade keeps profit and a missing profit stays blank`() {
        val trades = DemoClosedTrades.parse(
            """
            [
              {"positionId":7,"instrumentId":1002,"isBuy":true,"units":1.5,"netProfit":-2.5,"closeTimestamp":"2026-11-01T12:00:00Z","socialTradeId":424242,"parentPositionId":111},
              {"positionId":8,"instrumentId":1003,"isBuy":false,"units":2,"closeTimestamp":"not-a-date"},
              {"instrumentId":9,"netProfit":0},
              "skip"
            ]
            """.trimIndent(),
        )
        assertEquals(2, trades?.size)
        val first = trades?.get(0)
        assertEquals("7", first?.positionId)
        assertEquals("1002", first?.instrumentId)
        assertEquals("BUY", first?.side)
        assertEquals("1.5", first?.quantity)
        assertEquals("-2.5", first?.netProfit)
        assertEquals("2026-11-01T12:00:00Z", first?.closedAt)
        val second = trades?.get(1)
        assertEquals("8", second?.positionId)
        assertEquals("SELL", second?.side)
        assertNull(second?.netProfit)
        assertNull(second?.closedAt)
        assertFalse(trades.toString().contains("424242"))
        assertFalse(trades.toString().contains("111"))
    }

    @Test
    fun `an empty page is an empty list and a profit object is not a list`() {
        assertEquals(emptyList<ClosedTrade>(), DemoClosedTrades.parse("[]"))
        assertNull(DemoClosedTrades.parse("""{"closedPositionsNetProfit":12.5}"""))
        assertNull(DemoClosedTrades.parse("not-json"))
    }

    @Test
    fun `history uses one demo page and does not call the real path`() {
        val calls = mutableListOf<URI>()
        val trades = DemoClosedTrades.execute(
            HttpGet { uri, _ ->
                calls += uri
                InboundHttp(200, null, """[{"positionId":7,"instrumentId":1002,"netProfit":1}]""")
            },
            keys(),
            now,
        )
        assertEquals(1, calls.size)
        val uri = calls.single()
        assertEquals("/api/v1/trading/info/trade/demo/history", uri.path)
        assertTrue(uri.query.contains("minDate=2025-11-28"))
        assertTrue(uri.query.contains("page=1"))
        assertTrue(uri.query.contains("pageSize=100"))
        assertFalse(uri.path.contains("/real"))
        assertEquals("1", trades?.single()?.netProfit)
    }

    @Test
    fun `missing keys do not call and a long rate limit is not retried`() {
        var calls = 0
        val unread = DemoClosedTradeRead(
            keys = { null },
            transport = HttpGet { _, _ ->
                calls += 1
                throw IllegalStateException("no network")
            },
        ).read(now)
        assertNull(unread)
        assertEquals(0, calls)

        val limited = DemoClosedTrades.execute(
            HttpGet { _, _ ->
                calls += 1
                InboundHttp(429, 30, "")
            },
            keys(),
            now,
            sleep = { throw IllegalStateException("must not wait") },
        )
        assertNull(limited)
        assertEquals(1, calls)
    }

    private fun keys(): DemoKeyMaterial = DemoKeyMaterial.fromEnvironment(
        mapOf("ETORO_DEMO_API_KEY" to apiKey, "ETORO_DEMO_USER_KEY" to userKey),
    )!!
}
