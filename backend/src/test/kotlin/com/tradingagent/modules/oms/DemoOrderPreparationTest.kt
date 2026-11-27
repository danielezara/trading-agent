package com.tradingagent.modules.oms

import com.tradingagent.kernel.EvidenceStatus
import com.tradingagent.kernel.OrderCreationForbidden
import com.tradingagent.modules.configuration.EvidenceGate
import com.tradingagent.modules.risk.PolicyGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DemoOrderPreparationTest {
    @Test
    fun `a draft enforces the minimum size and stays unorderable`() {
        val book = DemoOrderBook()
        val small = book.prepare("key-1", "1001", "BUY", 99, "EUR")
        assertEquals(DraftOutcome.Rejected("MIN_SIZE"), small)
        assertEquals(0, book.size())

        val prepared = book.prepare("key-1", "1001", "BUY", DemoOrderBook.MIN_NOTIONAL_MINOR, "EUR")
        val accepted = prepared as DraftOutcome.Accepted
        assertFalse(accepted.draft.orderable)
        assertEquals("BUY", accepted.draft.side)
        assertEquals(100L, accepted.draft.notionalMinor)
        assertEquals(1, book.size())
    }

    @Test
    fun `the same idempotency key replays one draft and a different body conflicts`() {
        val book = DemoOrderBook()
        val first = book.prepare("same-key", "1001", "BUY", 250, "EUR") as DraftOutcome.Accepted
        val replay = book.prepare("same-key", "1001", "BUY", 250, "EUR")
        assertEquals(DraftOutcome.Replay(first.draft), replay)
        val conflict = book.prepare("same-key", "1001", "BUY", 400, "EUR")
        assertEquals(DraftOutcome.Conflict(first.draft), conflict)
        assertEquals(1, book.size())
        assertEquals(DraftOutcome.Rejected("IDEMPOTENCY_KEY"), book.prepare(" ", "1001", "BUY", 250, "EUR"))
        assertEquals(DraftOutcome.Rejected("SIDE"), book.prepare("other-key", "1001", "SELL", 250, "EUR"))
    }

    @Test
    fun `submitting a draft is rejected and does not mark a capability pass`() {
        val book = DemoOrderBook()
        val draft = (book.prepare("key-9", "1005", "BUY", 250, "EUR") as DraftOutcome.Accepted).draft
        val verdict = UnsentDemoOrders().submit(draft)
        assertFalse(verdict.accepted)
        assertFalse(verdict.brokerCalled)
        assertEquals(PolicyGate.MISSING_RESERVATION, verdict.reason)
        assertThrows(OrderCreationForbidden::class.java) { OrderIntentService().create() }
        val gate = EvidenceGate()
        assertEquals(EvidenceStatus.UNVERIFIED, gate.rows().first { it.controlId == "RF-044" }.status)
        assertEquals(EvidenceStatus.UNVERIFIED, gate.rows().first { it.controlId == "RF-045" }.status)
        assertFalse(gate.allowsActive())
    }
}
