package com.tradingagent.api

import com.tradingagent.modules.audit.AuditLog
import com.tradingagent.modules.configuration.ConfigurationService
import com.tradingagent.modules.configuration.ConfigurationVersion
import com.tradingagent.modules.configuration.EvidenceGate
import com.tradingagent.modules.ingestion.ChannelHealth
import com.tradingagent.modules.ingestion.IngestionAssessment
import com.tradingagent.modules.ingestion.IngestionPipeline
import com.tradingagent.modules.operational.OperationalStateService
import com.tradingagent.modules.reconciliation.ReconciliationJob
import com.tradingagent.modules.scheduler.DurableScheduler
import com.tradingagent.modules.strategy.ChannelObservation
import com.tradingagent.modules.strategy.DecisionInput
import com.tradingagent.modules.strategy.StrategyEngine
import com.tradingagent.modules.strategy.StrategyOutcome
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class SystemStatusController(
    private val operationalStateService: OperationalStateService,
    private val configurationService: ConfigurationService,
    private val evidenceGate: EvidenceGate,
    private val strategyEngine: StrategyEngine,
    private val ingestionPipeline: IngestionPipeline,
    private val reconciliationJob: ReconciliationJob,
    private val durableScheduler: DurableScheduler,
    private val auditLog: AuditLog,
) {
    @GetMapping("/system/status")
    fun status(): ApiEnvelope<SystemStatusData> {
        val outcome = latestDecision()
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
                ingestionSource = "classpath-fixture",
                reconciliationArmed = reconciliationJob.armed(),
                armedJobs = durableScheduler.armedJobs(),
                auditWired = auditLog.wired(),
                configurationVersionNumber = configurationService.active().versionNumber,
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

    @GetMapping("/configurations")
    fun configurations(): ApiEnvelope<List<ConfigurationData>> = ApiEnvelope(
        data = configurationService.versions().map { version -> configurationData(version, configurationService.active().versionId) },
        meta = ApiMeta(schemaVersion = "configuration-list/1"),
        warnings = emptyList(),
    )

    @GetMapping("/configurations/active")
    fun activeConfiguration(): ApiEnvelope<ConfigurationData> {
        val active = configurationService.active()
        val budgetUnset = active.budget.assignedBudgetMinor == null
        return ApiEnvelope(
            data = configurationData(active, active.versionId),
            meta = ApiMeta(
                schemaVersion = "configuration/1",
                baseCurrency = active.budget.baseCurrency,
            ),
            warnings = if (budgetUnset) {
                listOf(
                    ApiWarning(
                        code = "BUDGET_UNSET",
                        detail = "La configurazione attiva non contiene parametri economici. Nessun ordine è possibile.",
                    ),
                )
            } else {
                emptyList()
            },
        )
    }

    @GetMapping("/operational-state")
    fun operationalState(): ApiEnvelope<OperationalStateData> {
        val snapshot = operationalStateService.snapshot()
        return ApiEnvelope(
            data = OperationalStateData(
                state = snapshot.state.wire,
                since = snapshot.since,
                reason = snapshot.reason,
                generation = snapshot.generation,
                actions = OperationalActionsData(
                    newEntries = snapshot.permissions.newEntries,
                    localExits = snapshot.permissions.localExits,
                    cancel = snapshot.permissions.cancel,
                    reconcilePermitted = snapshot.permissions.reconcilePermitted,
                    reconcileWired = snapshot.permissions.reconcileWired,
                ),
            ),
            meta = ApiMeta(schemaVersion = "operational-state/1"),
            warnings = emptyList(),
        )
    }

    @GetMapping("/data-health")
    fun dataHealth(): ApiEnvelope<DataHealthData> {
        val assessment = ingestionPipeline.assess(Instant.now())
        return ApiEnvelope(
            data = dataHealthData(assessment),
            meta = ApiMeta(schemaVersion = "data-health/1"),
            warnings = listOf(
                ApiWarning(
                    code = "FIXTURE_ONLY",
                    detail = "I canali arrivano da una fixture sul classpath. Nessuna rete e nessun broker.",
                ),
            ),
        )
    }

    @GetMapping("/decisions/latest")
    fun latest(): ApiEnvelope<DecisionData> {
        val outcome = latestDecision()
        return ApiEnvelope(
            data = decisionData(outcome),
            meta = ApiMeta(schemaVersion = "decision/1"),
            warnings = listOf(
                ApiWarning(
                    code = "NO_TRADE",
                    detail = "Il motore emette soltanto NO-TRADE. Nessun ordine è stato creato.",
                ),
            ),
        )
    }

    private fun latestDecision(at: Instant = Instant.now()): StrategyOutcome {
        val assessment = ingestionPipeline.assess(at)
        return strategyEngine.evaluate(
            DecisionInput(
                operationalState = operationalStateService.current(),
                evidenceAllowsActive = evidenceGate.allowsActive(),
                channels = assessment.channels.map { channel ->
                    ChannelObservation(
                        channel = channel.channel,
                        acceptedCount = channel.acceptedCount,
                        stale = channel.stale,
                        conflict = channel.conflict,
                        qualityMillis = channel.qualityMillis,
                    )
                },
                evaluatedAt = at,
            ),
        )
    }

    private fun dataHealthData(assessment: IngestionAssessment) = DataHealthData(
        source = assessment.source,
        network = assessment.network,
        connectorVersion = assessment.connectorVersion,
        quarantinedCount = assessment.quarantinedCount,
        channels = assessment.channels.map { channel -> channelData(channel) },
    )

    private fun channelData(channel: ChannelHealth) = ChannelHealthData(
        channel = channel.channel,
        source = channel.source,
        acceptedCount = channel.acceptedCount,
        rejectedCount = channel.rejectedCount,
        duplicateCount = channel.duplicateCount,
        stale = channel.stale,
        conflict = channel.conflict,
        qualityMillis = channel.qualityMillis,
        status = channel.status,
    )

    private fun decisionData(outcome: StrategyOutcome) = DecisionData(
        action = outcome.action,
        reasonCodes = outcome.reasonCodes.map { it.name },
        summary = outcome.summary,
        inputHash = outcome.inputHash,
        evaluatedAt = outcome.evaluatedAt,
    )

    private fun configurationData(version: ConfigurationVersion, activeId: java.util.UUID) = ConfigurationData(
        versionId = version.versionId.toString(),
        versionNumber = version.versionNumber,
        author = version.author,
        createdAt = version.createdAt,
        reason = version.reason,
        contentSha256 = version.contentSha256,
        active = version.versionId == activeId,
        budget = BudgetData(
            baseCurrency = version.budget.baseCurrency,
            assignedBudgetMinor = version.budget.assignedBudgetMinor,
            cashBufferMinor = version.budget.cashBufferMinor,
            maxPositions = version.budget.maxPositions,
        ),
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
    val ingestionSource: String,
    val reconciliationArmed: Boolean,
    val armedJobs: Int,
    val auditWired: Boolean,
    val configurationVersionNumber: Long,
)

data class ConfigurationData(
    val versionId: String,
    val versionNumber: Long,
    val author: String,
    val createdAt: Instant,
    val reason: String,
    val contentSha256: String,
    val active: Boolean,
    val budget: BudgetData,
)

data class BudgetData(
    val baseCurrency: String?,
    val assignedBudgetMinor: Long?,
    val cashBufferMinor: Long?,
    val maxPositions: Int?,
)

data class OperationalStateData(
    val state: String,
    val since: Instant,
    val reason: String,
    val generation: Long,
    val actions: OperationalActionsData,
)

data class OperationalActionsData(
    val newEntries: Boolean,
    val localExits: Boolean,
    val cancel: Boolean,
    val reconcilePermitted: Boolean,
    val reconcileWired: Boolean,
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

data class DataHealthData(
    val source: String,
    val network: Boolean,
    val connectorVersion: String,
    val quarantinedCount: Int,
    val channels: List<ChannelHealthData>,
)

data class ChannelHealthData(
    val channel: String,
    val source: String,
    val acceptedCount: Int,
    val rejectedCount: Int,
    val duplicateCount: Int,
    val stale: Boolean,
    val conflict: Boolean,
    val qualityMillis: Int,
    val status: String,
)

data class DecisionData(
    val action: String,
    val reasonCodes: List<String>,
    val summary: String,
    val inputHash: String,
    val evaluatedAt: Instant,
)
