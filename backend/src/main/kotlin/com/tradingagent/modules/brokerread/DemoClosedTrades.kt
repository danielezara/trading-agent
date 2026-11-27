package com.tradingagent.modules.brokerread

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class ClosedTrade(
    val positionId: String,
    val instrumentId: String?,
    val side: String?,
    val quantity: String?,
    val netProfit: String?,
    val closedAt: String?,
)

object DemoClosedTrades {
    val ENDPOINT: URI = URI.create("https://public-api.etoro.com/api/v1/trading/info/trade/demo/history")
    private val mapper: ObjectMapper = ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    fun execute(
        transport: HttpGet,
        material: DemoKeyMaterial,
        now: Instant,
        sleep: (Duration) -> Unit = {},
    ): List<ClosedTrade>? {
        require(ENDPOINT.scheme == "https") { "demo history requires https" }
        require(ENDPOINT.host == "public-api.etoro.com") { "demo history host is fixed" }
        require(ENDPOINT.path == "/api/v1/trading/info/trade/demo/history") { "demo history path is fixed" }
        require(ENDPOINT.path.contains("/demo/")) { "demo history path is required" }
        require(!ENDPOINT.path.contains("/real")) { "real trading path is forbidden" }
        val uri = requestUri(now)
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
        return parse(response.body)
    }

    fun requestUri(now: Instant): URI {
        val minDate = LocalDate.ofInstant(now, ZoneOffset.UTC).minusYears(1).plusDays(1)
        return URI.create("$ENDPOINT?minDate=$minDate&page=1&pageSize=$PAGE_SIZE")
    }

    fun parse(body: String): List<ClosedTrade>? {
        if (body.isBlank()) {
            return null
        }
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return null
        }
        if (!root.isArray) {
            return null
        }
        val rows = linkedMapOf<String, ClosedTrade>()
        root.forEach { node ->
            val trade = readTrade(node) ?: return@forEach
            if (!rows.containsKey(trade.positionId)) {
                rows[trade.positionId] = trade
            }
        }
        return rows.values.toList()
    }

    private fun readTrade(node: JsonNode): ClosedTrade? {
        if (!node.isObject) {
            return null
        }
        val positionId = positiveIntegral(node.get("positionId")) ?: return null
        return ClosedTrade(
            positionId = positionId,
            instrumentId = positiveIntegral(node.get("instrumentId")),
            side = side(node.get("isBuy")),
            quantity = quantity(node.get("units")),
            netProfit = decimal(node.get("netProfit")),
            closedAt = timestamp(node.get("closeTimestamp")),
        )
    }

    private fun positiveIntegral(node: JsonNode?): String? {
        if (node == null || node.isNull || !node.isIntegralNumber) {
            return null
        }
        val text = node.asText().trim()
        if (text.isEmpty() || text.any { !it.isDigit() } || text.all { it == '0' }) {
            return null
        }
        return text.trimStart('0')
    }

    private fun side(node: JsonNode?): String? = when {
        node == null || !node.isBoolean -> null
        node.booleanValue() -> "BUY"
        else -> "SELL"
    }

    private fun quantity(node: JsonNode?): String? {
        val text = decimal(node) ?: return null
        if (text.startsWith("-")) {
            return null
        }
        return text
    }

    private fun decimal(node: JsonNode?): String? {
        if (node == null || node.isNull || !node.isNumber) {
            return null
        }
        val value = node.decimalValue()
        if (value.compareTo(BigDecimal.ZERO) == 0) {
            return "0"
        }
        return value.stripTrailingZeros().toPlainString()
    }

    private fun timestamp(node: JsonNode?): String? {
        if (node == null || !node.isTextual) {
            return null
        }
        val text = node.asText().trim()
        return try {
            Instant.parse(text)
            text
        } catch (_: Exception) {
            null
        }
    }

    private const val PAGE_SIZE: Int = 100
}

fun interface ClosedTradeRead {
    /** Null when the demo history GET is not attempted or does not return a list. */
    fun read(now: Instant): List<ClosedTrade>?
}

class DemoClosedTradeRead(
    private val keys: () -> DemoKeyMaterial?,
    private val transport: HttpGet,
    private val sleep: (Duration) -> Unit = {},
) : ClosedTradeRead {
    override fun read(now: Instant): List<ClosedTrade>? {
        val material = keys() ?: return null
        return DemoClosedTrades.execute(transport, material, now, sleep)
    }
}
