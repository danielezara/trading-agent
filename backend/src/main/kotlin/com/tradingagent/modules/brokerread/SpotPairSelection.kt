package com.tradingagent.modules.brokerread

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tradingagent.modules.configuration.SpotSelection
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.time.Instant

enum class SpotMode { PAPER, BACKTEST }

data class CatalogRow(
    val instrumentId: String,
    val symbol: String,
    val displayName: String,
    val assetClass: String,
    val exchangeName: String,
    val hidden: Boolean?,
    val delisted: Boolean?,
    val activeInPlatform: Boolean?,
    val currentlyTradable: Boolean?,
    val exchangeOpen: Boolean?,
)

data class CatalogPage(val totalItems: Int?, val rows: List<CatalogRow>) {
    val complete: Boolean = totalItems != null && totalItems == rows.size
}

data class SpotBar(val start: Instant, val end: Instant, val volume: BigDecimal?)

data class SpotQuote(val bid: BigDecimal?, val ask: BigDecimal?, val quotedAt: Instant?)

data class AssetObservation(
    val page: CatalogPage = CatalogPage(0, emptyList()),
    val quote: SpotQuote? = null,
    val bars: List<SpotBar> = emptyList(),
    val openPosition: Boolean = false,
)

data class SelectionRow(
    val ticker: String,
    val exchange: String,
    val pair: String,
    val mode: String,
    val outcome: String,
    val reason: String,
    val observed: String,
    val threshold: String,
    val entryBlocked: Boolean,
    val exitBlocked: Boolean,
)

object SpotCatalog {
    private val mapper: ObjectMapper = ObjectMapper()
    private val symbolPattern = Regex("[A-Z0-9.]{1,16}")
    const val PAGE_SIZE: Int = 100

    fun uri(asset: String): URI {
        require(symbolPattern.matches(asset)) { "spot asset is not usable" }
        val uri = URI.create("${DemoSymbolLookup.SEARCH}?internalSymbolFull=$asset&pageSize=$PAGE_SIZE")
        require(uri.scheme == "https") { "spot search requires https" }
        require(uri.host == "public-api.etoro.com") { "spot search host is fixed" }
        require(uri.path == "/api/v1/market-data/search") { "spot search path is the search get" }
        require(!uri.path.contains("real")) { "real trading path is forbidden" }
        require(!uri.path.contains("/feeds")) { "news and social feeds are not read" }
        require(!uri.query.contains(asset + "EUR") && !uri.query.contains(asset + "USD")) {
            "spot search does not concatenate a quote onto the ticker"
        }
        return uri
    }

    fun candlesUri(id: String, timeframe: String, count: Long): URI {
        require(Regex("[1-9][0-9]{0,9}").matches(id)) { "instrument id is not a positive integer" }
        require(timeframe == "FiveMinutes") { "candle read uses the five minute history" }
        require(count in 1..100_000) { "candle count does not match the history interval" }
        val uri = URI.create(
            "https://public-api.etoro.com/api/v1/market-data/instruments/$id/history/candles/desc/FiveMinutes/$count",
        )
        require(uri.scheme == "https" && uri.host == "public-api.etoro.com") { "candles host is fixed" }
        require(!uri.path.contains("real") && !uri.path.contains("/feeds")) { "candle path is a market-data get" }
        return uri
    }

