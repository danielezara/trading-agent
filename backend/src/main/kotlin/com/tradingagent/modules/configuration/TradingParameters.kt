package com.tradingagent.modules.configuration

import org.yaml.snakeyaml.Yaml
import java.security.MessageDigest

data class EconomicParameters(
    val baseCurrency: String?,
    val currencyScale: Int,
    val assignedBudgetAmount: String?,
    val assignedBudgetMinor: Long?,
    val maxLossBps: Int?,
    val maxLossAction: String?,
    val horizon: String?,
    val allowlistUniverse: String,
    val allowlistDirection: String,
    val allowlistLeverage: Int,
    val allowlistInstrumentIds: List<String>,
    val entrySymbols: List<String> = emptyList(),
    val benchmarkName: String,
    val benchmarkReturnBasis: String,
    val cashBufferMinor: Long?,
    val maxPositions: Int?,
    val maxDrawdownBps: Int?,
    val riskPerTradeBps: Int?,
    val aggregateRiskBps: Int?,
    val instrumentExposureBps: Int?,
    val sectorExposureBps: Int?,
    val minCashBps: Int?,
    val fxHaircutBps: Int?,
    val cooldownSeconds: Int?,
    val maxHoldingCount: Int?,
) {
    fun maxLossMinor(): Long? {
        val budget = assignedBudgetMinor ?: return null
        val bps = maxLossBps ?: return null
        return Math.multiplyExact(budget, bps.toLong()) / 10_000L
    }

    fun canonical(): String = listOf(
        "base_currency=${baseCurrency ?: ""}",
        "currency_scale=$currencyScale",
        "assigned_budget_amount=${assignedBudgetAmount ?: ""}",
        "assigned_budget_minor=${assignedBudgetMinor ?: ""}",
        "max_loss_bps=${maxLossBps ?: ""}",
        "max_loss_action=${maxLossAction ?: ""}",
        "horizon=${horizon ?: ""}",
        "allowlist_universe=$allowlistUniverse",
        "allowlist_direction=$allowlistDirection",
        "allowlist_leverage=$allowlistLeverage",
        "allowlist_instrument_ids=${allowlistInstrumentIds.joinToString(",")}",
        "entry_symbols=${entrySymbols.joinToString(",")}",
        "benchmark_name=$benchmarkName",
        "benchmark_return_basis=$benchmarkReturnBasis",
        "cash_buffer_minor=${cashBufferMinor ?: ""}",
        "max_positions=${maxPositions ?: ""}",
        "max_drawdown_bps=${maxDrawdownBps ?: ""}",
        "risk_per_trade_bps=${riskPerTradeBps ?: ""}",
        "aggregate_risk_bps=${aggregateRiskBps ?: ""}",
        "instrument_exposure_bps=${instrumentExposureBps ?: ""}",
        "sector_exposure_bps=${sectorExposureBps ?: ""}",
        "min_cash_bps=${minCashBps ?: ""}",
        "fx_haircut_bps=${fxHaircutBps ?: ""}",
        "cooldown_seconds=${cooldownSeconds ?: ""}",
        "max_holding_count=${maxHoldingCount ?: ""}",
    ).joinToString("\n")
}

data class TechnicalParameters(
    val calibratable: Boolean,
    val tieBreak: String,
    val values: Map<String, Int>,
) {
    fun int(key: String): Int = values.getValue(key)

    val decisionBarSeconds: Long get() = int("decision_bar_seconds").toLong()
    val qualityThresholdMillis: Int get() = int("quality_threshold_millis")
    val qualitySchemaMillis: Int get() = int("quality_schema_millis")
    val qualityProvenanceMillis: Int get() = int("quality_provenance_millis")
    val qualityFreshnessMillis: Int get() = int("quality_freshness_millis")
    val pricesStaleSeconds: Long get() = int("prices_stale_seconds").toLong()
    val fxReferenceMaxAgeDays: Int get() = int("fx_reference_max_age_days")
    val newsStaleSeconds: Long get() = int("news_stale_seconds").toLong()
    val slowDataStaleSeconds: Long get() = int("slow_data_stale_seconds").toLong()
    val priceConflictBps: Int get() = int("price_conflict_bps")
    val priceConflictAlignSeconds: Long get() = int("price_conflict_align_seconds").toLong()

    fun canonical(): String = buildString {
        append("calibratable=$calibratable\n")
        append("tie_break=$tieBreak\n")
        values.toSortedMap().forEach { (key, value) -> append("$key=$value\n") }
    }
}

