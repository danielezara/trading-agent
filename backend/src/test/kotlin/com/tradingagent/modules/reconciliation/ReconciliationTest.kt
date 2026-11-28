package com.tradingagent.modules.reconciliation

import com.tradingagent.kernel.EvidenceStatus
import com.tradingagent.kernel.OperationalState
import com.tradingagent.modules.audit.AuditLog
import com.tradingagent.modules.configuration.EvidenceGate
import com.tradingagent.modules.ledger.Journal
import com.tradingagent.modules.ledger.JournalAccount
import com.tradingagent.modules.ledger.JournalEntry
import com.tradingagent.modules.ledger.JournalLine
import com.tradingagent.modules.ledger.Ledger
import com.tradingagent.modules.ledger.LedgerBook
import com.tradingagent.modules.ledger.LedgerPosition
import com.tradingagent.modules.operational.OperationalStateService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ReconciliationTest {
    private val at = Instant.parse("2026-11-27T00:00:30Z")

    @Test
    fun `an empty portfolio read matches an empty ledger`() {
        val report = Reconciliation.compare(broker = emptyBroker(), ledger = LedgerBook.empty(), state = OperationalState.OBSERVATION)
        assertEquals(Reconciliation.MATCH, report.status)
        assertTrue(report.classes.isEmpty())
        assertEquals(0, report.positionDiffCount)
        assertNull(report.cashDiffMinor)
        assertFalse(report.orderSent)
        assertFalse(report.brokerCalled)
        assertEquals(OperationalState.OBSERVATION, report.operationalState)
    }

    @Test
    fun `a broker position missing from the ledger is a mismatch and sends nothing`() {
        val state = OperationalStateService(clock = { at })
        val report = Reconciliation.compare(
            broker = emptyBroker(
                positions = listOf(LedgerPosition("1002", "0.049485")),
                cashMinor = 28_035L,
                cashCurrency = "USD",
            ),
            ledger = LedgerBook.empty(),
            state = state.current(),
        )
        val recorded = ReconciliationJob(AuditLog()).record(report, at, "version", "a".repeat(64))
        assertEquals(Reconciliation.MISMATCH, recorded.status)
        assertEquals(listOf("cash", "position"), recorded.classes)
        assertEquals(1, recorded.positionDiffCount)
        assertFalse(recorded.orderSent)
        assertFalse(recorded.brokerCalled)
        assertEquals(OperationalState.OBSERVATION, state.current())
        assertEquals(OperationalState.OBSERVATION, recorded.operationalState)
        assertEquals(EvidenceStatus.UNVERIFIED, EvidenceGate().rows().first { it.controlId == "RF-045" }.status)
        assertEquals(EvidenceStatus.UNVERIFIED, EvidenceGate().rows().first { it.controlId == "RF-059" }.status)
    }

    @Test
    fun `cash within one minor unit matches and a larger gap does not`() {
        val close = Reconciliation.compare(
            broker = emptyBroker(cashMinor = 101L, cashCurrency = "EUR"),
            ledger = book(cashMinor = 100L, cashCurrency = "EUR"),
            state = OperationalState.OBSERVATION,
        )
        assertEquals(Reconciliation.MATCH, close.status)
        assertFalse(close.classes.contains("cash"))

        val gap = Reconciliation.compare(
            broker = emptyBroker(cashMinor = 102L, cashCurrency = "EUR"),
            ledger = book(cashMinor = 100L, cashCurrency = "EUR"),
            state = OperationalState.OBSERVATION,
        )
        assertEquals(Reconciliation.MISMATCH, gap.status)
        assertEquals(listOf("cash"), gap.classes)
        assertEquals(2L, gap.cashDiffMinor)
        assertFalse(gap.orderSent)
    }

    @Test
    fun `a different currency is a cash mismatch without a conversion`() {
        val report = Reconciliation.compare(
            broker = emptyBroker(cashMinor = 100L, cashCurrency = "USD"),
            ledger = book(cashMinor = 100L, cashCurrency = "EUR"),
            state = OperationalState.OBSERVATION,
        )
        assertEquals(Reconciliation.MISMATCH, report.status)
        assertEquals(listOf("cash"), report.classes)
        assertFalse(report.brokerCalled)
        assertEquals(OperationalState.OBSERVATION, report.operationalState)
    }

    @Test
    fun `equal quantities match and an order only on the broker is a mismatch`() {
        val quantities = Reconciliation.compare(
            broker = emptyBroker(positions = listOf(LedgerPosition("1002", "0.10"))),
            ledger = book(positions = listOf(LedgerPosition("1002", "0.1"))),
            state = OperationalState.OBSERVATION,
        )
        assertEquals(Reconciliation.MATCH, quantities.status)

        val orders = Reconciliation.compare(
            broker = emptyBroker(orderIds = listOf("order-1")),
            ledger = LedgerBook.empty(),
            state = OperationalState.OBSERVATION,
        )
        assertEquals(listOf("order"), orders.classes)
        assertEquals(Reconciliation.MISMATCH, orders.status)
        assertFalse(orders.orderSent)
    }

    @Test
    fun `an empty execution history keeps the fill register empty`() {
        val report = Reconciliation.compare(
            broker = emptyBroker(positions = listOf(LedgerPosition("1002", "1"))),
            ledger = LedgerBook.empty(),
            state = OperationalState.OBSERVATION,
            executionHistoryIds = emptyList(),
        )
        assertEquals(0, report.fillCount)
        assertEquals(FillRegister.EMPTY_HISTORY, report.fillStatus)
        assertEquals(Reconciliation.MISMATCH, report.status)
        assertFalse(report.orderSent)
        assertFalse(report.brokerCalled)
        assertTrue(Reconciliation.payload(report).contains("fill_status=EMPTY_HISTORY"))
        assertFalse(Reconciliation.payload(report).contains("1002"))

        val unread = FillRegister.align(null)
        assertEquals(FillRegister.UNAVAILABLE, unread.status)
        assertTrue(unread.fillIds.isEmpty())

        val one = FillRegister.align(listOf("position-7"))
        assertEquals(listOf("position-7"), one.fillIds)
        assertEquals(FillRegister.POPULATED, one.status)
    }

    @Test
    fun `a missing portfolio read is unavailable rather than a mismatch`() {
        val report = Reconciliation.compare(broker = null, ledger = Ledger().book(), state = OperationalState.OBSERVATION)
        assertEquals(Reconciliation.UNAVAILABLE, report.status)
        assertTrue(report.classes.isEmpty())
        assertFalse(report.orderSent)
        assertEquals(OperationalState.OBSERVATION, report.operationalState)
    }

    @Test
    fun `the recorded comparison stays on the audit chain`() {
        val audit = AuditLog()
        val job = ReconciliationJob(audit)
        val report = Reconciliation.compare(
            broker = emptyBroker(positions = listOf(LedgerPosition("1002", "1"))),
            ledger = Ledger().book(),
            state = OperationalState.OBSERVATION,
        )
        job.record(report, at, "version", "b".repeat(64))
        val event = audit.events().single()
        assertEquals("ReconciliationRecorded", event.eventType)
        assertEquals(AuditLog.sha256(Reconciliation.payload(report)), event.payloadSha256)
        assertTrue(audit.verify().valid)
        assertFalse(job.armed())
        assertEquals(Reconciliation.MISMATCH, job.latest()?.status)
        assertFalse(Reconciliation.payload(report).contains("ETORO"))
    }

    @Test
    fun `a posted broker cash balance is booked and an empty journal is not`() {
        val empty = Ledger().book()
        assertNull(empty.cashMinor)
        val journal = Journal()
        journal.post(
            JournalEntry(
                "cash-1",
                listOf(
                    JournalLine(JournalAccount.BROKER_CASH, 100L),
                    JournalLine(JournalAccount.EQUITY, -100L),
                ),
            ),
        )
        val booked = Ledger(journal).book()
        assertEquals(100L, booked.cashMinor)
        assertNull(booked.cashCurrency)
        assertTrue(booked.positions.isEmpty())
    }

    private fun emptyBroker(
        positions: List<LedgerPosition> = emptyList(),
        cashMinor: Long? = null,
        cashCurrency: String? = null,
        orderIds: List<String> = emptyList(),
    ) = BrokerBooks(positions, cashMinor, cashCurrency, orderIds, emptyList())

    private fun book(
        positions: List<LedgerPosition> = emptyList(),
        cashMinor: Long? = null,
        cashCurrency: String? = null,
    ) = LedgerBook(positions, cashMinor, cashCurrency, emptyList(), emptyList())
}
