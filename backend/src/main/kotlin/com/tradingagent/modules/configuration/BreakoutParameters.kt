package com.tradingagent.modules.configuration

import java.math.BigDecimal

data class BreakoutParameters(
    val strategy: String,
    val timeframe: String,
    val barSeconds: Long,
    val entryLookback: Int,
    val exitLookback: Int,
    val stopDistanceRatio: BigDecimal,
    val positionSizeMinor: Long,
    val maxPositionMinor: Long,
    val maxExposureMinor: Long,
    val maxPositions: Int,
    val priority: String,
) {
    fun canonical(): String = listOf(
        "strategy=$strategy",
        "timeframe=$timeframe",
        "bar_seconds=$barSeconds",
        "entry_lookback=$entryLookback",
        "exit_lookback=$exitLookback",
        "stop_distance_ratio=${stopDistanceRatio.toPlainString()}",
        "position_size_minor=$positionSizeMinor",
        "max_position_minor=$maxPositionMinor",
        "max_exposure_minor=$maxExposureMinor",
        "max_positions=$maxPositions",
        "priority=$priority",
    ).joinToString("\n")

    companion object {
        const val STRATEGY_ID: String = "breakout/1"
        /** Lower instrument id is authorized first when several pairs signal together. */
        const val PRIORITY: String = "instrument_id"
    }
}
