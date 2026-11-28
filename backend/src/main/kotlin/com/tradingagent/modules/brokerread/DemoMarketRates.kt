package com.tradingagent.modules.brokerread

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tradingagent.modules.ingestion.LivePriceBatch
import com.tradingagent.modules.ingestion.LivePriceLookup
import com.tradingagent.modules.ingestion.LiveRate
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.time.Instant

object DemoMarketRates {
    val ENDPOINT: URI = URI.create("https://public-api.etoro.com/api/v1/market-data/instruments/rates")
    private val mapper: ObjectMapper = ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    fun execute(
        transport: HttpGet,
        material: DemoKeyMaterial,
        instrumentIds: List<String>,
        sleep: (Duration) -> Unit = {},
    ): LivePriceBatch {
        val body = fetchBody(transport, material, instrumentIds, sleep) ?: return LivePriceBatch(emptyList(), 0)
        return parse(body)
    }

    fun readDisplay(
        transport: HttpGet,
        material: DemoKeyMaterial,
        instrumentIds: List<String>,
        sleep: (Duration) -> Unit = {},
    ): Map<String, DisplayQuote> {
        val body = fetchBody(transport, material, instrumentIds, sleep) ?: return emptyMap()
        return displayQuotes(body)
    }

    private fun fetchBody(
        transport: HttpGet,
        material: DemoKeyMaterial,
        instrumentIds: List<String>,
        sleep: (Duration) -> Unit,
    ): String? {
        require(ENDPOINT.scheme == "https") { "market data requires https" }
        require(ENDPOINT.host == "public-api.etoro.com") { "market data host is fixed" }
        require(ENDPOINT.path == "/api/v1/market-data/instruments/rates") { "market data path is the rates get" }
        require(!ENDPOINT.path.contains("/real")) { "real trading path is forbidden" }
        require(!ENDPOINT.path.contains("/feeds")) { "news and social feeds are not read" }
        val uri = requestUri(instrumentIds)
        val first = try {
            transport.get(uri, material.headers())
        } catch (_: Exception) {
            return null
        }
        val response = if (first.status == 429) {
            val delay = first.retryAfterSeconds
            if (delay != null && delay in 0..2) {
                sleep(Duration.ofSeconds(delay))
                try {
                    transport.get(uri, material.headers())
                } catch (_: Exception) {
                    return null
                }
            } else {
                return null
            }
        } else {
            first
        }
        if (response.status != 200) {
            return null
        }
        return response.body
    }

    fun requestUri(instrumentIds: List<String>): URI {
        val ids = instrumentIds
            .map { it.trim() }
            .filter { id -> id.toIntOrNull()?.let { it in 1..Int.MAX_VALUE } == true }
            .distinct()
            .take(MAX_INSTRUMENTS)
        require(ids.isNotEmpty()) { "instrument ids are required" }
        return URI.create("$ENDPOINT?instrumentIds=${ids.joinToString(",")}")
    }