data class TradingParameters(
    val schema: String,
    val author: String,
    val reason: String,
    val sourcePath: String,
    val sourceSha256: String,
    val economic: EconomicParameters,
    val technical: TechnicalParameters,
    val spotSelection: SpotSelection,
) {
    fun toBudgetPolicy(): BudgetPolicy = BudgetPolicy(
        baseCurrency = economic.baseCurrency,
        assignedBudgetMinor = economic.assignedBudgetMinor,
        cashBufferMinor = economic.cashBufferMinor,
        maxPositions = economic.maxPositions,
    )

    fun withBudget(budget: BudgetPolicy): TradingParameters {
        val amount = if (budget.assignedBudgetMinor == economic.assignedBudgetMinor) economic.assignedBudgetAmount else null
        return copy(
            economic = economic.copy(
                baseCurrency = budget.baseCurrency,
                assignedBudgetAmount = amount,
                assignedBudgetMinor = budget.assignedBudgetMinor,
                cashBufferMinor = budget.cashBufferMinor,
                maxPositions = budget.maxPositions,
            ),
        )
    }

    fun canonical(): String = listOf(
        "schema=$schema",
        "author=$author",
        "reason=$reason",
        "source_path=$sourcePath",
        "source_sha256=$sourceSha256",
        economic.canonical(),
        technical.canonical(),
        spotSelection.canonical(),
    ).joinToString("\n")

    companion object {
        const val SOURCE_PATH: String = "config/observation-v1.yaml"

        fun current(): TradingParameters = Holder.value

        fun load(path: String = SOURCE_PATH): TradingParameters = TradingParametersLoader.load(path)
    }
}

private object Holder {
    val value: TradingParameters = TradingParametersLoader.load(TradingParameters.SOURCE_PATH)
}

