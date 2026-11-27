package com.tradingagent.api

import com.tradingagent.modules.audit.AuditLog
import com.tradingagent.modules.brokerread.CapabilityProbe
import com.tradingagent.modules.configuration.ConfigurationService
import com.tradingagent.modules.strategy.ClosedBarPolicy
import com.tradingagent.modules.configuration.ConfigurationVersion
import com.tradingagent.modules.configuration.EvidenceGate
import com.tradingagent.modules.decision.DecisionCycle
import com.tradingagent.modules.decision.RecordedDecision
import com.tradingagent.modules.strategy.ObservationSignal
import com.tradingagent.modules.ingestion.ChannelHealth
import com.tradingagent.modules.ingestion.IngestionAssessment
import com.tradingagent.modules.ingestion.IngestionPipeline
import com.tradingagent.modules.ingestion.ObservationPrices
import com.tradingagent.kernel.OperationalState
import com.tradingagent.modules.ledger.Ledger
import com.tradingagent.modules.operational.OperationalStateService
import com.tradingagent.modules.reconciliation.Reconciliation
import com.tradingagent.modules.reconciliation.ReconciliationJob
import com.tradingagent.modules.scheduler.DurableScheduler
import java.time.LocalDate
import java.time.ZoneOffset
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
    private val decisionCycle: DecisionCycle,
    private val ingestionPipeline: IngestionPipeline,
    private val observationPrices: ObservationPrices,
    private val reconciliationJob: ReconciliationJob,
    private val ledger: Ledger,
    private val durableScheduler: DurableScheduler,
    private val auditLog: AuditLog,
    private val capabilityProbe: CapabilityProbe,
) {
    @GetMapping("/system/status")
    fun status(): ApiEnvelope<SystemStatusData> {
        val outcome = decisionCycle.latest().outcome
        val probe = capabilityProbe.current()
        return ApiEnvelope(
            data = SystemStatusData(
                operationalState = operationalStateService.current().wire,
                environment = "DEMO",
                tradingEnabled = false,
                brokerDispatch = "disabled",
                brokerConnected = probe.connected,
                lastProbeAt = probe.observedAt,
                probeErrorClass = probe.errorClass,
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
                    detail = "Nessun ordine e nessuna promozione ad ACTIVE. La lettura Demo è solo GET.",
                ),
            ),
        )
    }

    @GetMapping("/evidence-gate")
    fun evidence(): ApiEnvelope<EvidenceGateData> {
        val probe = capabilityProbe.refreshIfStale()
        return ApiEnvelope(
        data = EvidenceGateData(
            allowsActive = evidenceGate.allowsActive(),
            brokerConnected = probe.connected,
            lastProbeAt = probe.observedAt,
            probeErrorClass = probe.errorClass,
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

    @GetMapping("/configurations")
    fun configurations(): ApiEnvelope<List<ConfigurationData>> = ApiEnvelope(
        data = configurationService.versions().map { version -> configurationData(version, configurationService.active().versionId) },
        meta = ApiMeta(schemaVersion = "configuration-list/1"),
        warnings = emptyList(),
    )

    @GetMapping("/configurations/active")
    fun activeConfiguration(): ApiEnvelope<ConfigurationData> {
        val active = configurationService.active()
        val budgetUnset = active.budget.assignedBudgetMinor == null || active.budget.baseCurrency == null
        val capability = ApiWarning(
            code = "CAPABILITY_UNVERIFIED",
            detail = "Capability e licenze restano UNVERIFIED. La decisione resta NO-TRADE.",
        )
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
                        detail = "La configurazione attiva non contiene budget e valuta. Nessun ordine è possibile.",
                    ),
                    capability,
                )
            } else {
                listOf(capability)
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

    @GetMapping("/reconciliation")
    fun reconciliation(): ApiEnvelope<ReconciliationData> {
        val state = operationalStateService.current()
        val probe = capabilityProbe.current()
        val configuration = configurationService.active()
        val report = reconciliationJob.record(
            Reconciliation.compare(
                broker = if (probe.connected) {
                    brokerBooks(probe.positions, probe.brokerCash, probe.orders, probe.executions)
                } else {
                    null
                },
                ledger = ledger.book(),
                state = state,
            ),
            occurredAt = Instant.now(),
            configVersionId = configuration.versionId.toString(),
            configContentSha256 = configuration.contentSha256,
        )
        check(operationalStateService.current() == OperationalState.OBSERVATION) {
            "la riconciliazione non può lasciare OBSERVATION"
        }
        check(!report.orderSent && !report.brokerCalled) { "la riconciliazione non invia ordini" }
        return ApiEnvelope(
            data = reconciliationData(report),
            meta = ApiMeta(schemaVersion = "reconciliation/1"),
            warnings = listOf(
                ApiWarning(
                    code = "READ_ONLY",
                    detail = "Il confronto è in sola lettura. Una differenza non invia ordini e non esce da OBSERVATION.",
                ),
            ),
        )
    }

    @GetMapping("/data-health")
    fun dataHealth(): ApiEnvelope<DataHealthData> {
        val assessment = observationPrices.assess(Instant.now())
        val warning = if (assessment.network) {
            ApiWarning(
                code = "READ_ONLY",
                detail = "I prezzi sono una GET di mercato in sola lettura. La licenza non è verificata. Un prezzo stale o mancante resta NO-TRADE.",
            )
        } else {
            ApiWarning(
                code = "FIXTURE_ONLY",
                detail = "I canali arrivano da una fixture sul classpath. Nessuna rete e nessun broker.",
            )
        }
        return ApiEnvelope(
            data = dataHealthData(assessment),
            meta = ApiMeta(schemaVersion = "data-health/1"),
            warnings = listOf(warning),
        )
    }

    @GetMapping("/decisions/latest")
    fun latest(): ApiEnvelope<DecisionData> {
        val recorded = decisionCycle.latest()
        return ApiEnvelope(
            data = decisionData(recorded),
            meta = ApiMeta(schemaVersion = "decision/1"),
            warnings = listOf(
                ApiWarning(
                    code = "NO_TRADE",
                    detail = "Il motore emette soltanto NO-TRADE. Nessun ordine è stato creato.",
                ),
            ),
        )
    }

    @GetMapping("/audit")
    fun audit(): ApiEnvelope<AuditData> {
        val events = auditLog.events()
        val verification = auditLog.verify()
        val day = events.lastOrNull()?.let { LocalDate.ofInstant(it.occurredAt, ZoneOffset.UTC) }
        return ApiEnvelope(
            data = AuditData(
                durable = false,
                chainValid = verification.valid,
                eventCount = verification.eventCount,
                headHash = verification.headHash,
                dailyDigest = day?.let { auditLog.dailyDigest(it) },
                dailyDigestSealed = day?.let { auditLog.digestSealed(it) } ?: false,
                events = events.map { event ->
                    AuditEventData(
                        sequence = event.sequence,
                        occurredAt = event.occurredAt,
                        correlationId = event.correlationId,
                        eventType = event.eventType,
                        configVersionId = event.configVersionId,
                        configContentSha256 = event.configContentSha256,
                        eventHash = event.eventHash,
                    )
                },
            ),
            meta = ApiMeta(schemaVersion = "audit/1"),
            warnings = listOf(
                ApiWarning(
                    code = "AUDIT_NOT_DURABLE",
                    detail = "La catena è in memoria. Il digest esterno non è collegato e nessuna firma è stata prodotta.",
                ),
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
        category = channel.category,
        eventTime = channel.eventTime,
        ingestedAt = channel.ingestedAt,
        license = channel.license,
    )

    private fun decisionData(recorded: RecordedDecision) = DecisionData(
        action = recorded.outcome.action,
        reasonCodes = recorded.outcome.reasonCodes.map { it.name },
        summary = recorded.outcome.summary,
        inputHash = recorded.outcome.inputHash,
        evaluatedAt = recorded.outcome.evaluatedAt,
        configVersionId = recorded.configuration.versionId.toString(),
        configContentSha256 = recorded.configuration.contentSha256,
        auditSequence = recorded.auditEvent.sequence,
        auditEventHash = recorded.auditEvent.eventHash,
        policy = PolicyData(
            accepted = recorded.policy.accepted,
            reason = recorded.policy.reason,
            brokerCalled = recorded.policy.brokerCalled,
        ),
        signal = signalData(recorded.outcome.signal),
        ruleAction = recorded.rule.action,
        ruleReason = recorded.rule.reason,
        ruleInstrumentId = recorded.rule.instrumentId,
        ruleOrderable = recorded.rule.orderable,
        closedBarCount = recorded.closedBarCount,
        bookLossMinor = recorded.bookLossMinor,
        bookLossCurrency = recorded.bookLossCurrency,
        lossBlocksEntries = recorded.lossBlocksEntries,
        demoEntryStatus = recorded.demoEntry.status,
        demoEntryReason = recorded.demoEntry.reason,
        demoEntryHttpStatus = recorded.demoEntry.httpStatus,
        demoEntryOrderId = recorded.demoEntry.orderId,
    )

    private fun signalData(signal: ObservationSignal) = SignalData(
        signalId = signal.signalId,
        origin = signal.origin,
        originVersion = signal.originVersion,
        instrumentId = signal.instrumentId,
        direction = signal.direction,
        evaluatedAt = signal.evaluatedAt,
        validUntil = signal.validUntil,
        confidenceMillis = signal.confidenceMillis,
        rationale = signal.rationale,
        orderable = signal.orderable,
    )

    private fun configurationData(version: ConfigurationVersion, activeId: java.util.UUID) = ConfigurationData(
        versionId = version.versionId.toString(),
        versionNumber = version.versionNumber,
        author = version.author,
        createdAt = version.createdAt,
        reason = version.reason,
        contentSha256 = version.contentSha256,
        validFromBarOpen = version.validFromBarOpen,
        active = version.versionId == activeId,
        budget = BudgetData(
            baseCurrency = version.budget.baseCurrency,
            assignedBudgetMinor = version.budget.assignedBudgetMinor,
            cashBufferMinor = version.budget.cashBufferMinor,
            maxPositions = version.budget.maxPositions,
        ),
        economic = EconomicViewData(
            maxLossBps = version.parameters.economic.maxLossBps,
            maxLossAction = version.parameters.economic.maxLossAction,
            horizon = version.parameters.economic.horizon,
            allowlistUniverse = version.parameters.economic.allowlistUniverse,
            allowlistDirection = version.parameters.economic.allowlistDirection,
            leverage = version.parameters.economic.allowlistLeverage,
            benchmarkName = version.parameters.economic.benchmarkName,
            benchmarkReturnBasis = version.parameters.economic.benchmarkReturnBasis,
            technicalCalibratable = version.parameters.technical.calibratable,
            sourcePath = version.parameters.sourcePath,
            riskPerTradeBps = version.parameters.economic.riskPerTradeBps,
            aggregateRiskBps = version.parameters.economic.aggregateRiskBps,
            instrumentExposureBps = version.parameters.economic.instrumentExposureBps,
            sectorExposureBps = version.parameters.economic.sectorExposureBps,
            minCashBps = version.parameters.economic.minCashBps,
            cooldownSeconds = version.parameters.economic.cooldownSeconds,
            maxHoldingCount = version.parameters.economic.maxHoldingCount,
            entryRule = ClosedBarPolicy.RULE_ID,
            priceSource = ClosedBarPolicy.PRICE_SOURCE,
            decisionBarSeconds = version.parameters.technical.decisionBarSeconds,
        ),
    )
}

data class SystemStatusData(
    val operationalState: String,
    val environment: String,
    val tradingEnabled: Boolean,
    val brokerDispatch: String,
    val brokerConnected: Boolean,
    val lastProbeAt: Instant?,
    val probeErrorClass: String?,
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
    val validFromBarOpen: Instant,
    val active: Boolean,
    val budget: BudgetData,
    val economic: EconomicViewData,
)

data class EconomicViewData(
    val maxLossBps: Int?,
    val maxLossAction: String?,
    val horizon: String?,
    val allowlistUniverse: String?,
    val allowlistDirection: String?,
    val leverage: Int?,
    val benchmarkName: String?,
    val benchmarkReturnBasis: String?,
    val technicalCalibratable: Boolean,
    val sourcePath: String,
    val riskPerTradeBps: Int?,
    val aggregateRiskBps: Int?,
    val instrumentExposureBps: Int?,
    val sectorExposureBps: Int?,
    val minCashBps: Int?,
    val cooldownSeconds: Int?,
    val maxHoldingCount: Int?,
    val entryRule: String,
    val priceSource: String,
    val decisionBarSeconds: Long,
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
    val brokerConnected: Boolean,
    val lastProbeAt: Instant?,
    val probeErrorClass: String?,
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
    val category: String?,
    val eventTime: Instant?,
    val ingestedAt: Instant?,
    val license: String?,
)

data class DecisionData(
    val action: String,
    val reasonCodes: List<String>,
    val summary: String,
    val inputHash: String,
    val evaluatedAt: Instant,
    val configVersionId: String,
    val configContentSha256: String,
    val auditSequence: Long,
    val auditEventHash: String,
    val policy: PolicyData,
    val signal: SignalData,
    val ruleAction: String,
    val ruleReason: String,
    val ruleInstrumentId: String?,
    val ruleOrderable: Boolean,
    val closedBarCount: Int,
    val bookLossMinor: Long?,
    val bookLossCurrency: String?,
    val lossBlocksEntries: Boolean,
    val demoEntryStatus: String,
    val demoEntryReason: String,
    val demoEntryHttpStatus: Int?,
    val demoEntryOrderId: String?,
)

data class SignalData(
    val signalId: String,
    val origin: String,
    val originVersion: String,
    val instrumentId: String?,
    val direction: String,
    val evaluatedAt: Instant,
    val validUntil: Instant?,
    val confidenceMillis: Int,
    val rationale: String,
    val orderable: Boolean,
)

data class PolicyData(
    val accepted: Boolean,
    val reason: String,
    val brokerCalled: Boolean,
)

data class AuditData(
    val durable: Boolean,
    val chainValid: Boolean,
    val eventCount: Int,
    val headHash: String?,
    val dailyDigest: String?,
    val dailyDigestSealed: Boolean,
    val events: List<AuditEventData>,
)

data class AuditEventData(
    val sequence: Long,
    val occurredAt: Instant,
    val correlationId: String,
    val eventType: String,
    val configVersionId: String,
    val configContentSha256: String,
    val eventHash: String,
)
