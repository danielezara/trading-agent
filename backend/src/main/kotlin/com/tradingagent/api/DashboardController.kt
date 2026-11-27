package com.tradingagent.api

import com.tradingagent.kernel.Money
import com.tradingagent.modules.identity.AccessModel
import com.tradingagent.modules.identity.Role
import com.tradingagent.modules.brokerread.BrokerCashBalance
import com.tradingagent.modules.brokerread.CashMovement
import com.tradingagent.modules.brokerread.DisplayQuote
import com.tradingagent.modules.brokerread.ObservedOrder
import com.tradingagent.modules.brokerread.ObservedPosition
import com.tradingagent.modules.configuration.ConfigurationService
import com.tradingagent.modules.ledger.Ledger
import com.tradingagent.modules.readmodel.DashboardReadModel
import com.tradingagent.modules.reconciliation.Reconciliation
import com.tradingagent.modules.reconciliation.ReconciliationJob
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/dashboard/v1")
class DashboardController(
    private val readModel: DashboardReadModel,
    private val accessModel: AccessModel,
    private val ledger: Ledger,
    private val reconciliationJob: ReconciliationJob,
    private val configurationService: ConfigurationService,
) {
    @GetMapping("/session")
    fun session(): ApiEnvelope<SessionData> = ApiEnvelope(
        data = SessionData(
            authenticated = false,
            role = null,
            environment = "DEMO",
            capabilities = CapabilitiesData(
                view = true,
                acknowledgeAlert = false,
                killSwitch = accessModel.allowsMutation(Role.EMERGENCY_ADMIN),
                reset = false,
                configure = false,
                placeOrder = false,
            ),
        ),
        meta = ApiMeta(schemaVersion = "session/1"),
        warnings = listOf(
            ApiWarning(
                code = "AUTH_NOT_WIRED",
                detail = "Identità non collegata. Il servizio ascolta solo in locale e non esegue comandi.",
            ),
        ),
    )

    @GetMapping("/overview")
    fun overview(response: HttpServletResponse): ApiEnvelope<OverviewData> {
        response.setHeader("Cache-Control", "no-store")
        val snapshot = readModel.portfolio()
        val configuration = configurationService.active()
        val report = reconciliationJob.record(
            Reconciliation.compare(
                broker = if (snapshot.brokerConnected) {
                    brokerBooks(snapshot.positions, snapshot.brokerCash, snapshot.orders, snapshot.executions)
                } else {
                    null
                },
                ledger = ledger.book(),
                state = snapshot.operationalState,
            ),
            occurredAt = Instant.now(),
            configVersionId = configuration.versionId.toString(),
            configContentSha256 = configuration.contentSha256,
        )
        check(report.operationalState == snapshot.operationalState && !report.orderSent && !report.brokerCalled) {
            "la lettura non può lasciare OBSERVATION né inviare un ordine"
        }
        return ApiEnvelope(
            data = OverviewData(
                operationalState = snapshot.operationalState.wire,
                tradingEnabled = snapshot.tradingEnabled,
                equity = snapshot.equity,
                brokerCash = snapshot.brokerCash,
                assignedBudget = snapshot.assignedBudget,
                availableCash = snapshot.availableCash,
                reservedCash = snapshot.reservedCash,
                cashBuffer = snapshot.cashBuffer,
                externalHoldings = snapshot.externalHoldings,
                positionCount = snapshot.positionCount,
                unknownOrderCount = snapshot.unknownOrderCount,
                configurationVersionId = snapshot.configurationVersionId,
                configurationVersionNumber = snapshot.configurationVersionNumber,
                ledgerStatus = snapshot.ledgerStatus,
                fxStatus = snapshot.fxStatus,
                fxSource = snapshot.fx?.source,
                fxVersion = snapshot.fx?.version,
                fxObservedAt = snapshot.fx?.observedAt,
                fxHaircutBps = snapshot.fx?.haircutBps,
                journalEntryCount = snapshot.journalEntryCount,
                journalBalanced = snapshot.journalBalanced,
                brokerConnected = snapshot.brokerConnected,
                lastProbeAt = snapshot.lastProbeAt,
                probeErrorClass = snapshot.probeErrorClass,
                accountLabel = snapshot.accountLabel,
                positions = snapshot.positions?.map { position ->
                    positionData(position, snapshot.quotes, snapshot.instrumentNames, snapshot.instrumentClasses)
                },
                orders = snapshot.orders?.map { order -> orderData(order) },
                executions = snapshot.executions?.map { order -> orderData(order) },
                closedTrades = snapshot.closedTrades?.map { trade -> closedTradeData(trade) },
                cashBalances = snapshot.cashBalances,
                cashMovements = snapshot.cashMovements,
                reconciliation = reconciliationData(report),
            ),
            meta = ApiMeta(schemaVersion = "portfolio-snapshot/1"),
            warnings = buildList {
                add(
                    ApiWarning(
                        code = "DATA_UNAVAILABLE",
                        detail = "Il giornale locale non è collegato. I null non sono zeri.",
                    ),
                )
                if (snapshot.ledgerStatus == Ledger.STATUS_FX_UNAVAILABLE) {
                    add(
                        ApiWarning(
                            code = "FX_UNAVAILABLE",
                            detail = "La cassa mostrata resta quella nativa eToro. Il disponibile in EUR non è una conversione del saldo.",
                        ),
                    )
                }
            },
        )
    }
}

