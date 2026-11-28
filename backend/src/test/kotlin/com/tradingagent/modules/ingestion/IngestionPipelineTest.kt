package com.tradingagent.modules.ingestion

import com.tradingagent.kernel.QualityScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class IngestionPipelineTest {
    @Test
    fun `classpath fixture has no network and no usable channel`() {
        val pipeline = IngestionPipeline.fromClasspath()
        assertFalse(pipeline.enabled())
        val at = Instant.parse("2026-11-27T00:00:30Z")
        val health = pipeline.assess(at)
        assertEquals("classpath-fixture", health.source)
        assertFalse(health.network)
        assertEquals("fixture-1", health.connectorVersion)
        assertEquals(1, health.quarantinedCount)
        val prices = health.channels.first { it.channel == "prices" }
        assertEquals(2, prices.acceptedCount)
        assertEquals(1, prices.duplicateCount)
        assertEquals(1, prices.rejectedCount)
        assertTrue(prices.conflict)
        assertFalse(prices.stale)
        assertEquals(0, prices.qualityMillis)
        assertEquals("CONFLICT", prices.status)
        assertEquals("market-data", prices.category)
        assertEquals("fixture-market-crosscheck", prices.source)
        assertEquals(Instant.parse("2026-11-27T00:00:10Z"), prices.eventTime)
        assertEquals(Instant.parse("2026-11-27T00:00:11Z"), prices.ingestedAt)
        assertEquals("fixture-unverified", prices.license)
        val news = health.channels.first { it.channel == "news" }
        assertEquals(1, news.acceptedCount)
        assertEquals(0, news.rejectedCount)
        assertFalse(news.stale)
        assertEquals("news", news.category)
        assertEquals("fixture-news", news.source)
        assertEquals(Instant.parse("2026-11-27T00:00:00Z"), news.eventTime)
        assertEquals(Instant.parse("2026-11-27T00:00:02Z"), news.ingestedAt)
        assertEquals("fixture-unverified", news.license)
        assertEquals("UNUSABLE", news.status)
        assertFalse(listOf(news.source, news.category, news.license, news.status).joinToString().contains("synthetic-headline"))
        val macro = health.channels.first { it.channel == "macro" }
        assertEquals(1, macro.acceptedCount)
        assertFalse(macro.stale)
        assertEquals("macro", macro.category)
        assertEquals("fixture-macro", macro.source)
        assertEquals(Instant.parse("2026-11-27T00:00:00Z"), macro.eventTime)
        assertEquals(Instant.parse("2026-11-27T00:00:02Z"), macro.ingestedAt)
        assertEquals("fixture-unverified", macro.license)
        assertEquals("UNUSABLE", macro.status)
        val fundamentals = health.channels.first { it.channel == "fundamentals" }
        assertEquals(1, fundamentals.acceptedCount)
        assertFalse(fundamentals.stale)
        assertEquals("fundamentals", fundamentals.category)
        assertEquals("fixture-fundamentals", fundamentals.source)
        assertEquals(Instant.parse("2026-11-27T00:00:00Z"), fundamentals.eventTime)
        assertEquals(Instant.parse("2026-11-27T00:00:02Z"), fundamentals.ingestedAt)
        assertEquals("fixture-unverified", fundamentals.license)
        assertEquals("UNUSABLE", fundamentals.status)
        assertTrue(health.channels.none { it.status == "HEALTHY" })
        assertEquals("NOT_WIRED", health.channels.first { it.channel == "orders" }.status)
        assertEquals("NOT_WIRED", health.channels.first { it.channel == "positions" }.status)
    }

    @Test
    fun `prices become stale only after two minutes`() {
        val pipeline = IngestionPipeline.fromJson(
            """
            {
              "connector_version": "fixture-stale",
              "records": [
                {
                  "record_id": "price-one",
                  "channel": "prices",
                  "category": "market-data",
                  "provider_class": "deterministic",
                  "source_name": "fixture-market-data",
                  "event_time": "2026-11-27T00:00:00Z",
                  "ingested_at": "2026-11-27T00:00:01Z",
                  "license": "fixture-unverified",
                  "license_verified": false,
                  "contract_verified": false,
                  "instrument_id": "INST-1",
                  "value": "100.00"
                }
              ]
            }
            """.trimIndent(),
        )
        val boundary = pipeline.assess(Instant.parse("2026-11-27T00:02:00Z"))
        val after = pipeline.assess(Instant.parse("2026-11-27T00:02:01Z"))
        assertFalse(boundary.channels.first { it.channel == "prices" }.stale)
        assertEquals("UNUSABLE", boundary.channels.first { it.channel == "prices" }.status)
        assertTrue(after.channels.first { it.channel == "prices" }.stale)
        assertEquals("STALE", after.channels.first { it.channel == "prices" }.status)
    }

    @Test
    fun `verified deterministic record reaches the quality threshold`() {
        val pipeline = IngestionPipeline.fromJson(
            """
            {
              "connector_version": "fixture-qualified",
              "records": [
                {
                  "record_id": "price-ok",
                  "channel": "prices",
                  "category": "market-data",
                  "provider_class": "deterministic",
                  "source_name": "fixture-market-data",
                  "event_time": "2026-11-27T00:00:00Z",
                  "ingested_at": "2026-11-27T00:00:01Z",
                  "license": "fixture-verified",
                  "license_verified": true,
                  "contract_verified": true,
                  "instrument_id": "INST-1",
                  "value": "100.00"
                }
              ]
            }
            """.trimIndent(),
        )
        val prices = pipeline.assess(Instant.parse("2026-11-27T00:00:30Z"))
            .channels.first { it.channel == "prices" }
        assertEquals(1000, prices.qualityMillis)
        assertEquals("HEALTHY", prices.status)
        assertTrue(prices.qualityMillis >= QualityScale.THRESHOLD_MILLIS)
    }

    @Test
    fun `news becomes stale only after ten minutes and drops the body`() {
        val pipeline = IngestionPipeline.fromJson(newsJson())
        val fresh = pipeline.assess(Instant.parse("2026-11-27T00:10:00Z")).channels.first { it.channel == "news" }
        val stale = pipeline.assess(Instant.parse("2026-11-27T00:10:01Z")).channels.first { it.channel == "news" }
        assertFalse(fresh.stale)
        assertEquals("UNUSABLE", fresh.status)
        assertTrue(stale.stale)
        assertEquals("STALE", stale.status)
        assertEquals("fixture-news", stale.source)
        assertEquals("news", stale.category)
        assertEquals(Instant.parse("2026-11-27T00:00:00Z"), stale.eventTime)
        assertEquals(Instant.parse("2026-11-27T00:00:02Z"), stale.ingestedAt)
        assertEquals("fixture-unverified", stale.license)
        assertEquals(1, stale.acceptedCount)
        assertFalse(pipeline.assess(Instant.parse("2026-11-27T00:10:01Z")).network)
        assertFalse(listOf(stale.source, stale.category, stale.license).joinToString().contains("synthetic-headline"))
    }

    @Test
    fun `macro and fundamentals go stale after two missed hourly checks`() {
        val pipeline = IngestionPipeline.fromJson(slowJson("2026-11-01T00:00:00Z", "2026-11-27T00:00:00Z"))
        val boundary = pipeline.assess(Instant.parse("2026-11-27T02:00:00Z"))
        val after = pipeline.assess(Instant.parse("2026-11-27T02:00:01Z"))
        listOf("macro", "fundamentals").forEach { name ->
            val atBoundary = boundary.channels.first { it.channel == name }
            val stale = after.channels.first { it.channel == name }
            assertEquals(Instant.parse("2026-11-01T00:00:00Z"), atBoundary.eventTime)
            assertFalse(atBoundary.stale)
            assertEquals("UNUSABLE", atBoundary.status)
            assertTrue(stale.stale)
            assertEquals("STALE", stale.status)
            assertEquals("fixture-unverified", stale.license)
            assertEquals(1, stale.acceptedCount)
        }
        assertFalse(after.network)
        val checked = IngestionPipeline.fromJson(slowJson("2026-11-01T00:00:00Z", "2026-11-27T02:00:00Z"))
            .assess(Instant.parse("2026-11-27T02:00:01Z"))
        assertFalse(checked.channels.first { it.channel == "macro" }.stale)
        assertFalse(checked.channels.first { it.channel == "fundamentals" }.stale)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), checked.channels.first { it.channel == "macro" }.eventTime)
    }

    @Test
    fun `missing macro and fundamentals are unusable and not stale`() {
        val health = IngestionPipeline.fromJson(
            """{"connector_version":"fixture-empty","records":[]}""",
        ).assess(Instant.parse("2026-11-27T00:00:30Z"))
        listOf("macro", "fundamentals").forEach { name ->
            val channel = health.channels.first { it.channel == name }
            assertEquals(0, channel.acceptedCount)
            assertFalse(channel.stale)
            assertEquals("UNUSABLE", channel.status)
            assertEquals(null, channel.eventTime)
            assertEquals(null, channel.license)
        }
        assertFalse(health.network)
    }

    @Test
    fun `a missing news fixture is unusable and not stale`() {
        val news = IngestionPipeline.fromJson(
            """{"connector_version":"fixture-empty","records":[]}""",
        ).assess(Instant.parse("2026-11-27T00:00:30Z")).channels.first { it.channel == "news" }
        assertEquals(0, news.acceptedCount)
        assertFalse(news.stale)
        assertEquals("UNUSABLE", news.status)
        assertEquals(null, news.eventTime)
        assertEquals(null, news.license)
        assertFalse(IngestionPipeline.fromClasspath().assess(Instant.parse("2026-11-27T00:00:30Z")).network)
    }

    @Test
    fun `a missing prices fixture is unusable and not stale`() {
        val prices = IngestionPipeline.fromJson(
            """{"connector_version":"fixture-empty","records":[]}""",
        ).assess(Instant.parse("2026-11-27T00:00:30Z")).channels.first { it.channel == "prices" }
        assertEquals(0, prices.acceptedCount)
        assertFalse(prices.stale)
        assertEquals("UNUSABLE", prices.status)
        assertEquals(null, prices.eventTime)
        assertEquals(null, prices.license)
        assertFalse(IngestionPipeline.fromClasspath().assess(Instant.parse("2026-11-27T00:00:30Z")).network)
    }

    @Test
    fun `quality uses integer weights and license gates`() {
        assertEquals(
            1000,
            DeterministicQuality.millis(true, true, 1, 1, 1, 1, 1, 1),
        )
        assertEquals(0, DeterministicQuality.millis(false, true, 1, 1, 1, 1, 1, 1))
        assertEquals(0, DeterministicQuality.millis(true, false, 1, 1, 1, 1, 1, 1))
        assertEquals(750, DeterministicQuality.millis(true, true, 1, 1, 1, 1, 0, 1))
        assertEquals(875, DeterministicQuality.millis(true, true, 1, 1, 1, 1, 1, 2))
    }

    @Test
    fun `a live price batch replaces only prices and stays unverified`() {
        val at = Instant.parse("2026-11-27T00:10:00Z")
        val pipeline = IngestionPipeline.fromClasspath()
        val fresh = pipeline.assess(
            at,
            LivePriceBatch(
                rates = listOf(LiveRate("6270", BigDecimal("10.5"), at.minusSeconds(30))),
                rejectedCount = 0,
            ),
        )
        assertEquals("mixed", fresh.source)
        assertTrue(fresh.network)
        val prices = fresh.channels.first { it.channel == "prices" }
        assertEquals("etoro-public-api", prices.source)
        assertEquals("market-data", prices.category)
        assertEquals("unverified", prices.license)
        assertEquals(0, prices.qualityMillis)
        assertFalse(prices.stale)
        assertFalse(prices.conflict)
        assertEquals(1, prices.acceptedCount)
        assertEquals("UNUSABLE", prices.status)
        assertEquals(at, prices.ingestedAt)
        val news = fresh.channels.first { it.channel == "news" }
        assertEquals("fixture-news", news.source)
        assertEquals("fixture-unverified", news.license)
        assertEquals(1, news.acceptedCount)

        val stale = pipeline.assess(
            at,
            LivePriceBatch(
                rates = listOf(LiveRate("6270", BigDecimal("10.5"), at.minusSeconds(121))),
                rejectedCount = 0,
            ),
        )
        val stalePrices = stale.channels.first { it.channel == "prices" }
        assertTrue(stalePrices.stale)
        assertEquals("STALE", stalePrices.status)
        assertEquals("unverified", stalePrices.license)

        val missing = pipeline.assess(at, LivePriceBatch(emptyList(), 1))
        val missingPrices = missing.channels.first { it.channel == "prices" }
        assertEquals(0, missingPrices.acceptedCount)
        assertFalse(missingPrices.stale)
        assertEquals(1, missingPrices.rejectedCount)
        assertEquals("UNUSABLE", missingPrices.status)
        assertTrue(missing.network)
        assertEquals("fixture-news", missing.channels.first { it.channel == "news" }.source)
    }

    private fun newsJson() = """
        {
          "connector_version": "fixture-news",
          "records": [
            {
              "record_id": "news-1",
              "channel": "news",
              "category": "news",
              "provider_class": "deterministic",
              "source_name": "fixture-news",
              "event_time": "2026-11-27T00:00:00Z",
              "ingested_at": "2026-11-27T00:00:02Z",
              "license": "fixture-unverified",
              "license_verified": false,
              "contract_verified": false,
              "instrument_id": "INST-1",
              "value": "synthetic-headline"
            }
          ]
        }
    """.trimIndent()

    private fun slowJson(eventTime: String, ingestedAt: String) = """
        {
          "connector_version": "fixture-slow",
          "records": [
            {
              "record_id": "macro-1",
              "channel": "macro",
              "category": "macro",
              "provider_class": "deterministic",
              "source_name": "fixture-macro",
              "event_time": "$eventTime",
              "ingested_at": "$ingestedAt",
              "license": "fixture-unverified",
              "license_verified": false,
              "contract_verified": false,
              "instrument_id": "MACRO-1",
              "value": "1.0"
            },
            {
              "record_id": "fundamentals-1",
              "channel": "fundamentals",
              "category": "fundamentals",
              "provider_class": "deterministic",
              "source_name": "fixture-fundamentals",
              "event_time": "$eventTime",
              "ingested_at": "$ingestedAt",
              "license": "fixture-unverified",
              "license_verified": false,
              "contract_verified": false,
              "instrument_id": "INST-1",
              "value": "10"
            }
          ]
        }
    """.trimIndent()
}
