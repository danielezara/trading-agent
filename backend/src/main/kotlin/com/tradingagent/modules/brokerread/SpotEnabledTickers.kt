package com.tradingagent.modules.brokerread

import com.tradingagent.modules.configuration.SpotSelection
import java.time.Duration
import java.time.Instant

object SpotEnabledTickers {
    fun symbols(entrySymbols: List<String>, rows: List<SelectionRow>): List<String> {
        val enabled = rows.filter { !it.entryBlocked }.map { it.ticker }
        return (entrySymbols + enabled).distinct()
    }

    fun instrumentId(row: SelectionRow): String? {
        if (row.entryBlocked) {
            return null
        }
        val id = row.pair.substringAfter('#', "")
        return id.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }
    }

    fun report(rows: List<SelectionRow>): String {
        val enabled = rows.filter { !it.entryBlocked }.joinToString(",") { it.ticker }.ifEmpty { "-" }
        val excluded = rows.filter { it.entryBlocked }.joinToString(",") { "${it.ticker}:${it.reason}" }.ifEmpty { "-" }
        return "spot_evaluated=${rows.size} enabled=$enabled excluded=$excluded"
    }

    fun load(
        config: SpotSelection,
        transport: HttpGet,
        material: DemoKeyMaterial,
        asOf: Instant,
        sleep: (Duration) -> Unit = {},
    ): List<String> = selectRows(config, transport, material, asOf, sleep).filter { !it.entryBlocked }.map { it.ticker }

    fun selectRows(
        config: SpotSelection,
        transport: HttpGet,
        material: DemoKeyMaterial,
        asOf: Instant,
        sleep: (Duration) -> Unit = {},
    ): List<SelectionRow> {
        val count = candleCount(config, asOf) ?: return emptyList()
        val observations = linkedMapOf<String, AssetObservation>()
        config.assets.forEach { asset ->
            val page = SpotCatalog.parse(read(transport, material, SpotCatalog.uri(asset), sleep).orEmpty())
            val instrumentId = SpotPairSelection.listedInstrumentId(config, asset, page)
            val quote = if (instrumentId != null) {
                val body = read(transport, material, DemoMarketRates.requestUri(listOf(instrumentId)), sleep)
                body?.let { SpotCatalog.parseQuote(it, instrumentId) }
            } else {
                null
            }
            val bars = if (instrumentId != null) {
                val body = read(transport, material, SpotCatalog.candlesUri(instrumentId, config.timeframe, count), sleep)
                body?.let { SpotCatalog.parseBars(it, config.barSeconds) }.orEmpty()
            } else {
                emptyList()
            }
            observations[asset] = AssetObservation(page, quote, bars, openPosition = false)
        }
        return SpotPairSelection.select(config, SpotMode.PAPER, asOf, observations)
    }

    private fun candleCount(config: SpotSelection, asOf: Instant): Long? {
        val until = if (asOf.isAfter(config.historyEnd)) asOf else config.historyEnd
        val seconds = until.epochSecond - config.historyStart.epochSecond
        if (seconds <= 0 || config.barSeconds <= 0) {
            return null
        }
        val count = seconds / config.barSeconds + 2
        return count.takeIf { it in 1..100_000 }
    }

    private fun read(transport: HttpGet, material: DemoKeyMaterial, uri: java.net.URI, sleep: (Duration) -> Unit): String? {
        val first = try {
            transport.get(uri, material.headers())
        } catch (_: Exception) {
            return null
        }
        val retryAfter = first.retryAfterSeconds
        val response = if (first.status == 429 && retryAfter != null && retryAfter in 0L..2L) {
            sleep(Duration.ofSeconds(retryAfter))
            try {
                transport.get(uri, material.headers())
            } catch (_: Exception) {
                return null
            }
        } else {
            first
        }
        sleep(Duration.ofMillis(200))
        if (response.status != 200 || response.body.isBlank()) {
            return null
        }
        return response.body
    }
}