    fun parse(body: String): CatalogPage {
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return CatalogPage(null, emptyList())
        }
        if (!root.isObject) {
            return CatalogPage(null, emptyList())
        }
        val total = root.get("totalItems")?.takeIf { it.isIntegralNumber }?.asInt()
        val items = root.get("items")
        if (items == null || !items.isArray) {
            return CatalogPage(null, emptyList())
        }
        return CatalogPage(total, items.mapNotNull(::row))
    }

    fun parseBars(body: String, barSeconds: Long): List<SpotBar> {
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return emptyList()
        }
        val groups = root.get("candles") ?: return emptyList()
        if (!groups.isArray) {
            return emptyList()
        }
        val bars = mutableListOf<SpotBar>()
        groups.forEach groupLoop@{ group ->
            val rows = group.get("candles") ?: return@groupLoop
            if (!rows.isArray) {
                return@groupLoop
            }
            rows.forEach rowLoop@{ node ->
                val start = node.get("fromDate")?.takeIf { it.isTextual }?.asText()?.let { text ->
                    runCatching { Instant.parse(text) }.getOrNull()
                } ?: return@rowLoop
                bars += SpotBar(start, start.plusSeconds(barSeconds), volume(node.get("volume")))
            }
        }
        return bars
    }

    fun parseQuote(body: String, instrumentId: String): SpotQuote? {
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return null
        }
        val rates = root.get("rates") ?: return null
        if (!rates.isArray) {
            return null
        }
        rates.forEach { node ->
            val id = node.get("instrumentID")?.takeIf { it.isIntegralNumber }?.asInt() ?: return@forEach
            if (id.toString() != instrumentId) {
                return@forEach
            }
            val quotedAt = node.get("date")?.takeIf { it.isTextual }?.asText()?.let { text ->
                runCatching { Instant.parse(text) }.getOrNull()
            }
            return SpotQuote(decimal(node.get("bid")), decimal(node.get("ask")), quotedAt)
        }
        return null
    }

    private fun row(item: JsonNode): CatalogRow? {
        if (!item.isObject) {
            return null
        }
        val id = positive(item.get("instrumentId")) ?: return null
        val internal = positive(item.get("internalInstrumentId"))
        if (internal != null && internal != id) {
            return null
        }
        val symbol = item.get("internalSymbolFull")?.takeIf { it.isTextual }?.asText()?.trim().orEmpty()
        if (symbol.isEmpty()) {
            return null
        }
        return CatalogRow(
            instrumentId = id.toString(),
            symbol = symbol,
            displayName = item.get("internalInstrumentDisplayName")?.takeIf { it.isTextual }?.asText()?.trim().orEmpty(),
            assetClass = item.get("internalAssetClassName")?.takeIf { it.isTextual }?.asText()?.trim().orEmpty(),
            exchangeName = item.get("internalExchangeName")?.takeIf { it.isTextual }?.asText()?.trim().orEmpty(),
            hidden = flag(item, "isHiddenFromClient"),
            delisted = flag(item, "isDelisted"),
            activeInPlatform = flag(item, "isActiveInPlatform"),
            currentlyTradable = flag(item, "isCurrentlyTradable"),
            exchangeOpen = flag(item, "isExchangeOpen"),
        )
    }

    private fun positive(node: JsonNode?): Int? {
        val value = node?.takeIf { it.isIntegralNumber }?.asInt() ?: return null
        return value.takeIf { it > 0 }
    }

    private fun flag(node: JsonNode, field: String): Boolean? {
        val value = node.get(field) ?: return null
        if (!value.isBoolean) {
            return null
        }
        return value.asBoolean()
    }

    private fun volume(node: JsonNode?): BigDecimal? {
        if (node == null || node.isNull) {
            return null
        }
        return decimal(node)
    }

    private fun decimal(node: JsonNode?): BigDecimal? {
        val value = when {
            node == null || node.isNull -> return null
            node.isNumber -> node.decimalValue()
            node.isTextual -> node.asText().trim().toBigDecimalOrNull() ?: return null
            else -> return null
        }
        if (value.signum() < 0) {
            return null
        }
        return value
    }
}

object SpotPairSelection {
    const val LIMIT: String =
        "Limite: il backtest non usa lo stato attuale del mercato come prova che la coppia fosse attiva nel periodo. " +
            "Contano le candele dello storico, e un volume si legge solo sulla barra già chiusa in quell'istante. " +
            "Se manca lo storico dello spread e il modello non è configurato, il controllo è non verificabile. " +
            "Un'esclusione blocca solo un nuovo ingresso."

    fun select(
        config: SpotSelection,
        mode: SpotMode,
        asOf: Instant,
        observations: Map<String, AssetObservation>,
    ): List<SelectionRow> = config.assets.map { asset ->
        row(config, mode, asOf, asset, observations[asset] ?: AssetObservation())
    }

