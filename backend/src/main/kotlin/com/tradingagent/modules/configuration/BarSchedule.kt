package com.tradingagent.modules.configuration

import java.time.Instant

object BarSchedule {
    const val LENGTH_SECONDS: Long = 300

    fun isBoundary(instant: Instant): Boolean =
        instant.nano == 0 && Math.floorMod(instant.epochSecond, LENGTH_SECONDS) == 0L

    fun open(instant: Instant): Instant =
        Instant.ofEpochSecond(instant.epochSecond - Math.floorMod(instant.epochSecond, LENGTH_SECONDS))

    fun nextBoundary(instant: Instant): Instant =
        if (isBoundary(instant)) instant.plusSeconds(LENGTH_SECONDS) else open(instant).plusSeconds(LENGTH_SECONDS)
}
