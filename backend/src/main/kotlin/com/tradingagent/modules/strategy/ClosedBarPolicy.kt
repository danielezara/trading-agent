package com.tradingagent.modules.strategy

import com.tradingagent.modules.configuration.EconomicParameters
import com.tradingagent.modules.configuration.TechnicalParameters
import java.time.Instant
import kotlin.math.min

data class PriceBar(
    val start: Instant,
    val end: Instant,
    val closeMinor: Long,
    val highMinor: Long,
    val lowMinor: Long,
)

data class HeldPosition(
    val entryCloseMinor: Long,
    val barsHeld: Int,
)

data class ClosedBarInput(
    val now: Instant,
    val instrumentId: String?,
    val priceSource: String,
    val bars: List<PriceBar>,
    val position: HeldPosition?,
    val assetClass: String,
    val leverage: Int,
    val investedMinor: Long,
    val sectorInvestedMinor: Long?,
    val lossMinor: Long,
    val openPositions: Int,
    val lastExitAt: Instant?,
    val economic: EconomicParameters,
    val technical: TechnicalParameters,
)

data class RuleDecision(
    val action: String,
    val instrumentId: String?,
    val direction: String,
    val notionalMinor: Long?,
    val orderable: Boolean,
    val reason: String,
)

data class EntryCandidate(
    val priceSource: String,
    val barClosed: Boolean,
    val assetClass: String,
    val direction: String,
    val leverage: Int,
    val notionalMinor: Long,
    val investedMinor: Long,
    val sectorInvestedMinor: Long?,
    val lossMinor: Long,
    val openPositions: Int,
    val economic: EconomicParameters,
)

object MandateControl {
    const val OK: String = "OK"
    const val PRICE_SOURCE: String = "PRICE_SOURCE"
    const val BAR_OPEN: String = "BAR_OPEN"
    const val BAR_SIZE: String = "BAR_SIZE"
    const val BAR_INVALID: String = "BAR_INVALID"
    const val DIRECTION: String = "DIRECTION"
    const val LEVERAGE: String = "LEVERAGE"
    const val UNIVERSE: String = "UNIVERSE"
    const val HORIZON: String = "HORIZON"
    const val BENCHMARK: String = "BENCHMARK"
    const val LOSS_LIMIT: String = "LOSS_LIMIT"
    const val BUDGET_LIMIT: String = "BUDGET_LIMIT"
    const val EXPOSURE_LIMIT: String = "EXPOSURE_LIMIT"
    const val SIZE: String = "SIZE"
    const val SECTOR_UNKNOWN: String = "SECTOR_UNKNOWN"
    const val EXISTING_POSITION: String = "EXISTING_POSITION"
    const val COOLDOWN: String = "COOLDOWN"
    const val WARMUP: String = "WARMUP"
    const val SCORE: String = "SCORE"
    const val HOLDING: String = "HOLDING"
    const val STOP: String = "STOP"

    fun configReason(economic: EconomicParameters): String? = when {
        economic.horizon != "days" -> HORIZON
        economic.benchmarkName != "MSCI World" || economic.benchmarkReturnBasis != "total_return" -> BENCHMARK
        economic.allowlistUniverse != "large_cap_stocks_and_etfs" -> UNIVERSE
        economic.allowlistDirection != "long_only" -> DIRECTION
        economic.allowlistLeverage != 1 -> LEVERAGE
        economic.maxLossAction != "block" || economic.maxLossBps != 1_000 -> LOSS_LIMIT
        economic.baseCurrency != "EUR" || economic.assignedBudgetMinor != 50_000L -> BUDGET_LIMIT
        economic.riskPerTradeBps != 50 -> SIZE
        economic.aggregateRiskBps != 200 -> SIZE
        economic.instrumentExposureBps != 1_500 -> EXPOSURE_LIMIT
        economic.sectorExposureBps != 3_000 -> EXPOSURE_LIMIT
        economic.minCashBps != 2_000 -> BUDGET_LIMIT
        economic.cashBufferMinor != 10_000L -> BUDGET_LIMIT
        economic.maxPositions != 8 -> EXPOSURE_LIMIT
        economic.cooldownSeconds != 300 -> COOLDOWN
        economic.maxHoldingCount != 390 -> HOLDING
        else -> null
    }

