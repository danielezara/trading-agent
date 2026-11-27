package com.tradingagent

import com.tradingagent.config.TradingProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(TradingProperties::class)
class TradingAgentApplication

fun main(args: Array<String>) {
    runApplication<TradingAgentApplication>(*args)
}
