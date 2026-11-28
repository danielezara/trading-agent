package com.tradingagent.config

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

@Component
class SkeletonSafetyGuard(
    private val properties: TradingProperties,
) {
    @PostConstruct
    fun validate() {
        val env = System.getenv()
        SkeletonSafetyCheck.assertConfiguration(properties, env)
        demoKeys = DemoKeySlot.fromEnvironment(env)
    }

    private var demoKeys: DemoKeySlot = DemoKeySlot.fromEnvironment(emptyMap())
}
