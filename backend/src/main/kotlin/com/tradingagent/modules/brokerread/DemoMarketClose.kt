package com.tradingagent.modules.brokerread

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI

object DemoMarketClose {
    private val mapper: ObjectMapper = ObjectMapper()
    private val positionId = Regex("[1-9][0-9]{0,17}")
    private val errorCode = Regex("[A-Za-z0-9_]{1,40}")

    fun submit(
        transport: HttpPost,
        material: DemoKeyMaterial,
        positionId: String,
    ): DemoOpenResult {
        val id = this.positionId.matchEntire(positionId)?.value
            ?: return DemoOpenResult("NOT_SENT", "POSITION")
        val uri = endpoint(id)
        val response = transport.post(uri, material.headers(), "{}")
        if (response.status == 200) {
            return DemoOpenResult(status = "ACCEPTED", reason = "OK", httpStatus = 200, orderId = id)
        }
        return DemoOpenResult(
            status = "REJECTED",
            reason = parseError(response.body) ?: "HTTP_${response.status}",
            httpStatus = response.status,
        )
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

    fun endpoint(positionId: String): URI {
        require(this.positionId.matches(positionId)) { "position id is not usable" }
        val uri = URI.create(
            "https://public-api.etoro.com/api/v1/trading/execution/demo/market-close-orders/positions/$positionId",
        )
        require(uri.scheme == "https") { "demo close requires https" }
        require(uri.host == "public-api.etoro.com") { "demo close host is fixed" }
        require(uri.path == "/api/v1/trading/execution/demo/market-close-orders/positions/$positionId") {
            "demo close path is the market close by position"
        }
        require(uri.path.contains("/demo/")) { "demo close path is required" }
        require(!uri.path.contains("real")) { "real trading path is forbidden" }
        return uri
    }
}
