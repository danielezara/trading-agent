package com.tradingagent.modules.brokerread

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Duration
import java.time.Instant

enum class AdvertisedLeverage { ABSENT, ONE, REJECTED }

data class SymbolResolution(val instrumentId: String, val includeLeverage: Boolean)

fun interface EntryCandidates {
    fun load(now: Instant, heldInstrumentIds: Set<String>): List<ObservedInstrumentBars>
}

object DemoSymbolLookup {
    const val SEARCH: String = "https://public-api.etoro.com/api/v1/market-data/search"
    private val symbolPattern = Regex("[A-Z0-9.]{1,16}")
    private val mapper: ObjectMapper = ObjectMapper()

    fun uri(symbol: String): URI {
        require(symbolPattern.matches(symbol)) { "entry symbol is not usable" }
        val uri = URI.create("$SEARCH?internalSymbolFull=$symbol&pageSize=5")
        require(uri.scheme == "https") { "symbol lookup requires https" }
        require(uri.host == "public-api.etoro.com") { "symbol lookup host is fixed" }
        require(uri.path == "/api/v1/market-data/search") { "symbol lookup path is the search get" }
        require(!uri.path.contains("real")) { "real trading path is forbidden" }
        require(!uri.path.contains("/feeds")) { "news and social feeds are not read" }
        return uri
    }

    fun parseId(body: String, symbol: String): String? = parse(body, symbol)?.instrumentId

    fun parse(body: String, symbol: String): SymbolResolution? {
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return null
        }
        val items = root.get("items") ?: return null
        if (!items.isArray) {
            return null
        }
        items.forEach { item ->
            if (item.get("internalSymbolFull")?.takeIf { it.isTextual }?.asText() != symbol) {
                return@forEach
            }
            if (item.get("isHiddenFromClient")?.asBoolean() == true) {
                return@forEach
            }
            val id = positive(item.get("instrumentId")) ?: return@forEach
            val internal = positive(item.get("internalInstrumentId"))
            if (internal != null && internal != id) {
                return@forEach
            }
            return when (advertisedLeverage(item)) {
                AdvertisedLeverage.REJECTED -> null
                AdvertisedLeverage.ONE -> SymbolResolution(id.toString(), includeLeverage = true)
                AdvertisedLeverage.ABSENT -> SymbolResolution(id.toString(), includeLeverage = false)
            }
        }
        return null
    }

    fun advertisedLeverage(item: com.fasterxml.jackson.databind.JsonNode): AdvertisedLeverage {
        if (!item.isObject) {
            return AdvertisedLeverage.ABSENT
        }
        val fields = item.properties().asSequence()
            .filter { it.key.contains("leverage", ignoreCase = true) }
            .map { it.value }
            .toList()
        if (fields.isEmpty()) {
            return AdvertisedLeverage.ABSENT
        }
        return if (fields.any(::allowsOne)) AdvertisedLeverage.ONE else AdvertisedLeverage.REJECTED
    }

    private fun allowsOne(node: com.fasterxml.jackson.databind.JsonNode): Boolean = when {
        node.isIntegralNumber -> node.asInt() == 1
        node.isFloatingPointNumber -> node.asDouble() == 1.0
        node.isTextual -> node.asText().trim() == "1"
        node.isArray -> node.any(::allowsOne)
        node.isObject -> {
            val min = node.get("min")?.takeIf { it.isNumber }?.asDouble()
            val max = node.get("max")?.takeIf { it.isNumber }?.asDouble()
            if (min != null && max != null) {
                min <= 1.0 && 1.0 <= max
            } else {
                node.any(::allowsOne)
            }
        }
        else -> false
    }

    private fun positive(node: com.fasterxml.jackson.databind.JsonNode?): Int? {
        val value = node?.takeIf { it.isIntegralNumber }?.asInt() ?: return null
        return value.takeIf { it > 0 }
    }
}

class DemoEntryBarRead(
    private val symbols: () -> List<String>,
    private val keys: () -> DemoKeyMaterial?,
    private val transport: HttpGet,
    private val sleep: (Duration) -> Unit = {},
) : EntryCandidates {
    override fun load(now: Instant, heldInstrumentIds: Set<String>): List<ObservedInstrumentBars> {
        val material = keys() ?: return emptyList()
        val openIds = openInstrumentIds(material) ?: return emptyList()
        val skip = heldInstrumentIds + openIds
        val resolved = symbols().mapNotNull { symbol -> resolve(material, symbol) }
            .distinctBy { it.instrumentId }
            .filter { it.instrumentId !in skip }
            .take(MAX_SYMBOLS)
        if (resolved.isEmpty()) {
            return emptyList()
        }
        val classes = DemoInstrumentClasses.execute(transport, material, resolved.map { it.instrumentId }, sleep).orEmpty()
        return resolved.map { symbol ->
            val classification = classes[symbol.instrumentId]
            val asset = if (classification?.officialType == "Crypto") "crypto" else classification?.assetClass ?: "unknown"
            val bars = DemoClosedBars.barsFor(transport, material, symbol.instrumentId, now, sleep)
            pause()
            ObservedInstrumentBars(symbol.instrumentId, asset, bars, symbol.includeLeverage)
        }
    }

    private fun openInstrumentIds(material: DemoKeyMaterial): Set<String>? {
        val ids = when (val outcome = DemoPortfolioRead.execute(transport, material, sleep)) {
            is ReadOutcome.Pass -> outcome.account.positions.orEmpty().map { it.instrumentId }.toSet()
            else -> return null
        }
        pause()
        return ids
    }

    private fun resolve(material: DemoKeyMaterial, symbol: String): SymbolResolution? {
        if (!SYMBOL.matches(symbol)) {
            return null
        }
        val body = read(material, DemoSymbolLookup.uri(symbol)) ?: return null
        pause()
        return DemoSymbolLookup.parse(body, symbol)
    }

    private fun pause() {
        sleep(Duration.ofMillis(200))
    }

    private fun read(material: DemoKeyMaterial, uri: URI): String? {
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

    private companion object {
        const val MAX_SYMBOLS: Int = 8
        val SYMBOL: Regex = Regex("[A-Z0-9.]{1,16}")
    }
}
