package com.tradingagent.modules.brokerread

import com.tradingagent.modules.configuration.SpotSelection
import java.time.Duration
import java.time.Instant

class SpotBreakoutCandidates(
    private val selection: () -> SpotSelection,
    private val keys: () -> DemoKeyMaterial?,
    private val transport: HttpGet,
    private val sleep: (Duration) -> Unit = {},
) : EntryCandidates {
    @Volatile
    var rows: List<SelectionRow> = emptyList()
        private set

    override fun load(now: Instant, heldInstrumentIds: Set<String>): List<ObservedInstrumentBars> {
        val material = keys() ?: return emptyList()
        val selected = try {
            SpotEnabledTickers.selectRows(selection(), transport, material, now, sleep)
        } catch (_: Exception) {
            emptyList()
        }
        rows = selected
        return selected.mapNotNull { row ->
            val id = SpotEnabledTickers.instrumentId(row) ?: return@mapNotNull null
            val bars = DemoClosedBars.barsFor(transport, material, id, now, sleep)
            ObservedInstrumentBars(id, "crypto", bars, includeLeverage = false, entryAllowed = !row.entryBlocked)
        }
    }
}
