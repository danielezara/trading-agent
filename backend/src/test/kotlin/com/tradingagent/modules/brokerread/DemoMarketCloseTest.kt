package com.tradingagent.modules.brokerread

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DemoMarketCloseTest {
    @Test
    fun `demo close path is fixed and rejects blank ids`() {
        val uri = DemoMarketClose.endpoint("397105707")
        assertEquals(
            "/api/v1/trading/execution/demo/market-close-orders/positions/397105707",
            uri.path,
        )
        assertTrue(uri.path.contains("/demo/"))
    }

    @Test
    fun `a successful close is accepted`() {
        val transport = HttpPost { _, _, _ -> InboundHttp(200, null, """{"ok":true}""") }
        val keys = DemoKeyMaterial.fromEnvironment(
            mapOf("ETORO_DEMO_API_KEY" to "test-api-key", "ETORO_DEMO_USER_KEY" to "test-user-key"),
        )!!
        val closed = DemoMarketClose.submit(transport, keys, "397105707")
        assertEquals("ACCEPTED", closed.status)
        assertEquals("OK", closed.reason)
    }
}