private object TradingParametersLoader {
    private val topKeys = setOf("schema", "author", "reason", "economic", "technical", "spot_selection")
    private val economicKeys = setOf(
        "base_currency",
        "currency_scale",
        "assigned_budget_amount",
        "assigned_budget_minor",
        "max_loss_bps",
        "max_loss_action",
        "horizon",
        "allowlist",
        "benchmark",
        "cash_buffer_minor",
        "max_positions",
        "max_drawdown_bps",
        "risk_per_trade_bps",
        "aggregate_risk_bps",
        "instrument_exposure_bps",
        "sector_exposure_bps",
        "min_cash_bps",
        "fx_haircut_bps",
        "cooldown_seconds",
        "max_holding_count",
    )
    private val allowlistKeys = setOf("universe", "direction", "leverage", "instrument_ids", "entry_symbols")
    private val spotKeys = setOf(
        "exchange",
        "quote_currency",
        "timeframe",
        "bar_seconds",
        "history_start",
        "history_end",
        "min_volume",
        "volume_unit",
        "volume_window",
        "max_spread",
        "spread_unit",
        "spread_model",
        "spread_model_value",
        "quote_max_age_seconds",
        "assets",
    )
    private val entrySymbol = Regex("[A-Z0-9.]{1,16}")
    private val benchmarkKeys = setOf("name", "return_basis")
    private val technicalIntKeys = listOf(
        "price_poll_seconds",
        "decision_bar_seconds",
        "order_poll_min_seconds",
        "order_poll_max_seconds",
        "news_poll_seconds",
        "macro_check_seconds",
        "slow_data_refresh_seconds",
        "prices_stale_seconds",
        "orders_stale_seconds",
        "news_stale_seconds",
        "slow_data_stale_seconds",
        "price_conflict_bps",
        "price_conflict_spread_multiple",
        "price_conflict_align_seconds",
        "volume_conflict_bps",
        "quality_schema_millis",
        "quality_provenance_millis",
        "quality_freshness_millis",
        "quality_threshold_millis",
        "probabilistic_schema_millis",
        "probabilistic_provenance_millis",
        "probabilistic_freshness_millis",
        "probabilistic_calibration_millis",
        "event_quality_threshold_millis",
        "weight_quant_millis",
        "weight_macro_millis",
        "weight_news_millis",
        "weight_fundamental_millis",
        "weight_liquidity_millis",
        "weight_trend_millis",
        "weight_momentum_millis",
        "weight_reversion_millis",
        "buy_score_millis",
        "buy_confidence_millis",
        "exit_score_millis",
        "confirmation_bars",
        "stability_scale_millis",
        "confidence_agreement_floor_millis",
        "confidence_agreement_slope_millis",
        "stop_atr_multiple_millis",
        "chase_bps",
        "event_blackout_minutes",
        "event_key_floor_minutes",
        "edge_shrinkage_millis",
        "edge_min_observations",
        "edge_min_bps",
        "edge_to_stop_millis",
        "cost_share_of_gross_edge_millis",
        "edge_buffer_bps",
        "stressed_cost_multiple_millis",
        "slippage_spread_divisor",
        "slippage_percentile",
        "slippage_atr_coefficient_millis",
        "regime_ema_fast_bars",
        "regime_ema_slow_bars",
        "regime_ema_bars",
        "regime_vol_bars",
        "regime_atr_bars",
        "regime_vol_percentile_favorable",
        "regime_vol_percentile_stress",
        "regime_lateral_atr_ratio_millis",
        "regime_size_trend_millis",
        "regime_size_lateral_millis",
        "regime_size_high_vol_millis",
        "regime_buy_score_lateral_millis",
        "regime_buy_score_high_vol_millis",
        "regime_stress_benchmark_drawdown_bps",
        "ece_bins",
        "ece_max_observations",
        "ece_min_observations",
        "ece_min_per_family",
        "ece_haircut_millis",
        "ece_divisor_millis",
        "winsor_low_percentile",
        "winsor_high_percentile",
        "correlation_cluster_millis",
        "ewma_half_life_bars",
        "covariance_weight_millis",
        "covariance_shrinkage_millis",
        "covariance_lookback_bars",
        "utility_floor_bps",
        "rebalance_band_of_target_millis",
        "rebalance_band_floor_bps",
        "utility_buffer_bps",
        "sizing_confidence_mid_millis",
        "sizing_confidence_high_millis",
        "sizing_confidence_full_millis",
        "llm_temperature_millis",
        "decision_deadline_seconds",
        "reconciliation_age_seconds",
        "alert_ack_seconds",
        "fx_reference_max_age_days",
    )

    fun load(path: String): TradingParameters {
        val text = read(path)
        require(!FORBIDDEN.containsMatchIn(text)) { "parameter file must not contain credentials" }
        require(!text.contains("http://") && !text.contains("https://")) { "parameter file must not contain a URL" }
        val root = mapping(Yaml().load(text), "parameters")
        requireExact(root, topKeys, "parameters")
        val economicNode = mapping(root.getValue("economic"), "economic")
        requireExact(economicNode, economicKeys, "economic")
        val technicalNode = mapping(root.getValue("technical"), "technical")
        requireExact(technicalNode, technicalIntKeys.toSet() + setOf("calibratable", "tie_break"), "technical")
        val economic = economic(economicNode)
        val technical = technical(technicalNode)
        val spotSelection = spotSelection(mapping(root.getValue("spot_selection"), "spot_selection"))
        require(root.getValue("schema") == "trading-parameters/1") { "unsupported parameter schema" }
        val author = requireText(root.getValue("author"), "author")
        val reason = requireText(root.getValue("reason"), "reason")
        return TradingParameters(
            schema = "trading-parameters/1",
            author = author,
            reason = reason,
            sourcePath = path,
            sourceSha256 = sha256(text),
            economic = economic,
            technical = technical,
            spotSelection = spotSelection,
        )
    }

