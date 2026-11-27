package com.tradingagent.modules.brokerread

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.time.Instant

class DemoMarketRatesTest {
    private val apiKey = "test-api-key"
    private val userKey = "test-user-key"

    @Test
    fun `rates parse last execution and date and ignore a quote without a time`() {
        val parsed = DemoMarketRates.parse(
            """
            {"rates":[
              {"instrumentID":6270,"ask":11,"bid":10,"lastExecution":10.5,"date":"2026-11-27T09:00:00Z"},
              {"instrumentID":100032,"ask":2,"bid":1,"lastExecution":1.25},
              {"instrumentID":1155,"lastExecution":-1,"date":"2026-11-27T09:00:00Z"}
            ]}
            """.trimIndent(),
        )
        assertEquals(1, parsed.rates.size)
        assertEquals(2, parsed.rejectedCount)
        val rate = parsed.rates.single()
        assertEquals("6270", rate.instrumentId)
        assertEquals(0, rate.value.compareTo(BigDecimal("10.5")))
        assertEquals(Instant.parse("2026-11-27T09:00:00Z"), rate.eventTime)
        assertFalse(rate.toString().contains("ask"))
    }

    @Test
    fun `the rates read is a single get and a failed call has no price`() {
        val calls = mutableListOf<URI>()
        val transport = HttpGet { uri, headers ->
            calls += uri
            assertEquals("/api/v1/market-data/instruments/rates", uri.path)
            assertTrue(uri.query.contains("instrumentIds=6270,100032"))
            assertFalse(uri.path.contains("/real"))
            assertFalse(uri.toString().contains("/feeds/"))
            assertTrue(headers.containsKey("x-api-key"))
            assertTrue(headers.containsKey("x-user-key"))
            assertTrue(headers.containsKey("x-request-id"))
            InboundHttp(401, null, """{"message":"nope"}""")
        }
        val batch = DemoMarketRates.execute(transport, keys(), listOf("6270", "100032", "6270"))
        assertTrue(batch.rates.isEmpty())
        assertEquals(1, calls.size)
    }

    @Test
    fun `a rates payload is accepted without storing the raw body`() {
        val transport = HttpGet { _, _ ->
            InboundHttp(
                status = 200,
                retryAfterSeconds = null,
                body = """{"rates":[{"instrumentID":1303,"bid":3,"ask":4,"lastExecution":3.5,"date":"2026-11-27T09:01:00Z"}]}""",
            )
        }
        val batch = DemoMarketRates.execute(transport, keys(), listOf("1303"))
        assertEquals("1303", batch.rates.single().instrumentId)
        assertEquals(0, batch.rates.single().value.compareTo(BigDecimal("3.5")))
        assertEquals(0, batch.rejectedCount)
    }

    @Test
    fun `missing keys do not call the rates endpoint`() {
        var calls = 0
        val lookup = DemoMarketRatesLookup(
            keys = { null },
            transport = HttpGet { _, _ ->
                calls += 1
                InboundHttp(200, null, """{"rates":[]}""")
            },
        )
        assertNull(lookup.lookup(listOf("6270")))
        assertEquals(0, calls)
        val empty = DemoMarketRatesLookup(
            keys = { keys() },
            transport = HttpGet { _, _ ->
                calls += 1
                InboundHttp(200, null, """{"rates":[]}""")
            },
        ).lookup(emptyList())
        assertEquals(0, empty!!.rates.size)
        assertEquals(0, calls)
    }

    @Test
    fun `a long rate limit does not retry`() {
        var calls = 0
        val slept = mutableListOf<Duration>()
        val batch = DemoMarketRates.execute(
            HttpGet { _, _ ->
                calls += 1
                InboundHttp(429, 30, "")
            },
            keys(),
            listOf("6270"),
            sleep = { slept += it },
        )
        assertTrue(batch.rates.isEmpty())
        assertEquals(1, calls)
        assertTrue(slept.isEmpty())
    }

    private fun keys() = DemoKeyMaterial.fromEnvironment(
        mapOf("ETORO_DEMO_API_KEY" to apiKey, "ETORO_DEMO_USER_KEY" to userKey),
    )!!
}