    fun render(rows: List<SelectionRow>): String = buildString {
        append(LIMIT).append('\n')
        append("ticker\texchange\tpair\tmode\toutcome\treason\tobserved\tthreshold\n")
        rows.forEach { row ->
            append(row.ticker).append('\t')
            append(row.exchange).append('\t')
            append(row.pair).append('\t')
            append(row.mode).append('\t')
            append(row.outcome).append('\t')
            append(row.reason).append('\t')
            append(row.observed).append('\t')
            append(row.threshold).append('\n')
        }
    }

    private fun row(
        config: SpotSelection,
        mode: SpotMode,
        asOf: Instant,
        asset: String,
        observation: AssetObservation,
    ): SelectionRow {
        val modeName = mode.name.lowercase()
        val identity = identify(config, asset, observation.page)
        if (identity.reason != null) {
            return excluded(asset, config.exchange, "-", modeName, identity.reason, identity.observed, "", observation.openPosition)
        }
        val pair = "${identity.symbol}#${identity.instrumentId}"
        val exchange = listOf(config.exchange, identity.exchangeName).filter { it.isNotBlank() }.joinToString("/")
        val volumeThreshold = threshold(config.minVolume, config.volumeUnit, config.volumeWindow)
        val spreadThreshold = threshold(config.maxSpread, config.spreadUnit, null)
        if (config.volumeUnit != "candle_volume" || config.volumeWindow != "one_closed_bar") {
            return excluded(asset, exchange, pair, modeName, "volume_unverifiable", "", volumeThreshold, observation.openPosition)
        }
        if (config.spreadUnit != "bid_ask_mid_ratio") {
            return excluded(asset, exchange, pair, modeName, "spread_unverifiable", "", spreadThreshold, observation.openPosition)
        }
        if (mode == SpotMode.BACKTEST) {
            val coveredUntil = knowableEnd(config, asOf)
            val gap = historyGap(config, observation.bars, coveredUntil)
            if (gap != null) {
                return excluded(asset, exchange, pair, modeName, gap, "covered_until=$coveredUntil", config.historyStart.toString() + ".." + coveredUntil, observation.openPosition)
            }
        }
        val volumeBar = volumeBar(config, mode, asOf, observation.bars)
        if (volumeBar?.volume == null && mode == SpotMode.BACKTEST) {
            return excluded(asset, exchange, pair, modeName, "volume_missing", "volume=", volumeThreshold, observation.openPosition)
        }
        if (volumeBar?.volume != null && volumeBar.volume < config.minVolume) {
            return excluded(
                asset,
                exchange,
                pair,
                modeName,
                "volume_below",
                observed(volumeBar.volume, config.volumeUnit, config.volumeWindow),
                volumeThreshold,
                observation.openPosition,
            )
        }
        val volumeObserved = volumeBar?.volume?.let { observed(it, config.volumeUnit, config.volumeWindow) }.orEmpty()
        return when (mode) {
            SpotMode.PAPER -> paperSpread(config, asOf, asset, exchange, pair, observation, volumeObserved, volumeThreshold, spreadThreshold)
            SpotMode.BACKTEST -> backtestSpread(config, asset, exchange, pair, volumeObserved, volumeThreshold, spreadThreshold, observation.openPosition)
        }
    }

    fun listedInstrumentId(config: SpotSelection, asset: String, page: CatalogPage): String? =
        identify(config, asset, page).instrumentId

