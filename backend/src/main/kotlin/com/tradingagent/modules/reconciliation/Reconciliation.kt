package com.tradingagent.modules.reconciliation

import com.tradingagent.kernel.OperationalState
import com.tradingagent.modules.audit.AuditLog
import com.tradingagent.modules.ledger.LedgerBook
import com.tradingagent.modules.ledger.LedgerPosition
import java.math.BigDecimal
import java.time.Instant

data class BrokerBooks(
    val positions: List<LedgerPosition>,
    val cashMinor: Long?,
    val cashCurrency: String?,
    val orderIds: List<String>,
    val executionIds: List<String>,
)

data class FillRegister(
    val fillIds: List<String>,
    val status: String,
) {
    init {
        require(status == EMPTY_HISTORY || status == UNAVAILABLE || status == POPULATED) {
            "stato del registro sconosciuto"
        }
        if (status == EMPTY_HISTORY || status == UNAVAILABLE) {
            require(fillIds.isEmpty()) { "un registro vuoto non contiene fill" }
        }
    }

    companion object {
        const val EMPTY_HISTORY: String = "EMPTY_HISTORY"
        const val UNAVAILABLE: String = "UNAVAILABLE"
        const val POPULATED: String = "POPULATED"

        /** History null means the read did not happen. An empty list is a real empty history, not a fill. */
        fun align(historyIds: List<String>?): FillRegister = when {
            historyIds == null -> FillRegister(emptyList(), UNAVAILABLE)
            historyIds.isEmpty() -> FillRegister(emptyList(), EMPTY_HISTORY)
            else -> FillRegister(historyIds, POPULATED)
        }
    }
}

data class ReconciliationReport(
    val status: String,
    val classes: List<String>,
    val positionDiffCount: Int,
    val cashDiffMinor: Long?,
    val orderSent: Boolean,
    val brokerCalled: Boolean,
    val operationalState: OperationalState,
    val fillCount: Int = 0,
    val fillStatus: String = FillRegister.UNAVAILABLE,
) {
    init {
        require(!orderSent) { "la riconciliazione non può inviare un ordine" }
        require(!brokerCalled) { "la riconciliazione non chiama il broker" }
        require(operationalState == OperationalState.OBSERVATION) {
            "la riconciliazione non può lasciare OBSERVATION"
        }
        require(
            (fillStatus == FillRegister.POPULATED && fillCount > 0) ||
                (fillStatus != FillRegister.POPULATED && fillCount == 0),
        ) {
            "un registro vuoto non contiene fill"
        }
    }
}

object Reconciliation {
    const val MATCH: String = "MATCH"
    const val MISMATCH: String = "MISMATCH"
    const val UNAVAILABLE: String = "UNAVAILABLE"
    const val CASH_TOLERANCE_MINOR: Long = 1

    fun compare(
        broker: BrokerBooks?,
        ledger: LedgerBook,
        state: OperationalState,
        executionHistoryIds: List<String>? = null,
    ): ReconciliationReport {
        val fills = FillRegister.align(executionHistoryIds)
        if (broker == null) {
            return ReconciliationReport(
                status = UNAVAILABLE,
                classes = emptyList(),
                positionDiffCount = 0,
                cashDiffMinor = null,
                orderSent = false,
                brokerCalled = false,
                operationalState = state,
                fillCount = fills.fillIds.size,
                fillStatus = fills.status,
            )
        }
        val positionDiffs = positionDiffCount(broker.positions, ledger.positions)
        val cashDiff = cashDiffMinor(broker, ledger)
        val classes = buildList {
            if (positionDiffs > 0) {
                add("position")
            }
            if (cashDiff != null) {
                add("cash")
            }
            if (idsDiffer(broker.orderIds, ledger.orderIds) || idsDiffer(broker.executionIds, ledger.executionIds)) {
                add("order")
            }
        }.sorted()
        return ReconciliationReport(
            status = if (classes.isEmpty()) MATCH else MISMATCH,
            classes = classes,
            positionDiffCount = positionDiffs,
            cashDiffMinor = cashDiff,
            orderSent = false,
            brokerCalled = false,
            operationalState = state,
            fillCount = fills.fillIds.size,
            fillStatus = fills.status,
        )
    }

