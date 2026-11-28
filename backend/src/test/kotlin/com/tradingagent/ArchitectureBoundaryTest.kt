package com.tradingagent

import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

@AnalyzeClasses(packages = ["com.tradingagent"])
class ArchitectureBoundaryTest {
    @ArchTest
    val kernelDoesNotDependOnModules: ArchRule = noClasses()
        .that().resideInAPackage("..kernel..")
        .should().dependOnClassesThat().resideInAnyPackage("..modules..", "..api..", "..config..")

    @ArchTest
    val tradingModulesDoNotDependOnExecutor: ArchRule = noClasses()
        .that().resideInAnyPackage(
            "..modules.strategy..",
            "..modules.chat..",
            "..modules.risk..",
            "..modules.portfolio..",
            "..modules.ingestion..",
            "..modules.features..",
            "..modules.identity..",
            "..modules.configuration..",
            "..modules.audit..",
            "..modules.readmodel..",
            "..modules.reconciliation..",
            "..modules.corporateaction..",
            "..modules.ledger..",
            "..modules.scheduler..",
            "..modules.outbox..",
            "..modules.operational..",
            "..modules.oms..",
        )
        .should().dependOnClassesThat().resideInAPackage("..modules.executor..")

    @ArchTest
    val chatStaysOffTheWritePath: ArchRule = noClasses()
        .that().resideInAPackage("..modules.chat..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..modules.oms..",
            "..modules.ledger..",
            "..modules.executor..",
            "..modules.operational..",
        )

    @ArchTest
    val executorDoesNotDecide: ArchRule = noClasses()
        .that().resideInAPackage("..modules.executor..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..modules.strategy..",
            "..modules.risk..",
            "..modules.chat..",
            "..modules.portfolio..",
        )

    @ArchTest
    val modulesAreFreeOfCycles: ArchRule = slices()
        .matching("com.tradingagent.modules.(*)..")
        .should().beFreeOfCycles()

    @ArchTest
    val noHttpClients: ArchRule = noClasses()
        .that().resideOutsideOfPackage("..modules.brokerread..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "java.net.http..",
            "okhttp3..",
            "org.apache.http..",
            "org.springframework.web.client..",
            "org.springframework.web.reactive..",
        )

    @ArchTest
    val noBrokerClientTypes: ArchRule = noClasses()
        .should().haveSimpleNameContaining("Etoro")
}
