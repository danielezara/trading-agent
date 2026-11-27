package com.tradingagent.modules.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
