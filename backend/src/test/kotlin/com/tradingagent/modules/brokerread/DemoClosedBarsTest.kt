package com.tradingagent.modules.brokerread

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class DemoClosedBarsTest {
    private val now: Instant = Instant.parse("2026-11-27T10:30:00Z")

    @Test
    fun `five minute candles keep native prices and drop the open bar`() {
        val start = now.minusSeconds(600)
        val body = candles(
            interval = "FiveMinutes",
            candle(start, "100.00", "101.00", "99.50", "100.25"),
            candle(start.plusSeconds(300), "100.25", "102.00", "100.00", "101.50"),
            candle(now, "101.50", "103.00", "101.00", "102.00"),
        )
        val selected = DemoClosedBars.select(DemoClosedBars.parse(body), now)
        assertEquals(2, selected.size)
        assertEquals(10_025_000_000L, selected[0].closeMinor)
        assertEquals(10_100_000_000L, selected[0].highMinor)
        assertEquals(9_950_000_000L, selected[0].lowMinor)
        assertEquals(start.plusSeconds(300), selected[0].end)
        assertEquals(start.plusSeconds(300), selected[1].start)
        assertTrue(selected.all { !it.end.isAfter(now) })
    }

    @Test
    fun `a different interval and a gap are not turned into five minute bars`() {
        val start = now.minusSeconds(1_200)
        assertTrue(DemoClosedBars.parse(candles("OneMinute", candle(start, "1", "1", "1", "1"))).isEmpty())
        val gapped = DemoClosedBars.select(
            DemoClosedBars.parse(
                candles(
                    "FiveMinutes",
                    candle(start, "10", "11", "9", "10"),
                    candle(start.plusSeconds(900), "12", "13", "11", "12"),
                    candle(start.plusSeconds(1_200), "12", "14", "12", "13"),
                ),
            ),
            now.plusSeconds(3_600),
        )
        assertEquals(2, gapped.size)
        assertEquals(start.plusSeconds(900), gapped[0].start)
    }

    @Test
    fun `conflicting candles for the same start are discarded`() {
        val start = now.minusSeconds(600)
        val selected = DemoClosedBars.select(
            listOf(
                bar(start, 10),
                bar(start, 11),
            ),
            now,
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `the candle path is the demo five minute history`() {
        val uri = DemoClosedBars.candlesUri("1001")
        assertEquals("/api/v1/market-data/instruments/1001/history/candles/desc/FiveMinutes/30", uri.path)
        assertFalse(uri.path.contains("real"))
        assertEquals("public-api.etoro.com", uri.host)
    }

    @Test
    fun `a failed candle read keeps the instrument and invents no bars`() {
        val calls = mutableListOf<String>()
        val book = DemoClosedBars.read(
            transport = HttpGet { uri, _ ->
                calls += uri.path
                when {
                    uri.path.endsWith("/portfolio") -> InboundHttp(200, null, """{"clientPortfolio":{"credit":1,"positions":[{"instrumentID":1001,"units":1,"amount":1}]}}""")
                    uri.path.endsWith("/instrument-types") -> InboundHttp(200, null, """{"instrumentTypes":[{"instrumentTypeID":5,"instrumentTypeDescription":"Stocks"}]}""")
                    uri.path.contains("/history/candles/") -> InboundHttp(503, null, "")
                    else -> InboundHttp(200, null, """{"instrumentDisplayDatas":[{"instrumentID":1001,"instrumentTypeID":5}]}""")
                }
            },
            material = keys(),
            now = now,
        )
        assertEquals(1, book.instruments.size)
        assertEquals("stock", book.instruments[0].assetClass)
        assertTrue(book.instruments[0].bars.isEmpty())
        assertTrue(calls.any { it.contains("/history/candles/desc/FiveMinutes/30") })
        assertTrue(calls.none { it.contains("real") })
    }

    private fun keys() = DemoKeyMaterial.fromEnvironment(
        mapOf("ETORO_DEMO_API_KEY" to "test-api-key", "ETORO_DEMO_USER_KEY" to "test-user-key"),
    )!!

    private fun candles(interval: String, vararg rows: String) = """
        {"interval":"$interval","candles":[{"instrumentId":1001,"candles":[${rows.joinToString(",")}]}]}
    """.trimIndent()

    private fun candle(start: Instant, open: String, high: String, low: String, close: String) =
        """{"instrumentID":1001,"fromDate":"$start","open":$open,"high":$high,"low":$low,"close":$close}"""

    private fun bar(start: Instant, close: Long) = ObservedBar(
        start = start,
        end = start.plusSeconds(300),
        closeMinor = close,
        highMinor = close,
        lowMinor = close,
    )
}
