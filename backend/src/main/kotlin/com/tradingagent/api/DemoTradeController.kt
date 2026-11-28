package com.tradingagent.api

import com.tradingagent.modules.brokerread.DemoEntryDispatch
import com.tradingagent.modules.brokerread.DemoKeyMaterial
import com.tradingagent.modules.brokerread.DemoMarketClose
import com.tradingagent.modules.brokerread.DemoOpenResult
import com.tradingagent.modules.brokerread.HttpPost
import com.tradingagent.modules.brokerread.JdkHttpPost
import com.tradingagent.modules.oms.KillSwitch
import com.tradingagent.modules.readmodel.DashboardReadModel
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

@RestController
@RequestMapping("/api/v1/demo")
class DemoTradeController(
    private val killSwitch: KillSwitch,
    private val readModel: DashboardReadModel,
) {
    private val transport: HttpPost = JdkHttpPost()
    private val keys: () -> DemoKeyMaterial? = { DemoKeyMaterial.fromEnvironment(System.getenv()) }
    private val entries = DemoEntryDispatch(transport = transport, keys = keys)

    @PostMapping("/orders/open")
    fun open(@RequestBody body: OpenDemoOrderRequest, response: HttpServletResponse): DemoTradeResponse {
        response.setHeader("Cache-Control", "no-store")
        if (killSwitch.armed()) {
            return DemoTradeResponse(status = "NOT_SENT", reason = "KILL_SWITCH")
        }
        val amount = body.amount?.let { runCatching { BigDecimal(it) }.getOrNull() }
            ?: return DemoTradeResponse(status = "NOT_SENT", reason = "AMOUNT")
        val instrumentId = body.instrumentId?.trim().orEmpty()
        val held = readModel.portfolio().positions.orEmpty().map { it.instrumentId }.toSet()
        return entries.submit(instrumentId, amount, held, body.leverage).toResponse()
    }

    @PostMapping("/orders/close/{positionId}")
    fun close(@PathVariable positionId: String, response: HttpServletResponse): DemoTradeResponse {
        response.setHeader("Cache-Control", "no-store")
        if (killSwitch.armed()) {
            return DemoTradeResponse(status = "NOT_SENT", reason = "KILL_SWITCH")
        }
        val material = keys() ?: return DemoTradeResponse(status = "NOT_SENT", reason = "KEYS_MISSING")
        val result = try {
            DemoMarketClose.submit(transport, material, positionId)
        } catch (_: Exception) {
            DemoOpenResult("REJECTED", "TRANSPORT")
        }
        return result.toResponse()
    }

    private fun DemoOpenResult.toResponse() = DemoTradeResponse(
        status = status,
        reason = reason,
        httpStatus = httpStatus,
        orderId = orderId,
    )
}

data class OpenDemoOrderRequest(
    val instrumentId: String? = null,
    val amount: String? = null,
    val leverage: Int? = 1,
)

data class DemoTradeResponse(
    val status: String,
    val reason: String,
    val httpStatus: Int? = null,
    val orderId: String? = null,
)
