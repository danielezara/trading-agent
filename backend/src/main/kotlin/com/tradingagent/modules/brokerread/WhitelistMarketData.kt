package com.tradingagent.modules.brokerread

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tradingagent.modules.configuration.SpotSelection
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

data class WhitelistCandle(
    val start: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val forming: Boolean,
)

data class WhitelistCard(
    val ticker: String,
    val symbol: String?,
    val instrumentId: String?,
    val reason: String?,
    val candles: List<WhitelistCandle>,
    val price: String?,
    val stale: Boolean,
    val quotedAt: String?,
)

/**
 * Independent per-ticker Demo market reads for the console UI.
 * Uses only the free public Demo API over HTTPS.
 */
class WhitelistMarketData(
    private val keys: () -> DemoKeyMaterial?,
    private val transport: HttpGet,
    private val clock: () -> Instant = Instant::now,
    private val maxParallel: Int = 6,
) {
    private val slots = ConcurrentHashMap<String, Slot>()
    private val pool = Executors.newFixedThreadPool(maxParallel)

    fun snapshot(): List<WhitelistCard> {
        val material = keys()
        if (material == null) {
            return SpotSelection.FIXED_ASSETS.map { empty(it, "Chiavi non disponibili") }
        }
        val at = clock()
        val due = SpotSelection.FIXED_ASSETS.filter { ticker -> due(ticker, at) }.take(maxParallel)
        val jobs = due.map { ticker -> pool.submit<Unit> { refresh(ticker, material, at) } }
        jobs.forEach { job -> await(job) }
        return SpotSelection.FIXED_ASSETS.map { ticker ->
            slots[ticker]?.card ?: empty(ticker, "Lettura in corso")
        }
    }

    private fun refresh(ticker: String, material: DemoKeyMaterial, at: Instant) {
        val previous = slots[ticker]
        if (!catalogFresh(previous, at)) {
            val search = read(transport, material, SpotCatalog.uri(ticker))
            if (search == null) {
                if (previous?.card?.candles?.isNotEmpty() == true) {
                    slots[ticker] = previous.copy(
                        card = previous.card.copy(stale = true, reason = "Ultimo disponibile"),
                        catalogAt = at,
                        pauseUntil = at.plusSeconds(FAIL_COOLDOWN_SECONDS),
                    )
                } else {
                    slots[ticker] = Slot(
                        card = empty(ticker, "Ricerca non disponibile"),
                        catalogAt = at,
                        candleAt = null,
                        pauseUntil = at.plusSeconds(FAIL_COOLDOWN_SECONDS),
                    )
                }
                return
            }
            val pair = euroPair(ticker, SpotCatalog.parse(search))
            if (pair == null) {
                slots[ticker] = Slot(
                    card = empty(ticker, "Coppia EUR assente"),
                    catalogAt = at,
                    candleAt = null,
                    pauseUntil = Instant.EPOCH,
                )
                return
            }
            slots[ticker] = Slot(
                card = WhitelistCard(
                    ticker = ticker,
                    symbol = pair.first,
                    instrumentId = pair.second,
                    reason = if (previous?.card?.candles?.isNotEmpty() == true) null else "Candele non disponibili",
                    candles = previous?.card?.candles.orEmpty(),
                    price = previous?.card?.price,
                    stale = previous?.card?.stale == true,
                    quotedAt = previous?.card?.quotedAt,
                ),
                catalogAt = at,
                candleAt = previous?.candleAt,
                pauseUntil = Instant.EPOCH,
            )
        }
        val ready = slots[ticker] ?: return
        val instrumentId = ready.card.instrumentId ?: return
        if (!candlesDue(ready, at)) {
            return
        }
        val body = read(transport, material, SpotCatalog.candlesUri(instrumentId, "FiveMinutes", CANDLE_COUNT))
        val candles = body?.let { parseCandles(it, at) }
        if (candles != null && candles.isNotEmpty()) {
            slots[ticker] = ready.copy(
                card = WhitelistCard(
                    ticker = ticker,
                    symbol = ready.card.symbol,
                    instrumentId = instrumentId,
                    reason = null,
                    candles = candles,
                    price = candles.last().close.toString(),
                    stale = false,
                    quotedAt = at.toString(),
                ),
                candleAt = at,
                pauseUntil = Instant.EPOCH,
            )
            return
        }
        val kept = if (ready.card.candles.isNotEmpty() && ready.card.price != null) {
            ready.card.copy(stale = true, reason = "Ultimo disponibile")
        } else {
            ready.card.copy(
                candles = emptyList(),
                price = null,
                stale = false,
                reason = if (body == null) "Candele non disponibili" else "Nessuna candela",
            )
        }
        slots[ticker] = ready.copy(
            card = kept,
            candleAt = at,
            pauseUntil = at.plusSeconds(FAIL_COOLDOWN_SECONDS),
        )
    }

    private fun due(ticker: String, at: Instant): Boolean {
        val slot = slots[ticker]
        if (slot != null && at.isBefore(slot.pauseUntil)) {
            return false
        }
        if (!catalogFresh(slot, at)) {
            return true
        }
        return slot!!.card.instrumentId != null && candlesDue(slot, at)
    }

    private fun catalogFresh(slot: Slot?, at: Instant): Boolean {
        if (slot == null) {
            return false
        }
        val ttl = if (slot.card.reason == "Ricerca non disponibile") FAIL_COOLDOWN_SECONDS else CATALOG_TTL_SECONDS
        return at.epochSecond - slot.catalogAt.epochSecond < ttl
    }

    private fun candlesDue(slot: Slot, at: Instant): Boolean =
        slot.candleAt == null || at.epochSecond - slot.candleAt.epochSecond >= CANDLE_TTL_SECONDS

    private data class Slot(
        val card: WhitelistCard,
        val catalogAt: Instant,
        val candleAt: Instant?,
        val pauseUntil: Instant,
    )

    companion object {
        private const val CANDLE_COUNT: Long = 30
        private const val CATALOG_TTL_SECONDS: Long = 600
        private const val CANDLE_TTL_SECONDS: Long = 20
        private const val FAIL_COOLDOWN_SECONDS: Long = 30
        private val mapper: ObjectMapper = ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

        fun euroPair(asset: String, page: CatalogPage): Pair<String, String>? {
            if (!page.complete) {
                return null
            }
            val pairs = page.rows.filter { row ->
                row.assetClass.equals("Crypto", ignoreCase = true) &&
                    row.symbol.startsWith(asset) &&
                    row.symbol.removePrefix(asset) == "EUR" &&
                    !row.symbol.contains('.')
            }
            val ids = pairs.map { it.instrumentId }.distinct()
            if (ids.size != 1) {
                return null
            }
            return pairs.first().symbol to ids.first()
        }

        fun parseCandles(body: String, now: Instant): List<WhitelistCandle> {
            val root = try {
                mapper.readTree(body)
            } catch (_: Exception) {
                return emptyList()
            }
            if (!root.isObject) {
                return emptyList()
            }
            val interval = root.get("interval")?.takeIf { it.isTextual }?.asText()
            if (interval != null && interval != "FiveMinutes") {
                return emptyList()
            }
            val groups = root.get("candles") ?: return emptyList()
            if (!groups.isArray) {
                return emptyList()
            }
            val bars = mutableListOf<WhitelistCandle>()
            groups.forEach { group ->
                val rows = group.get("candles") ?: return@forEach
                if (!rows.isArray) {
                    return@forEach
                }
                rows.forEach { row -> candle(row, now)?.let { bars += it } }
            }
            return bars.sortedBy { it.start }.distinctBy { it.start }
        }

        private fun candle(node: JsonNode, now: Instant): WhitelistCandle? {
            if (!node.isObject) {
                return null
            }
            val startText = node.get("fromDate")?.takeIf { it.isTextual }?.asText() ?: return null
            val start = runCatching { Instant.parse(startText) }.getOrNull() ?: return null
            if (start.isAfter(now)) {
                return null
            }
            val open = price(node.get("open")) ?: return null
            val high = price(node.get("high")) ?: return null
            val low = price(node.get("low")) ?: return null
            val close = price(node.get("close")) ?: return null
            if (low < 0 || low > open || low > close || high < open || high < close) {
                return null
            }
            return WhitelistCandle(
                start = startText,
                open = open.toDouble(),
                high = high.toDouble(),
                low = low.toDouble(),
                close = close.toDouble(),
                forming = start.plusSeconds(300).isAfter(now),
            )
        }

        private fun price(node: JsonNode?): BigDecimal? {
            val value = when {
                node == null || node.isNull -> return null
                node.isNumber -> node.decimalValue()
                node.isTextual -> node.asText().trim().toBigDecimalOrNull() ?: return null
                else -> return null
            }
            return value.takeIf { it.signum() >= 0 }
        }

        private fun empty(ticker: String, reason: String) = WhitelistCard(
            ticker = ticker,
            symbol = null,
            instrumentId = null,
            reason = reason,
            candles = emptyList(),
            price = null,
            stale = false,
            quotedAt = null,
        )

        private fun read(transport: HttpGet, material: DemoKeyMaterial, uri: java.net.URI): String? {
            val response = try {
                transport.get(uri, material.headers())
            } catch (_: Exception) {
                return null
            }
            if (response.status != 200 || response.body.isBlank()) {
                return null
            }
            return response.body
        }

        private fun await(job: Future<Unit>) {
            try {
                job.get(12, TimeUnit.SECONDS)
            } catch (_: Exception) {
                job.cancel(true)
            }
        }
    }
}
