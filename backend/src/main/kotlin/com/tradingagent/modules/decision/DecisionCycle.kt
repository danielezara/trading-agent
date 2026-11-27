package com.tradingagent.modules.decision

import com.tradingagent.modules.audit.AuditEvent
import com.tradingagent.modules.audit.AuditLog
import com.tradingagent.modules.brokerread.CapabilityProbe
import com.tradingagent.modules.configuration.ConfigurationService
import com.tradingagent.modules.configuration.ConfigurationVersion
import com.tradingagent.modules.configuration.EvidenceGate
import com.tradingagent.modules.ingestion.ChannelHealth
import com.tradingagent.modules.ingestion.IngestionPipeline
import com.tradingagent.modules.ledger.CashInputs
import com.tradingagent.modules.ledger.Ledger
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
    private val ledger: Ledger = Ledger(),
    private val capabilityProbe: CapabilityProbe = CapabilityProbe.offline(),
) {
    private var recorded: RecordedDecision? = null

    fun latest(): RecordedDecision = recorded
        ?: throw IllegalStateException("Nessuna decisione è stata registrata.")

    fun record(): RecordedDecision {
        val at = clock()
        val assessment = ingestionPipeline.assess(at)
        val configuration = configurationService.active()
        val brokerCash = capabilityProbe.current().brokerCash
        val quote = ledger.quote(
            CashInputs(
                currency = configuration.budget.baseCurrency,
                brokerCashMinor = brokerCash?.minorUnits,
                brokerCurrency = brokerCash?.currency,
                assignedBudgetMinor = configuration.budget.assignedBudgetMinor,
                cashBufferMinor = configuration.budget.cashBufferMinor,
            ),
        )
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
                fxStatus = quote.fxStatus,
            ),
        )
        check(outcome.action == "NO-TRADE") { "il ciclo di osservazione non può emettere un ordine" }
        check(!outcome.signal.orderable && outcome.signal.direction == "NONE" && outcome.signal.instrumentId == null) {
            "un segnale di osservazione non è eseguibile"
        }
        val prices = assessment.channels.firstOrNull { it.channel == "prices" }
        val news = assessment.channels.firstOrNull { it.channel == "news" }
        val macro = assessment.channels.firstOrNull { it.channel == "macro" }
        val fundamentals = assessment.channels.firstOrNull { it.channel == "fundamentals" }
        val payload = decisionPayload(outcome, prices, news, macro, fundamentals)
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

        fun decisionPayload(
            outcome: StrategyOutcome,
            prices: ChannelHealth? = null,
            news: ChannelHealth? = null,
            macro: ChannelHealth? = null,
            fundamentals: ChannelHealth? = null,
        ): String = listOf(
            "action=${outcome.action}",
            "reasons=${outcome.reasonCodes.joinToString(",") { it.name }}",
            "input_hash=${outcome.inputHash}",
            "signal_id=${outcome.signal.signalId}",
            "signal_orderable=${outcome.signal.orderable}",
            "prices_source=${prices?.source.orEmpty()}",
            "prices_category=${prices?.category.orEmpty()}",
            "prices_event_time=${prices?.eventTime ?: ""}",
            "prices_ingested_at=${prices?.ingestedAt ?: ""}",
            "prices_license=${prices?.license.orEmpty()}",
            "prices_stale=${prices?.stale ?: false}",
            "prices_accepted=${prices?.acceptedCount ?: 0}",
            "news_source=${news?.source.orEmpty()}",
            "news_category=${news?.category.orEmpty()}",
            "news_event_time=${news?.eventTime ?: ""}",
            "news_ingested_at=${news?.ingestedAt ?: ""}",
            "news_license=${news?.license.orEmpty()}",
            "news_stale=${news?.stale ?: false}",
            "news_accepted=${news?.acceptedCount ?: 0}",
            "macro_source=${macro?.source.orEmpty()}",
            "macro_category=${macro?.category.orEmpty()}",
            "macro_event_time=${macro?.eventTime ?: ""}",
            "macro_ingested_at=${macro?.ingestedAt ?: ""}",
            "macro_license=${macro?.license.orEmpty()}",
            "macro_stale=${macro?.stale ?: false}",
            "macro_accepted=${macro?.acceptedCount ?: 0}",
            "fundamentals_source=${fundamentals?.source.orEmpty()}",
            "fundamentals_category=${fundamentals?.category.orEmpty()}",
            "fundamentals_event_time=${fundamentals?.eventTime ?: ""}",
            "fundamentals_ingested_at=${fundamentals?.ingestedAt ?: ""}",
            "fundamentals_license=${fundamentals?.license.orEmpty()}",
            "fundamentals_stale=${fundamentals?.stale ?: false}",
            "fundamentals_accepted=${fundamentals?.acceptedCount ?: 0}",
        ).joinToString("\n")
    }
}
