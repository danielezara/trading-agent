package com.tradingagent.modules.readmodel

import com.tradingagent.kernel.EvidenceStatus
import com.tradingagent.kernel.OperationalState
import com.tradingagent.modules.brokerread.BrokerCashBalance
import com.tradingagent.modules.brokerread.CapabilityProbe
import com.tradingagent.modules.brokerread.CashBalanceRead
import com.tradingagent.modules.brokerread.EcbEuroFx
import com.tradingagent.modules.brokerread.ClosedTrade
import com.tradingagent.modules.brokerread.ClosedTradeRead
import com.tradingagent.modules.brokerread.DemoKeyMaterial
import com.tradingagent.modules.brokerread.DisplayQuote
import com.tradingagent.modules.brokerread.HttpGet
import com.tradingagent.modules.brokerread.InboundHttp
import com.tradingagent.modules.brokerread.InstrumentClassRead
import com.tradingagent.modules.brokerread.InstrumentClassification
import com.tradingagent.modules.brokerread.InstrumentNameRead
import com.tradingagent.modules.brokerread.PositionQuoteRead
import com.tradingagent.modules.configuration.ConfigurationService
import com.tradingagent.modules.configuration.EvidenceGate
import com.tradingagent.modules.ledger.Ledger
import com.tradingagent.modules.operational.OperationalStateService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class DashboardReadModelTest {
    @Test
    fun `observed usd cash stays beside the eur budget and available cash stays absent`() {
        val gate = EvidenceGate()
        val probe = CapabilityProbe(
            evidenceGate = gate,
            keys = {
                DemoKeyMaterial.fromEnvironment(
                    mapOf("ETORO_DEMO_API_KEY" to "test-api-key", "ETORO_DEMO_USER_KEY" to "test-user-key"),
                )
            },
            transport = HttpGet { _, _ ->
                InboundHttp(
                    status = 200,
                    retryAfterSeconds = null,
                    body = """{"clientPortfolio":{"credit":280.35,"accountCurrencyId":1,"positions":[]}}""",
                )
            },
            clock = { Instant.parse("2026-11-27T00:00:00Z") },
        )
        probe.refresh()
        val snapshot = DashboardReadModel(
            operationalStateService = OperationalStateService(clock = { Instant.parse("2026-11-27T00:00:00Z") }),
            ledger = Ledger(),
            configurationService = ConfigurationService(),
            capabilityProbe = probe,
        ).portfolio()

        assertEquals("USD", snapshot.brokerCash?.currency)
        assertEquals(28_035L, snapshot.brokerCash?.minorUnits)
        assertEquals("EUR", snapshot.assignedBudget?.currency)
        assertEquals(50_000L, snapshot.assignedBudget?.minorUnits)
        assertEquals(0, snapshot.positionCount)
        assertEquals(emptyList<com.tradingagent.modules.brokerread.ObservedPosition>(), snapshot.positions)
        assertEquals(emptyList<com.tradingagent.modules.brokerread.ObservedOrder>(), snapshot.orders)
        assertEquals(emptyList<com.tradingagent.modules.brokerread.ObservedOrder>(), snapshot.executions)
        assertNull(snapshot.availableCash)
        assertEquals(Ledger.STATUS_FX_UNAVAILABLE, snapshot.ledgerStatus)
        assertEquals(Ledger.FX_UNCONFIGURED, snapshot.fxStatus)
        assertEquals(EvidenceStatus.PASS, gate.rows().first { it.controlId == "RF-044" }.status)
        assertEquals(EvidenceStatus.UNVERIFIED, gate.rows().first { it.controlId == "RF-045" }.status)
        assertEquals(EvidenceStatus.UNVERIFIED, gate.rows().first { it.controlId == "RF-059" }.status)
        assertFalse(gate.allowsActive())
        assertFalse(snapshot.tradingEnabled)
    }

    @Test
    fun `a later overview read sees a position opened after the previous read`() {
        val bodies = ArrayDeque(
            listOf(
                """{"clientPortfolio":{"credit":10,"accountCurrencyId":1,"positions":[{"instrumentID":1,"units":1,"amount":1}]}}""",
                """{"clientPortfolio":{"credit":10,"accountCurrencyId":1,"positions":[{"instrumentID":1,"units":1,"amount":1},{"instrumentID":2,"units":2,"amount":2}]}}""",
            ),
        )
        var calls = 0
        val probe = CapabilityProbe(
            evidenceGate = EvidenceGate(),
            keys = {
                DemoKeyMaterial.fromEnvironment(
                    mapOf("ETORO_DEMO_API_KEY" to "test-api-key", "ETORO_DEMO_USER_KEY" to "test-user-key"),
                )
            },
            transport = HttpGet { _, _ ->
                calls += 1
                InboundHttp(status = 200, retryAfterSeconds = null, body = bodies.removeFirst())
            },
            clock = { Instant.parse("2026-11-27T00:00:00Z") },
        )
        val model = DashboardReadModel(
            operationalStateService = OperationalStateService(clock = { Instant.parse("2026-11-27T00:00:00Z") }),
            ledger = Ledger(),
            configurationService = ConfigurationService(),
            capabilityProbe = probe,
        )

        assertEquals(listOf("1"), model.portfolio().positions?.map { it.instrumentId })
        assertEquals(listOf("1", "2"), model.portfolio().positions?.map { it.instrumentId })
        assertEquals(2, calls)
    }

    @Test
    fun `a missing quote stays blank and a present bid is kept`() {
        val asked = mutableListOf<List<String>>()
        val snapshot = model(
            """{"clientPortfolio":{"credit":10,"accountCurrencyId":1,"positions":[{"instrumentID":6270,"units":1,"amount":1},{"instrumentID":100032,"units":2,"amount":2}]}}""",
            PositionQuoteRead { ids ->
                asked += ids
                mapOf("6270" to DisplayQuote("6270", BigDecimal("1.25"), Instant.parse("2026-11-27T09:30:00Z")))
            },
        ).portfolio()
        assertEquals(listOf(listOf("6270", "100032")), asked)
        assertEquals(0, snapshot.quotes?.get("6270")?.price?.compareTo(BigDecimal("1.25")))
        assertEquals(Instant.parse("2026-11-27T09:30:00Z"), snapshot.quotes?.get("6270")?.quotedAt)
        assertNull(snapshot.quotes?.get("100032"))
        assertEquals(OperationalState.OBSERVATION, snapshot.operationalState)
        assertFalse(snapshot.tradingEnabled)
    }

    @Test
    fun `a display name is attached and a missing name stays absent`() {
        val snapshot = model(
            """{"clientPortfolio":{"credit":10,"accountCurrencyId":1,"positions":[{"instrumentID":6270,"units":1,"amount":1},{"instrumentID":100032,"units":2,"amount":2}]}}""",
            PositionQuoteRead { emptyMap() },
            InstrumentNameRead { ids ->
                assertEquals(listOf("6270", "100032"), ids)
                mapOf("6270" to "Apple")
            },
        ).portfolio()
        assertEquals("Apple", snapshot.instrumentNames?.get("6270"))
        assertNull(snapshot.instrumentNames?.get("100032"))
        assertEquals(OperationalState.OBSERVATION, snapshot.operationalState)
        assertFalse(snapshot.tradingEnabled)
    }

    @Test
    fun `closed demo history is read with the connected portfolio and stays in observation`() {
        var seen: Instant? = null
        val snapshot = model(
            """{"clientPortfolio":{"credit":10,"accountCurrencyId":1,"positions":[]}}""",
            PositionQuoteRead { emptyMap() },
            closedTrades = ClosedTradeRead { now ->
                seen = now
                listOf(ClosedTrade("7", "1002", "BUY", "1.5", "-2.5", "2026-11-01T12:00:00Z"))
            },
        ).portfolio()
        assertEquals(Instant.parse("2026-11-27T00:00:00Z"), seen)
        assertEquals("-2.5", snapshot.closedTrades?.single()?.netProfit)
        assertEquals(OperationalState.OBSERVATION, snapshot.operationalState)
        assertFalse(snapshot.tradingEnabled)
    }

    @Test
    fun `an official stock stays inside and a missing type stays unknown`() {
        val snapshot = model(
            """{"clientPortfolio":{"credit":10,"accountCurrencyId":1,"positions":[{"instrumentID":6270,"units":1,"amount":1},{"instrumentID":100032,"units":1,"amount":1}]}}""",
            PositionQuoteRead { emptyMap() },
            classes = InstrumentClassRead {
                mapOf("6270" to InstrumentClassification("stock", "inside"))
            },
        ).portfolio()
        assertEquals("stock", snapshot.instrumentClasses?.get("6270")?.assetClass)
        assertEquals("inside", snapshot.instrumentClasses?.get("6270")?.allowlist)
        assertNull(snapshot.instrumentClasses?.get("100032"))
        assertEquals(OperationalState.OBSERVATION, snapshot.operationalState)
        assertFalse(snapshot.tradingEnabled)
    }

    @Test
    fun `a fresh ecb reference values usd cash in eur and a stale reference leaves it null`() {
        val body = """<Cube time='2026-11-26'><Cube currency='USD' rate='1.0975'/></Cube>"""
        val seen = mutableListOf<java.net.URI>()
        val transport = HttpGet { uri, _ ->
            seen += uri
            InboundHttp(200, null, body)
        }
        val fresh = model(
            """{"clientPortfolio":{"credit":280.35,"accountCurrencyId":1,"positions":[]}}""",
            PositionQuoteRead { emptyMap() },
            euroFx = EcbEuroFx(transport),
            clock = { Instant.parse("2026-11-27T10:00:00Z") },
        ).portfolio()
        assertEquals(listOf(EcbEuroFx.ENDPOINT), seen)
        assertEquals(Ledger.FX_APPLIED, fresh.fxStatus)
        assertEquals(Ledger.STATUS_CALCULATED, fresh.ledgerStatus)
        assertEquals("ecb-eurofxref", fresh.fx?.source)
        assertEquals("2026-11-26", fresh.fx?.version)
        assertEquals(100, fresh.fx?.haircutBps)
        assertEquals(Instant.parse("2026-11-26T15:00:00Z"), fresh.fx?.observedAt)
        assertNull(fresh.availableCash)
        assertNull(fresh.cashBalances)
        assertEquals(OperationalState.OBSERVATION, fresh.operationalState)
        assertFalse(fresh.tradingEnabled)

        val stale = model(
            """{"clientPortfolio":{"credit":280.35,"accountCurrencyId":1,"positions":[]}}""",
            PositionQuoteRead { emptyMap() },
            euroFx = EcbEuroFx(transport),
            clock = { Instant.parse("2026-12-02T10:00:00Z") },
        ).portfolio()
        assertNull(stale.availableCash)
        assertNull(stale.fx)
        assertEquals(Ledger.STATUS_FX_UNAVAILABLE, stale.ledgerStatus)
    }

    @Test
    fun `shown cash is the native etoro list and not a converted balance`() {
        val snapshot = model(
            """{"clientPortfolio":{"credit":280.35,"accountCurrencyId":1,"positions":[]}}""",
            PositionQuoteRead { emptyMap() },
            cashBalances = CashBalanceRead {
                listOf(
                    BrokerCashBalance("Cash", 204_244L, "EUR"),
                    BrokerCashBalance("Trading", 66_505L, "USD"),
                )
            },
        ).portfolio()
        assertEquals("EUR", snapshot.cashBalances?.get(0)?.currency)
        assertEquals(204_244L, snapshot.cashBalances?.get(0)?.minorUnits)
        assertEquals("Cash", snapshot.cashBalances?.get(0)?.accountType)
        assertEquals("USD", snapshot.cashBalances?.get(1)?.currency)
        assertEquals(66_505L, snapshot.cashBalances?.get(1)?.minorUnits)
        assertEquals(2, snapshot.cashBalances?.size)
        assertNull(snapshot.availableCash)
        assertEquals("USD", snapshot.brokerCash?.currency)
        assertFalse(snapshot.tradingEnabled)
        assertEquals(OperationalState.OBSERVATION, snapshot.operationalState)
    }

    @Test
    fun `quotes are not requested when there are no positions`() {
        var calls = 0
        val snapshot = model(
            """{"clientPortfolio":{"credit":10,"accountCurrencyId":1,"positions":[]}}""",
            PositionQuoteRead {
                calls += 1
                emptyMap()
            },
        ).portfolio()
        assertEquals(0, calls)
        assertNull(snapshot.quotes)
        assertEquals(emptyList<Any>(), snapshot.positions)
    }

    private fun model(
        body: String,
        quotes: PositionQuoteRead,
        names: InstrumentNameRead = InstrumentNameRead { null },
        closedTrades: ClosedTradeRead = ClosedTradeRead { null },
        classes: InstrumentClassRead = InstrumentClassRead { null },
        euroFx: com.tradingagent.modules.brokerread.EuroFxRead = com.tradingagent.modules.brokerread.EuroFxRead { _, _, _ -> null },
        cashBalances: CashBalanceRead = CashBalanceRead { null },
        clock: () -> Instant = { Instant.parse("2026-11-27T00:00:00Z") },
    ) = DashboardReadModel(
        operationalStateService = OperationalStateService(clock = { Instant.parse("2026-11-27T00:00:00Z") }),
        ledger = Ledger(),
        configurationService = ConfigurationService(),
        capabilityProbe = CapabilityProbe(
            evidenceGate = EvidenceGate(),
            keys = {
                DemoKeyMaterial.fromEnvironment(
                    mapOf("ETORO_DEMO_API_KEY" to "test-api-key", "ETORO_DEMO_USER_KEY" to "test-user-key"),
                )
            },
            transport = HttpGet { _, _ -> InboundHttp(200, null, body) },
            clock = { Instant.parse("2026-11-27T00:00:00Z") },
        ),
        positionQuotes = quotes,
        instrumentNames = names,
        instrumentClasses = classes,
        closedTrades = closedTrades,
        euroFx = euroFx,
        cashBalances = cashBalances,
        clock = clock,
    )
}
