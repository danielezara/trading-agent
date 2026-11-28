package com.tradingagent.modules.strategy

import com.tradingagent.modules.configuration.BreakoutParameters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BreakoutStrategyTest {
    @Test
    fun `a closed breakout is filled at the next open, not at the signal close`() {
        val events = BreakoutStrategy.replay("100109", entryBars(), true, params(), 50_000)
        val signal = events.single { it.signal == BreakoutStrategy.ENTER && it.riskReason == BreakoutStrategy.OK }
        val fill = events.single { it.signal == "FILL" && it.signalReason == BreakoutStrategy.BREAKOUT_HIGH }
        assertEquals(12L, signal.signalCloseMinor)
        assertNull(signal.fillMinor)
        assertEquals(BreakoutStrategy.NEXT_OPEN, signal.fillBasis)
        assertEquals(15L, fill.fillMinor)
        assertEquals(12L, fill.signalCloseMinor)
        assertTrue(fill.fillMinor != fill.signalCloseMinor)
        assertFalse(fill.stopGuaranteed)
    }

    @Test
    fun `a second entry on the same pair is blocked and the signal stays separate`() {
        val bars = entryBars()
        val position = BreakoutPosition("100109", 7_500, 15, BreakoutStrategy.stopPrice(15, BigDecimal("0.50")))
        val priced = BreakoutStrategy.signal("100109", bars, position, params())
        val blocked = BreakoutStrategy.authorize(priced, account(position = position), params())
        assertEquals(BreakoutStrategy.ENTER, priced.action)
        assertEquals(BreakoutStrategy.BREAKOUT_HIGH, priced.reason)
        assertEquals(BreakoutStrategy.EXISTING_POSITION, blocked.reason)
        assertFalse(blocked.approved)
        assertNull(BreakoutStrategy.plan(priced, blocked, 20, null))

        val pending = BreakoutStrategy.authorize(
            priced.copy(action = BreakoutStrategy.ENTER),
            account().copy(pendingEntryIds = setOf("100109")),
            params(),
        )
        assertEquals(BreakoutStrategy.PENDING_ENTRY, pending.reason)
    }

    @Test
    fun `simultaneous signals keep the lower instrument id when the position limit is full`() {
        val signals = listOf("2", "10", "1").map { id ->
            BreakoutSignal(id, BreakoutStrategy.ENTER, BreakoutStrategy.BREAKOUT_HIGH, 12)
        }
        val decisions = BreakoutStrategy.allocate(signals, account().copy(enabledIds = setOf("1", "2", "10")), params(maxPositions = 1))
        assertEquals(listOf("1", "10", "2"), decisions.map { it.signal.instrumentId })
        assertEquals(BreakoutStrategy.OK, decisions[0].authorization.reason)
        assertEquals(BreakoutStrategy.ENTER, decisions[1].signal.action)
        assertEquals(BreakoutStrategy.MAX_POSITIONS, decisions[1].authorization.reason)
        assertEquals(BreakoutStrategy.MAX_POSITIONS, decisions[2].authorization.reason)
        assertTrue(decisions.drop(1).all { it.execution == null })
    }

    @Test
    fun `an exit stays allowed after the pair is no longer enabled`() {
        val bars = listOf(
            bar(10, 10, 9, 10),
            bar(10, 12, 8, 8),
        )
        val position = BreakoutPosition("100109", 7_500, 15, 1)
        val priced = BreakoutStrategy.signal("100109", bars, position, params(exitLookback = 1))
        val authorization = BreakoutStrategy.authorize(priced, account(position = position, enabled = false), params())
        val execution = BreakoutStrategy.plan(priced, authorization, nextOpenMinor = 7, stopBar = null)
        assertEquals(BreakoutStrategy.EXIT, priced.action)
        assertEquals(BreakoutStrategy.CHANNEL_LOW, priced.reason)
        assertEquals(8L, priced.closeMinor)
        assertTrue(authorization.approved)
        assertEquals(7L, execution?.fillMinor)
        assertEquals(BreakoutStrategy.NEXT_OPEN, execution?.basis)
        assertEquals(false, execution?.stopGuaranteed)
        val entry = BreakoutStrategy.authorize(
            BreakoutSignal("100109", BreakoutStrategy.ENTER, BreakoutStrategy.BREAKOUT_HIGH, 12),
            account(enabled = false),
            params(),
        )
        assertEquals(BreakoutStrategy.PAIR_DISABLED, entry.reason)
    }

    @Test
    fun `an intrabar stop fills away from the stop price and a short history has no signal`() {
        val position = BreakoutPosition("100109", 7_500, 100, 90)
        val touched = bar(100, 100, 80, 95)
        val stop = BreakoutStrategy.signal("100109", listOf(bar(100, 100, 90, 100), touched), position, params(exitLookback = 1))
        assertEquals(BreakoutStrategy.STOP_INTRABAR, stop.reason)
        val filled = BreakoutStrategy.plan(stop, RiskAuthorization(true, BreakoutStrategy.OK), null, touched, position.stopMinor)
        assertEquals(80L, filled?.fillMinor)
        assertEquals(BreakoutStrategy.INTRABAR_LOW, filled?.basis)
        assertEquals(false, filled?.stopGuaranteed)
        val gap = bar(70, 75, 60, 72)
        val gapped = BreakoutStrategy.plan(
            stop,
            RiskAuthorization(true, BreakoutStrategy.OK),
            null,
            gap,
            position.stopMinor,
        )
        assertEquals(70L, gapped?.fillMinor)
        assertEquals(BreakoutStrategy.GAP_OPEN, gapped?.basis)
        val quiet = BreakoutStrategy.signal("100109", listOf(bar(10, 10, 9, 10)), null, params())
        val authorization = BreakoutStrategy.authorize(quiet, account(), params())
        assertEquals(BreakoutStrategy.HOLD, quiet.action)
        assertEquals(BreakoutStrategy.HISTORY, quiet.reason)
        assertEquals(BreakoutStrategy.NO_SIGNAL, authorization.reason)
    }

    private fun params(maxPositions: Int = 4, exitLookback: Int = 2): BreakoutParameters = BreakoutParameters(
        strategy = BreakoutParameters.STRATEGY_ID,
        timeframe = "FiveMinutes",
        barSeconds = 300,
        entryLookback = 2,
        exitLookback = exitLookback,
        stopDistanceRatio = BigDecimal("0.50"),
        positionSizeMinor = 7_500,
        maxPositionMinor = 7_500,
        maxExposureMinor = 40_000,
        maxPositions = maxPositions,
        priority = BreakoutParameters.PRIORITY,
    )

    private fun account(position: BreakoutPosition? = null, enabled: Boolean = true): BreakoutAccount = BreakoutAccount(
        cashMinor = 50_000,
        positions = listOfNotNull(position),
        pendingEntryIds = emptySet(),
        enabledIds = if (enabled) setOf("100109") else emptySet(),
    )

    private fun entryBars(): List<BreakoutBar> = listOf(
        bar(10, 10, 9, 10),
        bar(10, 11, 10, 11),
        bar(11, 12, 11, 12),
        bar(15, 16, 14, 15),
    )

    private fun bar(open: Long, high: Long, low: Long, close: Long): BreakoutBar =
        BreakoutBar(0, open, high, low, close)
}
