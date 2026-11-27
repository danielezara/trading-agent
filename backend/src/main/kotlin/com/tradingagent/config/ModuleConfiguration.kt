package com.tradingagent.config

import com.tradingagent.modules.audit.AuditLog
import com.tradingagent.modules.chat.ChatQuery
import com.tradingagent.modules.configuration.EvidenceGate
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
import com.tradingagent.modules.risk.RiskEngine
import com.tradingagent.modules.scheduler.DurableScheduler
import com.tradingagent.modules.strategy.StrategyEngine
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ModuleConfiguration {
    @Bean fun accessModel() = AccessModel()

    @Bean fun evidenceGate() = EvidenceGate()

    @Bean fun ingestionPipeline() = IngestionPipeline()

    @Bean fun featureStore() = FeatureStore()

    @Bean fun corporateActionRegistry() = CorporateActionRegistry()

    @Bean fun strategyEngine() = StrategyEngine()

    @Bean fun portfolioConstruction() = PortfolioConstruction()

    @Bean fun riskEngine() = RiskEngine()

    @Bean fun ledger() = Ledger()

    @Bean fun orderIntentService() = OrderIntentService()

    @Bean fun disabledBrokerExecutor() = DisabledBrokerExecutor()

    @Bean fun reconciliationJob() = ReconciliationJob()

    @Bean fun operationalStateService() = OperationalStateService()

    @Bean fun auditLog() = AuditLog()

    @Bean
    fun dashboardReadModel(
        operationalStateService: OperationalStateService,
        ledger: Ledger,
    ) = DashboardReadModel(operationalStateService, ledger)

    @Bean fun chatQuery() = ChatQuery()

    @Bean fun durableScheduler() = DurableScheduler()

    @Bean fun outboxDispatcher() = OutboxDispatcher()
}