    private fun spotSelection(node: Map<String, Any?>): SpotSelection {
        requireExact(node, spotKeys, "spot_selection")
        val timeframe = requireText(node.getValue("timeframe"), "timeframe")
        val barSeconds = requireInt(node.getValue("bar_seconds"), "bar_seconds")
        require(barSeconds > 0) { "bar_seconds must be positive" }
        require(timeframe == "FiveMinutes" && barSeconds == 300) {
            "timeframe and bar_seconds must name the same five minute bar"
        }
        val historyStart = instant(requireText(node.getValue("history_start"), "history_start"), "history_start")
        val historyEnd = instant(requireText(node.getValue("history_end"), "history_end"), "history_end")
        require(historyStart.isBefore(historyEnd)) { "history interval is empty" }
        val span = historyEnd.epochSecond - historyStart.epochSecond
        require(historyStart.epochSecond % barSeconds == 0L && span % barSeconds == 0L) {
            "history interval must align to bar_seconds"
        }
        val model = requireText(node.getValue("spread_model"), "spread_model")
        require(model == "none" || model == "fixed_ratio") { "spread_model must be none or fixed_ratio" }
        val modelValue = optionalText(node["spread_model_value"], "spread_model_value")
        require(model == "fixed_ratio" == (modelValue != null)) {
            "fixed_ratio needs spread_model_value and none leaves it empty"
        }
        val assets = stringList(node.getValue("assets"), "assets")
        require(assets == SpotSelection.FIXED_ASSETS) { "spot whitelist is fixed and has no substitutes" }
        return SpotSelection(
            exchange = requireText(node.getValue("exchange"), "exchange"),
            quoteCurrency = currency(requireText(node.getValue("quote_currency"), "quote_currency")),
            timeframe = timeframe,
            barSeconds = barSeconds.toLong(),
            historyStart = historyStart,
            historyEnd = historyEnd,
            minVolume = decimal(requireText(node.getValue("min_volume"), "min_volume"), "min_volume"),
            volumeUnit = token(node, "volume_unit", "candle_volume"),
            volumeWindow = token(node, "volume_window", "one_closed_bar"),
            maxSpread = decimal(requireText(node.getValue("max_spread"), "max_spread"), "max_spread"),
            spreadUnit = token(node, "spread_unit", "bid_ask_mid_ratio"),
            spreadModel = model,
            spreadModelValue = modelValue?.let { decimal(it, "spread_model_value") },
            quoteMaxAgeSeconds = requireInt(node.getValue("quote_max_age_seconds"), "quote_max_age_seconds").toLong(),
            assets = assets,
        )
    }

    private fun token(node: Map<String, Any?>, key: String, expected: String): String {
        val value = requireText(node.getValue(key), key)
        require(value == expected) { "$key must be $expected" }
        return value
    }

    private fun currency(value: String): String {
        require(value.length == 3 && value.all { it.isLetter() }) { "quote_currency must be a 3-letter ISO code" }
        return value
    }

    private fun instant(value: String, key: String): java.time.Instant = try {
        java.time.Instant.parse(value)
    } catch (_: Exception) {
        throw IllegalArgumentException("$key must be an instant")
    }

    private fun decimal(value: String, key: String): java.math.BigDecimal {
        require(Regex("[0-9]+(\\.[0-9]+)?").matches(value)) { "$key must be a decimal" }
        return java.math.BigDecimal(value)
    }

