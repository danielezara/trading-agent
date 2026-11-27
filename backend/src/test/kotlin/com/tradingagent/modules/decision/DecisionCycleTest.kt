package com.tradingagent.modules.decision

import com.tradingagent.kernel.EvidenceStatus
import com.tradingagent.kernel.ReasonCode
import com.tradingagent.modules.audit.AuditLog
import com.tradingagent.modules.brokerread.CapabilityProbe
import com.tradingagent.modules.brokerread.DemoKeyMaterial
import com.tradingagent.modules.brokerread.HttpGet
import com.tradingagent.modules.brokerread.InboundHttp
import com.tradingagent.modules.configuration.ConfigurationService
import com.tradingagent.modules.configuration.EvidenceGate
import com.tradingagent.modules.ingestion.IngestionPipeline
import com.tradingagent.modules.ledger.Ledger
import com.tradingagent.modules.operational.OperationalStateService
import com.tradingagent.modules.risk.PolicyGate
import com.tradingagent.modules.strategy.StrategyEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DecisionCycleTest {
    @Test
    fun `each decision is audited and the order attempt is refused`() {
        val clock = { Instant.parse("2026-11-27T00:00:30Z") }
        val audit = AuditLog()
        val configuration = ConfigurationService(clock)
        val cycle = DecisionCycle(
            strategyEngine = StrategyEngine(),
            ingestionPipeline = IngestionPipeline.fromClasspath(),
            evidenceGate = EvidenceGate(),
            operationalStateService = OperationalStateService(clock = clock),
            configurationService = configuration,
            auditLog = audit,
            policyGate = PolicyGate(),
            clock = clock,
        )
        val first = cycle.record()
        val second = cycle.record()
        assertEquals("NO-TRADE", first.outcome.action)
        assertEquals(ReasonCode.CAPABILITY_UNVERIFIED, first.outcome.reasonCode)
        assertEquals(first.outcome.inputHash, second.outcome.inputHash)
        assertEquals(configuration.active().versionId, first.configuration.versionId)
        assertEquals(configuration.active().contentSha256, first.auditEvent.configContentSha256)
        assertEquals(first.outcome.inputHash, first.auditEvent.correlationId)
        assertFalse(first.outcome.signal.orderable)
        assertEquals("NONE", first.outcome.signal.direction)
        assertNull(first.outcome.signal.instrumentId)
        assertFalse(first.policy.accepted)
        assertFalse(first.policy.brokerCalled)
        assertEquals(PolicyGate.MISSING_INTENT, first.policy.reason)
        assertEquals(2, audit.events().size)
        assertTrue(audit.verify().valid)
        val day = LocalDate.ofInstant(clock(), ZoneOffset.UTC)
        audit.sealDay(day)
        assertEquals(audit.dailyDigest(day), audit.sealedDigest(day))
        assertEquals(second.auditEvent.eventHash, cycle.latest().auditEvent.eventHash)
    }

    @Test
    fun `usd cash adds the fx reason and still cannot send an order`() {
        val gate = EvidenceGate()
        val probe = CapabilityProbe(
            evidenceGate = gate,
            keys = {
                DemoKeyMaterial.fromEnvironment(
                    mapOf("ETORO_DEMO_API_KEY" to "test-api-key", "ETORO_DEMO_USER_KEY" to "test-user-key"),
                )
            },
            transport = HttpGet { _, _ ->
                InboundHttp(200, null, """{"clientPortfolio":{"credit":10.00,"accountCurrencyId":1,"positions":[]}}""")
            },
            clock = { Instant.parse("2026-11-27T00:00:30Z") },
        )
        probe.refresh()
        val cycle = DecisionCycle(
            strategyEngine = StrategyEngine(),
            ingestionPipeline = IngestionPipeline.fromClasspath(),
            evidenceGate = gate,
            operationalStateService = OperationalStateService(clock = { Instant.parse("2026-11-27T00:00:30Z") }),
            configurationService = ConfigurationService(),
            auditLog = AuditLog(),
            policyGate = PolicyGate(),
            clock = { Instant.parse("2026-11-27T00:00:30Z") },
            ledger = Ledger(),
            capabilityProbe = probe,
        )
        val recorded = cycle.record()
        assertEquals("NO-TRADE", recorded.outcome.action)
        assertTrue(recorded.outcome.reasonCodes.contains(ReasonCode.FX_UNAVAILABLE))
        assertFalse(recorded.outcome.signal.orderable)
        assertFalse(recorded.policy.accepted)
        assertFalse(recorded.policy.brokerCalled)
        assertEquals(EvidenceStatus.UNVERIFIED, gate.rows().first { it.controlId == "RF-045" }.status)
        assertEquals(EvidenceStatus.UNVERIFIED, gate.rows().first { it.controlId == "RF-059" }.status)
        assertFalse(gate.allowsActive())
    }

    @Test
    fun `a stale prices fixture stays no trade and records its provenance`() {
        val at = Instant.parse("2026-11-27T00:02:01Z")
        val pipeline = IngestionPipeline.fromJson(pricesJson("2026-11-27T00:00:00Z"))
        val recorded = cycle(pricesJson("2026-11-27T00:00:00Z"), at).record()
        val assessment = pipeline.assess(at)
        val prices = assessment.channels.first { it.channel == "prices" }
        val news = assessment.channels.first { it.channel == "news" }
        val text = DecisionCycle.decisionPayload(recorded.outcome, prices, news)
        assertEquals("NO-TRADE", recorded.outcome.action)
        assertTrue(prices.stale)
        assertEquals("fixture-market-data", prices.source)
        assertEquals("market-data", prices.category)
        assertEquals("fixture-unverified", prices.license)
        assertTrue(recorded.outcome.reasonCodes.contains(ReasonCode.DATA_STALE))
        assertFalse(recorded.outcome.signal.orderable)
        assertFalse(recorded.policy.accepted)
        assertFalse(recorded.policy.brokerCalled)
        assertFalse(pipeline.assess(at).network)
        assertTrue(text.contains("prices_source=fixture-market-data"))
        assertTrue(text.contains("prices_event_time=2026-11-27T00:00:00Z"))
        assertTrue(text.contains("prices_license=fixture-unverified"))
        assertTrue(text.contains("prices_stale=true"))
        assertTrue(text.contains("news_accepted=0"))
        assertTrue(text.contains("news_stale=false"))
        assertFalse(text.contains("synthetic-headline"))
        assertEquals(sha256(text), recorded.auditEvent.payloadSha256)
    }

    @Test
    fun `a stale news fixture stays no trade and records its provenance`() {
        val at = Instant.parse("2026-11-27T00:10:01Z")
        val json = newsJson("2026-11-27T00:00:00Z", "2026-11-27T00:10:01Z")
        val pipeline = IngestionPipeline.fromJson(json)
        val recorded = cycle(json, at).record()
        val assessment = pipeline.assess(at)
        val news = assessment.channels.first { it.channel == "news" }
        val prices = assessment.channels.first { it.channel == "prices" }
        val text = DecisionCycle.decisionPayload(recorded.outcome, prices, news)
        assertTrue(news.stale)
        assertFalse(prices.stale)
        assertEquals("fixture-news", news.source)
        assertEquals("news", news.category)
        assertEquals("fixture-unverified", news.license)
        assertEquals("NO-TRADE", recorded.outcome.action)
        assertTrue(recorded.outcome.reasonCodes.contains(ReasonCode.DATA_STALE))
        assertTrue(recorded.outcome.reasonCodes.contains(ReasonCode.DATA_MISSING))
        assertFalse(recorded.outcome.signal.orderable)
        assertFalse(recorded.policy.brokerCalled)
        assertFalse(assessment.network)
        assertTrue(text.contains("news_source=fixture-news"))
        assertTrue(text.contains("news_event_time=2026-11-27T00:00:00Z"))
        assertTrue(text.contains("news_ingested_at=2026-11-27T00:00:02Z"))
        assertTrue(text.contains("news_license=fixture-unverified"))
        assertTrue(text.contains("news_stale=true"))
        assertFalse(text.contains("synthetic-headline"))
        assertEquals(sha256(text), recorded.auditEvent.payloadSha256)
        assertEquals(EvidenceStatus.UNVERIFIED, EvidenceGate().rows().first { it.controlId == "RF-045" }.status)
        assertEquals(EvidenceStatus.UNVERIFIED, EvidenceGate().rows().first { it.controlId == "RF-059" }.status)
    }

    @Test
    fun `a missing news fixture stays no trade without calling a vendor`() {
        val json = pricesJson("2026-11-27T00:00:00Z")
        val at = Instant.parse("2026-11-27T00:00:30Z")
        val pipeline = IngestionPipeline.fromJson(json)
        val recorded = cycle(json, at).record()
        val news = pipeline.assess(at).channels.first { it.channel == "news" }
        assertEquals(0, news.acceptedCount)
        assertFalse(news.stale)
        assertEquals(null, news.license)
        assertFalse(pipeline.assess(at).network)
        assertEquals("NO-TRADE", recorded.outcome.action)
        assertTrue(recorded.outcome.reasonCodes.contains(ReasonCode.DATA_MISSING))
        assertFalse(recorded.outcome.reasonCodes.contains(ReasonCode.DATA_STALE))
        assertFalse(recorded.policy.brokerCalled)
        assertEquals(EvidenceStatus.UNVERIFIED, EvidenceGate().rows().first { it.controlId == "RF-045" }.status)
        assertEquals(EvidenceStatus.UNVERIFIED, EvidenceGate().rows().first { it.controlId == "RF-059" }.status)
    }

    @Test
    fun `a missing prices fixture stays no trade without calling a vendor`() {
        val json = """{"connector_version":"fixture-empty","records":[]}"""
        val at = Instant.parse("2026-11-27T00:00:30Z")
        val pipeline = IngestionPipeline.fromJson(json)
        val recorded = cycle(json, at).record()
        val prices = pipeline.assess(at).channels.first { it.channel == "prices" }
        assertEquals(0, prices.acceptedCount)
        assertFalse(prices.stale)
        assertEquals(null, prices.license)
        assertFalse(pipeline.assess(at).network)
        assertEquals("NO-TRADE", recorded.outcome.action)
        assertTrue(recorded.outcome.reasonCodes.contains(ReasonCode.DATA_MISSING))
        assertFalse(recorded.outcome.reasonCodes.contains(ReasonCode.DATA_STALE))
        assertFalse(recorded.policy.brokerCalled)
        assertEquals(EvidenceStatus.UNVERIFIED, EvidenceGate().rows().first { it.controlId == "RF-045" }.status)
        assertEquals(EvidenceStatus.UNVERIFIED, EvidenceGate().rows().first { it.controlId == "RF-059" }.status)
    }

    private fun cycle(json: String, at: Instant): DecisionCycle = DecisionCycle(
        strategyEngine = StrategyEngine(),
        ingestionPipeline = IngestionPipeline.fromJson(json),
        evidenceGate = EvidenceGate(),
        operationalStateService = OperationalStateService(clock = { at }),
        configurationService = ConfigurationService(),
        auditLog = AuditLog(),
        policyGate = PolicyGate(),
        clock = { at },
    )

    private fun sha256(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun pricesJson(eventTime: String) = """
        {
          "connector_version": "fixture-prices",
          "records": [
            {
              "record_id": "price-one",
              "channel": "prices",
              "category": "market-data",
              "provider_class": "deterministic",
              "source_name": "fixture-market-data",
              "event_time": "$eventTime",
              "ingested_at": "2026-11-27T00:00:01Z",
              "license": "fixture-unverified",
              "license_verified": false,
              "contract_verified": false,
              "instrument_id": "INST-1",
              "value": "100.00"
            }
          ]
        }
    """.trimIndent()

    private fun newsJson(newsEvent: String, priceEvent: String) = """
        {
          "connector_version": "fixture-news",
          "records": [
            {
              "record_id": "news-1",
              "channel": "news",
              "category": "news",
              "provider_class": "deterministic",
              "source_name": "fixture-news",
              "event_time": "$newsEvent",
              "ingested_at": "2026-11-27T00:00:02Z",
              "license": "fixture-unverified",
              "license_verified": false,
              "contract_verified": false,
              "instrument_id": "INST-1",
              "value": "synthetic-headline"
            },
            {
              "record_id": "price-one",
              "channel": "prices",
              "category": "market-data",
              "provider_class": "deterministic",
              "source_name": "fixture-market-data",
              "event_time": "$priceEvent",
              "ingested_at": "2026-11-27T00:10:01Z",
              "license": "fixture-unverified",
              "license_verified": false,
              "contract_verified": false,
              "instrument_id": "INST-1",
              "value": "100.00"
            }
          ]
        }
    """.trimIndent()
}
