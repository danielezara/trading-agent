package com.tradingagent.config

data class DemoKeySlot(
    val apiKeyPresent: Boolean,
    val userKeyPresent: Boolean,
) {
    fun complete(): Boolean = apiKeyPresent && userKeyPresent

    override fun toString(): String = "DemoKeySlot(apiKeyPresent=$apiKeyPresent, userKeyPresent=$userKeyPresent)"

    companion object {
        const val API_KEY_ENV: String = "ETORO_DEMO_API_KEY"
        const val USER_KEY_ENV: String = "ETORO_DEMO_USER_KEY"

        fun fromEnvironment(env: Map<String, String>): DemoKeySlot = DemoKeySlot(
            apiKeyPresent = !env[API_KEY_ENV].isNullOrBlank(),
            userKeyPresent = !env[USER_KEY_ENV].isNullOrBlank(),
        )
    }
}
