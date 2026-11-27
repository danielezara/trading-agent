package com.tradingagent.modules.brokerread

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tradingagent.kernel.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

data class InboundHttp(
    val status: Int,
    val retryAfterSeconds: Long?,
    val body: String,
)

fun interface HttpGet {
    fun get(uri: URI, headers: Map<String, String>): InboundHttp
}

class JdkHttpGet : HttpGet {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun get(uri: URI, headers: Map<String, String>): InboundHttp {
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(5))
            .GET()
            .header("Accept", "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val retryAfter = response.headers().firstValue("retry-after").orElse(null)?.trim()?.toLongOrNull()
        val body = response.body().orEmpty()
        return InboundHttp(
            status = response.statusCode(),
            retryAfterSeconds = retryAfter,
            body = if (body.length > MAX_BODY_CHARS) "" else body,
        )
    }

    private companion object {
        const val MAX_BODY_CHARS: Int = 2_000_000
    }
}

class DemoKeyMaterial private constructor(
    private val apiKey: String,
    private val userKey: String,
) {
    fun headers(): Map<String, String> = mapOf(
        "x-request-id" to UUID.randomUUID().toString(),
        "x-api-key" to apiKey,
        "x-user-key" to userKey,
    )

    override fun toString(): String = "DemoKeyMaterial(redacted)"

    companion object {
        fun fromEnvironment(env: Map<String, String>): DemoKeyMaterial? {
            val apiKey = env["ETORO_DEMO_API_KEY"]?.trim().orEmpty()
            val userKey = env["ETORO_DEMO_USER_KEY"]?.trim().orEmpty()
            if (apiKey.isEmpty() || userKey.isEmpty()) {
                return null
            }
            return DemoKeyMaterial(apiKey, userKey)
        }
    }
}

data class ObservedAccount(
    val brokerCash: Money?,
    val equity: Money?,
    val positionCount: Int?,
)

sealed interface ReadOutcome {
    data class Pass(val account: ObservedAccount) : ReadOutcome
    data class Failed(val errorClass: String) : ReadOutcome
    data class Unverified(val errorClass: String) : ReadOutcome
}

object DemoPortfolioRead {
    val ENDPOINT: URI = URI.create("https://public-api.etoro.com/api/v1/trading/info/demo/portfolio")
    private val mapper: ObjectMapper = ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    fun execute(
        transport: HttpGet,
        material: DemoKeyMaterial,
        sleep: (Duration) -> Unit = {},
    ): ReadOutcome {
        require(ENDPOINT.path.contains("/demo/")) { "demo read path is required" }
        require(!ENDPOINT.path.contains("/real/")) { "real trading path is forbidden" }
        val first = try {
            transport.get(ENDPOINT, material.headers())
        } catch (_: Exception) {
            return ReadOutcome.Unverified("TRANSPORT")
        }
        val response = if (first.status == 429) {
            val delay = first.retryAfterSeconds
            if (delay != null && delay in 0..2) {
                sleep(Duration.ofSeconds(delay))
                try {
                    transport.get(ENDPOINT, material.headers())
                } catch (_: Exception) {
                    return ReadOutcome.Unverified("TRANSPORT")
                }
            } else {
                return ReadOutcome.Unverified("RATE_LIMITED")
            }
        } else {
            first
        }
        return classify(response)
    }

    private fun classify(response: InboundHttp): ReadOutcome = when (response.status) {
        200 -> parse(response.body)?.let { ReadOutcome.Pass(it) } ?: ReadOutcome.Failed("RESPONSE_INVALID")
        401, 403 -> ReadOutcome.Failed("AUTHENTICATION")
        429 -> ReadOutcome.Unverified("RATE_LIMITED")
        in 400..499 -> ReadOutcome.Failed("CLIENT_ERROR")
        in 500..599 -> ReadOutcome.Failed("SERVER_ERROR")
        else -> ReadOutcome.Unverified("TRANSPORT")
    }

    fun parse(body: String): ObservedAccount? {
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return null
        }
        if (root == null || !root.isObject) {
            return null
        }
        val portfolio = root.get("clientPortfolio") ?: return null
        if (!portfolio.isObject) {
            return null
        }
        val currency = currency(portfolio.get("accountCurrencyId"))
        val credit = minor(portfolio.get("credit"))
        val brokerCash = if (currency != null && credit != null && credit >= 0) Money(credit, currency) else null
        val pnl = minor(portfolio.get("unrealizedPnL"))
        val equity = if (brokerCash != null && pnl != null) {
            Money(Math.addExact(brokerCash.minorUnits, pnl), currency!!)
        } else {
            null
        }
        val positions = portfolio.get("positions")
        val positionCount = if (positions != null && positions.isArray) positions.size() else null
        return ObservedAccount(brokerCash, equity, positionCount)
    }

    private fun currency(node: JsonNode?): String? = when {
        node == null || node.isNull || node.isMissingNode -> "USD"
        node.isIntegralNumber && node.intValue() == 1 -> "USD"
        else -> null
    }

    private fun minor(node: JsonNode?): Long? {
        if (node == null || node.isNull || !node.isNumber) {
            return null
        }
        val scaled = node.decimalValue().movePointRight(2).setScale(0, RoundingMode.DOWN)
        if (scaled.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 || scaled.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0) {
            return null
        }
        return scaled.longValueExact()
    }
}