data class SessionData(
    val authenticated: Boolean,
    val role: String?,
    val environment: String,
    val capabilities: CapabilitiesData,
)

data class CapabilitiesData(
    val view: Boolean,
    val acknowledgeAlert: Boolean,
    val killSwitch: Boolean,
    val reset: Boolean,
    val configure: Boolean,
    val placeOrder: Boolean,
)

data class OverviewData(
    val operationalState: String,
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
    val fxSource: String?,
    val fxVersion: String?,
    val fxObservedAt: Instant?,
    val fxHaircutBps: Int?,
    val journalEntryCount: Int,
    val journalBalanced: Boolean,
    val brokerConnected: Boolean,
    val lastProbeAt: java.time.Instant?,
    val probeErrorClass: String?,
    val accountLabel: String?,
    val positions: List<PositionData>?,
    val orders: List<OrderData>?,
    val executions: List<OrderData>?,
    val closedTrades: List<ClosedTradeData>?,
    val cashBalances: List<BrokerCashBalance>?,
    val cashMovements: List<CashMovement>?,
    val reconciliation: ReconciliationData,
)

data class PositionData(
    val instrumentId: String,
    val quantity: String?,
    val value: Money?,
    val quotePrice: String?,
    val quoteTime: Instant?,
    val displayName: String?,
    val assetClass: String?,
    val allowlist: String?,
    val officialType: String?,
)

data class ClosedTradeData(
    val positionId: String,
    val instrumentId: String?,
    val side: String?,
    val quantity: String?,
    val netProfit: String?,
    val closedAt: String?,
)

data class OrderData(
    val orderId: String,
    val instrumentId: String?,
    val side: String?,
    val quantity: String?,
    val value: Money?,
    val openedAt: String?,
)

private fun positionData(
    position: ObservedPosition,
    quotes: Map<String, DisplayQuote>?,
    names: Map<String, String>?,
    classes: Map<String, com.tradingagent.modules.brokerread.InstrumentClassification>?,
) = PositionData(
    instrumentId = position.instrumentId,
    quantity = position.quantity,
    value = position.value,
    quotePrice = quotes?.get(position.instrumentId)?.price?.toPlainString(),
    quoteTime = quotes?.get(position.instrumentId)?.quotedAt,
    displayName = names?.get(position.instrumentId)?.takeIf { it.isNotBlank() },
    assetClass = classes?.get(position.instrumentId)?.assetClass
        ?: classes?.let { com.tradingagent.modules.brokerread.InstrumentClassification.ASSET_UNKNOWN },
    allowlist = classes?.get(position.instrumentId)?.allowlist
        ?: classes?.let { com.tradingagent.modules.brokerread.InstrumentClassification.ALLOW_UNKNOWN },
    officialType = classes?.get(position.instrumentId)?.officialType,
)

private fun closedTradeData(trade: com.tradingagent.modules.brokerread.ClosedTrade) = ClosedTradeData(
    positionId = trade.positionId,
    instrumentId = trade.instrumentId,
    side = trade.side,
    quantity = trade.quantity,
    netProfit = trade.netProfit,
    closedAt = trade.closedAt,
)

private fun orderData(order: ObservedOrder) = OrderData(
    orderId = order.orderId,
    instrumentId = order.instrumentId,
    side = order.side,
    quantity = order.quantity,
    value = order.value,
    openedAt = order.openedAt,
)
