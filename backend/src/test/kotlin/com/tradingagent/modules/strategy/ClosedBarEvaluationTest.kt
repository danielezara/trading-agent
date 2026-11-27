package com.tradingagent.modules.strategy

import com.tradingagent.modules.configuration.ConfigurationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ClosedBarEvaluationTest {
    private val now: Instant = Instant.parse("2026-11-27T10:30:00Z")
    private val parameters = ConfigurationService().active().parameters

    @Test
    fun `a short history stays no trade`() {
        val decision = ClosedBarEvaluation.decide(
            now,
            BarBook(0, listOf(InstrumentBars("1001", "stock", bars(2)))),
            parameters.economic,
            parameters.technical,
        )
        assertEquals("NO-TRADE", decision.action)
        assertEquals("WARMUP", decision.reason)
        assertFalse(decision.orderable)
    }

    @Test
    fun `enough rising stock bars are evaluated and stay unorderable`() {
        val rising = ClosedBarEvaluation.decide(
            now,
            BarBook(0, listOf(InstrumentBars("1001", "stock", bars(21, step = 10)))),
            parameters.economic,
            parameters.technical,
        )
        assertEquals("NO-TRADE", rising.action)
        assertEquals("SECTOR_UNKNOWN", rising.reason)
        assertEquals("1001", rising.instrumentId)
        assertFalse(rising.orderable)

        val crowded = ClosedBarEvaluation.decide(
            now,
            BarBook(8, listOf(InstrumentBars("1001", "stock", bars(21, step = 10)))),
            parameters.economic,
            parameters.technical,
        )
        assertEquals("NO-TRADE", crowded.action)
        assertEquals("EXPOSURE_LIMIT", crowded.reason)
        assertFalse(crowded.orderable)
    }

    @Test
    fun `a stock entry check is reported ahead of a non stock instrument`() {
        val decision = ClosedBarEvaluation.decide(
            now,
            BarBook(
                0,
                listOf(
                    InstrumentBars("1000", "other", bars(21, step = 10)),
                    InstrumentBars("1001", "stock", bars(21)),
                ),
            ),
            parameters.economic,
            parameters.technical,
        )
        assertEquals("NO-TRADE", decision.action)
        assertEquals("1001", decision.instrumentId)
        assertEquals("SCORE", decision.reason)
        assertFalse(decision.orderable)
    }

    @Test
    fun `an eur loss at ten percent blocks a new entry and a dollar loss does not`() {
        val breached = ClosedBarEvaluation.decide(
            now,
            BarBook(
                0,
                listOf(InstrumentBars("1001", "stock", bars(21, step = 10))),
                reportedLossMinor = 5_000,
                reportedLossCurrency = "EUR",
            ),
            parameters.economic,
            parameters.technical,
        )
        assertEquals("NO-TRADE", breached.action)
        assertEquals("LOSS_LIMIT", breached.reason)
        assertEquals("1001", breached.instrumentId)
        assertFalse(breached.orderable)
        assertTrue(
            BudgetLoss.blocksNewEntries(5_000, "EUR", parameters.economic),
        )

        val dollars = ClosedBarEvaluation.decide(
            now,
            BarBook(
                0,
                listOf(InstrumentBars("1001", "stock", bars(21, step = 10))),
                reportedLossMinor = 5_000,
                reportedLossCurrency = "USD",
            ),
            parameters.economic,
            parameters.technical,
        )
        assertEquals("NO-TRADE", dollars.action)
        assertEquals("SECTOR_UNKNOWN", dollars.reason)
        assertFalse(dollars.orderable)
        assertFalse(BudgetLoss.blocksNewEntries(5_000, "USD", parameters.economic))
        assertFalse(BudgetLoss.blocksNewEntries(4_999, "EUR", parameters.economic))
    }

    @Test
    fun `sixteen demo positions reject the entry`() {
        val decision = ClosedBarEvaluation.decide(
            now,
            BarBook(16, listOf(InstrumentBars("1001", "stock", bars(21, step = 10)))),
            parameters.economic,
            parameters.technical,
        )
        assertEquals("NO-TRADE", decision.action)
        assertEquals("EXPOSURE_LIMIT", decision.reason)
        assertEquals("1001", decision.instrumentId)
        assertFalse(decision.orderable)
    }

    @Test
    fun `a dollar loss uses an etoro rate and stays open when that rate is absent`() {
        val rate = listOf(PayloadRate("EUR", "USD", "1.09626"))
        val under = ClosedBarEvaluation.decide(
            now,
            BarBook(
                0,
                listOf(InstrumentBars("1001", "stock", bars(21, step = 10))),
                reportedLossMinor = 1_389,
                reportedLossCurrency = "USD",
                payloadRates = rate,
            ),
            parameters.economic,
            parameters.technical,
        )
        assertEquals("SECTOR_UNKNOWN", under.reason)
        assertFalse(BudgetLoss.blocksNewEntries(1_389, "USD", parameters.economic, rate))

        val breached = ClosedBarEvaluation.decide(
            now,
            BarBook(
                0,
                listOf(InstrumentBars("1001", "stock", bars(21, step = 10))),
                reportedLossMinor = 6_000,
                reportedLossCurrency = "USD",
                payloadRates = rate,
            ),
            parameters.economic,
            parameters.technical,
        )
        assertEquals("LOSS_LIMIT", breached.reason)
        assertEquals("NO-TRADE", breached.action)
        assertFalse(breached.orderable)
        assertTrue(BudgetLoss.blocksNewEntries(6_000, "USD", parameters.economic, rate))
        assertFalse(BudgetLoss.blocksNewEntries(6_000, "USD", parameters.economic))
    }

    @Test
    fun `no instruments stay no trade`() {
        val decision = ClosedBarEvaluation.decide(now, BarBook(0, emptyList()), parameters.economic, parameters.technical)
        assertEquals("NO-TRADE", decision.action)
        assertEquals("WARMUP", decision.reason)
        assertFalse(decision.orderable)
    }

    private fun bars(count: Int, step: Long = 0): List<PriceBar> {
        val origin = now.minusSeconds(300L * count)
        return (0 until count).map { index ->
            val close = 10_000_000_000L + index * step
            PriceBar(
                start = origin.plusSeconds(300L * index),
                end = origin.plusSeconds(300L * (index + 1)),
                closeMinor = close,
                highMinor = close + 50_000_000L,
                lowMinor = close - 50_000_000L,
            )
        }
    }
}
