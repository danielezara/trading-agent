package com.tradingagent.modules.strategy

import com.tradingagent.config.DemoKeySlot
import com.tradingagent.kernel.OperationalState
import com.tradingagent.kernel.ReasonCode
import com.tradingagent.modules.configuration.TradingParameters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Instant

class StrategyEngineTest {
    private val engine = StrategyEngine()
    private val at = Instant.parse("2026-11-27T00:00:30Z")

    @Test
    fun `closed default is a stable no trade`() {
        val first = engine.evaluate()
        val second = engine.evaluate()
        assertEquals("NO-TRADE", first.action)
        assertEquals(ReasonCode.CAPABILITY_UNVERIFIED, first.reasonCode)
        assertEquals(first.inputHash, second.inputHash)
        assertEquals(first.reasonCodes, second.reasonCodes)
        assertEquals(64, first.inputHash.length)
        assertEquals("EUR", TradingParameters.current().economic.baseCurrency)
        assertEquals(50_000L, TradingParameters.current().economic.assignedBudgetMinor)
        val missingKeys = DemoKeySlot.fromEnvironment(emptyMap())
        assertFalse(missingKeys.complete())
        assertEquals(ReasonCode.CAPABILITY_UNVERIFIED, first.reasonCode)
        assertEquals("NO-TRADE", first.action)
    }

    @Test
    fun `same observation input always yields the same decision`() {
        val input = DecisionInput(
            operationalState = OperationalState.OBSERVATION,
            evidenceAllowsActive = false,
            channels = listOf(
                ChannelObservation("prices", 2, stale = true, conflict = true, qualityMillis = 0),
            ),
            evaluatedAt = at,
        )
        val first = engine.evaluate(input)
        val second = engine.evaluate(input)
        assertEquals("NO-TRADE", first.action)
        assertEquals(
            listOf(
                ReasonCode.CAPABILITY_UNVERIFIED,
                ReasonCode.DATA_MISSING,
                ReasonCode.DATA_CONFLICT,
                ReasonCode.DATA_STALE,
            ),
            first.reasonCodes,
        )
        assertEquals(first.inputHash, second.inputHash)
        assertEquals(first.summary, second.summary)
    }

    @Test
    fun `a complete active input is still no trade`() {
        val channels = StrategyEngine.MANDATORY_CHANNELS.map { name ->
            ChannelObservation(
                channel = name,
                acceptedCount = 1,
                stale = false,
                conflict = false,
                qualityMillis = 1000,
            )
        }
        val input = DecisionInput(
            operationalState = OperationalState.ACTIVE,
            evidenceAllowsActive = true,
            channels = channels,
            evaluatedAt = at,
        )
        val outcome = engine.evaluate(input)
        assertEquals("NO-TRADE", outcome.action)
        assertEquals(listOf(ReasonCode.WARMUP_INCOMPLETE), outcome.reasonCodes)
        assertEquals(outcome.inputHash, engine.evaluate(input).inputHash)
    }
}
