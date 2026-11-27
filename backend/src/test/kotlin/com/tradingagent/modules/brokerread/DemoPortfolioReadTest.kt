package com.tradingagent.modules.brokerread

import com.tradingagent.kernel.EvidenceStatus
import com.tradingagent.modules.configuration.EvidenceGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration
import java.time.Instant

class DemoPortfolioReadTest {
    private val apiKey = "test-api-key"
    private val userKey = "test-user-key"

    @Test
    fun `a demo portfolio read can pass and expose the balance`() {
        val calls = mutableListOf<URI>()
        val transport = HttpGet { uri, headers ->
            calls += uri
            assertEquals(DemoPortfolioRead.ENDPOINT, uri)
            assertTrue(uri.path.contains("/demo/"))
            assertFalse(uri.path.contains("/real/"))
            assertTrue(headers.containsKey("x-api-key"))
            assertTrue(headers.containsKey("x-user-key"))
            assertTrue(headers.containsKey("x-request-id"))
            assertFalse(headers.values.any { it == "POST" })
            InboundHttp(
                status = 200,
                retryAfterSeconds = null,
                body = """
                    {"clientPortfolio":{"credit":280.35,"unrealizedPnL":1.20,"accountCurrencyId":1,"positions":[]}}
                """.trimIndent(),
            )
        }
        val material = DemoKeyMaterial.fromEnvironment(
            mapOf("ETORO_DEMO_API_KEY" to apiKey, "ETORO_DEMO_USER_KEY" to userKey),
        )!!
        assertFalse(material.toString().contains(apiKey))
        assertFalse(material.toString().contains(userKey))
        val outcome = DemoPortfolioRead.execute(transport, material)
        val pass = outcome as ReadOutcome.Pass
        assertEquals(28_035L, pass.account.brokerCash?.minorUnits)
        assertEquals("USD", pass.account.brokerCash?.currency)
        assertEquals(28_155L, pass.account.equity?.minorUnits)
        assertEquals(0, pass.account.positionCount)
        assertNull(pass.account.accountLabel)
        assertEquals(1, calls.size)
    }

    @Test
    fun `authentication failure does not pass and a rate limit stays unverified`() {
        val denied = DemoPortfolioRead.execute(
            HttpGet { _, _ -> InboundHttp(401, null, """{"message":"nope"}""") },
            keys(),
        )
        assertEquals(ReadOutcome.Failed("AUTHENTICATION"), denied)

        var calls = 0
        val slept = mutableListOf<Duration>()
        val limited = DemoPortfolioRead.execute(
            HttpGet { _, _ ->
                calls += 1
                InboundHttp(429, 30, "")
            },
            keys(),
            sleep = { slept += it },
        )
        assertEquals(ReadOutcome.Unverified("RATE_LIMITED"), limited)
        assertEquals(1, calls)
        assertTrue(slept.isEmpty())
    }

    @Test
    fun `a short retry-after retries the same get once`() {
        var calls = 0
        val outcome = DemoPortfolioRead.execute(
            HttpGet { _, _ ->
                calls += 1
                if (calls == 1) {
                    InboundHttp(429, 1, "")
                } else {
                    InboundHttp(200, null, """{"clientPortfolio":{"credit":10.00,"positions":[]}}""")
                }
            },
            keys(),
            sleep = {},
        )
        assertEquals(2, calls)
        assertTrue(outcome is ReadOutcome.Pass)
        assertEquals(1_000L, (outcome as ReadOutcome.Pass).account.brokerCash?.minorUnits)
    }

