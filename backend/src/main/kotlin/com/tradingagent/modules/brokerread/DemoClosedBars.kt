package com.tradingagent.modules.brokerread

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.time.Instant

data class ObservedBar(
    val start: Instant,
    val end: Instant,
    val closeMinor: Long,
    val highMinor: Long,
    val lowMinor: Long,
)

data class ObservedInstrumentBars(
    val instrumentId: String,
    val assetClass: String,
    val bars: List<ObservedBar>,
    val includeLeverage: Boolean = false,
)

data class ObservedBars(
    val openPositions: Int,
    val instruments: List<ObservedInstrumentBars>,
    val bookLossMinor: Long? = null,
    val bookLossCurrency: String? = null,
)

fun interface ClosedBarSource {
    /** Null when the candle GET is not attempted. An empty instrument list is a real read with nothing to keep. */
    fun load(now: Instant): ObservedBars?
}

object DemoClosedBars {
    const val INTERVAL: String = "FiveMinutes"
    const val CANDLE_COUNT: Int = 30
    const val BAR_SECONDS: Long = 300
    private const val PRICE_SCALE: Int = 8
    private const val CANDLES: String =
        "https://public-api.etoro.com/api/v1/market-data/instruments/%s/history/candles/desc/FiveMinutes/30"
    private val mapper: ObjectMapper = ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private val instrumentId = Regex("[1-9][0-9]{0,9}")

    fun read(
        transport: HttpGet,
        material: DemoKeyMaterial,
        now: Instant,
        sleep: (Duration) -> Unit = {},
    ): ObservedBars {
        val account = when (val outcome = DemoPortfolioRead.execute(transport, material, sleep)) {
            is ReadOutcome.Pass -> outcome.account
            else -> return ObservedBars(0, emptyList())
        }
        val ids = account.positions.orEmpty()
            .map { it.instrumentId }
            .filter { validId(it) }
            .distinct()
            .sorted()
            .take(MAX_INSTRUMENTS)
        val classes = if (ids.isEmpty()) {
            emptyMap()
        } else {
            DemoInstrumentClasses.execute(transport, material, ids, sleep).orEmpty()
        }
        val instruments = ids.map { id ->
            val body = fetch(transport, material, id, sleep)
            val bars = if (body == null) emptyList() else select(parse(body), now)
            val assetClass = classes[id]?.assetClass ?: "unknown"
            ObservedInstrumentBars(id, assetClass, bars)
        }
        val loss = OpenBookLoss.of(account.positions)
        return ObservedBars(
            account.positionCount ?: ids.size,
            instruments,
            loss?.minorUnits,
            loss?.currency,
        )
    }

    fun barsFor(
        transport: HttpGet,
        material: DemoKeyMaterial,
        id: String,
        now: Instant,
        sleep: (Duration) -> Unit = {},
    ): List<ObservedBar> {
        if (!validId(id)) {
            return emptyList()
        }
        val body = fetch(transport, material, id, sleep) ?: return emptyList()
        return select(parse(body), now)
    }

    fun candlesUri(id: String): URI {
        require(validId(id)) { "instrument id is not a positive integer" }
        val uri = URI.create(CANDLES.format(id))
        require(uri.scheme == "https") { "candles require https" }
        require(uri.host == "public-api.etoro.com") { "candles host is fixed" }
        require(uri.path == "/api/v1/market-data/instruments/$id/history/candles/desc/$INTERVAL/$CANDLE_COUNT") {
            "candles path is the five minute history"
        }
        require(!uri.path.contains("real")) { "real trading path is forbidden" }
        require(!uri.path.contains("/feeds")) { "news and social feeds are not read" }
        return uri
    }

    fun parse(body: String): List<ObservedBar> {
        if (body.isBlank()) {
            return emptyList()
        }
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return emptyList()
        }
        if (!root.isObject) {
            return emptyList()
        }
        val interval = root.get("interval")?.takeIf { it.isTextual }?.asText()
        if (interval != null && interval != INTERVAL) {
            return emptyList()
        }
        val groups = root.get("candles")
        if (groups == null || !groups.isArray) {
            return emptyList()
        }
        val parsed = mutableListOf<ObservedBar>()
        groups.forEach { group ->
            val rows = group.get("candles")
            if (rows != null && rows.isArray) {
                rows.forEach { row -> readBar(row)?.let { parsed += it } }
            }
        }
        return parsed
    }

    fun select(bars: List<ObservedBar>, now: Instant): List<ObservedBar> {
        val closed = bars.filter { !it.end.isAfter(now) }.sortedBy { it.start }
        if (closed.isEmpty()) {
            return emptyList()
        }
        val unique = linkedMapOf<Instant, ObservedBar>()
        for (bar in closed) {
            val previous = unique[bar.start]
            if (previous == null) {
                unique[bar.start] = bar
            } else if (previous != bar) {
                return emptyList()
            }
        }
        val ordered = unique.values.toList()
        var startIndex = ordered.lastIndex
        for (index in ordered.lastIndex downTo 1) {
            if (ordered[index - 1].end == ordered[index].start) {
                startIndex = index - 1
            } else {
                break
            }
        }
        return ordered.subList(startIndex, ordered.size)
    }

    private fun fetch(
        transport: HttpGet,
        material: DemoKeyMaterial,
        id: String,
        sleep: (Duration) -> Unit,
    ): String? {
        val uri = candlesUri(id)
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
        if (response.status != 200 || response.body.isBlank()) {
            return null
        }
        return response.body
    }

    private fun readBar(node: JsonNode): ObservedBar? {
        if (!node.isObject) {
            return null
        }
        val start = node.get("fromDate")?.takeIf { it.isTextual }?.asText()?.let { text ->
            runCatching { Instant.parse(text) }.getOrNull()
        } ?: return null
        val close = price(node.get("close")) ?: return null
        val high = price(node.get("high")) ?: return null
        val low = price(node.get("low")) ?: return null
        if (low < 0 || close < low || high < close) {
            return null
        }
        return ObservedBar(start, start.plusSeconds(BAR_SECONDS), close, high, low)
    }

    private fun price(node: JsonNode?): Long? {
        val decimal = when {
            node == null || node.isNull -> return null
            node.isNumber -> node.decimalValue()
            node.isTextual -> node.asText().trim().toBigDecimalOrNull() ?: return null
            else -> return null
        }
        if (decimal.signum() < 0) {
            return null
        }
        val scaled = decimal.movePointRight(PRICE_SCALE).stripTrailingZeros()
        if (scaled.scale() > 0) {
            return null
        }
        if (scaled.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            return null
        }
        return scaled.longValueExact()
    }

    private fun validId(id: String): Boolean {
        if (!instrumentId.matches(id)) {
            return false
        }
        val value = id.toLongOrNull() ?: return false
        return value in 1..Int.MAX_VALUE
    }

    private const val MAX_INSTRUMENTS: Int = 20
}

class DemoClosedBarRead(
    private val keys: () -> DemoKeyMaterial?,
    private val transport: HttpGet,
    private val sleep: (Duration) -> Unit = {},
) : ClosedBarSource {
    override fun load(now: Instant): ObservedBars? {
        val material = keys() ?: return null
        return DemoClosedBars.read(transport, material, now, sleep)
    }
}
