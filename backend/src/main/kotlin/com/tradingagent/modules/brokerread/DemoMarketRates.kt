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
        require(ENDPOINT.scheme == "https") { "market data requires https" }
        require(ENDPOINT.host == "public-api.etoro.com") { "market data host is fixed" }
        require(ENDPOINT.path == "/api/v1/market-data/instruments/rates") { "market data path is the rates get" }
        require(!ENDPOINT.path.contains("/real")) { "real trading path is forbidden" }
        val uri = requestUri(instrumentIds)
        val first = try {
            transport.get(uri, material.headers())
        } catch (_: Exception) {
            return LivePriceBatch(emptyList(), 0)
        }
        val response = if (first.status == 429) {
            val delay = first.retryAfterSeconds
            if (delay != null && delay in 0..2) {
                sleep(Duration.ofSeconds(delay))
                try {
                    transport.get(uri, material.headers())
                } catch (_: Exception) {
                    return LivePriceBatch(emptyList(), 0)
                }
            } else {
                return LivePriceBatch(emptyList(), 0)
            }
        } else {
            first
        }
        if (response.status != 200) {
            return LivePriceBatch(emptyList(), 0)
        }
        return parse(response.body)
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

    private const val MAX_INSTRUMENTS: Int = 100
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
