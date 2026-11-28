package com.tradingagent.modules.brokerread

import com.tradingagent.modules.configuration.SpotSelection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class SpotPairSelectionTest {
    private val start: Instant = Instant.parse("2026-11-28T04:00:00Z")
    private val end: Instant = Instant.parse("2026-11-28T04:15:00Z")
    private val asOf: Instant = Instant.parse("2026-11-28T04:10:00Z")

    @Test
    fun `a catalog spot pair in the quote currency is enabled from its id`() {
        val rows = select(
            paper(),
            SpotMode.PAPER,
            asOf,
            mapOf("BTC" to observation(page(eur("BTCEUR", "100109")), quote(), bars(asOf, "5"))),
        )
        val btc = row(rows, "BTC")
        assertEquals("enabled", btc.outcome)
        assertEquals("ok", btc.reason)
        assertEquals("BTCEUR#100109", btc.pair)
        assertEquals("etoro/Digital Currency", btc.exchange)
        assertFalse(btc.entryBlocked)
        assertTrue(btc.observed.contains("5 candle_volume one_closed_bar"))
        assertTrue(btc.threshold.contains("1 candle_volume one_closed_bar"))
        assertTrue(btc.threshold.contains("0.002 bid_ask_mid_ratio"))
        val uri = SpotCatalog.uri("BTC")
        assertEquals("/api/v1/market-data/search", uri.path)
        assertTrue(uri.query.contains("internalSymbolFull=BTC"))
        assertFalse(uri.query.contains("BTCEUR"))
        assertEquals(20, rows.size)
        assertEquals(SpotSelection.FIXED_ASSETS, rows.map { it.ticker })
    }

    @Test
    fun `a USDT pair does not replace a missing EUR pair and the asset stays listed`() {
        val rows = select(
            paper(),
            SpotMode.PAPER,
            asOf,
            mapOf("SOL" to observation(page(eur("SOLUSDT", "100999")), null, emptyList())),
        )
        val sol = row(rows, "SOL")
        assertEquals("excluded", sol.outcome)
        assertEquals("quote_absent", sol.reason)
        assertEquals("other=SOLUSDT", sol.observed)
        assertEquals("-", sol.pair)
        assertEquals("SOL", rows.map { it.ticker }.single { it == "SOL" })
        assertTrue(sol.entryBlocked)
    }

    @Test
    fun `a tradable pair stays eligible when the exchange flag is closed`() {
        val closed = eur("BTCEUR", "100109", exchangeOpen = false)
        val paperRows = select(paper(), SpotMode.PAPER, asOf, mapOf("BTC" to observation(page(closed), quote(), bars(asOf, "5"))))
        val tradable = row(paperRows, "BTC")
        assertEquals("enabled", tradable.outcome)
        assertFalse(tradable.entryBlocked)
        assertFalse(tradable.reason.contains("inactive"))

        val halted = eur("BTCEUR", "100109", exchangeOpen = false, currentlyTradable = false)
        val haltedRows = select(paper(), SpotMode.PAPER, asOf, mapOf("BTC" to observation(page(halted), quote(), bars(asOf, "5"))))
        assertEquals("enabled", row(haltedRows, "BTC").outcome)

        val backtestRows = select(
            paper(model = "fixed_ratio", modelValue = "0.001"),
            SpotMode.BACKTEST,
            end,
            mapOf("BTC" to observation(page(closed), wideQuote(), covered("5"), openPosition = true)),
        )
        val btc = row(backtestRows, "BTC")
        assertEquals("enabled", btc.outcome)
        assertEquals("spread_estimate", btc.reason)
        assertTrue(btc.observed.contains("estimate"))
        assertFalse(btc.reason.contains("inactive"))
    }

    @Test
    fun `a gap in the closed history excludes the new entry and still allows an exit`() {
        val partial = listOf(bar(start, "5"), bar(start.plusSeconds(600), "5"))
        val rows = select(
            paper(),
            SpotMode.BACKTEST,
            end,
            mapOf("BTC" to observation(page(eur("BTCEUR", "100109")), null, partial, openPosition = true)),
        )
        val btc = row(rows, "BTC")
        assertEquals("history_gap", btc.reason)
        assertTrue(btc.entryBlocked)
        assertFalse(btc.exitBlocked)
    }

    @Test
    fun `volume below the configured minimum is excluded with the same unit and window`() {
        val strict = paper(minVolume = "10")
        val loose = paper(minVolume = "1")
        val seen = mapOf("BTC" to observation(page(eur("BTCEUR", "100109")), quote(), bars(asOf, "5")))
        val below = row(select(strict, SpotMode.PAPER, asOf, seen), "BTC")
        assertEquals("volume_below", below.reason)
        assertTrue(below.observed.contains("5 candle_volume one_closed_bar"))
        assertTrue(below.threshold.contains("10 candle_volume one_closed_bar"))
        assertEquals("enabled", row(select(loose, SpotMode.PAPER, asOf, seen), "BTC").outcome)
    }

    @Test
    fun `a quote a few seconds newer than the cycle clock stays fresh`() {
        val rows = select(
            paper(),
            SpotMode.PAPER,
            asOf,
            mapOf("BTC" to observation(page(eur("BTCEUR", "100109")), SpotQuote(BigDecimal("100"), BigDecimal("100.10"), asOf.plusSeconds(15)), bars(asOf, "5"))),
        )
        assertEquals("enabled", row(rows, "BTC").outcome)
    }

    @Test
    fun `a wide bid ask spread blocks the new entry`() {
        val rows = select(
            paper(),
            SpotMode.PAPER,
            asOf,
            mapOf("BTC" to observation(page(eur("BTCEUR", "100109")), wideQuote(), bars(asOf, "5"))),
        )
        val btc = row(rows, "BTC")
        assertEquals("spread_wide", btc.reason)
        assertTrue(btc.observed.contains("bid_ask_mid_ratio"))
        assertTrue(btc.threshold.contains("0.002 bid_ask_mid_ratio"))
        assertTrue(btc.entryBlocked)
    }

    @Test
    fun `a missing candle volume does not block a quoted euro pair`() {
        val rows = select(
            paper(),
            SpotMode.PAPER,
            asOf,
            mapOf("ETH" to observation(page(eur("ETHEUR", "100110")), quote(), bars(asOf, null), openPosition = true)),
        )
        val eth = row(rows, "ETH")
        assertEquals("enabled", eth.outcome)
        assertFalse(eth.entryBlocked)
        assertFalse(eth.exitBlocked)
    }

    @Test
    fun `a backtest entry ignores a later bar and today's spread`() {
        val future = SpotBar(asOf, asOf.plusSeconds(300), BigDecimal("100"))
        val past = covered("0.5") + future
        val low = select(
            paper(),
            SpotMode.BACKTEST,
            asOf,
            mapOf("BTC" to observation(page(eur("BTCEUR", "100109")), wideQuote(), past)),
        )
        val lowRow = row(low, "BTC")
        assertEquals("volume_below", lowRow.reason)
        assertTrue(lowRow.observed.contains("0.5 candle_volume"))
        assertFalse(lowRow.observed.contains("100 candle_volume"))

        val enough = select(
            paper(),
            SpotMode.BACKTEST,
            asOf,
            mapOf("BTC" to observation(page(eur("BTCEUR", "100109")), wideQuote(), covered("5") + future)),
        )
        assertEquals("spread_unverifiable", row(enough, "BTC").reason)
    }

    @Test
    fun `two catalog ids for the same pair are ambiguous`() {
        val rows = select(
            paper(),
            SpotMode.PAPER,
            asOf,
            mapOf("BTC" to observation(page(eur("BTCEUR", "100109"), eur("BTCEUR", "100209")), null, emptyList())),
        )
        assertEquals("ambiguous", row(rows, "BTC").reason)
        assertEquals("100109,100209", row(rows, "BTC").observed)
    }

    @Test
    fun `the search payload keeps the catalog id and a null candle volume`() {
        val page = SpotCatalog.parse(
            """{"totalItems":1,"items":[{"internalSymbolFull":"BTCEUR","instrumentId":100109,"internalInstrumentId":100109,"internalInstrumentDisplayName":"Bitcoin/Euro","internalAssetClassName":"Crypto","internalExchangeName":"Digital Currency","isHiddenFromClient":false,"isDelisted":false,"isActiveInPlatform":true,"isCurrentlyTradable":true,"isExchangeOpen":true}]}""",
        )
        assertTrue(page.complete)
        assertEquals("100109", SpotPairSelection.listedInstrumentId(paper(), "BTC", page))
        val bars = SpotCatalog.parseBars(
            """{"interval":"FiveMinutes","candles":[{"candles":[{"instrumentID":100109,"fromDate":"2026-11-28T04:05:00Z","open":1,"high":1,"low":1,"close":1,"volume":null}]}]}""",
            300,
        )
        assertNull(bars.single().volume)
        assertNull(SpotCatalog.parseQuote("""{"rates":[{"instrumentID":100109,"ask":2}]}""", "100109")?.bid)
    }

    private fun paper(
        minVolume: String = "1",
        maxSpread: String = "0.002",
        model: String = "none",
        modelValue: String? = null,
    ): SpotSelection = SpotSelection(
        exchange = "etoro",
        quoteCurrency = "EUR",
        timeframe = "FiveMinutes",
        barSeconds = 300,
        historyStart = start,
        historyEnd = end,
        minVolume = BigDecimal(minVolume),
        volumeUnit = "candle_volume",
        volumeWindow = "one_closed_bar",
        maxSpread = BigDecimal(maxSpread),
        spreadUnit = "bid_ask_mid_ratio",
        spreadModel = model,
        spreadModelValue = modelValue?.let { BigDecimal(it) },
        quoteMaxAgeSeconds = 120,
        assets = SpotSelection.FIXED_ASSETS,
    )

    private fun select(
        config: SpotSelection,
        mode: SpotMode,
        at: Instant,
        observations: Map<String, AssetObservation>,
    ): List<SelectionRow> = SpotPairSelection.select(config, mode, at, observations)

    private fun row(rows: List<SelectionRow>, ticker: String): SelectionRow = rows.single { it.ticker == ticker }

    private fun observation(
        page: CatalogPage,
        quote: SpotQuote?,
        bars: List<SpotBar>,
        openPosition: Boolean = false,
    ): AssetObservation = AssetObservation(page, quote, bars, openPosition)

    private fun page(vararg rows: CatalogRow): CatalogPage = CatalogPage(rows.size, rows.toList())

    private fun eur(
        symbol: String,
        id: String,
        exchangeOpen: Boolean = true,
        currentlyTradable: Boolean = true,
    ): CatalogRow = CatalogRow(
        instrumentId = id,
        symbol = symbol,
        displayName = symbol,
        assetClass = "Crypto",
        exchangeName = "Digital Currency",
        hidden = false,
        delisted = false,
        activeInPlatform = true,
        currentlyTradable = currentlyTradable,
        exchangeOpen = exchangeOpen,
    )

    private fun quote(): SpotQuote = SpotQuote(BigDecimal("100"), BigDecimal("100.10"), asOf)

    private fun wideQuote(): SpotQuote = SpotQuote(BigDecimal("100"), BigDecimal("102"), asOf)

    private fun bars(endAt: Instant, volume: String?): List<SpotBar> = listOf(bar(endAt.minusSeconds(300), volume))

    private fun covered(volume: String): List<SpotBar> = listOf(0L, 300L, 600L).map { offset ->
        bar(start.plusSeconds(offset), volume)
    }

    private fun bar(barStart: Instant, volume: String?): SpotBar =
        SpotBar(barStart, barStart.plusSeconds(300), volume?.let { BigDecimal(it) })
}
