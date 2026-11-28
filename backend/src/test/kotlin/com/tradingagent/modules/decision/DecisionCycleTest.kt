package com.tradingagent.modules.decision

import com.tradingagent.kernel.EvidenceStatus
import com.tradingagent.kernel.ReasonCode
import com.tradingagent.modules.audit.AuditLog
import com.tradingagent.modules.brokerread.CapabilityProbe
import com.tradingagent.modules.brokerread.ClosedBarSource
import com.tradingagent.modules.brokerread.DemoEntrySubmit
import com.tradingagent.modules.brokerread.DemoKeyMaterial
import com.tradingagent.modules.brokerread.DemoOpenResult
import com.tradingagent.modules.brokerread.EntryCandidates
import com.tradingagent.modules.brokerread.ReportedRate
import com.tradingagent.modules.brokerread.ReportedRateSource
import com.tradingagent.modules.brokerread.HttpGet
import com.tradingagent.modules.brokerread.InboundHttp
import com.tradingagent.modules.brokerread.ObservedBar
import com.tradingagent.modules.brokerread.ObservedBars
import com.tradingagent.modules.brokerread.ObservedInstrumentBars
import com.tradingagent.modules.configuration.ConfigurationService
import com.tradingagent.modules.configuration.EvidenceGate
import com.tradingagent.modules.ingestion.IngestionAssessment
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
import com.tradingagent.kernel.OperationalState
import com.tradingagent.modules.ingestion.LivePriceBatch
import com.tradingagent.modules.ingestion.LiveRate
import java.math.BigDecimal
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
        assertTrue(gate.allowsActive())
        assertEquals("NO-TRADE", recorded.rule.action)
        assertEquals("WARMUP", recorded.rule.reason)
        assertFalse(recorded.rule.orderable)
        assertEquals(0, recorded.closedBarCount)
    }

    @Test
    fun `saved five minute bars are evaluated and still cannot send an order`() {
        val at = Instant.parse("2026-11-27T10:30:00Z")
        val origin = at.minusSeconds(300L * 21)
        val bars = (0 until 21).map { index ->
            val close = 10_000_000_000L + index * 10
            ObservedBar(origin.plusSeconds(300L * index), origin.plusSeconds(300L * (index + 1)), close, close + 1, close - 1)
        }
        val cycle = DecisionCycle(
            strategyEngine = StrategyEngine(),
            ingestionPipeline = IngestionPipeline.fromClasspath(),
            evidenceGate = EvidenceGate(),
            operationalStateService = OperationalStateService(clock = { at }),
            configurationService = ConfigurationService(),
            auditLog = AuditLog(),
            policyGate = PolicyGate(),
            clock = { at },
            closedBars = ClosedBarSource {
                ObservedBars(0, listOf(ObservedInstrumentBars("1001", "stock", bars)))
            },
        )
        val recorded = cycle.record()
        assertEquals(0, recorded.closedBarCount)
        assertEquals(21, cycle.storedBars().single().bars.size)
        assertEquals("NO-TRADE", recorded.outcome.action)
        assertEquals("NONE", recorded.outcome.signal.direction)
        assertFalse(recorded.outcome.signal.orderable)
        assertEquals("NO-TRADE", recorded.rule.action)
        assertEquals("WARMUP", recorded.rule.reason)
        assertFalse(recorded.rule.orderable)
        assertEquals("NOT_SENT", recorded.demoEntry.status)
        assertEquals("WARMUP", recorded.demoEntry.reason)
        assertFalse(recorded.policy.accepted)
        assertFalse(recorded.policy.brokerCalled)
    }

    @Test
    fun `sixteen broker positions do not block one new orderable entry`() {
        val origin = Instant.parse("2026-11-27T10:00:00Z")
        val at = origin.plusSeconds(300L * 20)
        val sender = RecordingEntry()
        val cycle = cycleWith(
            at = at,
            brokerPositions = 16,
            held = ObservedInstrumentBars("1005", "stock", emptyList()),
            candidate = ObservedInstrumentBars("1001", "crypto", risingBars(origin)),
            sender = sender,
            rate = "1.09626",
        )
        val recorded = cycle.record()
        assertEquals("ENTER", recorded.rule.action)
        assertEquals("LONG", recorded.rule.direction)
        assertEquals("1001", recorded.rule.instrumentId)
        assertEquals(7_500L, recorded.rule.notionalMinor)
        assertTrue(recorded.rule.orderable)
        assertEquals("NO-TRADE", recorded.outcome.action)
        assertFalse(recorded.outcome.signal.orderable)
        assertFalse(recorded.policy.brokerCalled)
        assertEquals(1, sender.calls.size)
        assertEquals("1001", sender.calls.single().first)
        assertEquals(BigDecimal("82.21"), sender.calls.single().second)
        assertEquals(setOf("1005"), sender.calls.single().third)
        assertEquals("ACCEPTED", recorded.demoEntry.status)
        assertEquals(200, recorded.demoEntry.httpStatus)
        assertEquals("9001", recorded.demoEntry.orderId)
        assertEquals(1, recorded.programOpenPositions)
        assertEquals(7_500L, recorded.programInvestedMinor)
    }

    @Test
    fun `an opened program position is not ordered again and the next symbol can be`() {
        val origin = Instant.parse("2026-11-27T10:00:00Z")
        val at = origin.plusSeconds(300L * 20)
        val sender = RecordingEntry()
        val book = ProgramPositions(mapOf("1001" to 7_500L))
        var pass = 0
        val cycle = cycleWith(
            at = at,
            brokerPositions = 16,
            held = ObservedInstrumentBars("1005", "stock", emptyList()),
            candidate = ObservedInstrumentBars("1002", "crypto", risingBars(origin)),
            sender = sender,
            rate = "1.09626",
            programPositions = book,
            candidates = { heldIds ->
                pass += 1
                val id = if (pass == 1) "1001" else "1002"
                listOf(ObservedInstrumentBars(id, "crypto", risingBars(origin))).filter { it.instrumentId !in heldIds }
            },
        )
        val skipped = cycle.record()
        assertEquals("NO-TRADE", skipped.rule.action)
        assertEquals("WARMUP", skipped.rule.reason)
        assertEquals(0, sender.calls.size)
        assertEquals(1, skipped.programOpenPositions)
        assertEquals(7_500L, skipped.programInvestedMinor)

        val opened = cycle.record()
        assertEquals("ENTER", opened.rule.action)
        assertEquals("1002", opened.rule.instrumentId)
        assertEquals(7_500L, opened.rule.notionalMinor)
        assertEquals(1, sender.calls.size)
        assertEquals("1002", sender.calls.single().first)
        assertTrue(sender.calls.single().third.contains("1001"))
        assertEquals(2, opened.programOpenPositions)
        assertEquals(15_000L, opened.programInvestedMinor)
        assertFalse(EntryPace.finished(opened.rule.reason))
    }

    @Test
    fun `four program positions leave no aggregate room for a fifth`() {
        val origin = Instant.parse("2026-11-27T10:00:00Z")
        val at = origin.plusSeconds(300L * 20)
        val sender = RecordingEntry()
        val book = ProgramPositions(
            mapOf("1001" to 7_500L, "1002" to 7_500L, "1003" to 7_500L, "1004" to 7_500L),
        )
        val cycle = cycleWith(
            at = at,
            brokerPositions = 16,
            held = ObservedInstrumentBars("1005", "stock", emptyList()),
            candidate = ObservedInstrumentBars("1008", "crypto", risingBars(origin)),
            sender = sender,
            rate = "1.09626",
            programPositions = book,
        )
        val recorded = cycle.record()
        assertEquals("NO-TRADE", recorded.rule.action)
        assertEquals("EXPOSURE_LIMIT", recorded.rule.reason)
        assertEquals(0, sender.calls.size)
        assertEquals(4, recorded.programOpenPositions)
        assertEquals(30_000L, recorded.programInvestedMinor)
        assertTrue(EntryPace.finished(recorded.rule.reason))
    }

    @Test
    fun `a no trade rule and a held instrument do not send`() {
        val origin = Instant.parse("2026-11-27T10:00:00Z")
        val at = origin.plusSeconds(300L * 20)
        val flat = RecordingEntry()
        val flatCycle = cycleWith(
            at = at,
            brokerPositions = 16,
            held = ObservedInstrumentBars("1005", "stock", emptyList()),
            candidate = ObservedInstrumentBars("1001", "crypto", flatBars(origin)),
            sender = flat,
            rate = "1.09626",
        )
        val noTrade = flatCycle.record()
        assertEquals("NO-TRADE", noTrade.rule.action)
        assertFalse(noTrade.rule.orderable)
        assertEquals("NOT_SENT", noTrade.demoEntry.status)
        assertEquals(0, flat.calls.size)

        val heldSender = RecordingEntry()
        val heldCycle = cycleWith(
            at = at,
            brokerPositions = 16,
            held = ObservedInstrumentBars("1001", "stock", emptyList()),
            candidate = ObservedInstrumentBars("1001", "crypto", risingBars(origin)),
            sender = heldSender,
            rate = "1.09626",
        )
        val held = heldCycle.record()
        assertEquals("NO-TRADE", held.rule.action)
        assertEquals("EXISTING_POSITION", held.rule.reason)
        assertEquals("NOT_SENT", held.demoEntry.status)
        assertEquals(0, heldSender.calls.size)
    }

    @Test
    fun `a missing eur usd rate does not invent an amount`() {
        val origin = Instant.parse("2026-11-27T10:00:00Z")
        val at = origin.plusSeconds(300L * 20)
        val sender = RecordingEntry()
        val cycle = cycleWith(
            at = at,
            brokerPositions = 0,
            held = ObservedInstrumentBars("1005", "stock", emptyList()),
            candidate = ObservedInstrumentBars("1001", "crypto", risingBars(origin)),
            sender = sender,
            rate = null,
        )
        val recorded = cycle.record()
        assertEquals("ENTER", recorded.rule.action)
        assertTrue(recorded.rule.orderable)
        assertEquals("NOT_SENT", recorded.demoEntry.status)
        assertEquals("RATE_MISSING", recorded.demoEntry.reason)
        assertEquals(0, sender.calls.size)
    }

    @Test
    fun `a stale prices fixture stays no trade and records its provenance`() {
        val at = Instant.parse("2026-11-27T00:02:01Z")
        val pipeline = IngestionPipeline.fromJson(pricesJson("2026-11-27T00:00:00Z"))
        val recorded = cycle(pricesJson("2026-11-27T00:00:00Z"), at).record()
        val assessment = pipeline.assess(at)
        val prices = assessment.channels.first { it.channel == "prices" }
        val text = payload(recorded, assessment)
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
        assertTrue(text.contains("macro_accepted=0"))
        assertTrue(text.contains("fundamentals_accepted=0"))
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
        val text = payload(recorded, assessment)
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
    fun `stale macro and fundamentals stay no trade and record provenance`() {
        val at = Instant.parse("2026-11-27T02:00:01Z")
        val json = slowDecisionJson()
        val pipeline = IngestionPipeline.fromJson(json)
        val recorded = cycle(json, at).record()
        val assessment = pipeline.assess(at)
        val macro = assessment.channels.first { it.channel == "macro" }
        val fundamentals = assessment.channels.first { it.channel == "fundamentals" }
        val text = payload(recorded, assessment)
        assertTrue(macro.stale)
        assertTrue(fundamentals.stale)
        assertFalse(assessment.channels.first { it.channel == "prices" }.stale)
        assertFalse(assessment.channels.first { it.channel == "news" }.stale)
        assertEquals("fixture-macro", macro.source)
        assertEquals("macro", macro.category)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), macro.eventTime)
        assertEquals(Instant.parse("2026-11-27T00:00:00Z"), macro.ingestedAt)
        assertEquals("fixture-unverified", macro.license)
        assertEquals("fixture-fundamentals", fundamentals.source)
        assertEquals("fundamentals", fundamentals.category)
        assertEquals("fixture-unverified", fundamentals.license)
        assertEquals("NO-TRADE", recorded.outcome.action)
        assertTrue(recorded.outcome.reasonCodes.contains(ReasonCode.DATA_STALE))
        assertTrue(recorded.outcome.reasonCodes.contains(ReasonCode.DATA_MISSING))
        assertFalse(recorded.outcome.signal.orderable)
        assertFalse(recorded.policy.brokerCalled)
        assertFalse(assessment.network)
        assertTrue(text.contains("macro_source=fixture-macro"))
        assertTrue(text.contains("macro_event_time=2026-11-01T00:00:00Z"))
        assertTrue(text.contains("macro_ingested_at=2026-11-27T00:00:00Z"))
        assertTrue(text.contains("macro_stale=true"))
        assertTrue(text.contains("fundamentals_source=fixture-fundamentals"))
        assertTrue(text.contains("fundamentals_event_time=2026-11-01T00:00:00Z"))
        assertTrue(text.contains("fundamentals_stale=true"))
        assertTrue(text.contains("fundamentals_license=fixture-unverified"))
        assertEquals(sha256(text), recorded.auditEvent.payloadSha256)
        assertEquals(EvidenceStatus.UNVERIFIED, EvidenceGate().rows().first { it.controlId == "RF-045" }.status)
        assertEquals(EvidenceStatus.UNVERIFIED, EvidenceGate().rows().first { it.controlId == "RF-059" }.status)
    }

    @Test
    fun `missing macro and fundamentals stay no trade without a vendor`() {
        val json = pricesJson("2026-11-27T00:00:00Z")
        val at = Instant.parse("2026-11-27T00:00:30Z")
        val recorded = cycle(json, at).record()
        val assessment = IngestionPipeline.fromJson(json).assess(at)
        val macro = assessment.channels.first { it.channel == "macro" }
        val fundamentals = assessment.channels.first { it.channel == "fundamentals" }
        assertEquals(0, macro.acceptedCount)
        assertEquals(0, fundamentals.acceptedCount)
        assertFalse(macro.stale)
        assertFalse(fundamentals.stale)
        assertEquals(null, macro.license)
        assertEquals(null, fundamentals.license)
        assertFalse(assessment.network)
        assertEquals("NO-TRADE", recorded.outcome.action)
        assertTrue(recorded.outcome.reasonCodes.contains(ReasonCode.DATA_MISSING))
        assertFalse(recorded.outcome.reasonCodes.contains(ReasonCode.DATA_STALE))
        assertFalse(recorded.policy.brokerCalled)
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

    @Test
    fun `live rates stay no trade when fresh stale or missing and do not verify the license`() {
        val at = Instant.parse("2026-11-27T00:10:00Z")
        val pipeline = IngestionPipeline.fromClasspath()
        val freshBatch = LivePriceBatch(listOf(LiveRate("6270", BigDecimal("10.5"), at.minusSeconds(30))), 0)
        val fresh = recordLive(pipeline, at, freshBatch)
        val freshPayload = payload(fresh.recorded, pipeline.assess(at, freshBatch))
        assertEquals("NO-TRADE", fresh.recorded.outcome.action)
        assertTrue(fresh.recorded.outcome.reasonCodes.contains(ReasonCode.DATA_MISSING))
        assertFalse(fresh.recorded.outcome.reasonCodes.contains(ReasonCode.DATA_STALE))
        assertFalse(fresh.recorded.policy.brokerCalled)
        assertFalse(fresh.recorded.outcome.signal.orderable)
        assertEquals(OperationalState.OBSERVATION, fresh.state.current())
        assertEquals(sha256(freshPayload), fresh.recorded.auditEvent.payloadSha256)
        assertTrue(freshPayload.contains("prices_source=etoro-public-api"))
        assertTrue(freshPayload.contains("prices_license=unverified"))
        assertTrue(freshPayload.contains("prices_stale=false"))
        assertTrue(freshPayload.contains("news_source=fixture-news"))
        assertFalse(freshPayload.contains("10.5"))
        assertFalse(freshPayload.contains("prices_license=PASS"))

        val staleBatch = LivePriceBatch(listOf(LiveRate("6270", BigDecimal("10.5"), at.minusSeconds(121))), 0)
        val stale = recordLive(pipeline, at, staleBatch)
        val stalePayload = payload(stale.recorded, pipeline.assess(at, staleBatch))
        assertEquals("NO-TRADE", stale.recorded.outcome.action)
        assertTrue(stale.recorded.outcome.reasonCodes.contains(ReasonCode.DATA_STALE))
        assertTrue(stalePayload.contains("prices_stale=true"))
        assertTrue(stalePayload.contains("prices_license=unverified"))
        assertEquals(sha256(stalePayload), stale.recorded.auditEvent.payloadSha256)
        assertFalse(stale.recorded.policy.brokerCalled)
        assertEquals(OperationalState.OBSERVATION, stale.state.current())

        val missingBatch = LivePriceBatch(emptyList(), 0)
        val missing = recordLive(pipeline, at, missingBatch)
        val missingPayload = payload(missing.recorded, pipeline.assess(at, missingBatch))
        assertEquals("NO-TRADE", missing.recorded.outcome.action)
        assertTrue(missing.recorded.outcome.reasonCodes.contains(ReasonCode.DATA_MISSING))
        assertFalse(missing.recorded.outcome.reasonCodes.contains(ReasonCode.DATA_STALE))
        assertTrue(missingPayload.contains("prices_accepted=0"))
        assertTrue(missingPayload.contains("prices_stale=false"))
        assertEquals(sha256(missingPayload), missing.recorded.auditEvent.payloadSha256)
        assertFalse(missing.recorded.policy.brokerCalled)
        assertEquals(OperationalState.OBSERVATION, missing.state.current())
        assertEquals(EvidenceStatus.UNVERIFIED, EvidenceGate().rows().first { it.controlId == "RF-045" }.status)
        assertEquals(EvidenceStatus.UNVERIFIED, EvidenceGate().rows().first { it.controlId == "RF-059" }.status)
    }

    private fun recordLive(pipeline: IngestionPipeline, at: Instant, batch: LivePriceBatch): LiveDecision {
        val state = OperationalStateService(clock = { at })
        val cycle = DecisionCycle(
            strategyEngine = StrategyEngine(),
            ingestionPipeline = pipeline,
            evidenceGate = EvidenceGate(),
            operationalStateService = state,
            configurationService = ConfigurationService(),
            auditLog = AuditLog(),
            policyGate = PolicyGate(),
            clock = { at },
            assess = { pipeline.assess(it, batch) },
        )
        return LiveDecision(cycle.record(), state)
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

    private fun slowDecisionJson() = """
        {
          "connector_version": "fixture-slow",
          "records": [
            {
              "record_id": "macro-1",
              "channel": "macro",
              "category": "macro",
              "provider_class": "deterministic",
              "source_name": "fixture-macro",
              "event_time": "2026-11-01T00:00:00Z",
              "ingested_at": "2026-11-27T00:00:00Z",
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
              "event_time": "2026-11-01T00:00:00Z",
              "ingested_at": "2026-11-27T00:00:00Z",
              "license": "fixture-unverified",
              "license_verified": false,
              "contract_verified": false,
              "instrument_id": "INST-1",
              "value": "10"
            },
            {
              "record_id": "price-one",
              "channel": "prices",
              "category": "market-data",
              "provider_class": "deterministic",
              "source_name": "fixture-market-data",
              "event_time": "2026-11-27T02:00:01Z",
              "ingested_at": "2026-11-27T02:00:01Z",
              "license": "fixture-unverified",
              "license_verified": false,
              "contract_verified": false,
              "instrument_id": "INST-1",
              "value": "100.00"
            },
            {
              "record_id": "news-1",
              "channel": "news",
              "category": "news",
              "provider_class": "deterministic",
              "source_name": "fixture-news",
              "event_time": "2026-11-27T02:00:01Z",
              "ingested_at": "2026-11-27T02:00:01Z",
              "license": "fixture-unverified",
              "license_verified": false,
              "contract_verified": false,
              "instrument_id": "INST-1",
              "value": "synthetic-headline"
            }
          ]
        }
    """.trimIndent()

    private fun cycleWith(
        at: Instant,
        brokerPositions: Int,
        held: ObservedInstrumentBars,
        candidate: ObservedInstrumentBars,
        sender: RecordingEntry,
        rate: String?,
        programPositions: ProgramPositions = ProgramPositions(),
        candidates: ((Set<String>) -> List<ObservedInstrumentBars>)? = null,
    ): DecisionCycle = DecisionCycle(
        strategyEngine = StrategyEngine(),
        ingestionPipeline = IngestionPipeline.fromClasspath(),
        evidenceGate = EvidenceGate(),
        operationalStateService = OperationalStateService(clock = { at }),
        configurationService = ConfigurationService(),
        auditLog = AuditLog(),
        policyGate = PolicyGate(),
        clock = { at },
        closedBars = ClosedBarSource { ObservedBars(brokerPositions, listOf(held)) },
        reportedRates = ReportedRateSource {
            if (rate == null) emptyList() else listOf(ReportedRate("EUR", "USD", rate))
        },
        entryCandidates = EntryCandidates { _, heldIds -> candidates?.invoke(heldIds) ?: listOf(candidate) },
        demoEntries = sender,
        programPositions = programPositions,
    )

    private fun risingBars(origin: Instant): List<ObservedBar> = (0 until 20).map { index ->
        val close = 10_000L + if (index >= 18) (index - 17) * 50L else 0L
        val start = origin.plusSeconds(300L * index)
        ObservedBar(start, start.plusSeconds(300), close, close + 50, close - 50)
    }

    private fun flatBars(origin: Instant): List<ObservedBar> = (0 until 20).map { index ->
        val start = origin.plusSeconds(300L * index)
        ObservedBar(start, start.plusSeconds(300), 10_000L, 10_050L, 9_950L)
    }

    private class RecordingEntry(
        private val result: DemoOpenResult = DemoOpenResult("ACCEPTED", "OK", 200, "9001"),
    ) : DemoEntrySubmit {
        val calls = mutableListOf<Triple<String, BigDecimal, Set<String>>>()

        override fun submit(instrumentId: String, amount: BigDecimal, heldInstrumentIds: Set<String>): DemoOpenResult {
            calls += Triple(instrumentId, amount, heldInstrumentIds)
            return result
        }
    }

    private fun payload(recorded: RecordedDecision, assessment: IngestionAssessment): String {
        fun channel(name: String) = assessment.channels.first { it.channel == name }
        return DecisionCycle.decisionPayload(
            recorded.outcome,
            channel("prices"),
            channel("news"),
            channel("macro"),
            channel("fundamentals"),
        )
    }

    private data class LiveDecision(
        val recorded: RecordedDecision,
        val state: OperationalStateService,
    )
}
