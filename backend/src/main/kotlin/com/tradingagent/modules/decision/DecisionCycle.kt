package com.tradingagent.modules.decision

import com.tradingagent.modules.audit.AuditEvent
import com.tradingagent.modules.audit.AuditLog
import com.tradingagent.modules.configuration.ConfigurationService
import com.tradingagent.modules.configuration.ConfigurationVersion
import com.tradingagent.modules.configuration.EvidenceGate
import com.tradingagent.modules.ingestion.IngestionPipeline
import com.tradingagent.modules.operational.OperationalStateService
import com.tradingagent.modules.risk.DispatchPreconditions
import com.tradingagent.modules.risk.PolicyGate
import com.tradingagent.modules.risk.PolicyVerdict
import com.tradingagent.modules.strategy.ChannelObservation
import com.tradingagent.modules.strategy.DecisionInput
import com.tradingagent.modules.strategy.StrategyEngine
import com.tradingagent.modules.strategy.StrategyOutcome
import java.time.Instant

data class RecordedDecision(
    val outcome: StrategyOutcome,
    val configuration: ConfigurationVersion,
    val auditEvent: AuditEvent,
    val policy: PolicyVerdict,
)

class DecisionCycle(
    private val strategyEngine: StrategyEngine,
    private val ingestionPipeline: IngestionPipeline,
    private val evidenceGate: EvidenceGate,
    private val operationalStateService: OperationalStateService,
    private val configurationService: ConfigurationService,
    private val auditLog: AuditLog,
    private val policyGate: PolicyGate,
    private val clock: () -> Instant = Instant::now,
) {
    private var recorded: RecordedDecision? = null

    fun latest(): RecordedDecision = recorded
        ?: throw IllegalStateException("Nessuna decisione è stata registrata.")

    fun record(): RecordedDecision {
        val at = clock()
        val assessment = ingestionPipeline.assess(at)
        val outcome = strategyEngine.evaluate(
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
        check(outcome.action == "NO-TRADE") { "il ciclo di osservazione non può emettere un ordine" }
        val configuration = configurationService.active()
        val payload = decisionPayload(outcome)
        val auditEvent = auditLog.append(
            occurredAt = at,
            correlationId = outcome.inputHash,
            eventType = "DecisionRecorded",
            configVersionId = configuration.versionId.toString(),
            configContentSha256 = configuration.contentSha256,
            componentVersion = COMPONENT_VERSION,
            payload = payload,
        )
        val policy = policyGate.evaluate(DispatchPreconditions())
        check(!policy.accepted && !policy.brokerCalled) { "la policy non può accettare un invio" }
        val result = RecordedDecision(outcome, configuration, auditEvent, policy)
        recorded = result
        return result
    }

    companion object {
        const val COMPONENT_VERSION: String = "observation-decision/1"

        fun decisionPayload(outcome: StrategyOutcome): String = listOf(
            "action=${outcome.action}",
            "reasons=${outcome.reasonCodes.joinToString(",") { it.name }}",
            "input_hash=${outcome.inputHash}",
        ).joinToString("\n")
    }
}
