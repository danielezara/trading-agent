package com.tradingagent.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "trading")
data class TradingProperties(
    val environment: String = "DEMO",
    val operationalState: String = "OBSERVATION",
    val broker: Broker = Broker(),
) {
    data class Broker(
        val enabled: Boolean = false,
        val baseUrl: String = "",
    )
}