    fun reviewEntry(candidate: EntryCandidate): String {
        configReason(candidate.economic)?.let { return it }
        if (candidate.priceSource != ClosedBarPolicy.PRICE_SOURCE) {
            return PRICE_SOURCE
        }
        if (!candidate.barClosed) {
            return BAR_OPEN
        }
        if (candidate.assetClass != "stock" && candidate.assetClass != "etf") {
            return UNIVERSE
        }
        if (candidate.direction != "LONG") {
            return DIRECTION
        }
        if (candidate.leverage != 1) {
            return LEVERAGE
        }
        val budget = candidate.economic.assignedBudgetMinor ?: return SIZE
        val maxLoss = candidate.economic.maxLossMinor() ?: return LOSS_LIMIT
        if (candidate.lossMinor >= maxLoss) {
            return LOSS_LIMIT
        }
        if (candidate.notionalMinor < MIN_ORDER_MINOR) {
            return SIZE
        }
        if (candidate.notionalMinor > budget || candidate.investedMinor < 0) {
            return if (candidate.investedMinor < 0) SIZE else BUDGET_LIMIT
        }
        val instrumentCap = budget * candidate.economic.instrumentExposureBps!! / 10_000L
        if (candidate.notionalMinor > instrumentCap) {
            return EXPOSURE_LIMIT
        }
        val minCash = budget * candidate.economic.minCashBps!! / 10_000L
        val room = budget - minCash - candidate.investedMinor
        if (room < candidate.notionalMinor) {
            return BUDGET_LIMIT
        }
        val maxPositions = candidate.economic.maxPositions ?: return SIZE
        if (candidate.openPositions >= maxPositions) {
            return EXPOSURE_LIMIT
        }
        val riskMoney = budget * candidate.economic.riskPerTradeBps!! / 10_000L
        val aggregate = budget * candidate.economic.aggregateRiskBps!! / 10_000L
        val usedRisk = candidate.openPositions.toLong() * riskMoney
        if (usedRisk + riskMoney > aggregate) {
            return EXPOSURE_LIMIT
        }
        val sectorInvested = candidate.sectorInvestedMinor ?: return SECTOR_UNKNOWN
        if (sectorInvested < 0) {
            return SIZE
        }
        val sectorCap = budget * candidate.economic.sectorExposureBps!! / 10_000L
        if (sectorInvested + candidate.notionalMinor > sectorCap) {
            return EXPOSURE_LIMIT
        }
        val buffer = candidate.economic.cashBufferMinor ?: return SIZE
        if (candidate.investedMinor + candidate.notionalMinor + buffer > budget) {
            return BUDGET_LIMIT
        }
        return OK
    }

    private const val MIN_ORDER_MINOR: Long = 100L
}

object ClosedBarPolicy {
    const val RULE_ID: String = "closed-bar-trend/1"
    const val PRICE_SOURCE: String = "etoro"
    const val KILL_SWITCH: String = "KILL_SWITCH"

