package com.tradingagent.modules.brokerread

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI

data class BrokerCashBalance(
    val accountType: String,
    val minorUnits: Long,
    val currency: String,
)

fun interface CashBalanceRead {
    /** Null when the balances GET is not attempted or does not return a list. */
    fun read(): List<BrokerCashBalance>?
}

object NativeCashBalances {
    val ENDPOINT: URI = URI.create(
        "https://public-api.etoro.com/api/v1/balances?accountTypes=Trading,Cash&expand=equityDetails&includeZeroBalances=true",
    )
    private val mapper: ObjectMapper = ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private val currencyCode = Regex("[A-Z]{3}")

    fun execute(transport: HttpGet, material: DemoKeyMaterial): List<BrokerCashBalance>? {
        require(ENDPOINT.scheme == "https") { "balances require https" }
        require(ENDPOINT.host == "public-api.etoro.com") { "balances host is fixed" }
        require(ENDPOINT.path == "/api/v1/balances") { "balances path is fixed" }
        require(!ENDPOINT.path.contains("real")) { "real trading path is forbidden" }
        val response = try {
            transport.get(ENDPOINT, material.headers())
        } catch (_: Exception) {
            return null
        }
        if (response.status != 200 || response.body.isBlank()) {
            return null
        }
        return parse(response.body)
    }

    fun parse(body: String): List<BrokerCashBalance>? {
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return null
        }
        if (!root.isObject) {
            return null
        }
        val rows = root.get("balances") ?: return null
        if (!rows.isArray) {
            return null
        }
        val parsed = mutableListOf<BrokerCashBalance>()
        for (row in rows) {
            if (!row.isObject) {
                continue
            }
            val accountType = text(row.get("accountType")) ?: continue
            if (accountType != "Cash" && accountType != "Trading") {
                continue
            }
            val currency = text(row.get("currency")) ?: continue
            if (!currencyCode.matches(currency)) {
                continue
            }
            val equity = row.get("equityDetails")
            val available = if (equity != null && equity.isObject) minor(equity.get("available")) else null
            val amount = when {
                available != null -> available
                accountType == "Cash" -> minor(row.get("balance"))
                else -> null
            } ?: continue
            parsed += BrokerCashBalance(
                accountType = accountType,
                minorUnits = amount,
                currency = currency,
            )
        }
        return parsed
    }

    private fun text(node: JsonNode?): String? {
        if (node == null || !node.isTextual) {
            return null
        }
        val value = node.asText().trim()
        return value.takeIf { it.isNotEmpty() }
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

class DemoCashBalanceRead(
    private val keys: () -> DemoKeyMaterial?,
    private val transport: HttpGet,
) : CashBalanceRead {
    override fun read(): List<BrokerCashBalance>? {
        val material = keys() ?: return null
        return NativeCashBalances.execute(transport, material)
    }
}
