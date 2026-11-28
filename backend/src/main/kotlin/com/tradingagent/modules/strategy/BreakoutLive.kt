package com.tradingagent.modules.strategy

import com.tradingagent.modules.configuration.BreakoutParameters

data class BreakoutCycleInput(
    val candidates: List<InstrumentBars>,
    val openIds: Set<String>,
    val enabledIds: Set<String>,
    val parameters: BreakoutParameters,
    val entriesBlocked: Boolean,
    val lossBlocksEntries: Boolean,
)

object BreakoutLive {
    fun decide(input: BreakoutCycleInput): RuleDecision {
        if (input.entriesBlocked) {
            return none(null, ClosedBarPolicy.KILL_SWITCH)
        }
        if (input.lossBlocksEntries) {
            return none(null, MandateControl.LOSS_LIMIT)
        }
        val params = input.parameters
        val positions = input.openIds.map { id ->
            BreakoutPosition(id, params.positionSizeMinor, 0, 0)
        }
        val signals = signals(input, positions, params)
        val account = BreakoutAccount(
            cashMinor = (params.maxExposureMinor - positions.sumOf { it.sizeMinor }).coerceAtLeast(0),
            positions = positions,
            pendingEntryIds = emptySet(),
            enabledIds = input.enabledIds,
        )
        if (signals.isEmpty()) {
            val reason = if (input.openIds.isEmpty()) BreakoutStrategy.HISTORY else BreakoutStrategy.EXISTING_POSITION
            return none(input.openIds.minOrNull(), reason)
        }
        val decisions = BreakoutStrategy.allocate(signals, account, params)
        val chosen = decisions.firstOrNull { it.signal.action == BreakoutStrategy.ENTER && it.authorization.approved }
        if (chosen != null) {
            return RuleDecision(
                action = "ENTER",
                instrumentId = chosen.signal.instrumentId,
                direction = "LONG",
                notionalMinor = params.positionSizeMinor,
                orderable = true,
                reason = chosen.signal.reason,
            )
        }
        val blocked = decisions.firstOrNull { it.signal.action == BreakoutStrategy.ENTER }
        if (blocked != null) {
            return none(blocked.signal.instrumentId, "${blocked.signal.reason}/${blocked.authorization.reason}")
        }
        val exit = decisions.firstOrNull { it.signal.action == BreakoutStrategy.EXIT }
        if (exit != null) {
            return none(exit.signal.instrumentId, exit.signal.reason)
        }
        val hold = decisions.firstOrNull()
        return none(hold?.signal?.instrumentId, hold?.signal?.reason ?: BreakoutStrategy.NONE)
    }

    fun signals(input: BreakoutCycleInput): List<BreakoutSignal> {
        val params = input.parameters
        val positions = input.openIds.map { id ->
            BreakoutPosition(id, params.positionSizeMinor, 0, 0)
        }
        return signals(input, positions, params)
    }

    private fun signals(
        input: BreakoutCycleInput,
        positions: List<BreakoutPosition>,
        params: BreakoutParameters,
    ): List<BreakoutSignal> = input.candidates.map { instrument ->
        val held = positions.firstOrNull { it.instrumentId == instrument.instrumentId }
        BreakoutStrategy.signal(instrument.instrumentId, bars(instrument), held, params)
    }

    private fun bars(instrument: InstrumentBars): List<BreakoutBar> = instrument.bars.map { bar ->
        // Il giro vivo non ha l'open della candela. Lo stop intrabar non si valuta su un open inventato.
        BreakoutBar(
            startEpochSecond = bar.start.epochSecond,
            openMinor = Long.MAX_VALUE,
            highMinor = bar.highMinor,
            lowMinor = bar.lowMinor,
            closeMinor = bar.closeMinor,
        )
    }

    private fun none(instrumentId: String?, reason: String): RuleDecision = RuleDecision(
        action = "NO-TRADE",
        instrumentId = instrumentId,
        direction = "NONE",
        notionalMinor = null,
        orderable = false,
        reason = reason,
    )
}
