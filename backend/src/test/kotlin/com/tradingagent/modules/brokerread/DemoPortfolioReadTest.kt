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
    }

    private fun keys(): DemoKeyMaterial = DemoKeyMaterial.fromEnvironment(
        mapOf("ETORO_DEMO_API_KEY" to apiKey, "ETORO_DEMO_USER_KEY" to userKey),
    )!!
}
