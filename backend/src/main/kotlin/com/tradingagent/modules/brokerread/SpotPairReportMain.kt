package com.tradingagent.modules.brokerread

import com.tradingagent.modules.configuration.TradingParameters
import java.time.Duration
import java.time.Instant

fun main(args: Array<String>) {
    val mode = when (args.singleOrNull() ?: "paper") {
        "paper" -> SpotMode.PAPER
        "backtest" -> SpotMode.BACKTEST
        else -> {
            System.err.println("usage: ./gradlew spotPairReport -Pmode=paper|backtest")
            kotlin.system.exitProcess(2)
        }
    }
    val material = DemoKeyMaterial.fromEnvironment(System.getenv())
    if (material == null) {
        System.err.println("spot pair report was not read: demo keys are missing")
        kotlin.system.exitProcess(2)
    }
    val config = TradingParameters.load().spotSelection
    val transport = JdkHttpGet()
    val asOf = Instant.now()
    val count = candleCount(config.historyStart, config.historyEnd, config.barSeconds, asOf)
    val observations = linkedMapOf<String, AssetObservation>()
    config.assets.forEach { asset ->
        val page = SpotCatalog.parse(read(transport, material, SpotCatalog.uri(asset)).orEmpty())
        val instrumentId = SpotPairSelection.listedInstrumentId(config, asset, page)
        val quote = if (mode == SpotMode.PAPER && instrumentId != null) {
            val body = read(transport, material, DemoMarketRates.requestUri(listOf(instrumentId)))
            body?.let { SpotCatalog.parseQuote(it, instrumentId) }
        } else {
            null
        }
        val bars = if (instrumentId != null && count != null) {
            val body = read(transport, material, SpotCatalog.candlesUri(instrumentId, config.timeframe, count))
            body?.let { SpotCatalog.parseBars(it, config.barSeconds) }.orEmpty()
        } else {
            emptyList()
        }
        observations[asset] = AssetObservation(page, quote, bars, openPosition = false)
        Thread.sleep(200)
    }
    println(SpotPairSelection.render(SpotPairSelection.select(config, mode, asOf, observations)))
}

private fun candleCount(start: Instant, end: Instant, barSeconds: Long, asOf: Instant): Long? {
    val until = if (asOf.isAfter(end)) asOf else end
    val seconds = until.epochSecond - start.epochSecond
    if (seconds <= 0 || barSeconds <= 0) {
        return null
    }
    return seconds / barSeconds + 2
}

private fun read(transport: HttpGet, material: DemoKeyMaterial, uri: java.net.URI): String? {
    val first = try {
        transport.get(uri, material.headers())
    } catch (_: Exception) {
        return null
    }
    val retryAfter = first.retryAfterSeconds
    val response = if (first.status == 429 && retryAfter != null && retryAfter in 0L..2L) {
        Thread.sleep(Duration.ofSeconds(retryAfter).toMillis())
        try {
            transport.get(uri, material.headers())
        } catch (_: Exception) {
            return null
        }
    } else {
        first
    }
    if (response.status != 200 || response.body.isBlank()) {
        return null
    }
    return response.body
}
