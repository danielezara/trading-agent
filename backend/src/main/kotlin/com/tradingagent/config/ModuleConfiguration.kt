package com.tradingagent.config

import com.tradingagent.modules.audit.AuditLog
import com.tradingagent.modules.brokerread.CapabilityProbe
import com.tradingagent.modules.brokerread.CashBalanceRead
import com.tradingagent.modules.brokerread.CashMovementRead
import com.tradingagent.modules.brokerread.DemoCashBalanceRead
import com.tradingagent.modules.brokerread.DemoCashMovementRead
import com.tradingagent.modules.brokerread.DemoKeyMaterial
import com.tradingagent.modules.brokerread.DemoInstrumentNameRead
import com.tradingagent.modules.brokerread.DemoMarketRatesLookup
import com.tradingagent.modules.brokerread.ClosedTradeRead
import com.tradingagent.modules.brokerread.DemoClosedBarRead
import com.tradingagent.modules.brokerread.DemoClosedTradeRead
import com.tradingagent.modules.brokerread.DemoInstrumentClassRead
import com.tradingagent.modules.brokerread.EcbEuroFx
import com.tradingagent.modules.brokerread.EuroFxRead
import com.tradingagent.modules.brokerread.InstrumentClassRead
import com.tradingagent.modules.brokerread.DemoPositionQuotes
import com.tradingagent.modules.brokerread.InstrumentNameRead
import com.tradingagent.modules.brokerread.JdkHttpGet
import com.tradingagent.modules.brokerread.PositionQuoteRead
import com.tradingagent.modules.chat.ChatQuery
import com.tradingagent.modules.configuration.ConfigurationService
import com.tradingagent.modules.configuration.EvidenceGate
import com.tradingagent.modules.decision.DecisionCycle
import com.tradingagent.modules.corporateaction.CorporateActionRegistry
import com.tradingagent.modules.brokerread.DemoReportedRateRead
import com.tradingagent.modules.executor.DisabledBrokerExecutor
import com.tradingagent.modules.oms.KillSwitch
import com.tradingagent.modules.features.FeatureStore
import com.tradingagent.modules.identity.AccessModel
import com.tradingagent.modules.ingestion.IngestionPipeline
import com.tradingagent.modules.ingestion.LivePriceLookup
import com.tradingagent.modules.ingestion.ObservationPrices
import com.tradingagent.modules.ledger.Ledger
import com.tradingagent.modules.oms.OrderIntentService
import com.tradingagent.modules.operational.OperationalStateService
import com.tradingagent.modules.outbox.OutboxDispatcher
import com.tradingagent.modules.portfolio.PortfolioConstruction
import com.tradingagent.modules.readmodel.DashboardReadModel
import com.tradingagent.modules.reconciliation.ReconciliationJob
import com.tradingagent.modules.risk.PolicyGate
import com.tradingagent.modules.risk.RiskEngine
import com.tradingagent.modules.scheduler.DurableScheduler
import com.tradingagent.modules.strategy.StrategyEngine
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.LocalDate
import java.time.ZoneOffset

@Configuration
class ModuleConfiguration {
    @Bean fun accessModel() = AccessModel()

    @Bean fun evidenceGate() = EvidenceGate()

    @Bean
    fun capabilityProbe(evidenceGate: EvidenceGate): CapabilityProbe {
        val probe = CapabilityProbe(
            evidenceGate = evidenceGate,
            keys = { DemoKeyMaterial.fromEnvironment(System.getenv()) },
            transport = JdkHttpGet(),
        )
        probe.refresh()
        return probe
    }

    @Bean fun configurationService() = ConfigurationService()

    @Bean fun ingestionPipeline() = IngestionPipeline()

    @Bean
    fun livePriceLookup(): LivePriceLookup = DemoMarketRatesLookup(
        keys = { DemoKeyMaterial.fromEnvironment(System.getenv()) },
        transport = JdkHttpGet(),
    )

    @Bean
    fun observationPrices(
        ingestionPipeline: IngestionPipeline,
        capabilityProbe: CapabilityProbe,
        livePriceLookup: LivePriceLookup,
    ) = ObservationPrices(
        pipeline = ingestionPipeline,
        lookup = livePriceLookup,
        instrumentIds = {
            capabilityProbe.current().positions.orEmpty().map { it.instrumentId }
        },
    )

    @Bean fun featureStore() = FeatureStore()

    @Bean fun corporateActionRegistry() = CorporateActionRegistry()

    @Bean fun strategyEngine() = StrategyEngine()

    @Bean fun portfolioConstruction() = PortfolioConstruction()