    fun decide(input: ClosedBarInput, entriesBlocked: Boolean = false): RuleDecision {
        if (entriesBlocked) {
            return if (input.position != null) hold(input, KILL_SWITCH) else none(KILL_SWITCH)
        }
        MandateControl.configReason(input.economic)?.let { return none(it) }
        technicalReason(input.technical)?.let { return none(it) }
        if (input.priceSource != PRICE_SOURCE) {
            return none(MandateControl.PRICE_SOURCE)
        }
        if (input.leverage != 1) {
            return none(MandateControl.LEVERAGE)
        }
        val barSeconds = input.technical.decisionBarSeconds
        val closed = mutableListOf<PriceBar>()
        var sawOpen = false
        for (bar in input.bars) {
            if (bar.end.isAfter(input.now)) {
                sawOpen = true
                continue
            }
            if (!validBar(bar, barSeconds)) {
                return none(if (bar.end.epochSecond - bar.start.epochSecond != barSeconds) MandateControl.BAR_SIZE else MandateControl.BAR_INVALID)
            }
            closed += bar
        }
        closed.sortBy { it.end }
        if (closed.zipWithNext().any { (left, right) -> !left.end.isBefore(right.end) }) {
            return none(MandateControl.BAR_INVALID)
        }
        if (closed.isEmpty()) {
            return none(if (sawOpen) MandateControl.BAR_OPEN else MandateControl.WARMUP)
        }
        val confirmation = input.technical.int("confirmation_bars")
        if (closed.size < confirmation + 1) {
            return none(MandateControl.WARMUP)
        }
        val position = input.position
        if (position != null && input.assetClass != "stock" && input.assetClass != "etf") {
            return hold(input, MandateControl.UNIVERSE)
        }
        val maxLoss = input.economic.maxLossMinor()
        if (position != null && maxLoss != null && input.lossMinor >= maxLoss) {
            return hold(input, MandateControl.LOSS_LIMIT)
        }
        if (position == null) {
            val cap = input.economic.maxPositions
            if (cap != null && input.openPositions >= cap) {
                return none(MandateControl.EXPOSURE_LIMIT)
            }
        }
        val atrBars = input.technical.int("regime_atr_bars")
        val atr = averageRange(closed, atrBars)
        val lastClose = closed.last().closeMinor
        if (position != null) {
            val holding = input.economic.maxHoldingCount ?: return none(MandateControl.HOLDING)
            if (position.barsHeld >= holding) {
                return exit(input, MandateControl.HOLDING)
            }
            val stopMultiple = input.technical.int("stop_atr_multiple_millis")
            if (atr != null && atr > 0) {
                val stopDistance = atr * stopMultiple / 1_000L
                if (stopDistance > 0 && lastClose <= position.entryCloseMinor - stopDistance) {
                    return exit(input, MandateControl.STOP)
                }
            }
            val score = trendScore(closed, confirmation)
            val exitScore = input.technical.int("exit_score_millis")
            if (score < exitScore) {
                return exit(input, MandateControl.SCORE)
            }
            return hold(input, MandateControl.SCORE)
        }
        val score = trendScore(closed, confirmation)
        val buyScore = input.technical.int("buy_score_millis")
        if (score < buyScore) {
            return none(MandateControl.SCORE)
        }
        if (input.assetClass != "stock" && input.assetClass != "etf") {
            return none(MandateControl.UNIVERSE)
        }
        if (maxLoss != null && input.lossMinor >= maxLoss) {
            return none(MandateControl.LOSS_LIMIT)
        }
        val cooldown = input.economic.cooldownSeconds ?: return none(MandateControl.COOLDOWN)
        val lastExit = input.lastExitAt
        if (lastExit != null && input.now.isBefore(lastExit.plusSeconds(cooldown.toLong()))) {
            return none(MandateControl.COOLDOWN)
        }
        val budget = input.economic.assignedBudgetMinor ?: return none(MandateControl.SIZE)
        val minCashBps = input.economic.minCashBps ?: return none(MandateControl.SIZE)
        val room = budget - budget * minCashBps / 10_000L - input.investedMinor
        if (room <= 0) {
            return none(MandateControl.BUDGET_LIMIT)
        }
        if (atr == null || atr <= 0) {
            return none(MandateControl.WARMUP)
        }
        val notional = sizedNotional(lastClose, atr, input) ?: return none(MandateControl.SIZE)
        val review = MandateControl.reviewEntry(
            EntryCandidate(
                priceSource = input.priceSource,
                barClosed = true,
                assetClass = input.assetClass,
                direction = "LONG",
                leverage = input.leverage,
                notionalMinor = notional,
                investedMinor = input.investedMinor,
                sectorInvestedMinor = input.sectorInvestedMinor,
                lossMinor = input.lossMinor,
                openPositions = input.openPositions,
                economic = input.economic,
            ),
        )
        if (review != MandateControl.OK) {
            return none(review)
        }
        return RuleDecision(
            action = "ENTER",
            instrumentId = input.instrumentId,
            direction = "LONG",
            notionalMinor = notional,
            orderable = true,
            reason = MandateControl.OK,
        )
    }

