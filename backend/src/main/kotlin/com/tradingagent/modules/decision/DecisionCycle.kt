package com.tradingagent.modules.decision

import com.tradingagent.modules.audit.AuditEvent
import com.tradingagent.modules.audit.AuditLog
import com.tradingagent.modules.brokerread.CapabilityProbe
import com.tradingagent.modules.brokerread.ClosedBarSource
import com.tradingagent.modules.brokerread.DemoEntrySubmit
import com.tradingagent.modules.brokerread.DemoOpenResult
import com.tradingagent.modules.brokerread.EntryCandidates
import com.tradingagent.modules.brokerread.EuroFxRead
import com.tradingagent.modules.brokerread.ObservedBars
import com.tradingagent.modules.brokerread.ReportedRateSource
import com.tradingagent.modules.brokerread.ObservedInstrumentBars
import com.tradingagent.modules.configuration.ConfigurationService
import com.tradingagent.modules.configuration.ConfigurationVersion
import com.tradingagent.modules.configuration.EvidenceGate
import com.tradingagent.modules.ingestion.ChannelHealth
import com.tradingagent.modules.ingestion.IngestionAssessment
import com.tradingagent.modules.ingestion.IngestionPipeline
import com.tradingagent.modules.ledger.CashInputs
import com.tradingagent.modules.ledger.Ledger
import com.tradingagent.modules.operational.OperationalStateService
import com.tradingagent.modules.risk.DispatchPreconditions
import com.tradingagent.modules.risk.PolicyGate
import com.tradingagent.modules.risk.PolicyVerdict
import com.tradingagent.modules.strategy.BarBook
import com.tradingagent.modules.strategy.BreakoutCycleInput
import com.tradingagent.modules.strategy.BreakoutLive
import com.tradingagent.modules.strategy.BudgetLoss
import com.tradingagent.modules.strategy.ChannelObservation
import com.tradingagent.modules.strategy.ClosedBarEvaluation
import com.tradingagent.modules.strategy.InstrumentBars
import com.tradingagent.modules.strategy.PayloadRate
import com.tradingagent.modules.strategy.PriceBar
import com.tradingagent.modules.strategy.RuleDecision
import com.tradingagent.modules.strategy.DecisionInput
import com.tradingagent.modules.strategy.StrategyEngine
import com.tradingagent.modules.strategy.StrategyOutcome
import com.tradingagent.modules.strategy.TradingAmount
import java.time.Instant

