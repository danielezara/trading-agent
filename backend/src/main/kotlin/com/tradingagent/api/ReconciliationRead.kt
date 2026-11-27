package com.tradingagent.api

import com.tradingagent.kernel.Money
import com.tradingagent.modules.brokerread.ObservedOrder
import com.tradingagent.modules.brokerread.ObservedPosition
import com.tradingagent.modules.ledger.LedgerPosition
import com.tradingagent.modules.reconciliation.BrokerBooks
import com.tradingagent.modules.reconciliation.ReconciliationReport

fun brokerBooks(
    positions: List<ObservedPosition>?,
    cash: Money?,
    orders: List<ObservedOrder>?,
    executions: List<ObservedOrder>?,
): BrokerBooks = BrokerBooks(
    positions = positions.orEmpty().map { position -> LedgerPosition(position.instrumentId, position.quantity) },
    cashMinor = cash?.minorUnits,
    cashCurrency = cash?.currency,
    orderIds = orders.orEmpty().map { order -> order.orderId },
    executionIds = executions.orEmpty().map { order -> order.orderId },
)

fun reconciliationData(report: ReconciliationReport) = ReconciliationData(
    status = report.status,
    classes = report.classes,
    positionDiffCount = report.positionDiffCount,
    cashDiffMinor = report.cashDiffMinor,
    orderSent = report.orderSent,
    brokerCalled = report.brokerCalled,
    operationalState = report.operationalState.wire,
    fillCount = report.fillCount,
    fillStatus = report.fillStatus,
)

data class ReconciliationData(
    val status: String,
    val classes: List<String>,
    val positionDiffCount: Int,
    val cashDiffMinor: Long?,
    val orderSent: Boolean,
    val brokerCalled: Boolean,
    val operationalState: String,
    val fillCount: Int,
    val fillStatus: String,
)