    private fun economic(node: Map<String, Any?>): EconomicParameters {
        val scale = requireInt(node.getValue("currency_scale"), "currency_scale")
        require(scale in 0..4) { "currency_scale is out of range" }
        val currency = optionalText(node["base_currency"], "base_currency")
        if (currency != null) {
            require(currency.length == 3 && currency.all { it.isLetter() }) { "base_currency must be a 3-letter ISO code" }
        }
        val amount = optionalText(node["assigned_budget_amount"], "assigned_budget_amount")
        val minor = optionalLong(node["assigned_budget_minor"], "assigned_budget_minor")
        require((amount == null) == (minor == null)) { "budget amount and minor units must be set together" }
        if (amount != null && minor != null) {
            require(currency != null) { "base currency is required when the budget is set" }
            require(minorUnits(amount, scale) == minor) { "assigned_budget_amount does not match assigned_budget_minor" }
            require(minor >= 0) { "assigned budget must be >= 0" }
        }
        val maxLossBps = optionalInt(node["max_loss_bps"], "max_loss_bps")
        val maxLossAction = optionalText(node["max_loss_action"], "max_loss_action")
        if (maxLossBps != null) {
            require(maxLossBps in 0..10_000) { "max_loss_bps is out of range" }
            require(maxLossAction == "block") { "max loss breach can only block" }
            if (minor != null) {
                require(Math.multiplyExact(minor, maxLossBps.toLong()) % 10_000L == 0L) {
                    "max loss does not land on a minor unit"
                }
            }
        }
        val allowlist = mapping(node.getValue("allowlist"), "allowlist")
        requireExact(allowlist, allowlistKeys, "allowlist")
        val benchmark = mapping(node.getValue("benchmark"), "benchmark")
        requireExact(benchmark, benchmarkKeys, "benchmark")
        val leverage = requireInt(allowlist.getValue("leverage"), "leverage")
        require(leverage == 1) { "leverage must be 1" }
        val direction = requireText(allowlist.getValue("direction"), "direction")
        require(direction == "long_only") { "direction must be long_only" }
        return EconomicParameters(
            baseCurrency = currency,
            currencyScale = scale,
            assignedBudgetAmount = amount,
            assignedBudgetMinor = minor,
            maxLossBps = maxLossBps,
            maxLossAction = maxLossAction,
            horizon = optionalText(node["horizon"], "horizon"),
            allowlistUniverse = requireText(allowlist.getValue("universe"), "universe"),
            allowlistDirection = direction,
            allowlistLeverage = leverage,
            allowlistInstrumentIds = stringList(allowlist.getValue("instrument_ids"), "instrument_ids"),
            entrySymbols = entrySymbols(allowlist.getValue("entry_symbols")),
            benchmarkName = requireText(benchmark.getValue("name"), "benchmark.name"),
            benchmarkReturnBasis = requireText(benchmark.getValue("return_basis"), "benchmark.return_basis"),
            cashBufferMinor = optionalLong(node["cash_buffer_minor"], "cash_buffer_minor"),
            maxPositions = optionalInt(node["max_positions"], "max_positions"),
            maxDrawdownBps = optionalInt(node["max_drawdown_bps"], "max_drawdown_bps"),
            riskPerTradeBps = optionalInt(node["risk_per_trade_bps"], "risk_per_trade_bps"),
            aggregateRiskBps = optionalInt(node["aggregate_risk_bps"], "aggregate_risk_bps"),
            instrumentExposureBps = optionalInt(node["instrument_exposure_bps"], "instrument_exposure_bps"),
            sectorExposureBps = optionalInt(node["sector_exposure_bps"], "sector_exposure_bps"),
            minCashBps = optionalInt(node["min_cash_bps"], "min_cash_bps"),
            fxHaircutBps = optionalInt(node["fx_haircut_bps"], "fx_haircut_bps"),
            cooldownSeconds = optionalInt(node["cooldown_seconds"], "cooldown_seconds"),
            maxHoldingCount = optionalInt(node["max_holding_count"], "max_holding_count"),
        )
    }

    private fun technical(node: Map<String, Any?>): TechnicalParameters {
        val calibratable = node.getValue("calibratable")
        require(calibratable is Boolean && calibratable) { "technical thresholds must stay calibratable" }
        val values = technicalIntKeys.associateWith { key -> requireInt(node.getValue(key), key) }
        require(values.getValue("decision_bar_seconds") > 0) { "decision_bar_seconds must be positive" }
        require(values.getValue("order_poll_min_seconds") <= values.getValue("order_poll_max_seconds")) {
            "order poll range is inverted"
        }
        require(values.getValue("quality_schema_millis") + values.getValue("quality_provenance_millis") + values.getValue("quality_freshness_millis") == 1_000) {
            "deterministic quality weights must sum to 1000"
        }
        require(
            values.getValue("probabilistic_schema_millis") +
                values.getValue("probabilistic_provenance_millis") +
                values.getValue("probabilistic_freshness_millis") +
                values.getValue("probabilistic_calibration_millis") == 1_000,
        ) { "probabilistic quality weights must sum to 1000" }
        require(
            values.getValue("weight_quant_millis") +
                values.getValue("weight_macro_millis") +
                values.getValue("weight_news_millis") +
                values.getValue("weight_fundamental_millis") +
                values.getValue("weight_liquidity_millis") == 1_000,
        ) { "score weights must sum to 1000" }
        require(values.getValue("weight_trend_millis") + values.getValue("weight_momentum_millis") + values.getValue("weight_reversion_millis") == 1_000) {
            "quant weights must sum to 1000"
        }
        require(values.getValue("covariance_weight_millis") + values.getValue("covariance_shrinkage_millis") == 1_000) {
            "covariance weights must sum to 1000"
        }
        require(values.getValue("quality_threshold_millis") in 0..1_000) { "quality threshold is out of range" }
        return TechnicalParameters(
            calibratable = true,
            tieBreak = requireText(node.getValue("tie_break"), "tie_break"),
            values = values,
        )
    }

