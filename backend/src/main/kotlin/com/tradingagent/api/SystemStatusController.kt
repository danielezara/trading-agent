package com.tradingagent.api

import com.tradingagent.modules.audit.AuditLog
import com.tradingagent.modules.configuration.EvidenceGate
import com.tradingagent.modules.ingestion.IngestionPipeline
import com.tradingagent.modules.operational.OperationalStateService
import com.tradingagent.modules.reconciliation.ReconciliationJob
import com.tradingagent.modules.scheduler.DurableScheduler
import com.tradingagent.modules.strategy.StrategyEngine
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class SystemStatusController(
    private val operationalStateService: OperationalStateService,
    private val evidenceGate: EvidenceGate,
    private val strategyEngine: StrategyEngine,
    private val ingestionPipeline: IngestionPipeline,
    private val reconciliationJob: ReconciliationJob,
    private val durableScheduler: DurableScheduler,
    private val auditLog: AuditLog,
) {
    @GetMapping("/system/status")
    fun status(): ApiEnvelope<SystemStatusData> {
        val outcome = strategyEngine.evaluate()
        return ApiEnvelope(
            data = SystemStatusData(
                operationalState = operationalStateService.current().wire,
                environment = "DEMO",
                tradingEnabled = false,
                brokerDispatch = "disabled",
                activeTransitionAllowed = evidenceGate.allowsActive(),
                strategyAction = outcome.action,
                strategyReason = outcome.reasonCode.name,
                ingestionEnabled = ingestionPipeline.enabled(),
                reconciliationArmed = reconciliationJob.armed(),
                armedJobs = durableScheduler.armedJobs(),
                auditWired = auditLog.wired(),
            ),
            meta = ApiMeta(schemaVersion = "system-status/1"),
            warnings = listOf(
                ApiWarning(
                    code = "SKELETON",
                    detail = "Nessun broker, nessun ordine e nessuna promozione ad ACTIVE.",
                ),
            ),
        )
    }

    @GetMapping("/evidence-gate")
    fun evidence(): ApiEnvelope<EvidenceGateData> = ApiEnvelope(
        data = EvidenceGateData(
            allowsActive = evidenceGate.allowsActive(),
            rows = evidenceGate.rows().map { row ->
                EvidenceRowData(
                    controlId = row.controlId,
                    scope = row.scope,
                    ownerRole = row.ownerRole,
                    status = row.status.name,
                )
            },
        ),
        meta = ApiMeta(schemaVersion = "evidence-gate/1"),
        warnings = emptyList(),
    )
}

data class SystemStatusData(
    val operationalState: String,
    val environment: String,
    val tradingEnabled: Boolean,
    val brokerDispatch: String,
    val activeTransitionAllowed: Boolean,
    val strategyAction: String,
    val strategyReason: String,
    val ingestionEnabled: Boolean,
    val reconciliationArmed: Boolean,
    val armedJobs: Int,
    val auditWired: Boolean,
)

data class EvidenceGateData(
    val allowsActive: Boolean,
    val rows: List<EvidenceRowData>,
)

data class EvidenceRowData(
    val controlId: String,
    val scope: String,
    val ownerRole: String,
    val status: String,
)

data class ApiEnvelope<T>(
    val data: T,
    val meta: ApiMeta,
    val warnings: List<ApiWarning>,
)

data class ApiMeta(
    val apiVersion: String = "v1",
    val schemaVersion: String,
    val requestId: String = UUID.randomUUID().toString(),
    val generatedAt: Instant = Instant.now(),
    val serverTime: Instant = generatedAt,
    val environment: String = "DEMO",
    val baseCurrency: String? = null,
    val consistency: String = "unavailable",
    val asOf: Instant? = null,
    val reconciledAt: Instant? = null,
    val freshness: FreshnessData = FreshnessData(),
)

data class FreshnessData(
    val status: String = "unavailable",
    val ageMs: Long? = null,
    val staleAfterMs: Long? = null,
)

data class ApiWarning(
    val code: String,
    val detail: String,
)