    private fun technicalReason(technical: TechnicalParameters): String? = when {
        technical.decisionBarSeconds != 300L -> MandateControl.BAR_SIZE
        technical.int("price_poll_seconds") != 60 -> MandateControl.PRICE_SOURCE
        technical.int("confirmation_bars") != 2 -> MandateControl.SCORE
        technical.int("buy_score_millis") != 650 -> MandateControl.SCORE
        technical.int("exit_score_millis") != 350 -> MandateControl.SCORE
        technical.int("stop_atr_multiple_millis") != 2_000 -> MandateControl.STOP
        technical.int("regime_atr_bars") != 20 -> MandateControl.WARMUP
        else -> null
    }

    private fun sizedNotional(closeMinor: Long, atrMinor: Long, input: ClosedBarInput): Long? {
        val budget = input.economic.assignedBudgetMinor ?: return null
        val riskBps = input.economic.riskPerTradeBps ?: return null
        val exposureBps = input.economic.instrumentExposureBps ?: return null
        val minCashBps = input.economic.minCashBps ?: return null
        if (closeMinor <= 0 || atrMinor <= 0) {
            return null
        }
        val stop = atrMinor * input.technical.int("stop_atr_multiple_millis") / 1_000L
        if (stop <= 0) {
            return null
        }
        val riskMoney = budget * riskBps / 10_000L
        if (riskMoney <= 0) {
            return null
        }
        val raw = try {
            Math.multiplyExact(riskMoney, closeMinor) / stop
        } catch (_: ArithmeticException) {
            return null
        }
        val instrumentCap = budget * exposureBps / 10_000L
        val minCash = budget * minCashBps / 10_000L
        val room = budget - minCash - input.investedMinor
        if (room <= 0) {
            return null
        }
        val notional = min(min(raw, instrumentCap), min(room, budget))
        if (notional < 100L) {
            return null
        }
        return notional
    }

    private fun validBar(bar: PriceBar, barSeconds: Long): Boolean {
        if (bar.end.epochSecond - bar.start.epochSecond != barSeconds) {
            return false
        }
        if (bar.lowMinor < 0 || bar.closeMinor < bar.lowMinor || bar.highMinor < bar.closeMinor) {
            return false
        }
        return true
    }

    private fun averageRange(closed: List<PriceBar>, atrBars: Int): Long? {
        if (closed.size < atrBars || atrBars <= 0) {
            return null
        }
        var sum = 0L
        for (bar in closed.takeLast(atrBars)) {
            val range = bar.highMinor - bar.lowMinor
            if (range < 0) {
                return null
            }
            sum = try {
                Math.addExact(sum, range)
            } catch (_: ArithmeticException) {
                return null
            }
        }
        val average = sum / atrBars
        return average.takeIf { it > 0 }
    }

    private fun trendScore(closed: List<PriceBar>, confirmation: Int): Int {
        val steps = (0 until confirmation).map { offset ->
            val newer = closed[closed.size - confirmation + offset].closeMinor
            val older = closed[closed.size - confirmation + offset - 1].closeMinor
            newer.compareTo(older)
        }
        return when {
            steps.all { it > 0 } -> 1_000
            steps.all { it < 0 } -> 0
            else -> 500
        }
    }

    private fun none(reason: String) = RuleDecision(
        action = "NO-TRADE",
        instrumentId = null,
        direction = "NONE",
        notionalMinor = null,
        orderable = false,
        reason = reason,
    )

    private fun hold(input: ClosedBarInput, reason: String) = RuleDecision(
        action = "HOLD",
        instrumentId = input.instrumentId,
        direction = "LONG",
        notionalMinor = null,
        orderable = false,
        reason = reason,
    )

    private fun exit(input: ClosedBarInput, reason: String) = RuleDecision(
        action = "EXIT",
        instrumentId = input.instrumentId,
        direction = "LONG",
        notionalMinor = null,
        orderable = false,
        reason = reason,
    )
}
