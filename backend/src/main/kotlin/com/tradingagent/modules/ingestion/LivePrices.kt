package com.tradingagent.modules.ingestion

import java.math.BigDecimal
import java.time.Instant

data class LiveRate(
    val instrumentId: String,
    val value: BigDecimal,
    val eventTime: Instant,
)

data class LivePriceBatch(
    val rates: List<LiveRate>,
    val rejectedCount: Int,
)

fun interface LivePriceLookup {
    /** Null when the read is not attempted. An empty batch was attempted and has no usable price. */
    fun lookup(instrumentIds: List<String>): LivePriceBatch?
}

class ObservationPrices(
    private val pipeline: IngestionPipeline,
    private val lookup: LivePriceLookup,
    private val instrumentIds: () -> List<String>,
) {
    fun assess(now: Instant): IngestionAssessment {
        val ids = instrumentIds()
            .map { it.trim() }
            .filter { id -> id.toIntOrNull()?.let { it in 1..Int.MAX_VALUE } == true }
            .distinct()
            .take(MAX_INSTRUMENTS)
        val batch = lookup.lookup(ids)
        return if (batch == null) pipeline.assess(now) else pipeline.assess(now, batch)
    }

    private companion object {
        const val MAX_INSTRUMENTS: Int = 100
    }
}
