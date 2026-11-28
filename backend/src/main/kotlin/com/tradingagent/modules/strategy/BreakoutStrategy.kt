package com.tradingagent.modules.strategy

import com.tradingagent.modules.configuration.BreakoutParameters
import java.math.BigDecimal
import java.math.RoundingMode

data class BreakoutBar(
    val startEpochSecond: Long,
    val openMinor: Long,
    val highMinor: Long,
    val lowMinor: Long,
    val closeMinor: Long,
)

data class BreakoutPosition(
    val instrumentId: String,
    val sizeMinor: Long,
    val entryFillMinor: Long,
    val stopMinor: Long,
)

data class BreakoutAccount(
    val cashMinor: Long,
    val positions: List<BreakoutPosition>,
    val pendingEntryIds: Set<String>,
    val enabledIds: Set<String>,
)

data class BreakoutSignal(
    val instrumentId: String,
    val action: String,
    val reason: String,
    val closeMinor: Long?,
)

data class RiskAuthorization(
    val approved: Boolean,
    val reason: String,
)

data class SimulatedExecution(
    val fillMinor: Long?,
    val basis: String,
    val signalCloseMinor: Long?,
    val stopGuaranteed: Boolean,
)

data class BreakoutDecision(
    val signal: BreakoutSignal,
    val authorization: RiskAuthorization,
    val execution: SimulatedExecution?,
)

data class BreakoutEvent(
    val barIndex: Int,
    val instrumentId: String,
    val signal: String,
    val signalReason: String,
    val riskReason: String,
    val fillMinor: Long?,
    val fillBasis: String?,
    val signalCloseMinor: Long?,
    val stopGuaranteed: Boolean,
)

object BreakoutStrategy {
    const val ENTER: String = "ENTER"
    const val EXIT: String = "EXIT"
    const val HOLD: String = "HOLD"
    const val BREAKOUT_HIGH: String = "breakout_high"
    const val CHANNEL_LOW: String = "channel_low"
    const val STOP_INTRABAR: String = "stop_intrabar"
    const val HISTORY: String = "history"
    const val NONE: String = "none"
    const val NO_SIGNAL: String = "no_signal"
    const val OK: String = "ok"
    const val PAIR_DISABLED: String = "pair_disabled"
    const val EXISTING_POSITION: String = "existing_position"
    const val PENDING_ENTRY: String = "pending_entry"
    const val NO_CAPITAL: String = "no_capital"
    const val POSITION_SIZE: String = "position_size"
    const val EXPOSURE: String = "exposure"
    const val MAX_POSITIONS: String = "max_positions"
    const val NO_POSITION: String = "no_position"
    const val NEXT_OPEN: String = "next_open"
    const val INTRABAR_LOW: String = "intrabar_low"
    const val GAP_OPEN: String = "gap_open"

    fun signal(instrumentId: String, bars: List<BreakoutBar>, position: BreakoutPosition?, params: BreakoutParameters): BreakoutSignal {
        if (bars.isEmpty()) {
            return BreakoutSignal(instrumentId, HOLD, HISTORY, null)
        }
        val current = bars.last()
        val prior = bars.dropLast(1)
        if (position != null) {
            if (current.openMinor <= position.stopMinor || current.lowMinor <= position.stopMinor) {
                return BreakoutSignal(instrumentId, EXIT, STOP_INTRABAR, current.closeMinor)
            }
            if (prior.size >= params.exitLookback) {
                val floor = prior.takeLast(params.exitLookback).minOf { it.lowMinor }
                if (current.closeMinor < floor) {
                    return BreakoutSignal(instrumentId, EXIT, CHANNEL_LOW, current.closeMinor)
                }
            } else {
                return BreakoutSignal(instrumentId, HOLD, HISTORY, current.closeMinor)
            }
            if (prior.size >= params.entryLookback) {
                val ceiling = prior.takeLast(params.entryLookback).maxOf { it.highMinor }
                if (current.closeMinor > ceiling) {
                    return BreakoutSignal(instrumentId, ENTER, BREAKOUT_HIGH, current.closeMinor)
                }
            }
            return BreakoutSignal(instrumentId, HOLD, NONE, current.closeMinor)
        }
        if (prior.size < params.entryLookback) {
            return BreakoutSignal(instrumentId, HOLD, HISTORY, current.closeMinor)
        }
        val ceiling = prior.takeLast(params.entryLookback).maxOf { it.highMinor }
        if (current.closeMinor > ceiling) {
            return BreakoutSignal(instrumentId, ENTER, BREAKOUT_HIGH, current.closeMinor)
        }
        return BreakoutSignal(instrumentId, HOLD, NONE, current.closeMinor)
    }

