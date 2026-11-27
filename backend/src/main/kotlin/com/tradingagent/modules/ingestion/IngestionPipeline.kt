package com.tradingagent.modules.ingestion

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tradingagent.modules.configuration.TradingParameters
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

data class ChannelHealth(
    val channel: String,
    val source: String,
    val acceptedCount: Int,
    val rejectedCount: Int,
    val duplicateCount: Int,
    val stale: Boolean,
    val conflict: Boolean,
    val qualityMillis: Int,
    val status: String,
    val providerClass: String,
    val category: String? = null,
    val eventTime: Instant? = null,
    val ingestedAt: Instant? = null,
    val license: String? = null,
)

data class IngestionAssessment(
    val source: String,
    val network: Boolean,
    val connectorVersion: String,
    val quarantinedCount: Int,
    val channels: List<ChannelHealth>,
)

object DeterministicQuality {
    fun millis(
        licenseVerified: Boolean,
        contractVerified: Boolean,
        schemaNumerator: Int,
        schemaDenominator: Int,
        provenanceNumerator: Int,
        provenanceDenominator: Int,
        freshnessNumerator: Int,
        freshnessDenominator: Int,
    ): Int {
        if (!licenseVerified || !contractVerified) {
            return 0
        }
        val technical = TradingParameters.current().technical
        return weighted(technical.qualitySchemaMillis, schemaNumerator, schemaDenominator) +
            weighted(technical.qualityProvenanceMillis, provenanceNumerator, provenanceDenominator) +
            weighted(technical.qualityFreshnessMillis, freshnessNumerator, freshnessDenominator)
    }

    private fun weighted(weight: Int, numerator: Int, denominator: Int): Int {
        require(numerator >= 0 && denominator >= 0 && numerator <= denominator) {
            "quality rate is out of range"
        }
        if (denominator == 0) {
            return 0
        }
        return (weight.toLong() * numerator / denominator).toInt()
    }
}