    private fun paperSpread(
        config: SpotSelection,
        asOf: Instant,
        asset: String,
        exchange: String,
        pair: String,
        observation: AssetObservation,
        volumeObserved: String,
        volumeThreshold: String,
        spreadThreshold: String,
    ): SelectionRow {
        val quote = observation.quote
        val quotedAt = quote?.quotedAt
        if (quotedAt == null) {
            return excluded(asset, exchange, pair, "paper", "stale", volumeObserved, volumeThreshold, observation.openPosition)
        }
        val age = asOf.epochSecond - quotedAt.epochSecond
        if (age < 0 || age > config.quoteMaxAgeSeconds) {
            return excluded(asset, exchange, pair, "paper", "stale", volumeObserved, "quote_max_age_seconds=${config.quoteMaxAgeSeconds}", observation.openPosition)
        }
        val ratio = midRatio(quote.bid, quote.ask)
            ?: return excluded(asset, exchange, pair, "paper", "spread_missing", volumeObserved, spreadThreshold, observation.openPosition)
        val spreadObserved = observed(ratio, config.spreadUnit, null)
        if (ratio > config.maxSpread) {
            return excluded(asset, exchange, pair, "paper", "spread_wide", "$volumeObserved $spreadObserved", spreadThreshold, observation.openPosition)
        }
        return enabled(asset, exchange, pair, "paper", "ok", "$volumeObserved $spreadObserved", "$volumeThreshold $spreadThreshold", observation.openPosition)
    }

    private fun backtestSpread(
        config: SpotSelection,
        asset: String,
        exchange: String,
        pair: String,
        volumeObserved: String,
        volumeThreshold: String,
        spreadThreshold: String,
        openPosition: Boolean,
    ): SelectionRow {
        if (config.spreadModel != "fixed_ratio" || config.spreadModelValue == null) {
            return excluded(asset, exchange, pair, "backtest", "spread_unverifiable", volumeObserved, spreadThreshold, openPosition)
        }
        val estimate = config.spreadModelValue
        val spreadObserved = observed(estimate, config.spreadUnit, null) + " estimate"
        if (estimate > config.maxSpread) {
            return excluded(asset, exchange, pair, "backtest", "spread_wide", "$volumeObserved $spreadObserved", spreadThreshold, openPosition)
        }
        return enabled(asset, exchange, pair, "backtest", "spread_estimate", "$volumeObserved $spreadObserved", "$volumeThreshold $spreadThreshold", openPosition)
    }

    private data class Identity(
        val instrumentId: String? = null,
        val symbol: String? = null,
        val exchangeName: String = "",
        val hidden: Boolean? = null,
        val delisted: Boolean? = null,
        val activeInPlatform: Boolean? = null,
        val currentlyTradable: Boolean? = null,
        val exchangeOpen: Boolean? = null,
        val reason: String? = null,
        val observed: String = "",
    )

    private fun identify(config: SpotSelection, asset: String, page: CatalogPage): Identity {
        if (config.exchange != "etoro") {
            return Identity(reason = "exchange_unsupported", observed = config.exchange)
        }
        if (!page.complete) {
            return Identity(reason = "catalog_incomplete")
        }
        val spot = page.rows.filter { it.assetClass == "Crypto" && !it.symbol.contains('.') }
        val pairs = spot.mapNotNull { row ->
            val suffix = quoteSuffix(asset, row.symbol) ?: return@mapNotNull null
            row to suffix
        }
        val wanted = pairs.filter { it.second == config.quoteCurrency }
        val ids = wanted.map { it.first.instrumentId }.distinct()
        if (ids.size > 1) {
            return Identity(reason = "ambiguous", observed = ids.joinToString(","))
        }
        if (ids.isEmpty()) {
            val other = pairs.firstOrNull { it.second.isNotEmpty() && it.second != config.quoteCurrency }
            return if (other == null) {
                Identity(reason = "missing", observed = "quote=${config.quoteCurrency}")
            } else {
                Identity(reason = "quote_absent", observed = "other=${other.first.symbol}")
            }
        }
        val chosen = wanted.first().first
        return Identity(
            instrumentId = chosen.instrumentId,
            symbol = chosen.symbol,
            exchangeName = chosen.exchangeName,
            hidden = chosen.hidden,
            delisted = chosen.delisted,
            activeInPlatform = chosen.activeInPlatform,
            currentlyTradable = chosen.currentlyTradable,
            exchangeOpen = chosen.exchangeOpen,
        )
    }

