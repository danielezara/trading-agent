package com.tradingagent.modules.brokerread

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Duration

data class InstrumentClassification(
    val assetClass: String,
    val allowlist: String,
) {
    companion object {
        val UNKNOWN = InstrumentClassification("unknown", "unknown")
        const val ASSET_STOCK = "stock"
        const val ASSET_ETF = "etf"
        const val ASSET_OTHER = "other"
        const val ASSET_UNKNOWN = "unknown"
        const val ALLOW_INSIDE = "inside"
        const val ALLOW_OUTSIDE = "outside"
        const val ALLOW_UNKNOWN = "unknown"
    }
}

object DemoInstrumentClasses {
    val TYPES_ENDPOINT: URI = URI.create("https://public-api.etoro.com/api/v1/market-data/instrument-types")
    private val mapper: ObjectMapper = ObjectMapper()

    fun execute(
        transport: HttpGet,
        material: DemoKeyMaterial,
        instrumentIds: List<String>,
        sleep: (Duration) -> Unit = {},
    ): Map<String, InstrumentClassification>? {
        require(TYPES_ENDPOINT.scheme == "https") { "instrument types require https" }
        require(TYPES_ENDPOINT.host == "public-api.etoro.com") { "instrument types host is fixed" }
        require(TYPES_ENDPOINT.path == "/api/v1/market-data/instrument-types") { "instrument types path is fixed" }
        require(!TYPES_ENDPOINT.path.contains("/real")) { "real trading path is forbidden" }
        require(!TYPES_ENDPOINT.path.contains("/feeds")) { "news and social feeds are not read" }
        val ids = distinctIds(instrumentIds)
        if (ids.isEmpty()) {
            return emptyMap()
        }
        val typeIds = readTypeIds(transport, material, ids, sleep) ?: return null
        val descriptions = readDescriptions(transport, material, sleep) ?: emptyMap()
        return ids.associateWith { id -> classify(typeIds[id]?.let { descriptions[it] }) }
    }

    fun parseTypeIds(body: String): Map<String, Int?>? {
        val rows = arrayField(body, "instrumentDisplayDatas") ?: return null
        val types = linkedMapOf<String, Int?>()
        rows.forEach { node ->
            val instrument = positiveInt(node.get("instrumentID")) ?: return@forEach
            types.putIfAbsent(instrument.toString(), positiveInt(node.get("instrumentTypeID")))
        }
        return types
    }

    fun parseDescriptions(body: String): Map<Int, String>? {
        val rows = arrayField(body, "instrumentTypes") ?: return null
        val descriptions = linkedMapOf<Int, String>()
        rows.forEach { node ->
            val typeId = positiveInt(node.get("instrumentTypeID")) ?: return@forEach
            val description = node.get("instrumentTypeDescription")?.takeIf { it.isTextual }?.asText()?.trim().orEmpty()
            if (description.isNotEmpty()) {
                descriptions.putIfAbsent(typeId, description)
            }
        }
        return descriptions
    }

    fun classify(description: String?): InstrumentClassification = when (description) {
        "Stocks" -> InstrumentClassification(InstrumentClassification.ASSET_STOCK, InstrumentClassification.ALLOW_INSIDE)
        "ETF" -> InstrumentClassification(InstrumentClassification.ASSET_ETF, InstrumentClassification.ALLOW_INSIDE)
        null, "" -> InstrumentClassification.UNKNOWN
        else -> InstrumentClassification(InstrumentClassification.ASSET_OTHER, InstrumentClassification.ALLOW_OUTSIDE)
    }

    private fun readTypeIds(
        transport: HttpGet,
        material: DemoKeyMaterial,
        ids: List<String>,
        sleep: (Duration) -> Unit,
    ): Map<String, Int?>? {
        val body = readBody(transport, DemoInstrumentNames.requestUri(ids), material, sleep) ?: return null
        return parseTypeIds(body)
    }

    private fun readDescriptions(
        transport: HttpGet,
        material: DemoKeyMaterial,
        sleep: (Duration) -> Unit,
    ): Map<Int, String>? {
        val body = readBody(transport, TYPES_ENDPOINT, material, sleep) ?: return null
        return parseDescriptions(body)
    }

    private fun readBody(
        transport: HttpGet,
        uri: URI,
        material: DemoKeyMaterial,
        sleep: (Duration) -> Unit,
    ): String? {
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

    private fun arrayField(body: String, field: String): Iterable<JsonNode>? {
        if (body.isBlank()) {
            return null
        }
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return null
        }
        if (!root.isObject) {
            return null
        }
        val rows = root.get(field) ?: return null
        if (!rows.isArray) {
            return null
        }
        return rows
    }

    private fun positiveInt(node: JsonNode?): Int? {
        if (node == null || node.isNull || !node.isIntegralNumber) {
            return null
        }
        val value = node.asInt()
        return if (value > 0) value else null
    }

    private fun distinctIds(instrumentIds: List<String>): List<String> = instrumentIds
        .map { it.trim() }
        .filter { id -> id.toIntOrNull()?.let { it in 1..Int.MAX_VALUE } == true }
        .distinct()
        .take(MAX_INSTRUMENTS)

    private const val MAX_INSTRUMENTS: Int = 100
}

fun interface InstrumentClassRead {
    /** Null when the instrument payload is not read. A missing type is unknown, not guessed. */
    fun read(instrumentIds: List<String>): Map<String, InstrumentClassification>?
}

class DemoInstrumentClassRead(
    private val keys: () -> DemoKeyMaterial?,
    private val transport: HttpGet,
    private val sleep: (Duration) -> Unit = {},
) : InstrumentClassRead {
    override fun read(instrumentIds: List<String>): Map<String, InstrumentClassification>? {
        val material = keys() ?: return null
        return DemoInstrumentClasses.execute(transport, material, instrumentIds, sleep)
    }
}