    fun authorize(signal: BreakoutSignal, account: BreakoutAccount, params: BreakoutParameters): RiskAuthorization {
        if (signal.action == HOLD) {
            return RiskAuthorization(false, NO_SIGNAL)
        }
        if (signal.action == EXIT) {
            val held = account.positions.any { it.instrumentId == signal.instrumentId }
            return RiskAuthorization(held, if (held) OK else NO_POSITION)
        }
        if (signal.instrumentId !in account.enabledIds) {
            return RiskAuthorization(false, PAIR_DISABLED)
        }
        if (account.positions.any { it.instrumentId == signal.instrumentId }) {
            return RiskAuthorization(false, EXISTING_POSITION)
        }
        if (signal.instrumentId in account.pendingEntryIds) {
            return RiskAuthorization(false, PENDING_ENTRY)
        }
        if (params.positionSizeMinor > params.maxPositionMinor) {
            return RiskAuthorization(false, POSITION_SIZE)
        }
        if (account.cashMinor < params.positionSizeMinor) {
            return RiskAuthorization(false, NO_CAPITAL)
        }
        val exposure = account.positions.sumOf { it.sizeMinor }
        if (exposure + params.positionSizeMinor > params.maxExposureMinor) {
            return RiskAuthorization(false, EXPOSURE)
        }
        val used = account.positions.size + account.pendingEntryIds.size
        if (used >= params.maxPositions) {
            return RiskAuthorization(false, MAX_POSITIONS)
        }
        return RiskAuthorization(true, OK)
    }

    /**
     * When several pairs signal together, lower instrument id is authorized first.
     * A later pair sees the slots, exposure and cash already taken by the earlier ids.
     */
    fun allocate(signals: List<BreakoutSignal>, account: BreakoutAccount, params: BreakoutParameters): List<BreakoutDecision> {
        val ordered = signals.sortedBy { it.instrumentId }
        var cash = account.cashMinor
        val positions = account.positions.toMutableList()
        val pending = account.pendingEntryIds.toMutableSet()
        return ordered.map { signal ->
            val view = account.copy(cashMinor = cash, positions = positions.toList(), pendingEntryIds = pending.toSet())
            val authorization = authorize(signal, view, params)
            if (authorization.approved && signal.action == ENTER) {
                cash -= params.positionSizeMinor
                pending += signal.instrumentId
                positions += BreakoutPosition(signal.instrumentId, params.positionSizeMinor, 0, 0)
            }
            BreakoutDecision(signal, authorization, plan(signal, authorization, null, null))
        }
    }

    fun plan(
        signal: BreakoutSignal,
        authorization: RiskAuthorization,
        nextOpenMinor: Long?,
        stopBar: BreakoutBar?,
        stopMinor: Long? = null,
    ): SimulatedExecution? {
        if (!authorization.approved) {
            return null
        }
        if (signal.action == ENTER || signal.reason == CHANNEL_LOW) {
            return SimulatedExecution(nextOpenMinor, NEXT_OPEN, signal.closeMinor, stopGuaranteed = false)
        }
        if (signal.reason == STOP_INTRABAR && stopBar != null && stopMinor != null) {
            val (fill, basis) = if (stopBar.openMinor <= stopMinor) {
                stopBar.openMinor to GAP_OPEN
            } else {
                stopBar.lowMinor to INTRABAR_LOW
            }
            return SimulatedExecution(fill, basis, signal.closeMinor, stopGuaranteed = false)
        }
        return null
    }

