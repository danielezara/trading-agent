package com.tradingagent.modules.strategy

import com.tradingagent.modules.configuration.TradingParameters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class ClosedBarPolicyTest {
    private val parameters = TradingParameters.load()
    private val origin = Instant.parse("2026-11-27T10:00:00Z")

    @Test
    fun `two rising closed bars enter long inside the 500 eur caps and stay unorderable`() {
        val decision = ClosedBarPolicy.decide(input(bars = rising()))
        assertEquals("ENTER", decision.action)
        assertEquals("LONG", decision.direction)
        assertEquals("1001", decision.instrumentId)
        assertEquals(7_500L, decision.notionalMinor)
        assertEquals(MandateControl.OK, decision.reason)
        assertFalse(decision.orderable)
    }

    @Test
    fun `an open bar is ignored and a series that is still open does not enter`() {
        val openOnly = ClosedBarPolicy.decide(
            input(bars = listOf(bar(0, 10_000, endAfterSeconds = 600)), now = origin.plusSeconds(300)),
        )
        assertEquals("NO-TRADE", openOnly.action)
        assertEquals(MandateControl.BAR_OPEN, openOnly.reason)
        assertFalse(openOnly.orderable)

        val withClosed = ClosedBarPolicy.decide(
            input(bars = rising() + bar(20, 10_200, endAfterSeconds = 600), now = origin.plusSeconds(300L * 20)),
        )
        assertEquals("ENTER", withClosed.action)
        assertFalse(withClosed.orderable)
    }

    @Test
    fun `a short a higher leverage crypto and a loss at 10 percent are rejected`() {
        val economic = parameters.economic
        assertEquals(
            MandateControl.DIRECTION,
            MandateControl.reviewEntry(candidate(direction = "SHORT")),
        )
        assertEquals(
            MandateControl.LEVERAGE,
            MandateControl.reviewEntry(candidate(leverage = 2)),
        )
        assertEquals(
            MandateControl.UNIVERSE,
            MandateControl.reviewEntry(candidate(assetClass = "other")),
        )
        assertEquals(
            MandateControl.HORIZON,
            MandateControl.reviewEntry(candidate(economic = economic.copy(horizon = "minutes"))),
        )
        assertEquals(
            MandateControl.BENCHMARK,
            MandateControl.reviewEntry(candidate(economic = economic.copy(benchmarkReturnBasis = "price"))),
        )
        val blocked = ClosedBarPolicy.decide(input(bars = rising(), lossMinor = 5_000))
        assertEquals("NO-TRADE", blocked.action)
        assertEquals(MandateControl.LOSS_LIMIT, blocked.reason)
        assertFalse(blocked.orderable)
        val under = ClosedBarPolicy.decide(input(bars = rising(), lossMinor = 4_999))
        assertEquals("ENTER", under.action)
    }

    @Test
    fun `size above the instrument cap or the remaining budget is rejected`() {
        assertEquals(
            MandateControl.EXPOSURE_LIMIT,
            MandateControl.reviewEntry(candidate(notionalMinor = 7_501)),
        )
        assertEquals(
            MandateControl.BUDGET_LIMIT,
            MandateControl.reviewEntry(candidate(investedMinor = 40_000)),
        )
        val full = ClosedBarPolicy.decide(input(bars = rising(), investedMinor = 40_000))
        assertEquals("NO-TRADE", full.action)
        assertEquals(MandateControl.BUDGET_LIMIT, full.reason)
        assertNull(full.notionalMinor)
    }

    @Test
    fun `an existing long holds exits on two falling closes or the stop and does not close crypto`() {
        val flat = ClosedBarPolicy.decide(
            input(bars = flatBars(), position = HeldPosition(entryCloseMinor = 10_000, barsHeld = 3)),
        )
        assertEquals("HOLD", flat.action)
        assertFalse(flat.orderable)

        val down = ClosedBarPolicy.decide(
            input(bars = falling(), position = HeldPosition(entryCloseMinor = 10_100, barsHeld = 3)),
        )
        assertEquals("EXIT", down.action)
        assertEquals(MandateControl.SCORE, down.reason)
        assertFalse(down.orderable)

        val stopped = ClosedBarPolicy.decide(
            input(
                bars = flatBars(close = 9_000),
                position = HeldPosition(entryCloseMinor = 12_000, barsHeld = 3),
            ),
        )
        assertEquals("EXIT", stopped.action)
        assertEquals(MandateControl.STOP, stopped.reason)

        val expired = ClosedBarPolicy.decide(
            input(bars = rising(), position = HeldPosition(entryCloseMinor = 10_000, barsHeld = 390)),
        )
        assertEquals("EXIT", expired.action)
        assertEquals(MandateControl.HOLDING, expired.reason)

        val crypto = ClosedBarPolicy.decide(
            input(
                bars = falling(),
                position = HeldPosition(entryCloseMinor = 10_000, barsHeld = 400),
                assetClass = "other",
            ),
        )
        assertEquals("HOLD", crypto.action)
        assertEquals(MandateControl.UNIVERSE, crypto.reason)
        assertFalse(crypto.orderable)
    }

    @Test
    fun `missing closed bars and a missing sector do not invent an entry`() {
        val empty = ClosedBarPolicy.decide(input(bars = emptyList()))
        assertEquals("NO-TRADE", empty.action)
        assertEquals(MandateControl.WARMUP, empty.reason)
        assertFalse(empty.orderable)

        val noSector = ClosedBarPolicy.decide(input(bars = rising(), sectorInvestedMinor = null))
        assertEquals("NO-TRADE", noSector.action)
        assertEquals(MandateControl.SECTOR_UNKNOWN, noSector.reason)
    }

    private fun candidate(
        direction: String = "LONG",
        leverage: Int = 1,
        assetClass: String = "stock",
        notionalMinor: Long = 7_500,
        investedMinor: Long = 0,
        economic: com.tradingagent.modules.configuration.EconomicParameters = parameters.economic,
    ) = EntryCandidate(
        priceSource = ClosedBarPolicy.PRICE_SOURCE,
        barClosed = true,
        assetClass = assetClass,
        direction = direction,
        leverage = leverage,
        notionalMinor = notionalMinor,
        investedMinor = investedMinor,
        sectorInvestedMinor = 0,
        lossMinor = 0,
        openPositions = 0,
        economic = economic,
    )

    private fun input(
        bars: List<PriceBar>,
        now: Instant = origin.plusSeconds(300L * 20),
        position: HeldPosition? = null,
        assetClass: String = "stock",
        investedMinor: Long = 0,
        sectorInvestedMinor: Long? = 0,
        lossMinor: Long = 0,
    ) = ClosedBarInput(
        now = now,
        instrumentId = "1001",
        priceSource = ClosedBarPolicy.PRICE_SOURCE,
        bars = bars,
        position = position,
        assetClass = assetClass,
        leverage = 1,
        investedMinor = investedMinor,
        sectorInvestedMinor = sectorInvestedMinor,
        lossMinor = lossMinor,
        openPositions = 0,
        lastExitAt = null,
        economic = parameters.economic,
        technical = parameters.technical,
    )

    private fun rising(): List<PriceBar> = (0 until 20).map { index ->
        val close = 10_000L + if (index >= 18) (index - 17) * 50L else 0L
        bar(index, close)
    }

    private fun falling(): List<PriceBar> = (0 until 20).map { index ->
        val close = when {
            index >= 19 -> 10_050L
            index >= 18 -> 10_080L
            else -> 10_100L
        }
        bar(index, close)
    }

    private fun flatBars(close: Long = 10_000L): List<PriceBar> = (0 until 20).map { index -> bar(index, close) }

    private fun bar(index: Int, close: Long, endAfterSeconds: Long = 300): PriceBar {
        val start = origin.plusSeconds(300L * index)
        return PriceBar(
            start = start,
            end = start.plusSeconds(endAfterSeconds),
            closeMinor = close,
            highMinor = close + 50,
            lowMinor = close - 50,
        )
    }
}
