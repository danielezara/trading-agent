package com.tradingagent.modules.brokerread

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Duration

object DemoInstrumentNames {
    val ENDPOINT: URI = URI.create("https://public-api.etoro.com/api/v1/market-data/instruments")
    private val mapper: ObjectMapper = ObjectMapper()

    fun execute(
        transport: HttpGet,
        material: DemoKeyMaterial,
        instrumentIds: List<String>,
        sleep: (Duration) -> Unit = {},
    ): Map<String, String> {
        require(ENDPOINT.scheme == "https") { "instrument metadata requires https" }
        require(ENDPOINT.host == "public-api.etoro.com") { "instrument metadata host is fixed" }
        require(ENDPOINT.path == "/api/v1/market-data/instruments") { "instrument metadata path is the instruments get" }
        require(!ENDPOINT.path.contains("/rates")) { "instrument metadata is not the rates get" }
        require(!ENDPOINT.path.contains("/real")) { "real trading path is forbidden" }
        require(!ENDPOINT.path.contains("/feeds")) { "news and social feeds are not read" }
        val uri = requestUri(instrumentIds)
        val first = try {
            transport.get(uri, material.headers())
        } catch (_: Exception) {
            return emptyMap()
        }
        val response = if (first.status == 429) {
            val delay = first.retryAfterSeconds
            if (delay != null && delay in 0..2) {
                sleep(Duration.ofSeconds(delay))
                try {
                    transport.get(uri, material.headers())
                } catch (_: Exception) {
                    return emptyMap()
                }
            } else {
                return emptyMap()
            }
        } else {
            first
        }
        if (response.status != 200) {
            return emptyMap()
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

    fun parse(body: String): Map<String, String> {
        if (body.isBlank()) {
            return emptyMap()
        }
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return emptyMap()
        }
        val rows = root.get("instrumentDisplayDatas")
        if (rows == null || !rows.isArray) {
            return emptyMap()
        }
        val names = linkedMapOf<String, String>()
        rows.forEach { node ->
            val name = readName(node) ?: return@forEach
            names[name.first] = name.second
        }
        return names
    }

    private fun readName(node: JsonNode): Pair<String, String>? {
        val instrument = node.get("instrumentID")?.takeIf { it.isIntegralNumber }?.asInt()
        if (instrument == null || instrument <= 0) {
            return null
        }
        val name = node.get("instrumentDisplayName")?.takeIf { it.isTextual }?.asText()?.trim().orEmpty()
        if (name.isEmpty()) {
            return null
        }
        return instrument.toString() to name
    }

    private const val MAX_INSTRUMENTS: Int = 100
}

fun interface InstrumentNameRead {
    /** Null when the instruments GET is not attempted. A missing name is absent from the map. */
    fun read(instrumentIds: List<String>): Map<String, String>?
}

class DemoInstrumentNameRead(
    private val keys: () -> DemoKeyMaterial?,
    private val transport: HttpGet,
    private val sleep: (Duration) -> Unit = {},
) : InstrumentNameRead {
    override fun read(instrumentIds: List<String>): Map<String, String>? {
        val material = keys() ?: return null
        val ids = instrumentIds
            .map { it.trim() }
            .filter { id -> id.toIntOrNull()?.let { it in 1..Int.MAX_VALUE } == true }
            .distinct()
            .take(100)
        if (ids.isEmpty()) {
            return emptyMap()
        }
        return DemoInstrumentNames.execute(transport, material, ids, sleep)
    }
}