    fun evaluateClosedBar(
        instrumentId: String,
        bars: List<BreakoutBar>,
        account: BreakoutAccount,
        params: BreakoutParameters,
    ): BreakoutDecision {
        val position = account.positions.firstOrNull { it.instrumentId == instrumentId }
        val priced = signal(instrumentId, bars, position, params)
        val authorization = authorize(priced, account, params)
        val execution = plan(priced, authorization, nextOpenMinor = null, stopBar = bars.lastOrNull(), stopMinor = position?.stopMinor)
        return BreakoutDecision(priced, authorization, execution)
    }

    fun replay(
        instrumentId: String,
        bars: List<BreakoutBar>,
        enabled: Boolean,
        params: BreakoutParameters,
        cashMinor: Long,
    ): List<BreakoutEvent> {
        var cash = cashMinor
        var position: BreakoutPosition? = null
        var pending: BreakoutSignal? = null
        val events = mutableListOf<BreakoutEvent>()
        bars.forEachIndexed { index, bar ->
            val waiting = pending
            if (waiting != null && (waiting.action == ENTER || waiting.reason == CHANNEL_LOW)) {
                val fill = bar.openMinor
                events += BreakoutEvent(
                    barIndex = index,
                    instrumentId = instrumentId,
                    signal = "FILL",
                    signalReason = waiting.reason,
                    riskReason = OK,
                    fillMinor = fill,
                    fillBasis = NEXT_OPEN,
                    signalCloseMinor = waiting.closeMinor,
                    stopGuaranteed = false,
                )
                if (waiting.action == ENTER) {
                    position = BreakoutPosition(
                        instrumentId = instrumentId,
                        sizeMinor = params.positionSizeMinor,
                        entryFillMinor = fill,
                        stopMinor = stopPrice(fill, params.stopDistanceRatio),
                    )
                    cash -= params.positionSizeMinor
                } else {
                    cash += position?.sizeMinor ?: 0
                    position = null
                }
                pending = null
            }
            val history = bars.subList(0, index + 1)
            val held = position
            val priced = signal(instrumentId, history, held, params)
            val account = BreakoutAccount(
                cashMinor = cash,
                positions = listOfNotNull(held),
                pendingEntryIds = emptySet(),
                enabledIds = if (enabled) setOf(instrumentId) else emptySet(),
            )
            val authorization = authorize(priced, account, params)
            val execution = plan(priced, authorization, nextOpenMinor = null, stopBar = bar, stopMinor = held?.stopMinor)
            events += BreakoutEvent(
                barIndex = index,
                instrumentId = instrumentId,
                signal = priced.action,
                signalReason = priced.reason,
                riskReason = authorization.reason,
                fillMinor = execution?.takeIf { it.basis != NEXT_OPEN }?.fillMinor,
                fillBasis = execution?.basis,
                signalCloseMinor = priced.closeMinor,
                stopGuaranteed = execution?.stopGuaranteed ?: false,
            )
            if (authorization.approved && priced.reason == STOP_INTRABAR) {
                cash += held?.sizeMinor ?: 0
                position = null
                pending = null
            } else if (authorization.approved && (priced.action == ENTER || priced.reason == CHANNEL_LOW)) {
                pending = priced
            }
        }
        return events
    }

    fun stopPrice(entryFillMinor: Long, ratio: BigDecimal): Long {
        val kept = BigDecimal.ONE.subtract(ratio)
        return BigDecimal.valueOf(entryFillMinor).multiply(kept).setScale(0, RoundingMode.FLOOR).longValueExact()
    }
}
