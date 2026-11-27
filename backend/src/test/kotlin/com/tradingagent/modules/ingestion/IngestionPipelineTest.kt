package com.tradingagent.modules.ingestion

import com.tradingagent.kernel.QualityScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
}
