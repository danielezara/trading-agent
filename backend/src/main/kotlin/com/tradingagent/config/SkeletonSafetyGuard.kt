package com.tradingagent.config

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

@Component
class SkeletonSafetyGuard(
    private val properties: TradingProperties,
) {
    @PostConstruct
    fun validate() {
        SkeletonSafetyCheck.assertConfiguration(properties, System.getenv())
    }
}
