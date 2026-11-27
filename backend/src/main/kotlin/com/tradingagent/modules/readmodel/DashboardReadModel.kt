package com.tradingagent.modules.readmodel

import com.tradingagent.kernel.Money
import com.tradingagent.kernel.OperationalState
import com.tradingagent.modules.ledger.Ledger
import com.tradingagent.modules.operational.OperationalStateService

data class PortfolioSnapshot(
    val operationalState: OperationalState,
    val tradingEnabled: Boolean,
    val equity: Money?,
    val brokerCash: Money?,
    val assignedBudget: Money?,
    val availableCash: Money?,
    val reservedCash: Money?,
    val positionCount: Int?,
    val unknownOrderCount: Int?,
)

class DashboardReadModel(
    private val operationalStateService: OperationalStateService,
    private val ledger: Ledger,
) {
    fun portfolio(): PortfolioSnapshot {
        val available = ledger.availableCash()
        return PortfolioSnapshot(
            operationalState = operationalStateService.current(),
            tradingEnabled = false,
            equity = null,
            brokerCash = null,
            assignedBudget = null,
            availableCash = available,
            reservedCash = null,
            positionCount = null,
            unknownOrderCount = null,
        )
    }
}