data class RecordedDecision(
    val outcome: StrategyOutcome,
    val configuration: ConfigurationVersion,
    val auditEvent: AuditEvent,
    val policy: PolicyVerdict,
    val rule: RuleDecision,
    val closedBarCount: Int,
    val bookLossMinor: Long? = null,
    val bookLossCurrency: String? = null,
    val lossBlocksEntries: Boolean = false,
    val demoEntry: DemoOpenResult = DemoOpenResult("NOT_SENT", "WARMUP"),
    val programOpenPositions: Int = 0,
    val programInvestedMinor: Long = 0,
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
    private val assess: (Instant) -> IngestionAssessment = { instant -> ingestionPipeline.assess(instant) },
    private val euroFx: EuroFxRead = EuroFxRead { _, _, _ -> null },
    private val closedBars: ClosedBarSource = ClosedBarSource { null },
    private val reportedRates: ReportedRateSource = ReportedRateSource { emptyList() },
    private val entriesBlocked: () -> Boolean = { false },
    private val entryCandidates: EntryCandidates = EntryCandidates { _, _ -> emptyList() },
    private val demoEntries: DemoEntrySubmit = DemoEntrySubmit { _, _, _, _ -> DemoOpenResult("NOT_SENT", "UNWIRED") },
    private val programPositions: ProgramPositions = ProgramPositions(),
    private val breakout: Boolean = false,
) {
    private var recorded: RecordedDecision? = null
    private var storedBars: List<ObservedInstrumentBars> = emptyList()

    @Volatile
    var entryLoop: String = "OFF"

    fun storedBars(): List<ObservedInstrumentBars> = storedBars

    fun latest(): RecordedDecision = recorded
        ?: throw IllegalStateException("Nessuna decisione è stata registrata.")

    fun record(): RecordedDecision {
        val at = clock()
        val assessment = assess(at)
        val configuration = configurationService.active()
        val brokerCash = capabilityProbe.current().brokerCash
        val fx = euroFx.read(
            at,
            configuration.parameters.economic.fxHaircutBps,
            configuration.parameters.technical.fxReferenceMaxAgeDays,
        )
        val quote = ledger.quote(
            CashInputs(
                currency = configuration.budget.baseCurrency,
                brokerCashMinor = brokerCash?.minorUnits,
                brokerCurrency = brokerCash?.currency,
                assignedBudgetMinor = configuration.budget.assignedBudgetMinor,
                externalHoldingsMinor = if (fx == null) null else 0,
                reservedCashMinor = if (fx == null) null else 0,
                cashBufferMinor = if (fx == null) configuration.budget.cashBufferMinor else configuration.budget.cashBufferMinor ?: 0,
                fx = fx,
            ),
        )
        val observed = try {
            closedBars.load(at)
        } catch (_: Exception) {
            null
        } ?: ObservedBars(0, emptyList())
        storedBars = observed.instruments
        if (breakout) {
            val size = configuration.parameters.breakout.positionSizeMinor
            observed.blockedEntryIds.forEach { instrumentId -> programPositions.note(instrumentId, size) }
        }
        val programHeld = programPositions.ids()
        val candidates = try {
            entryCandidates.load(at, programHeld)
        } catch (_: Exception) {
            emptyList()
        }
        val held = observed.instruments.map { it.instrumentId }.filter { it.isNotBlank() }.toSet() + programHeld
        val rates = try {
            reportedRates.load().map { rate ->
                PayloadRate(rate.currency, rate.displayCurrency, rate.rate)
            }
        } catch (_: Exception) {
            emptyList()
        }
        val programInvested = programPositions.investedMinor()
        val book = BarBook(
            openPositions = programPositions.count(),
            instruments = candidates.map { instrument ->
                InstrumentBars(
                    instrumentId = instrument.instrumentId,
                    assetClass = instrument.assetClass,
                    bars = instrument.bars.map { bar ->
                        PriceBar(bar.start, bar.end, bar.closeMinor, bar.highMinor, bar.lowMinor)
                    },
                )
            },
            reportedLossMinor = observed.bookLossMinor,
            reportedLossCurrency = observed.bookLossCurrency,
            payloadRates = rates,
            entriesBlocked = entriesBlocked(),
            investedMinor = programInvested,
            // Il feed non assegna un settore comune: il tetto di settore non accorpa nomi diversi.
            sectorInvestedMinor = 0,
            heldInstrumentIds = held,
        )
        val lossBlocksEntries = BudgetLoss.blocksNewEntries(
            observed.bookLossMinor,
            observed.bookLossCurrency,
            configuration.parameters.economic,
            rates,
        )
        val rule = if (breakout) {
            val enabled = book.instruments.map { it.instrumentId }.toSet() + observed.blockedEntryIds
            BreakoutLive.decide(
                BreakoutCycleInput(
                    candidates = book.instruments,
                    openIds = observed.blockedEntryIds,
                    enabledIds = enabled,
                    parameters = configuration.parameters.breakout,
                    entriesBlocked = entriesBlocked(),
                    lossBlocksEntries = lossBlocksEntries,
                ),
            )
        } else {
            ClosedBarEvaluation.decide(
                at,
                book,
                configuration.parameters.economic,
                configuration.parameters.technical,
            )
        }
        val closedBarCount = book.instruments
            .filter { it.instrumentId !in held }
            .maxOfOrNull { it.bars.size } ?: 0
        if (rule.action == "ENTER") {
            check(rule.orderable && rule.direction == "LONG") { "un ingresso della regola è long e inviabile" }
        } else {
            check(!rule.orderable) { "solo un ingresso nuovo può essere inviabile" }
        }
        if (!breakout && closedBarCount < configuration.parameters.technical.int("regime_atr_bars")) {
            check(rule.action == "NO-TRADE") {
                "senza abbastanza barre chiuse il ciclo non può proporre un ingresso"
            }
        }
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
        val demoEntry = demoEntry(rule, rates, held, candidates)
        if (demoEntry.status == "ACCEPTED" && rule.instrumentId != null && rule.notionalMinor != null) {
            programPositions.note(rule.instrumentId, rule.notionalMinor)
        }
        val payload = decisionPayload(
            outcome,
            prices,
            news,
            macro,
            fundamentals,
            ruleAction = rule.action,
            ruleReason = rule.reason,
            ruleInstrument = rule.instrumentId.orEmpty(),
            ruleOrderable = rule.orderable,
            closedBars = closedBarCount,
            bookLossMinor = observed.bookLossMinor,
            bookLossCurrency = observed.bookLossCurrency.orEmpty(),
            lossBlocksEntries = lossBlocksEntries,
            demoStatus = demoEntry.status,
            demoReason = demoEntry.reason,
            demoHttpStatus = demoEntry.httpStatus,
            demoOrderId = demoEntry.orderId.orEmpty(),
        )
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
        val result = RecordedDecision(
            outcome,
            configuration,
            auditEvent,
            policy,
            rule,
            closedBarCount,
            observed.bookLossMinor,
            observed.bookLossCurrency,
            lossBlocksEntries,
            demoEntry,
            programPositions.count(),
            programPositions.investedMinor(),
        )
        recorded = result
        return result
    }

    private fun demoEntry(
        rule: RuleDecision,
        rates: List<PayloadRate>,
        held: Set<String>,
        candidates: List<ObservedInstrumentBars>,
    ): DemoOpenResult {
        if (rule.action != "ENTER" || !rule.orderable || rule.instrumentId == null || rule.notionalMinor == null) {
            return DemoOpenResult("NOT_SENT", rule.reason)
        }
        if (rule.instrumentId in held) {
            return DemoOpenResult("NOT_SENT", "EXISTING_POSITION")
        }
        val amount = TradingAmount.usd(rule.notionalMinor, rates)
            ?: return DemoOpenResult("NOT_SENT", "RATE_MISSING")
        if (!TradingAmount.acceptable(amount)) {
            return DemoOpenResult("NOT_SENT", "AMOUNT")
        }
        val leverage = if (candidates.firstOrNull { it.instrumentId == rule.instrumentId }?.includeLeverage == true) 1 else null
        return demoEntries.submit(rule.instrumentId, amount, held, leverage)
    }

    companion object {
        const val COMPONENT_VERSION: String = "observation-decision/1"

        fun decisionPayload(
            outcome: StrategyOutcome,
            prices: ChannelHealth? = null,
            news: ChannelHealth? = null,
            macro: ChannelHealth? = null,
            fundamentals: ChannelHealth? = null,
            ruleAction: String = "NO-TRADE",
            ruleReason: String = "WARMUP",
            ruleInstrument: String = "",
            ruleOrderable: Boolean = false,
            closedBars: Int = 0,
            bookLossMinor: Long? = null,
            bookLossCurrency: String = "",
            lossBlocksEntries: Boolean = false,
            demoStatus: String = "NOT_SENT",
            demoReason: String = "WARMUP",
            demoHttpStatus: Int? = null,
            demoOrderId: String = "",
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
            "rule_action=$ruleAction",
            "rule_reason=$ruleReason",
            "rule_instrument=$ruleInstrument",
            "rule_orderable=$ruleOrderable",
            "closed_bars=$closedBars",
            "book_loss_minor=${bookLossMinor ?: ""}",
            "book_loss_currency=$bookLossCurrency",
            "loss_blocks_entries=$lossBlocksEntries",
            "demo_entry_status=$demoStatus",
            "demo_entry_reason=$demoReason",
            "demo_entry_http_status=${demoHttpStatus ?: ""}",
            "demo_entry_order_id=$demoOrderId",
        ).joinToString("\n")
    }
}

class ProgramPositions(seed: Map<String, Long> = emptyMap()) {
    private val notionals = linkedMapOf<String, Long>()

    init {
        seed.forEach { (instrumentId, notionalMinor) ->
            require(instrumentId.isNotBlank() && notionalMinor > 0) { "una posizione del programma ha un nominale positivo" }
            notionals.putIfAbsent(instrumentId, notionalMinor)
        }
    }

    fun ids(): Set<String> = notionals.keys.toSet()

    fun count(): Int = notionals.size

    fun investedMinor(): Long = notionals.values.sum()

    fun note(instrumentId: String, notionalMinor: Long) {
        if (instrumentId.isBlank() || notionalMinor <= 0) {
            return
        }
        notionals.putIfAbsent(instrumentId, notionalMinor)
    }

    companion object {
        const val OPENED_INSTRUMENT: String = "1001"
        const val OPENED_NOTIONAL_MINOR: Long = 7_500L

        /** AAPL, ordine 397030666, 75,00 EUR. Conta nel budget e non si riapre. */
        fun withOpenedBook(): ProgramPositions = ProgramPositions(mapOf(OPENED_INSTRUMENT to OPENED_NOTIONAL_MINOR))
    }
}