    fun payload(report: ReconciliationReport): String = listOf(
        "status=${report.status}",
        "classes=${report.classes.joinToString(",")}",
        "position_diffs=${report.positionDiffCount}",
        "cash_diff_minor=${report.cashDiffMinor ?: ""}",
        "order_sent=${report.orderSent}",
        "broker_called=${report.brokerCalled}",
        "operational_state=${report.operationalState.wire}",
        "fill_count=${report.fillCount}",
        "fill_status=${report.fillStatus}",
    ).joinToString("\n")

    private fun positionDiffCount(broker: List<LedgerPosition>, ledger: List<LedgerPosition>): Int {
        val brokerIndex = index(broker) ?: return broker.size.coerceAtLeast(1)
        val ledgerIndex = index(ledger) ?: return ledger.size.coerceAtLeast(1)
        val instruments = brokerIndex.keys + ledgerIndex.keys
        return instruments.count { instrument ->
            val left = brokerIndex[instrument]
            val right = ledgerIndex[instrument]
            left == null || right == null || !sameQuantity(left, right)
        }
    }

    private fun index(rows: List<LedgerPosition>): Map<String, String?>? {
        if (rows.any { it.instrumentId.isBlank() }) {
            return null
        }
        val grouped = rows.groupBy { it.instrumentId }
        if (grouped.any { it.value.size > 1 }) {
            return null
        }
        return grouped.mapValues { entry -> entry.value.single().quantity }
    }

    private fun sameQuantity(left: String?, right: String?): Boolean {
        val leftDecimal = decimal(left) ?: return false
        val rightDecimal = decimal(right) ?: return false
        return leftDecimal.compareTo(rightDecimal) == 0
    }

    private fun decimal(raw: String?): BigDecimal? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching { BigDecimal(raw) }.getOrNull()
    }

    private fun cashDiffMinor(broker: BrokerBooks, ledger: LedgerBook): Long? {
        val brokerCash = broker.cashMinor
        val ledgerCash = ledger.cashMinor
        if (brokerCash == null && ledgerCash == null) {
            return null
        }
        if (brokerCash == null || ledgerCash == null) {
            return brokerCash ?: ledgerCash
        }
        val brokerCurrency = broker.cashCurrency
        val ledgerCurrency = ledger.cashCurrency
        if (
            brokerCurrency.isNullOrBlank() || ledgerCurrency.isNullOrBlank() ||
            !brokerCurrency.equals(ledgerCurrency, ignoreCase = true)
        ) {
            return brokerCash
        }
        val diff = Math.subtractExact(brokerCash, ledgerCash)
        val gap = if (diff < 0) Math.negateExact(diff) else diff
        return if (gap > CASH_TOLERANCE_MINOR) gap else null
    }

    private fun idsDiffer(broker: List<String>, ledger: List<String>): Boolean {
        fun clean(ids: List<String>) = ids.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return clean(broker) != clean(ledger)
    }
}

class ReconciliationJob(
    private val auditLog: AuditLog,
) {
    private var latest: ReconciliationReport? = null

    fun armed(): Boolean = false

    fun latest(): ReconciliationReport? = latest

    fun record(
        report: ReconciliationReport,
        occurredAt: Instant,
        configVersionId: String,
        configContentSha256: String,
    ): ReconciliationReport {
        check(report.operationalState == OperationalState.OBSERVATION) {
            "la riconciliazione non può lasciare OBSERVATION"
        }
        check(!report.orderSent && !report.brokerCalled) { "la riconciliazione non invia ordini" }
        val payload = Reconciliation.payload(report)
        auditLog.append(
            occurredAt = occurredAt,
            correlationId = AuditLog.sha256(payload).take(16),
            eventType = "ReconciliationRecorded",
            configVersionId = configVersionId,
            configContentSha256 = configContentSha256,
            componentVersion = COMPONENT_VERSION,
            payload = payload,
        )
        latest = report
        return report
    }

    companion object {
        const val COMPONENT_VERSION: String = "observation-reconciliation/1"
    }
}
