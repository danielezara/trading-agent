package com.tradingagent.modules.configuration

import java.math.BigDecimal
import java.time.Instant

data class SpotSelection(
    val exchange: String,
    val quoteCurrency: String,
    val timeframe: String,
    val barSeconds: Long,
    val historyStart: Instant,
    val historyEnd: Instant,
    val minVolume: BigDecimal,
    val volumeUnit: String,
    val volumeWindow: String,
    val maxSpread: BigDecimal,
    val spreadUnit: String,
    val spreadModel: String,
    val spreadModelValue: BigDecimal?,
    val quoteMaxAgeSeconds: Long,
    val assets: List<String>,
) {
    fun canonical(): String = listOf(
        "exchange=$exchange",
        "quote_currency=$quoteCurrency",
        "timeframe=$timeframe",
        "bar_seconds=$barSeconds",
        "history_start=$historyStart",
        "history_end=$historyEnd",
        "min_volume=${minVolume.toPlainString()}",
        "volume_unit=$volumeUnit",
        "volume_window=$volumeWindow",
        "max_spread=${maxSpread.toPlainString()}",
        "spread_unit=$spreadUnit",
        "spread_model=$spreadModel",
        "spread_model_value=${spreadModelValue?.toPlainString() ?: ""}",
        "quote_max_age_seconds=$quoteMaxAgeSeconds",
        "assets=${assets.joinToString(",")}",
    ).joinToString("\n")

    companion object {
        val FIXED_ASSETS: List<String> = listOf(
            "BTC",
            "ETH",
            "BNB",
            "XRP",
            "SOL",
            "TRX",
            "HYPE",
            "ZEC",
            "DOGE",
            "LEO",
            "XMR",
            "LINK",
            "ADA",
            "XLM",
            "BCH",
            "NEAR",
            "LTC",
            "CC",
            "AVAX",
            "SUI",
        )
    }
}
