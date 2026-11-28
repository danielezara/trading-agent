package com.tradingagent.modules.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class LedgerTest {
    @Test
    fun `available cash uses minor units and the budget cap`() {
        val quote = Ledger().quote(
            CashInputs(
                currency = "EUR",
                brokerCashMinor = 2_000_000,
                assignedBudgetMinor = 1_000_000,
                externalHoldingsMinor = 200_000,
                reservedCashMinor = 50_000,
                cashBufferMinor = 20_000,
            ),
        )
        assertEquals("CALCULATED", quote.status)
        assertEquals("NOT_REQUIRED", quote.fxStatus)
        assertEquals(730_000L, quote.availableMinor)
        assertFalse(quote.shortfall)
        assertEquals(730_000L, quote.money(quote.availableMinor)!!.minorUnits)
    }

    @Test
    fun `broker cash caps the budget room`() {
        val quote = Ledger().quote(
            CashInputs(
                currency = "EUR",
                brokerCashMinor = 800_000,
                assignedBudgetMinor = 1_000_000,
                externalHoldingsMinor = 50_000,
                reservedCashMinor = 10_000,
                cashBufferMinor = 20_000,
            ),
        )
        assertEquals(770_000L, quote.availableMinor)
    }

    @Test
    fun `missing observations stay null`() {
        val quote = Ledger().quote(CashInputs(currency = "EUR", assignedBudgetMinor = 1_000))
        assertEquals("UNOBSERVED", quote.status)
        assertEquals("UNOBSERVED", quote.fxStatus)
        assertNull(quote.availableMinor)
        assertEquals(1_000L, quote.assignedBudgetMinor)
        assertNull(Ledger().availableCash())
    }

    @Test
    fun `a shortfall is visible and inputs cannot be negative`() {
        val quote = Ledger().quote(
            CashInputs(
                currency = "EUR",
                brokerCashMinor = 100,
                assignedBudgetMinor = 100,
                externalHoldingsMinor = 0,
                reservedCashMinor = 80,
                cashBufferMinor = 30,
            ),
        )
        assertEquals(-10L, quote.availableMinor)
        assertTrue(quote.shortfall)
        assertThrows(IllegalArgumentException::class.java) {
            CashInputs(currency = "EUR", reservedCashMinor = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CashInputs(assignedBudgetMinor = 1)
        }
    }

    @Test
    fun `usd broker cash is not converted into a eur budget without a quote`() {
        val quote = Ledger().quote(
            CashInputs(
                currency = "EUR",
                brokerCashMinor = 9_940_437,
                brokerCurrency = "USD",
                assignedBudgetMinor = 50_000,
                externalHoldingsMinor = 0,
                reservedCashMinor = 0,
                cashBufferMinor = 0,
            ),
        )
        assertEquals("FX_UNAVAILABLE", quote.status)
        assertEquals("UNCONFIGURED", quote.fxStatus)
        assertNull(quote.availableMinor)
        assertEquals(9_940_437L, quote.brokerCashMinor)
        assertEquals("USD", quote.brokerCurrency)
        assertNull(quote.brokerCashInBaseMinor)
        assertFalse(quote.shortfall)
    }

    @Test
    fun `an explicit fx quote converts with floor division and the configured haircut`() {
        val quote = Ledger().quote(
            CashInputs(
                currency = "EUR",
                brokerCashMinor = 1_080_000,
                brokerCurrency = "USD",
                assignedBudgetMinor = 500_000,
                externalHoldingsMinor = 0,
                reservedCashMinor = 0,
                cashBufferMinor = 0,
                fx = FxQuote(
                    source = "fixture",
                    version = "fx-1",
                    observedAt = Instant.parse("2026-11-27T00:00:00Z"),
                    baseCurrency = "EUR",
                    quoteCurrency = "USD",
                    quoteMinorPerBaseMinorNumerator = 108,
                    quoteMinorPerBaseMinorDenominator = 100,
                    haircutBps = 100,
                ),
            ),
        )
        assertEquals("APPLIED", quote.fxStatus)
        assertEquals("CALCULATED", quote.status)
        assertEquals(990_000L, quote.brokerCashInBaseMinor)
        assertEquals(500_000L, quote.availableMinor)
        assertEquals("EUR", quote.money(quote.availableMinor)!!.currency)
    }

    @Test
    fun `a quote for the wrong pair is not applied`() {
        val quote = Ledger().quote(
            CashInputs(
                currency = "EUR",
                brokerCashMinor = 100,
                brokerCurrency = "USD",
                assignedBudgetMinor = 100,
                externalHoldingsMinor = 0,
                reservedCashMinor = 0,
                cashBufferMinor = 0,
                fx = FxQuote(
                    source = "fixture",
                    version = "fx-gbp",
                    observedAt = Instant.parse("2026-11-27T00:00:00Z"),
                    baseCurrency = "EUR",
                    quoteCurrency = "GBP",
                    quoteMinorPerBaseMinorNumerator = 85,
                    quoteMinorPerBaseMinorDenominator = 100,
                    haircutBps = 0,
                ),
            ),
        )
        assertEquals("FX_UNAVAILABLE", quote.status)
        assertNull(quote.availableMinor)
    }
}
