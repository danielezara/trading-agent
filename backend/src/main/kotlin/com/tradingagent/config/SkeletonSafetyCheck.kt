package com.tradingagent.config

object SkeletonSafetyCheck {
    val forbiddenSecretEnv: Set<String> = setOf(
        "ETORO_API_KEY",
        "ETORO_API_SECRET",
        "ETORO_USER_KEY",
        "ETORO_PASSWORD",
        "ETORO_TOKEN",
        "BROKER_API_KEY",
        "BROKER_API_SECRET",
        "LLM_API_KEY",
        "OPENAI_API_KEY",
    )

    fun assertConfiguration(properties: TradingProperties, env: Map<String, String>) {
        if (properties.environment != "DEMO") {
            fail("TRADING_ENVIRONMENT deve essere DEMO")
        }
        if (properties.operationalState != "OBSERVATION") {
            fail("TRADING_OPERATIONAL_STATE deve essere OBSERVATION")
        }
        if (properties.broker.enabled) {
            fail("TRADING_BROKER_ENABLED deve essere false")
        }
        if (properties.broker.baseUrl.isNotBlank()) {
            fail("TRADING_BROKER_BASE_URL deve restare vuoto")
        }
        val present = forbiddenSecretEnv.filter { name -> !env[name].isNullOrBlank() }
        if (present.isNotEmpty()) {
            fail("Variabili segrete non ammesse in questo scheletro: ${present.joinToString(",")}")
        }
    }

    private fun fail(message: String): Nothing = throw IllegalStateException(message)
}