    private fun read(path: String): String {
        val stream = TradingParameters::class.java.classLoader.getResourceAsStream(path)
            ?: throw IllegalStateException("missing parameter file $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun mapping(value: Any?, label: String): Map<String, Any?> {
        require(value is Map<*, *>) { "$label must be a map" }
        return value.entries.associate { (key, nested) ->
            require(key is String) { "$label has a non-string key" }
            key to nested
        }
    }

    private fun requireExact(node: Map<String, Any?>, expected: Set<String>, label: String) {
        val unknown = node.keys - expected
        val missing = expected - node.keys
        require(unknown.isEmpty()) { "$label has unknown keys: $unknown" }
        require(missing.isEmpty()) { "$label is missing keys: $missing" }
    }

    private fun requireText(value: Any?, key: String): String {
        require(value is String && value.isNotBlank()) { "$key is required" }
        return value
    }

    private fun optionalText(value: Any?, key: String): String? = when (value) {
        null -> null
        is String -> value.takeIf { it.isNotBlank() }
        else -> throw IllegalArgumentException("$key must be text or null")
    }

    private fun requireInt(value: Any?, key: String): Int =
        optionalInt(value, key) ?: throw IllegalArgumentException("$key is required")

    private fun optionalInt(value: Any?, key: String): Int? = when (value) {
        null -> null
        is Number -> {
            val long = value.toLong()
            require(value.toDouble() == long.toDouble()) { "$key must be an integer" }
            require(long in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$key is out of range" }
            long.toInt()
        }
        else -> throw IllegalArgumentException("$key must be an integer or null")
    }

    private fun optionalLong(value: Any?, key: String): Long? = when (value) {
        null -> null
        is Number -> {
            val long = value.toLong()
            require(value.toDouble() == long.toDouble()) { "$key must be an integer" }
            long
        }
        else -> throw IllegalArgumentException("$key must be an integer or null")
    }

    private fun entrySymbols(value: Any?): List<String> {
        val symbols = stringList(value, "entry_symbols")
        require(symbols.size <= 8) { "entry_symbols cannot exceed 8" }
        require(symbols.distinct().size == symbols.size) { "entry_symbols must be unique" }
        symbols.forEach { symbol ->
            require(entrySymbol.matches(symbol)) { "entry_symbols contains an invalid symbol" }
        }
        return symbols
    }

    private fun stringList(value: Any?, key: String): List<String> {
        require(value is List<*>) { "$key must be a list" }
        return value.map { item ->
            require(item is String && item.isNotBlank()) { "$key contains a blank id" }
            item
        }
    }

    private fun minorUnits(amount: String, scale: Int): Long {
        val parts = amount.split('.')
        require(parts.size == 2 && parts[0].isNotEmpty() && parts[1].length == scale) {
            "assigned_budget_amount must use $scale decimal places"
        }
        require(parts[0].all { it.isDigit() } && parts[1].all { it.isDigit() }) {
            "assigned_budget_amount must be a decimal amount"
        }
        var factor = 1L
        repeat(scale) { factor *= 10 }
        return Math.addExact(Math.multiplyExact(parts[0].toLong(), factor), parts[1].toLong())
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private val FORBIDDEN = Regex("(?i)(api[_-]?key|api[_-]?secret|access[_-]?token|password|BEGIN PRIVATE)")
}
