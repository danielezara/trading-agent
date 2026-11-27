package com.tradingagent.modules.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JournalTest {
    @Test
    fun `classpath fixture stays balanced and is not the live quote`() {
        val journal = JournalFixtures.loadClasspath()
        assertEquals(2, journal.entryCount())
        assertEquals(75_000L, journal.balance(JournalAccount.BROKER_CASH))
        assertEquals(25_000L, journal.balance(JournalAccount.RESERVED_CASH))
        assertEquals(-100_000L, journal.balance(JournalAccount.EQUITY))
        assertTrue(journal.isBalanced())

        val live = Ledger()
        assertEquals(0, live.journalEntryCount())
        assertTrue(live.journalBalanced())
        assertEquals("UNOBSERVED", live.quote(CashInputs()).status)
        assertEquals(null, live.availableCash())
    }

    @Test
    fun `unbalanced and duplicate entries are rejected`() {
        val journal = Journal()
        assertThrows(IllegalArgumentException::class.java) {
            journal.post(
                JournalEntry(
                    id = "bad",
                    lines = listOf(
                        JournalLine(JournalAccount.BROKER_CASH, 10),
                        JournalLine(JournalAccount.EQUITY, -9),
                    ),
                ),
            )
        }
        journal.post(
            JournalEntry(
                id = "ok",
                lines = listOf(
                    JournalLine(JournalAccount.BROKER_CASH, 10),
                    JournalLine(JournalAccount.EQUITY, -10),
                ),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            journal.post(
                JournalEntry(
                    id = "ok",
                    lines = listOf(
                        JournalLine(JournalAccount.BROKER_CASH, 1),
                        JournalLine(JournalAccount.EQUITY, -1),
                    ),
                ),
            )
        }
    }
}