    @Test
    fun `missing keys stay unverified and one pass does not open active`() {
        var called = false
        val gate = EvidenceGate()
        val probe = CapabilityProbe(
            evidenceGate = gate,
            keys = { null },
            transport = HttpGet { _, _ ->
                called = true
                InboundHttp(200, null, """{"clientPortfolio":{"credit":1}}""")
            },
            clock = { Instant.parse("2026-11-27T08:00:00Z") },
        )
        val missing = probe.refresh()
        assertFalse(called)
        assertFalse(missing.connected)
        assertEquals("KEYS_MISSING", missing.errorClass)
        assertEquals(EvidenceStatus.UNVERIFIED, gate.rows().first { it.controlId == "RF-044" }.status)
        assertFalse(gate.allowsActive())

        val passing = CapabilityProbe(
            evidenceGate = gate,
            keys = { keys() },
            transport = HttpGet { _, _ ->
                InboundHttp(200, null, """{"clientPortfolio":{"credit":10,"positions":[]}}""")
            },
            clock = { Instant.parse("2026-11-27T08:00:00Z") },
        ).refresh()
        assertTrue(passing.connected)
        assertEquals(EvidenceStatus.PASS, gate.rows().first { it.controlId == "RF-044" }.status)
        assertEquals(EvidenceStatus.UNVERIFIED, gate.rows().first { it.controlId == "RF-045" }.status)
        assertFalse(gate.allowsActive())
    }

    @Test
    fun `an account label is shown only when the read provides a public one`() {
        val named = DemoPortfolioRead.parse(
            """{"clientPortfolio":{"credit":1,"username":"demo.user"}}""",
        )
        assertEquals("demo.user", named?.accountLabel)
        val numbered = DemoPortfolioRead.parse(
            """{"username":"12","clientPortfolio":{"credit":1}}""",
        )
        assertEquals("12", numbered?.accountLabel)
        val secret = DemoPortfolioRead.parse(
            """{"clientPortfolio":{"credit":1,"username":"${"a".repeat(80)}"}}""",
        )
        assertNull(secret?.accountLabel)
        val absent = DemoPortfolioRead.parse("""{"clientPortfolio":{"credit":1}}""")
        assertNull(absent?.accountLabel)
        assertEquals(emptyList<ObservedPosition>(), absent?.positions)
    }

    @Test
    fun `positions keep instrument quantity and value and stay empty when none`() {
        val parsed = DemoPortfolioRead.parse(
            """
            {"clientPortfolio":{
              "credit":280.35,
              "accountCurrencyId":1,
              "positions":[
                {"instrumentID":1002,"units":0.049485,"amount":100,"CID":7765437},
                {"units":1,"amount":5},
                "skip",
                {"instrumentID":1003,"amount":12.5}
              ],
              "mirrors":[{"positions":[{"instrumentID":9,"units":1,"amount":50}]}]
            }}
            """.trimIndent(),
        )
        assertEquals(2, parsed?.positionCount)
        assertEquals("1002", parsed?.positions?.get(0)?.instrumentId)
        assertEquals("0.049485", parsed?.positions?.get(0)?.quantity)
        assertEquals(10_000L, parsed?.positions?.get(0)?.value?.minorUnits)
        assertEquals("USD", parsed?.positions?.get(0)?.value?.currency)
        assertEquals("1003", parsed?.positions?.get(1)?.instrumentId)
        assertNull(parsed?.positions?.get(1)?.quantity)
        assertEquals(1_250L, parsed?.positions?.get(1)?.value?.minorUnits)

        val none = DemoPortfolioRead.parse("""{"clientPortfolio":{"credit":10,"positions":[]}}""")
        assertEquals(0, none?.positionCount)
        assertEquals(emptyList<ObservedPosition>(), none?.positions)
        assertEquals(emptyList<ObservedOrder>(), none?.orders)
        assertEquals(emptyList<ObservedOrder>(), none?.executions)
    }

