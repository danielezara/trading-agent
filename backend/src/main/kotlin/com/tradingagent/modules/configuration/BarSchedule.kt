package com.tradingagent.modules.configuration

import java.time.Instant

object BarSchedule {
    private fun lengthSeconds(): Long = TradingParameters.current().technical.decisionBarSeconds

    fun isBoundary(instant: Instant): Boolean =
        instant.nano == 0 && Math.floorMod(instant.epochSecond, lengthSeconds()) == 0L

    fun open(instant: Instant): Instant =
        Instant.ofEpochSecond(instant.epochSecond - Math.floorMod(instant.epochSecond, lengthSeconds()))

    fun nextBoundary(instant: Instant): Instant =
        if (isBoundary(instant)) instant.plusSeconds(lengthSeconds()) else open(instant).plusSeconds(lengthSeconds())
}
