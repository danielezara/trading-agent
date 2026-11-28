package com.tradingagent.modules.configuration

import com.tradingagent.kernel.EvidenceStatus
import com.tradingagent.kernel.QualityScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TradingParametersTest {
    @Test
    fun `the versioned file sets 500 EUR and leaves trading closed`() {
        val loaded = TradingParameters.load()
        val again = TradingParameters.load()
        assertEquals(loaded.canonical(), again.canonical())
        assertEquals(loaded.sourceSha256, again.sourceSha256)
        assertEquals(64, loaded.sourceSha256.length)
        assertEquals("daniele", loaded.author)
        assertEquals("EUR", loaded.economic.baseCurrency)
        assertEquals("500.00", loaded.economic.assignedBudgetAmount)
        assertEquals(50_000L, loaded.economic.assignedBudgetMinor)
        assertEquals(1_000, loaded.economic.maxLossBps)
        assertEquals("block", loaded.economic.maxLossAction)
        assertEquals(5_000L, loaded.economic.maxLossMinor())
        assertEquals("days", loaded.economic.horizon)
        assertEquals("bitcoin", loaded.economic.allowlistUniverse)
        assertEquals("long_only", loaded.economic.allowlistDirection)
        assertEquals(1, loaded.economic.allowlistLeverage)
        assertTrue(loaded.economic.allowlistInstrumentIds.isEmpty())
        assertEquals(listOf("BTC"), loaded.economic.entrySymbols)
        assertEquals("MSCI World", loaded.economic.benchmarkName)
        assertEquals("total_return", loaded.economic.benchmarkReturnBasis)
        assertEquals(10_000L, loaded.economic.cashBufferMinor)
        assertEquals(8, loaded.economic.maxPositions)
        assertNull(loaded.economic.maxDrawdownBps)
        assertEquals(50, loaded.economic.riskPerTradeBps)
        assertEquals(200, loaded.economic.aggregateRiskBps)
        assertEquals(1_500, loaded.economic.instrumentExposureBps)
        assertEquals(3_000, loaded.economic.sectorExposureBps)
        assertEquals(2_000, loaded.economic.minCashBps)
        assertEquals(300, loaded.economic.cooldownSeconds)
        assertEquals(390, loaded.economic.maxHoldingCount)
        assertEquals(SpotSelection.FIXED_ASSETS, loaded.spotSelection.assets)
        assertEquals("etoro", loaded.spotSelection.exchange)
        assertEquals("EUR", loaded.spotSelection.quoteCurrency)
        assertEquals("FiveMinutes", loaded.spotSelection.timeframe)
        assertEquals("1", loaded.spotSelection.minVolume.stripTrailingZeros().toPlainString())
        assertEquals("candle_volume", loaded.spotSelection.volumeUnit)
        assertEquals("one_closed_bar", loaded.spotSelection.volumeWindow)
        assertEquals("0.002", loaded.spotSelection.maxSpread.stripTrailingZeros().toPlainString())
        assertEquals("bid_ask_mid_ratio", loaded.spotSelection.spreadUnit)
        assertEquals("none", loaded.spotSelection.spreadModel)
        assertTrue(loaded.technical.calibratable)
        assertEquals(QualityScale.THRESHOLD_MILLIS, loaded.technical.qualityThresholdMillis)
        assertEquals(300, loaded.technical.decisionBarSeconds.toInt())
        assertEquals(850, loaded.technical.int("quality_threshold_millis"))
        assertEquals(650, loaded.technical.int("buy_score_millis"))
        assertEquals(350, loaded.technical.int("exit_score_millis"))
        assertEquals("instrument_id", loaded.technical.tieBreak)
        val gate = EvidenceGate()
        assertFalse(gate.allowsActive())
        assertTrue(gate.rows().all { it.status == EvidenceStatus.UNVERIFIED })
        assertTrue(gate.rows().none { it.status == EvidenceStatus.PASS })
    }
}
