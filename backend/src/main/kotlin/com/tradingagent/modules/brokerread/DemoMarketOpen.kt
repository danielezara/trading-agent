package com.tradingagent.modules.brokerread

import com.fasterxml.jackson.databind.ObjectMapper
import com.tradingagent.modules.strategy.TradingAmount
import java.math.BigDecimal
import java.net.URI

data class DemoOpenResult(
    val status: String,
    val reason: String,
    val httpStatus: Int? = null,
    val orderId: String? = null,
)

fun interface DemoEntrySubmit {
    fun submit(instrumentId: String, amount: BigDecimal, heldInstrumentIds: Set<String>): DemoOpenResult
}

object DemoMarketOpen {
    val ENDPOINT: URI = URI.create(
        "https://public-api.etoro.com/api/v1/trading/execution/demo/market-open-orders/by-amount",
    )
    private val mapper: ObjectMapper = ObjectMapper()
    private val instrumentId = Regex("[1-9][0-9]{0,9}")
    private val orderId = Regex("[0-9]{1,18}")
    private val errorCode = Regex("[A-Za-z0-9_]{1,40}")

    fun submit(
        transport: HttpPost,
        material: DemoKeyMaterial,
        instrumentId: String,
        amount: BigDecimal,
    ): DemoOpenResult {
        val uri = endpoint()
        val id = this.instrumentId.matchEntire(instrumentId)?.value?.toIntOrNull()
            ?: return DemoOpenResult("NOT_SENT", "INSTRUMENT")
        if (!TradingAmount.acceptable(amount) || amount.scale() > 2) {
            return DemoOpenResult("NOT_SENT", "AMOUNT")
        }
        val cash = amount.setScale(2)
        val body = """{"InstrumentID":$id,"IsBuy":true,"Leverage":1,"Amount":${cash.toPlainString()}}"""
        val response = transport.post(uri, material.headers(), body)
        if (response.status == 200) {
            val opened = parseOrderId(response.body)
            return DemoOpenResult(
                status = "ACCEPTED",
                reason = if (opened == null) "ORDER_ID_UNPARSED" else "OK",
                httpStatus = 200,
                orderId = opened,
            )
        }
        return DemoOpenResult(
            status = "REJECTED",
            reason = parseError(response.body) ?: "HTTP_${response.status}",
            httpStatus = response.status,
        )
    }

    fun parseOrderId(body: String): String? {
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return null
        }
        val opened = root.get("orderForOpen") ?: return null
        val id = opened.get("orderID") ?: return null
        val text = when {
            id.isIntegralNumber -> id.asText()
            id.isTextual -> id.asText().trim()
            else -> return null
        }
        return text.takeIf { orderId.matches(it) }
    }

    fun parseError(body: String): String? {
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return null
        }
        val code = root.get("errorCode")?.takeIf { it.isTextual }?.asText()?.trim().orEmpty()
        return code.takeIf { errorCode.matches(it) }
    }

    private fun endpoint(): URI {
        require(ENDPOINT.scheme == "https") { "demo open requires https" }
        require(ENDPOINT.host == "public-api.etoro.com") { "demo open host is fixed" }
        require(ENDPOINT.path == "/api/v1/trading/execution/demo/market-open-orders/by-amount") {
            "demo open path is the market open by amount"
        }
        require(ENDPOINT.path.contains("/demo/")) { "demo open path is required" }
        require(!ENDPOINT.path.contains("real")) { "real trading path is forbidden" }
        require(!ENDPOINT.path.contains("close")) { "closing a position is forbidden" }
        return ENDPOINT
    }
}

class DemoEntryDispatch(
    private val transport: HttpPost,
    private val keys: () -> DemoKeyMaterial?,
) : DemoEntrySubmit {
    private val attempted = mutableSetOf<String>()

    override fun submit(instrumentId: String, amount: BigDecimal, heldInstrumentIds: Set<String>): DemoOpenResult {
        if (instrumentId in heldInstrumentIds) {
            return DemoOpenResult("NOT_SENT", "EXISTING_POSITION")
        }
        if (!TradingAmount.acceptable(amount)) {
            return DemoOpenResult("NOT_SENT", "AMOUNT")
        }
        if (instrumentId in attempted) {
            return DemoOpenResult("NOT_SENT", "ALREADY_ATTEMPTED")
        }
        val material = keys() ?: return DemoOpenResult("NOT_SENT", "KEYS_MISSING")
        attempted += instrumentId
        return try {
            DemoMarketOpen.submit(transport, material, instrumentId, amount)
        } catch (_: Exception) {
            DemoOpenResult("REJECTED", "TRANSPORT")
        }
    }
}
