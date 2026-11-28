package com.tradingagent.modules.brokerread

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.time.Instant

data class CashMovement(
    val movementId: String,
    val accountType: String,
    val direction: String,
    val status: String,
    val kind: String,
    val minorUnits: Long,
    val currency: String,
    val postedAt: String?,
)

fun interface CashMovementRead {
    /** Null when the cash-transaction GET is not attempted or does not return a list. */
    fun read(): List<CashMovement>?
}

object CashMovements {
    private const val TRANSACTIONS_PREFIX: String = "https://public-api.etoro.com/api/v1/money/accounts/cash/"
    private val mapper: ObjectMapper = ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private val accountId = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val currencyCode = Regex("[A-Z]{3}")
    private val token = Regex("[A-Za-z]{1,64}")
    private val movementId = Regex("[A-Za-z0-9]{1,64}")

    fun execute(transport: HttpGet, material: DemoKeyMaterial): List<CashMovement>? {
        val balances = try {
            transport.get(NativeCashBalances.ENDPOINT, material.headers())
        } catch (_: Exception) {
            return null
        }
        if (balances.status != 200 || balances.body.isBlank()) {
            return null
        }
        val accounts = cashAccountIds(balances.body) ?: return null
        val movements = mutableListOf<CashMovement>()
        for (id in accounts) {
            val response = try {
                transport.get(transactionsUri(id), material.headers())
            } catch (_: Exception) {
                return null
            }
            if (response.status != 200) {
                return null
            }
            val page = parse(response.body) ?: return null
            movements += page
        }
        return movements
    }

    fun transactionsUri(id: String): URI {
        require(accountId.matches(id)) { "cash account id is not a uuid" }
        val uri = URI.create("$TRANSACTIONS_PREFIX$id/transactions?pageSize=50")
        require(uri.scheme == "https") { "cash transactions require https" }
        require(uri.host == "public-api.etoro.com") { "cash transactions host is fixed" }
        require(uri.path == "/api/v1/money/accounts/cash/$id/transactions") { "cash transactions path is fixed" }
        require(!uri.path.contains("real")) { "real trading path is forbidden" }
        return uri
    }

    fun cashAccountIds(body: String): List<String>? {
        val root = readObject(body) ?: return null
        val rows = root.get("balances") ?: return null
        if (!rows.isArray) {
            return null
        }
        val ids = linkedSetOf<String>()
        for (row in rows) {
            if (!row.isObject) {
                continue
            }
            val accountType = text(row.get("accountType")) ?: continue
            if (accountType != "Cash") {
                continue
            }
            val id = text(row.get("accountId")) ?: continue
            if (accountId.matches(id)) {
                ids += id
            }
        }
        return ids.toList()
    }

    fun parse(body: String): List<CashMovement>? {
        val root = readObject(body) ?: return null
        val rows = root.get("results") ?: return null
        if (!rows.isArray) {
            return null
        }
        val parsed = mutableListOf<CashMovement>()
        for (row in rows) {
            val movement = readMovement(row) ?: continue
            parsed += movement
        }
        return parsed
    }

    private fun readMovement(row: JsonNode): CashMovement? {
        if (!row.isObject) {
            return null
        }
        val id = text(row.get("id")) ?: return null
        if (!movementId.matches(id)) {
            return null
        }
        val direction = text(row.get("direction")) ?: return null
        if (direction != "credit" && direction != "debit") {
            return null
        }
        val status = text(row.get("status")) ?: return null
        if (!token.matches(status)) {
            return null
        }
        val kind = text(row.get("transactionType")) ?: return null
        if (!token.matches(kind)) {
            return null
        }
        val currency = text(row.get("currency")) ?: return null
        if (!currencyCode.matches(currency)) {
            return null
        }
        val minor = minor(row.get("amount")) ?: return null
        return CashMovement(
            movementId = id,
            accountType = "Cash",
            direction = direction,
            status = status,
            kind = kind,
            minorUnits = minor,
            currency = currency,
            postedAt = timestamp(row.get("postedAt")),
        )
    }

    private fun readObject(body: String): JsonNode? {
        if (body.isBlank()) {
            return null
        }
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return null
        }
        return root.takeIf { it.isObject }
    }

    private fun text(node: JsonNode?): String? {
        if (node == null || node.isNull) {
            return null
        }
        val value = when {
            node.isTextual -> node.asText().trim()
            node.isIntegralNumber -> node.asText().trim()
            else -> return null
        }
        return value.takeIf { it.isNotEmpty() }
    }

    private fun minor(node: JsonNode?): Long? {
        val decimal = when {
            node == null || node.isNull -> return null
            node.isNumber -> node.decimalValue()
            node.isTextual -> node.asText().trim().toBigDecimalOrNull() ?: return null
            else -> return null
        }
        val scaled = decimal.movePointRight(2).setScale(0, RoundingMode.DOWN)
        if (scaled.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 || scaled.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0) {
            return null
        }
        return scaled.longValueExact()
    }

    private fun timestamp(node: JsonNode?): String? {
        val text = text(node) ?: return null
        return try {
            Instant.parse(text)
            text
        } catch (_: Exception) {
            null
        }
    }
}

class DemoCashMovementRead(
    private val keys: () -> DemoKeyMaterial?,
    private val transport: HttpGet,
) : CashMovementRead {
    override fun read(): List<CashMovement>? {
        val material = keys() ?: return null
        return CashMovements.execute(transport, material)
    }
}
