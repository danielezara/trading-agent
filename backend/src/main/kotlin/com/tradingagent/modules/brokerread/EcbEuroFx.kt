package com.tradingagent.modules.brokerread

import com.tradingagent.modules.ledger.FxQuote
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

fun interface EuroFxRead {
    fun read(at: Instant, haircutBps: Int?, maxAgeDays: Int): FxQuote?
}

/**
 * Official ECB euro foreign-exchange reference, daily XML.
 * The file quotes units of foreign currency per 1 euro. USD 1.0975 means 1 EUR = 1.0975 USD.
 * The file carries a date, not a clock time. The stored timestamp is 16:00 Europe/Berlin
 * on that date, the time the ECB says the reference is usually published. It is not a traded price.
 * A missing file, a missing USD rate, a null haircut, or a reference older than the configured
 * number of calendar days yields no quote. No rate is substituted.
 */
class EcbEuroFx(
    private val transport: HttpGet,
) : EuroFxRead {
    override fun read(at: Instant, haircutBps: Int?, maxAgeDays: Int): FxQuote? {
        require(ENDPOINT.scheme == "https") { "ECB reference requires https" }
        require(ENDPOINT.host == "www.ecb.europa.eu") { "ECB reference host is fixed" }
        require(ENDPOINT.path == "/stats/eurofxref/eurofxref-daily.xml") { "ECB reference path is fixed" }
        require(!ENDPOINT.path.contains("/real")) { "real trading path is forbidden" }
        if (haircutBps == null || haircutBps !in 0..10_000 || maxAgeDays < 0) {
            return null
        }
        val response = try {
            transport.get(ENDPOINT, mapOf("Accept" to "application/xml, text/xml"))
        } catch (_: Exception) {
            return null
        }
        if (response.status != 200 || response.body.isBlank()) {
            return null
        }
        return quote(response.body, at, haircutBps, maxAgeDays)
    }

    companion object {
        val ENDPOINT: URI = URI.create("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml")
        const val SOURCE: String = "ecb-eurofxref"
        private val ZONE: ZoneId = ZoneId.of("Europe/Berlin")
        private val timePattern = Regex("""time=['"](\d{4}-\d{2}-\d{2})['"]""")
        private val usdPattern = Regex(
            """currency=['"]USD['"][^>]*rate=['"]([0-9]+(?:\.[0-9]+)?)['"]|rate=['"]([0-9]+(?:\.[0-9]+)?)['"][^>]*currency=['"]USD['"]""",
        )

        fun quote(body: String, at: Instant, haircutBps: Int, maxAgeDays: Int): FxQuote? {
            val referenceDate = timePattern.find(body)?.groupValues?.get(1)?.let { text ->
                try {
                    LocalDate.parse(text)
                } catch (_: Exception) {
                    null
                }
            } ?: return null
            val today = LocalDate.ofInstant(at, ZONE)
            val age = ChronoUnit.DAYS.between(referenceDate, today)
            if (age < 0 || age > maxAgeDays) {
                return null
            }
            val match = usdPattern.find(body) ?: return null
            val rate = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
            val (numerator, denominator) = fraction(rate) ?: return null
            return FxQuote(
                source = SOURCE,
                version = referenceDate.toString(),
                observedAt = referenceDate.atTime(16, 0).atZone(ZONE).toInstant(),
                baseCurrency = "EUR",
                quoteCurrency = "USD",
                quoteMinorPerBaseMinorNumerator = numerator,
                quoteMinorPerBaseMinorDenominator = denominator,
                haircutBps = haircutBps,
            )
        }

        private fun fraction(rate: String): Pair<Long, Long>? {
            val parts = rate.split('.')
            if (parts.size > 2 || parts[0].isEmpty() || parts[0].any { !it.isDigit() }) {
                return null
            }
            val fraction = if (parts.size == 2) parts[1] else ""
            if (fraction.length > 8 || fraction.any { !it.isDigit() }) {
                return null
            }
            val numerator = (parts[0] + fraction).toLongOrNull() ?: return null
            if (numerator <= 0) {
                return null
            }
            var denominator = 1L
            repeat(fraction.length) {
                denominator = Math.multiplyExact(denominator, 10L)
            }
            return numerator to denominator
        }
    }
}