    fun parse(body: String): LivePriceBatch {
        if (body.isBlank()) {
            return LivePriceBatch(emptyList(), 0)
        }
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return LivePriceBatch(emptyList(), 1)
        }
        val rates = root.get("rates")
        if (rates == null || !rates.isArray) {
            return LivePriceBatch(emptyList(), 1)
        }
        val parsed = mutableListOf<LiveRate>()
        var rejected = 0
        rates.forEach { node ->
            val rate = readRate(node)
            if (rate == null) rejected += 1 else parsed += rate
        }
        return LivePriceBatch(parsed, rejected)
    }

    private fun readRate(node: JsonNode): LiveRate? {
        val instrument = node.get("instrumentID")?.takeIf { it.isIntegralNumber }?.asInt()
        if (instrument == null || instrument <= 0) {
            return null
        }
        val priceNode = node.get("lastExecution") ?: return null
        if (!priceNode.isNumber) {
            return null
        }
        val price = priceNode.decimalValue()
        if (price.compareTo(BigDecimal.ZERO) < 0) {
            return null
        }
        val eventTime = node.get("date")?.takeIf { it.isTextual }?.asText()?.let { text ->
            runCatching { Instant.parse(text) }.getOrNull()
        } ?: return null
        return LiveRate(instrument.toString(), price, eventTime)
    }

    fun displayQuotes(body: String): Map<String, DisplayQuote> {
        if (body.isBlank()) {
            return emptyMap()
        }
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return emptyMap()
        }
        val rates = root.get("rates")
        if (rates == null || !rates.isArray) {
            return emptyMap()
        }
        val kept = linkedMapOf<String, DisplayQuote>()
        rates.forEach { node ->
            val quote = readDisplayQuote(node) ?: return@forEach
            val previous = kept[quote.instrumentId]
            if (previous == null || prefer(quote, previous)) {
                kept[quote.instrumentId] = quote
            }
        }
        return kept
    }

    private fun readDisplayQuote(node: JsonNode): DisplayQuote? {
        val instrument = node.get("instrumentID")?.takeIf { it.isIntegralNumber }?.asInt()
        if (instrument == null || instrument <= 0) {
            return null
        }
        val bid = optionalDecimal(node, "bid")
        val last = optionalDecimal(node, "lastExecution")
        val price = bid ?: last
        val quotedAt = node.get("date")?.takeIf { it.isTextual }?.asText()?.let { text ->
            runCatching { Instant.parse(text) }.getOrNull()
        }
        if (price == null && quotedAt == null) {
            return null
        }
        return DisplayQuote(instrument.toString(), price, quotedAt)
    }

    private fun optionalDecimal(node: JsonNode, field: String): BigDecimal? {
        val raw = node.get(field) ?: return null
        if (raw.isNull || !raw.isNumber) {
            return null
        }
        val value = raw.decimalValue()
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return null
        }
        return value
    }

    private fun prefer(candidate: DisplayQuote, current: DisplayQuote): Boolean {
        val candidateTime = candidate.quotedAt
        val currentTime = current.quotedAt
        if (candidateTime != null && (currentTime == null || candidateTime.isAfter(currentTime))) {
            return true
        }
        return candidateTime == currentTime && current.price == null && candidate.price != null
    }

    private const val MAX_INSTRUMENTS: Int = 100
}

data class DisplayQuote(
    val instrumentId: String,
    val price: BigDecimal?,
    val quotedAt: Instant?,
)

fun interface PositionQuoteRead {
    /** Null when the rates GET is not attempted. A missing instrument is absent from the map. */
    fun read(instrumentIds: List<String>): Map<String, DisplayQuote>?
}

class DemoPositionQuotes(
    private val keys: () -> DemoKeyMaterial?,
    private val transport: HttpGet,
    private val sleep: (Duration) -> Unit = {},
) : PositionQuoteRead {
    override fun read(instrumentIds: List<String>): Map<String, DisplayQuote>? {
        val material = keys() ?: return null
        val ids = instrumentIds
            .map { it.trim() }
            .filter { id -> id.toIntOrNull()?.let { it in 1..Int.MAX_VALUE } == true }
            .distinct()
            .take(100)
        if (ids.isEmpty()) {
            return emptyMap()
        }
        return DemoMarketRates.readDisplay(transport, material, ids, sleep)
    }
}

class DemoMarketRatesLookup(
    private val keys: () -> DemoKeyMaterial?,
    private val transport: HttpGet,
    private val sleep: (Duration) -> Unit = {},
) : LivePriceLookup {
    override fun lookup(instrumentIds: List<String>): LivePriceBatch? {
        val material = keys() ?: return null
        if (instrumentIds.isEmpty()) {
            return LivePriceBatch(emptyList(), 0)
        }
        return DemoMarketRates.execute(transport, material, instrumentIds, sleep)
    }
}