    private fun quoteSuffix(asset: String, symbol: String): String? {
        if (!symbol.startsWith(asset)) {
            return null
        }
        val rest = symbol.removePrefix(asset)
        if (rest.isEmpty()) {
            return ""
        }
        if (rest.length !in 3..4 || rest.any { !it.isLetter() }) {
            return null
        }
        return rest
    }

    private fun knowableEnd(config: SpotSelection, asOf: Instant): Instant {
        val closed = if (asOf.epochSecond % config.barSeconds == 0L) {
            asOf
        } else {
            Instant.ofEpochSecond(asOf.epochSecond - asOf.epochSecond % config.barSeconds)
        }
        return if (closed.isAfter(config.historyEnd)) config.historyEnd else closed
    }

    private fun historyGap(config: SpotSelection, bars: List<SpotBar>, coveredUntil: Instant): String? {
        if (!coveredUntil.isAfter(config.historyStart)) {
            return "history_gap"
        }
        val span = coveredUntil.epochSecond - config.historyStart.epochSecond
        if (span % config.barSeconds != 0L) {
            return "history_unaligned"
        }
        val expected = (span / config.barSeconds).toInt()
        val inside = bars.filter { !it.start.isBefore(config.historyStart) && !it.end.isAfter(coveredUntil) }
        val starts = inside.groupBy { it.start }
        if (starts.size != expected || starts.values.any { it.size != 1 }) {
            return "history_gap"
        }
        var cursor = config.historyStart
        repeat(expected) {
            val bar = starts.getValue(cursor).single()
            if (bar.end != cursor.plusSeconds(config.barSeconds)) {
                return "history_gap"
            }
            cursor = bar.end
        }
        return null
    }

    private fun volumeBar(config: SpotSelection, mode: SpotMode, asOf: Instant, bars: List<SpotBar>): SpotBar? {
        val end = if (mode == SpotMode.BACKTEST) knowableEnd(config, asOf) else {
            val closed = if (asOf.epochSecond % config.barSeconds == 0L) asOf else Instant.ofEpochSecond(asOf.epochSecond - asOf.epochSecond % config.barSeconds)
            closed
        }
        return bars.filter { it.end == end && !it.end.isAfter(asOf) }.maxByOrNull { it.end }
    }

    private fun midRatio(bid: BigDecimal?, ask: BigDecimal?): BigDecimal? {
        if (bid == null || ask == null || bid.signum() <= 0 || ask < bid) {
            return null
        }
        val mid = bid.add(ask).divide(BigDecimal("2"), 8, RoundingMode.HALF_UP)
        if (mid.signum() <= 0) {
            return null
        }
        return ask.subtract(bid).divide(mid, 8, RoundingMode.HALF_UP)
    }

    private fun threshold(value: BigDecimal, unit: String, window: String?): String {
        val base = value.stripTrailingZeros().toPlainString() + " " + unit
        return if (window == null) base else "$base $window"
    }

    private fun observed(value: BigDecimal, unit: String, window: String?): String = threshold(value, unit, window)

    private fun excluded(
        ticker: String,
        exchange: String,
        pair: String,
        mode: String,
        reason: String,
        observed: String,
        threshold: String,
        openPosition: Boolean,
    ): SelectionRow = SelectionRow(
        ticker = ticker,
        exchange = exchange,
        pair = pair,
        mode = mode,
        outcome = "excluded",
        reason = reason,
        observed = observed,
        threshold = threshold,
        entryBlocked = true,
        exitBlocked = openPosition && false,
    )

    private fun enabled(
        ticker: String,
        exchange: String,
        pair: String,
        mode: String,
        reason: String,
        observed: String,
        threshold: String,
        openPosition: Boolean,
    ): SelectionRow = SelectionRow(
        ticker = ticker,
        exchange = exchange,
        pair = pair,
        mode = mode,
        outcome = "enabled",
        reason = reason,
        observed = observed,
        threshold = threshold,
        entryBlocked = false,
        exitBlocked = openPosition && false,
    )
}