    @Test
    fun `orders and close executions come from the same portfolio read`() {
        val parsed = DemoPortfolioRead.parse(
            """
            {"clientPortfolio":{
              "credit":10,
              "accountCurrencyId":1,
              "positions":[
                {"positionID":1,"orderID":555,"CID":7765437,"instrumentID":1002,"isBuy":true,"units":0.049485,"amount":100,"openDateTime":"2024-06-01T00:00:00Z"},
                {"instrumentID":1003,"units":1,"amount":5}
              ],
              "orders":[
                {"orderID":5669649,"CID":7765437,"instrumentID":100043,"isBuy":true,"units":688.231246,"amount":100,"openDateTime":"2024-06-06T08:07:25.083Z","executionType":0},
                "skip",
                {"instrumentID":1,"isBuy":true}
              ],
              "ordersForOpen":[
                {"orderId":5669649,"instrumentId":100043,"isBuy":true,"amountInUnits":1},
                {"orderID":77,"instrumentID":8,"isBuy":false,"amount":12.5,"openDateTime":"not-a-date"}
              ],
              "ordersForClose":[
                {"orderID":88,"instrumentID":1002,"unitsToDeduct":0.049485,"openDateTime":"2024-06-07T09:00:00Z","CID":7765437}
              ],
              "exitOrders":"obsolete",
              "mirrors":[{"orders":[{"orderID":9,"instrumentID":3,"isBuy":true,"amount":50}],"positions":[{"orderID":42,"instrumentID":7,"isBuy":true,"units":1,"amount":20}]}]
            }}
            """.trimIndent(),
        )
        assertEquals(2, parsed?.orders?.size)
        val open = parsed?.orders?.get(0)
        assertEquals("5669649", open?.orderId)
        assertEquals("100043", open?.instrumentId)
        assertEquals("BUY", open?.side)
        assertEquals("688.231246", open?.quantity)
        assertEquals(10_000L, open?.value?.minorUnits)
        assertEquals("2024-06-06T08:07:25.083Z", open?.openedAt)
        val second = parsed?.orders?.get(1)
        assertEquals("77", second?.orderId)
        assertEquals("SELL", second?.side)
        assertEquals(1_250L, second?.value?.minorUnits)
        assertNull(second?.openedAt)
        assertEquals(2, parsed?.executions?.size)
        val opened = parsed?.executions?.get(0)
        assertEquals("555", opened?.orderId)
        assertEquals("1002", opened?.instrumentId)
        assertEquals("BUY", opened?.side)
        assertEquals("0.049485", opened?.quantity)
        assertEquals(10_000L, opened?.value?.minorUnits)
        assertEquals("2024-06-01T00:00:00Z", opened?.openedAt)
        val close = parsed?.executions?.get(1)
        assertEquals("88", close?.orderId)
        assertEquals("1002", close?.instrumentId)
        assertEquals("0.049485", close?.quantity)
        assertNull(close?.side)
        assertEquals("2024-06-07T09:00:00Z", close?.openedAt)
        assertEquals(false, parsed?.orders?.any { it.orderId == "9" || it.orderId == "7765437" || it.orderId == "42" })
        assertEquals(false, parsed?.executions?.any { it.orderId == "7765437" || it.orderId == "42" || it.orderId == "9" })
    }

    @Test
    fun `open book loss uses the dollar amounts etoro already reports`() {
        val outcome = DemoPortfolioRead.execute(
            HttpGet { _, _ ->
                InboundHttp(
                    200,
                    null,
                    """
                    {"clientPortfolio":{"credit":100,"positions":[
                      {"instrumentID":1001,"units":1,"amount":40.00,"initialAmountInDollars":50.00},
                      {"instrumentID":1002,"units":1,"amount":30.00,"initialAmountInDollars":25.00}
                    ]}}
                    """.trimIndent(),
                )
            },
            keys(),
        )
        val positions = (outcome as ReadOutcome.Pass).account.positions
        val loss = OpenBookLoss.of(positions)
        assertEquals(500L, loss?.minorUnits)
        assertEquals("USD", loss?.currency)
        assertEquals(null, OpenBookLoss.of(positions?.map { it.copy(initialDollarsMinor = null) }))
        assertEquals(0L, OpenBookLoss.of(emptyList())?.minorUnits)
    }

    private fun keys(): DemoKeyMaterial = DemoKeyMaterial.fromEnvironment(
        mapOf("ETORO_DEMO_API_KEY" to apiKey, "ETORO_DEMO_USER_KEY" to userKey),
    )!!
}
