package com.tradingagent.modules.readmodel

import com.tradingagent.kernel.Money
import com.tradingagent.kernel.OperationalState
import java.time.Instant
import com.tradingagent.modules.brokerread.CapabilityProbe
import com.tradingagent.modules.brokerread.ClosedTrade
import com.tradingagent.modules.brokerread.ClosedTradeRead
import com.tradingagent.modules.brokerread.DisplayQuote
import com.tradingagent.modules.brokerread.InstrumentNameRead
import com.tradingagent.modules.brokerread.ObservedOrder
import com.tradingagent.modules.brokerread.ObservedPosition
import com.tradingagent.modules.brokerread.PositionQuoteRead
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
    val fxStatus: String,
    val journalEntryCount: Int,
    val journalBalanced: Boolean,
    val brokerConnected: Boolean,
    val lastProbeAt: Instant?,
    val probeErrorClass: String?,
    val accountLabel: String?,
    val positions: List<ObservedPosition>?,
    val quotes: Map<String, DisplayQuote>?,
    val instrumentNames: Map<String, String>?,
    val orders: List<ObservedOrder>?,
    val executions: List<ObservedOrder>?,
    val closedTrades: List<ClosedTrade>?,
)

class DashboardReadModel(
    private val operationalStateService: OperationalStateService,
    private val ledger: Ledger,
    private val configurationService: ConfigurationService,
    private val capabilityProbe: CapabilityProbe = CapabilityProbe.offline(),
    private val positionQuotes: PositionQuoteRead = PositionQuoteRead { null },
    private val instrumentNames: InstrumentNameRead = InstrumentNameRead { null },
    private val closedTrades: ClosedTradeRead = ClosedTradeRead { null },
) {
    fun portfolio(): PortfolioSnapshot {
        val configuration = configurationService.active()
        val budget = configuration.budget
        val observed = capabilityProbe.refresh()
        val brokerCash = observed.brokerCash
        val quote = ledger.quote(
            CashInputs(
                currency = budget.baseCurrency,
                brokerCashMinor = brokerCash?.minorUnits,
                brokerCurrency = brokerCash?.currency,
                assignedBudgetMinor = budget.assignedBudgetMinor,
                externalHoldingsMinor = null,
                reservedCashMinor = null,
                cashBufferMinor = budget.cashBufferMinor,
            ),
        )
        return PortfolioSnapshot(
            operationalState = operationalStateService.current(),
            tradingEnabled = false,
            equity = observed.equity,
            brokerCash = observed.brokerCash ?: quote.money(quote.brokerCashMinor),
            assignedBudget = quote.money(quote.assignedBudgetMinor),
            availableCash = quote.money(quote.availableMinor),
            reservedCash = quote.money(quote.reservedCashMinor),
            cashBuffer = quote.money(quote.cashBufferMinor),
            externalHoldings = quote.money(quote.externalHoldingsMinor),
            positionCount = observed.positionCount,
            unknownOrderCount = null,
            configurationVersionId = configuration.versionId.toString(),
            configurationVersionNumber = configuration.versionNumber,
            ledgerStatus = quote.status,
            fxStatus = quote.fxStatus,
            journalEntryCount = ledger.journalEntryCount(),
            journalBalanced = ledger.journalBalanced(),
            brokerConnected = observed.connected,
            lastProbeAt = observed.observedAt,
            probeErrorClass = observed.errorClass,
            accountLabel = observed.accountLabel,
            positions = observed.positions,
            quotes = observed.positions?.takeIf { it.isNotEmpty() }?.let { rows ->
                positionQuotes.read(rows.map { it.instrumentId })
            },
            instrumentNames = observed.positions?.takeIf { it.isNotEmpty() }?.let { rows ->
                instrumentNames.read(rows.map { it.instrumentId })
            },
            orders = observed.orders,
            executions = observed.executions,
            closedTrades = if (observed.connected) closedTrades.read(observed.observedAt ?: Instant.now()) else null,
        )
    }
}
