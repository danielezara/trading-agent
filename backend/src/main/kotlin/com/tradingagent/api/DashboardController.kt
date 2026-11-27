package com.tradingagent.api

import com.tradingagent.kernel.Money
import com.tradingagent.modules.identity.AccessModel
import com.tradingagent.modules.identity.Role
import com.tradingagent.modules.readmodel.DashboardReadModel
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/dashboard/v1")
class DashboardController(
    private val readModel: DashboardReadModel,
    private val accessModel: AccessModel,
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
    fun overview(): ApiEnvelope<OverviewData> {
        val snapshot = readModel.portfolio()
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
                journalEntryCount = snapshot.journalEntryCount,
                journalBalanced = snapshot.journalBalanced,
            ),
            meta = ApiMeta(schemaVersion = "portfolio-snapshot/1"),
            warnings = listOf(
                ApiWarning(
                    code = "DATA_UNAVAILABLE",
                    detail = "Portafoglio non disponibile: ledger e broker non sono collegati. I null non sono zeri.",
                ),
            ),
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
    val journalEntryCount: Int,
    val journalBalanced: Boolean,
)
