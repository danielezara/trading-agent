package com.tradingagent.modules.strategy

import com.tradingagent.modules.configuration.EconomicParameters
import com.tradingagent.modules.configuration.TechnicalParameters
import java.time.Instant

data class InstrumentBars(
    val instrumentId: String,
    val assetClass: String,
    val bars: List<PriceBar>,
)

data class BarBook(
    val openPositions: Int,
    val instruments: List<InstrumentBars>,
    val reportedLossMinor: Long? = null,
    val reportedLossCurrency: String? = null,
    val payloadRates: List<PayloadRate> = emptyList(),
    val entriesBlocked: Boolean = false,
)

object ClosedBarEvaluation {
    fun decide(
        now: Instant,
        book: BarBook,
        economic: EconomicParameters,
        technical: TechnicalParameters,
    ): RuleDecision {
        val instruments = book.instruments.sortedBy { it.instrumentId }
        if (instruments.isEmpty()) {
            return ClosedBarPolicy.decide(
                input(now, null, "unknown", emptyList(), book, economic, technical),
                book.entriesBlocked,
            )
        }
        val pairs = instruments.map { instrument ->
            instrument to ClosedBarPolicy.decide(
                input(now, instrument.instrumentId, instrument.assetClass, instrument.bars, book, economic, technical),
                book.entriesBlocked,
            )
        }
        val chosen = pairs.firstOrNull { it.second.action == "ENTER" }
            ?: pairs.firstOrNull { it.second.action != "NO-TRADE" }
            ?: pairs.firstOrNull { it.first.assetClass == "stock" || it.first.assetClass == "etf" }
            ?: pairs.first()
        val decision = chosen.second
        return if (decision.instrumentId != null) decision else decision.copy(instrumentId = chosen.first.instrumentId)
    }

    private fun input(
        now: Instant,
        instrumentId: String?,
        assetClass: String,
        bars: List<PriceBar>,
        book: BarBook,
        economic: EconomicParameters,
        technical: TechnicalParameters,
    ) = ClosedBarInput(
        now = now,
        instrumentId = instrumentId,
        priceSource = ClosedBarPolicy.PRICE_SOURCE,
        bars = bars,
        position = null,
        assetClass = assetClass,
        leverage = economic.allowlistLeverage,
        investedMinor = 0,
        sectorInvestedMinor = null,
        lossMinor = BudgetLoss.entryLossMinor(
            book.reportedLossMinor,
            book.reportedLossCurrency,
            economic,
            book.payloadRates,
        ),
        openPositions = book.openPositions,
        lastExitAt = null,
        economic = economic,
        technical = technical,
    )
}
