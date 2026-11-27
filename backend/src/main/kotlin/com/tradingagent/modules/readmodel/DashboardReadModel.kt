package com.tradingagent.modules.readmodel

import com.tradingagent.kernel.Money
import com.tradingagent.kernel.OperationalState
import com.tradingagent.modules.configuration.ConfigurationService
import com.tradingagent.modules.ledger.CashInputs
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
    val cashBuffer: Money?,
    val externalHoldings: Money?,
    val positionCount: Int?,
    val unknownOrderCount: Int?,
    val configurationVersionId: String,
    val configurationVersionNumber: Long,
    val ledgerStatus: String,
    val journalEntryCount: Int,
    val journalBalanced: Boolean,
)

class DashboardReadModel(
    private val operationalStateService: OperationalStateService,
    private val ledger: Ledger,
    private val configurationService: ConfigurationService,
) {
    fun portfolio(): PortfolioSnapshot {
        val configuration = configurationService.active()
        val budget = configuration.budget
        val quote = ledger.quote(
            CashInputs(
                currency = budget.baseCurrency,
                brokerCashMinor = null,
                assignedBudgetMinor = budget.assignedBudgetMinor,
                externalHoldingsMinor = null,
                reservedCashMinor = null,
                cashBufferMinor = budget.cashBufferMinor,
            ),
        )
        return PortfolioSnapshot(
            operationalState = operationalStateService.current(),
            tradingEnabled = false,
            equity = null,
            brokerCash = quote.money(quote.brokerCashMinor),
            assignedBudget = quote.money(quote.assignedBudgetMinor),
            availableCash = quote.money(quote.availableMinor),
            reservedCash = quote.money(quote.reservedCashMinor),
            cashBuffer = quote.money(quote.cashBufferMinor),
            externalHoldings = quote.money(quote.externalHoldingsMinor),
            positionCount = null,
            unknownOrderCount = null,
            configurationVersionId = configuration.versionId.toString(),
            configurationVersionNumber = configuration.versionNumber,
            ledgerStatus = quote.status,
            journalEntryCount = ledger.journalEntryCount(),
            journalBalanced = ledger.journalBalanced(),
        )
    }
}
