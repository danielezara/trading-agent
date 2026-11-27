package com.tradingagent.config

import com.tradingagent.modules.audit.AuditLog
import com.tradingagent.modules.brokerread.CapabilityProbe
import com.tradingagent.modules.brokerread.DemoKeyMaterial
import com.tradingagent.modules.brokerread.JdkHttpGet
import com.tradingagent.modules.chat.ChatQuery
import com.tradingagent.modules.configuration.ConfigurationService
import com.tradingagent.modules.configuration.EvidenceGate
import com.tradingagent.modules.decision.DecisionCycle
import com.tradingagent.modules.corporateaction.CorporateActionRegistry
import com.tradingagent.modules.executor.DisabledBrokerExecutor
import com.tradingagent.modules.features.FeatureStore
import com.tradingagent.modules.identity.AccessModel
import com.tradingagent.modules.ingestion.IngestionPipeline
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

    @Bean fun featureStore() = FeatureStore()

    @Bean fun corporateActionRegistry() = CorporateActionRegistry()

    @Bean fun strategyEngine() = StrategyEngine()

    @Bean fun portfolioConstruction() = PortfolioConstruction()

    @Bean fun riskEngine() = RiskEngine()

    @Bean fun policyGate() = PolicyGate()

    @Bean fun ledger() = Ledger()

    @Bean fun orderIntentService() = OrderIntentService()

    @Bean fun disabledBrokerExecutor() = DisabledBrokerExecutor()

    @Bean fun reconciliationJob() = ReconciliationJob()

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
        )
        val recorded = cycle.record()
        auditLog.sealDay(LocalDate.ofInstant(recorded.outcome.evaluatedAt, ZoneOffset.UTC))
        return cycle
    }

    @Bean
    fun dashboardReadModel(
        operationalStateService: OperationalStateService,
        ledger: Ledger,
        configurationService: ConfigurationService,
        capabilityProbe: CapabilityProbe,
    ) = DashboardReadModel(operationalStateService, ledger, configurationService, capabilityProbe)

    @Bean fun chatQuery() = ChatQuery()

    @Bean fun durableScheduler() = DurableScheduler()

    @Bean fun outboxDispatcher() = OutboxDispatcher()
}
