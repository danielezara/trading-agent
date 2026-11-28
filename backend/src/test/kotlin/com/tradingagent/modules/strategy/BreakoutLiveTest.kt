package com.tradingagent.modules.strategy

import com.tradingagent.modules.configuration.BreakoutParameters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class BreakoutLiveTest {
    @Test
    fun `a closed breakout becomes one orderable long entry`() {
        val decision = BreakoutLive.decide(input(listOf(instrument("100109", breakoutBars()))))
        assertEquals("ENTER", decision.action)
        assertEquals("LONG", decision.direction)
        assertEquals("100109", decision.instrumentId)
        assertEquals(7_500L, decision.notionalMinor)
        assertTrue(decision.orderable)
        assertEquals(BreakoutStrategy.BREAKOUT_HIGH, decision.reason)
    }

    @Test
    fun `an open bitcoin order blocks a second entry and keeps the signal`() {
        val decision = BreakoutLive.decide(
            input(listOf(instrument("100000", breakoutBars())), openIds = setOf("100000")),
        )
        assertEquals("NO-TRADE", decision.action)
        assertEquals("100000", decision.instrumentId)
        assertFalse(decision.orderable)
        assertEquals("breakout_high/existing_position", decision.reason)
    }

    @Test
    fun `simultaneous entries keep the lower instrument id inside the position limit`() {
        val decision = BreakoutLive.decide(
            input(
                listOf(instrument("2", breakoutBars()), instrument("1", breakoutBars())),
                maxPositions = 1,
            ),
        )
        assertEquals("ENTER", decision.action)
        assertEquals("1", decision.instrumentId)
        assertTrue(decision.orderable)
    }

    @Test
    fun `an exit on a pair that is no longer enabled is not a buy`() {
        val bars = listOf(bar(0, 100, 110, 90), bar(300, 80, 100, 70))
        val decision = BreakoutLive.decide(
            input(
                candidates = listOf(instrument("100109", bars)),
                openIds = setOf("100109"),
                enabled = emptySet(),
                exitLookback = 1,
            ),
        )
        assertEquals("NO-TRADE", decision.action)
        assertEquals(BreakoutStrategy.CHANNEL_LOW, decision.reason)
        assertFalse(decision.orderable)
    }

    private fun input(
        candidates: List<InstrumentBars>,
        openIds: Set<String> = emptySet(),
        enabled: Set<String>? = null,
        maxPositions: Int = 4,
        exitLookback: Int = 2,
    ): BreakoutCycleInput = BreakoutCycleInput(
        candidates = candidates,
        openIds = openIds,
        enabledIds = enabled ?: candidates.map { it.instrumentId }.toSet() + openIds,
        parameters = BreakoutParameters(
            strategy = BreakoutParameters.STRATEGY_ID,
            timeframe = "FiveMinutes",
            barSeconds = 300,
            entryLookback = 2,
            exitLookback = exitLookback,
            stopDistanceRatio = BigDecimal("0.02"),
            positionSizeMinor = 7_500,
            maxPositionMinor = 7_500,
            maxExposureMinor = 40_000,
            maxPositions = maxPositions,
            priority = BreakoutParameters.PRIORITY,
        ),
        entriesBlocked = false,
        lossBlocksEntries = false,
    )

    private fun instrument(id: String, bars: List<PriceBar>): InstrumentBars = InstrumentBars(id, "crypto", bars)

    private fun breakoutBars(): List<PriceBar> = listOf(
        bar(0, 10, 11, 9),
        bar(300, 10, 12, 10),
        bar(600, 13, 14, 12),
    )

    private fun bar(start: Long, close: Long, high: Long, low: Long): PriceBar =
        PriceBar(Instant.ofEpochSecond(start), Instant.ofEpochSecond(start + 300), close, high, low)
}