    @Bean fun riskEngine() = RiskEngine()

    @Bean fun policyGate() = PolicyGate()

    @Bean fun killSwitch() = KillSwitch()

    @Bean fun ledger() = Ledger()

    @Bean fun orderIntentService() = OrderIntentService()

    @Bean fun disabledBrokerExecutor() = DisabledBrokerExecutor()

    @Bean fun reconciliationJob(auditLog: AuditLog) = ReconciliationJob(auditLog)

    @Bean fun operationalStateService() = OperationalStateService()

    @Bean fun auditLog() = AuditLog()

    @Bean
    fun decisionCycle(
        strategyEngine: StrategyEngine,
        ingestionPipeline: IngestionPipeline,
        evidenceGate: EvidenceGate,
        operationalStateService: OperationalStateService,
        configurationService: ConfigurationService,
        auditLog: AuditLog,
        policyGate: PolicyGate,
        ledger: Ledger,
        capabilityProbe: CapabilityProbe,
        observationPrices: ObservationPrices,
        killSwitch: KillSwitch,
    ): DecisionCycle {
        val cycle = DecisionCycle(
            strategyEngine = strategyEngine,
            ingestionPipeline = ingestionPipeline,
            evidenceGate = evidenceGate,
            operationalStateService = operationalStateService,
            configurationService = configurationService,
            auditLog = auditLog,
            policyGate = policyGate,
            ledger = ledger,
            capabilityProbe = capabilityProbe,
            assess = { observationPrices.assess(it) },
            euroFx = euroFxRead(),
            closedBars = DemoClosedBarRead(
                keys = { DemoKeyMaterial.fromEnvironment(System.getenv()) },
                transport = JdkHttpGet(),
            ),
            reportedRates = DemoReportedRateRead(
                keys = { DemoKeyMaterial.fromEnvironment(System.getenv()) },
                transport = JdkHttpGet(),
            ),
            entriesBlocked = { killSwitch.armed() },
        )
        val recorded = cycle.record()
        auditLog.sealDay(LocalDate.ofInstant(recorded.outcome.evaluatedAt, ZoneOffset.UTC))
        return cycle
    }

    @Bean
    fun instrumentClassRead(): InstrumentClassRead = DemoInstrumentClassRead(
        keys = { DemoKeyMaterial.fromEnvironment(System.getenv()) },
        transport = JdkHttpGet(),
    )

    @Bean
    fun instrumentNameRead(): InstrumentNameRead = DemoInstrumentNameRead(
        keys = { DemoKeyMaterial.fromEnvironment(System.getenv()) },
        transport = JdkHttpGet(),
    )

    @Bean
    fun closedTradeRead(): ClosedTradeRead = DemoClosedTradeRead(
        keys = { DemoKeyMaterial.fromEnvironment(System.getenv()) },
        transport = JdkHttpGet(),
    )

    @Bean
    fun positionQuoteRead(): PositionQuoteRead = DemoPositionQuotes(
        keys = { DemoKeyMaterial.fromEnvironment(System.getenv()) },
        transport = JdkHttpGet(),
    )

    @Bean
    fun dashboardReadModel(
        operationalStateService: OperationalStateService,
        ledger: Ledger,
        configurationService: ConfigurationService,
        capabilityProbe: CapabilityProbe,
        positionQuoteRead: PositionQuoteRead,
        instrumentNameRead: InstrumentNameRead,
        closedTradeRead: ClosedTradeRead,
        instrumentClassRead: InstrumentClassRead,
    ) = DashboardReadModel(
        operationalStateService,
        ledger,
        configurationService,
        capabilityProbe,
        positionQuoteRead,
        instrumentNameRead,
        instrumentClassRead,
        closedTradeRead,
        euroFxRead(),
        cashBalanceRead(),
        cashMovementRead(),
    )

    @Bean
    fun cashMovementRead(): CashMovementRead = DemoCashMovementRead(
        keys = { DemoKeyMaterial.fromEnvironment(System.getenv()) },
        transport = JdkHttpGet(),
    )

    @Bean
    fun cashBalanceRead(): CashBalanceRead = DemoCashBalanceRead(
        keys = { DemoKeyMaterial.fromEnvironment(System.getenv()) },
        transport = JdkHttpGet(),
    )

    @Bean
    fun euroFxRead(): EuroFxRead = EcbEuroFx(transport = JdkHttpGet())

    @Bean fun chatQuery() = ChatQuery()

    @Bean fun durableScheduler() = DurableScheduler()

    @Bean fun outboxDispatcher() = OutboxDispatcher()
}