class IngestionPipeline private constructor(
    private val connectorVersion: String,
    private val accepted: List<AcceptedRecord>,
    private val rejectedByChannel: Map<String, Int>,
    private val duplicatesByChannel: Map<String, Int>,
    private val quarantinedCount: Int,
) {
    fun enabled(): Boolean = false

    fun assess(now: Instant): IngestionAssessment {
        val channels = (FIXTURE_CHANNELS + BROKER_CHANNELS).map { channel ->
            if (channel in BROKER_CHANNELS) {
                notWired(channel)
            } else {
                assessChannel(channel, now)
            }
        }.sortedBy { it.channel }
        return IngestionAssessment(
            source = "classpath-fixture",
            network = false,
            connectorVersion = connectorVersion,
            quarantinedCount = quarantinedCount,
            channels = channels,
        )
    }

    private fun assessChannel(channel: String, now: Instant): ChannelHealth {
        val rows = accepted.filter { it.channel == channel }
        val rejected = rejectedByChannel[channel] ?: 0
        val duplicates = duplicatesByChannel[channel] ?: 0
        val threshold = staleAfter(channel)
        val freshRows = rows.filter { !isStale(it.eventTime, now, threshold) }
        val newest = rows.maxByOrNull { it.eventTime }
        val stale = newest != null && isStale(newest.eventTime, now, threshold)
        val conflict = channel == "prices" && materialConflict(rows)
        val licenseOk = rows.isNotEmpty() && rows.all { it.licenseVerified }
        val contractOk = rows.isNotEmpty() && rows.all { it.contractVerified }
        val quality = DeterministicQuality.millis(
            licenseVerified = licenseOk,
            contractVerified = contractOk,
            schemaNumerator = rows.size,
            schemaDenominator = rows.size + rejected,
            provenanceNumerator = rows.size,
            provenanceDenominator = rows.size,
            freshnessNumerator = freshRows.size,
            freshnessDenominator = rows.size,
        )
        val status = when {
            rows.isEmpty() -> "UNUSABLE"
            conflict -> "CONFLICT"
            stale -> "STALE"
            quality < TradingParameters.current().technical.qualityThresholdMillis -> "UNUSABLE"
            else -> "HEALTHY"
        }
        return ChannelHealth(
            channel = channel,
            source = newest?.sourceName ?: "classpath-fixture",
            acceptedCount = rows.size,
            rejectedCount = rejected,
            duplicateCount = duplicates,
            stale = stale,
            conflict = conflict,
            qualityMillis = quality,
            status = status,
            providerClass = "deterministic",
            category = newest?.category,
            eventTime = newest?.eventTime,
            ingestedAt = newest?.ingestedAt,
            license = newest?.license,
        )
    }

    companion object {
        private val mapper = ObjectMapper()
        private val fixtureChannels = listOf("prices", "news", "macro", "fundamentals", "calendar")
        private val brokerChannels = listOf("orders", "positions")
        val FIXTURE_CHANNELS: List<String> = fixtureChannels
        val BROKER_CHANNELS: List<String> = brokerChannels

        operator fun invoke(): IngestionPipeline = fromClasspath()

        fun fromClasspath(resource: String = "/fixtures/ingestion/channels.json"): IngestionPipeline {
            val stream = IngestionPipeline::class.java.getResourceAsStream(resource)
                ?: throw IllegalStateException("ingestion fixture not found")
            return fromJson(stream.bufferedReader().use { it.readText() })
        }

        fun fromJson(json: String): IngestionPipeline {
            val root = mapper.readTree(json)
            val version = root.get("connector_version")?.asText()
            require(!version.isNullOrBlank()) { "connector_version is required" }
            val records = root.get("records")
            require(records != null && records.isArray) { "records must be an array" }
            val accepted = mutableListOf<AcceptedRecord>()
            val seen = mutableSetOf<String>()
            val rejected = mutableMapOf<String, Int>()
            val duplicates = mutableMapOf<String, Int>()
            var quarantined = 0
            records.forEach { node ->
                when (val parsed = parseRecord(node)) {
                    is ParseResult.Quarantined -> quarantined += 1
                    is ParseResult.Rejected -> {
                        val channel = parsed.channel
                        if (channel != null) {
                            rejected[channel] = (rejected[channel] ?: 0) + 1
                        } else {
                            quarantined += 1
                        }
                    }
                    is ParseResult.Ok -> {
                        if (!seen.add(parsed.record.recordId)) {
                            val channel = parsed.record.channel
                            duplicates[channel] = (duplicates[channel] ?: 0) + 1
                        } else {
                            accepted += parsed.record
                        }
                    }
                }
            }
            return IngestionPipeline(version, accepted, rejected, duplicates, quarantined)
        }

        private fun parseRecord(node: JsonNode): ParseResult {
            val channel = text(node, "channel")
            val providerClass = text(node, "provider_class")
            if (providerClass == "probabilistic") {
                return ParseResult.Quarantined
            }
            if (providerClass != "deterministic") {
                return ParseResult.Rejected(channel)
            }
            if (channel !in fixtureChannels) {
                return ParseResult.Rejected(channel)
            }
            val recordId = text(node, "record_id")
            val category = text(node, "category")
            val sourceName = text(node, "source_name")
            val eventTime = instant(node, "event_time")
            val ingestedAt = instant(node, "ingested_at")
            val license = text(node, "license")
            val instrumentId = text(node, "instrument_id")
            val licenseVerified = bool(node, "license_verified")
            val contractVerified = bool(node, "contract_verified")
            val value = decimal(node, "value")
            if (
                recordId == null || category == null || sourceName == null || eventTime == null ||
                ingestedAt == null || license == null || instrumentId == null ||
                licenseVerified == null || contractVerified == null || value == null
            ) {
                return ParseResult.Rejected(channel)
            }
            if (value.compareTo(BigDecimal.ZERO) < 0) {
                return ParseResult.Rejected(channel)
            }
            return ParseResult.Ok(
                AcceptedRecord(
                    recordId = recordId,
                    channel = channel!!,
                    category = category,
                    sourceName = sourceName,
                    eventTime = eventTime,
                    ingestedAt = ingestedAt,
                    license = license,
                    licenseVerified = licenseVerified,
                    contractVerified = contractVerified,
                    instrumentId = instrumentId,
                    value = value,
                ),
            )
        }

        private fun text(node: JsonNode, field: String): String? =
            node.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

        private fun bool(node: JsonNode, field: String): Boolean? =
            node.get(field)?.takeIf { it.isBoolean }?.asBoolean()

        private fun instant(node: JsonNode, field: String): Instant? {
            val raw = text(node, field) ?: return null
            return runCatching { Instant.parse(raw) }.getOrNull()
        }

        private fun decimal(node: JsonNode, field: String): BigDecimal? {
            val raw = text(node, field) ?: return null
            return runCatching { BigDecimal(raw) }.getOrNull()
        }

        private fun notWired(channel: String) = ChannelHealth(
            channel = channel,
            source = "not-wired",
            acceptedCount = 0,
            rejectedCount = 0,
            duplicateCount = 0,
            stale = false,
            conflict = false,
            qualityMillis = 0,
            status = "NOT_WIRED",
            providerClass = "none",
        )

        private fun staleAfter(channel: String): Duration {
            val technical = TradingParameters.current().technical
            return when (channel) {
                "prices" -> Duration.ofSeconds(technical.pricesStaleSeconds)
                "news" -> Duration.ofSeconds(technical.newsStaleSeconds)
                "macro", "fundamentals", "calendar" -> Duration.ofSeconds(technical.slowDataStaleSeconds)
                else -> Duration.ZERO
            }
        }

        private fun isStale(eventTime: Instant, now: Instant, threshold: Duration): Boolean {
            val age = Duration.between(eventTime, now)
            return age.isNegative || age > threshold
        }

        private fun materialConflict(rows: List<AcceptedRecord>): Boolean {
            for (leftIndex in rows.indices) {
                for (rightIndex in leftIndex + 1 until rows.size) {
                    val left = rows[leftIndex]
                    val right = rows[rightIndex]
                    if (left.instrumentId != right.instrumentId) {
                        continue
                    }
                    val gap = Duration.between(left.eventTime, right.eventTime).abs()
                    val align = TradingParameters.current().technical.priceConflictAlignSeconds
                    if (gap <= Duration.ofSeconds(align) && materialPriceGap(left.value, right.value)) {
                        return true
                    }
                }
            }
            return false
        }

        private fun materialPriceGap(left: BigDecimal, right: BigDecimal): Boolean {
            val diff = left.subtract(right).abs()
            val base = left.abs().max(right.abs())
            if (base.compareTo(BigDecimal.ZERO) == 0) {
                return false
            }
            val scaled = diff.multiply(BigDecimal(10_000))
            val limit = base.multiply(BigDecimal(TradingParameters.current().technical.priceConflictBps))
            return scaled.compareTo(limit) > 0
        }
    }
}

private data class AcceptedRecord(
    val recordId: String,
    val channel: String,
    val category: String,
    val sourceName: String,
    val eventTime: Instant,
    val ingestedAt: Instant,
    val license: String,
    val licenseVerified: Boolean,
    val contractVerified: Boolean,
    val instrumentId: String,
    val value: BigDecimal,
)

private sealed interface ParseResult {
    data class Ok(val record: AcceptedRecord) : ParseResult
    data class Rejected(val channel: String?) : ParseResult
    data object Quarantined : ParseResult
}
