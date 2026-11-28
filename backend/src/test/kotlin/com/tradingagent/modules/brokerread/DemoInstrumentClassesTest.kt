package com.tradingagent.modules.brokerread

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class DemoInstrumentClassesTest {
    private val apiKey = "test-api-key"
    private val userKey = "test-user-key"

    @Test
    fun `stocks and etfs are inside and any other official type is outside`() {
        assertEquals("stock", DemoInstrumentClasses.classify("Stocks").assetClass)
        assertEquals("inside", DemoInstrumentClasses.classify("Stocks").allowlist)
        assertEquals("etf", DemoInstrumentClasses.classify("ETF").assetClass)
        assertEquals("inside", DemoInstrumentClasses.classify("ETF").allowlist)
        assertEquals("other", DemoInstrumentClasses.classify("Crypto").assetClass)
        assertEquals("outside", DemoInstrumentClasses.classify("Crypto").allowlist)
        assertEquals("Crypto", DemoInstrumentClasses.classify("Crypto").officialType)
        assertEquals("Stocks", DemoInstrumentClasses.classify("Stocks").officialType)
        assertNull(DemoInstrumentClasses.classify(null).officialType)
        assertEquals("other", DemoInstrumentClasses.classify("CFD").assetClass)
        assertEquals("unknown", DemoInstrumentClasses.classify(null).assetClass)
        assertEquals("unknown", DemoInstrumentClasses.classify("").allowlist)
        assertEquals("other", DemoInstrumentClasses.classify("stocks").assetClass)
    }

    @Test
    fun `a missing type id stays unknown and a name is not used as a type`() {
        val types = DemoInstrumentClasses.parseTypeIds(
            """
            {"instrumentDisplayDatas":[
              {"instrumentID":6270,"instrumentTypeID":5,"instrumentDisplayName":"Apple","symbolFull":"AAPL"},
              {"instrumentID":100032,"instrumentDisplayName":"Bitcoin","symbolFull":"BTC"},
              {"instrumentID":100080,"instrumentTypeID":0,"symbolFull":"ETH"}
            ]}
            """.trimIndent(),
        )
        assertEquals(5, types?.get("6270"))
        assertNull(types?.get("100032"))
        assertNull(types?.get("100080"))
        assertFalse(types.toString().contains("Apple") || types.toString().contains("AAPL") || types.toString().contains("Bitcoin"))
    }

    @Test
    fun `classification uses the official description and does not guess an id`() {
        val calls = mutableListOf<URI>()
        val classes = DemoInstrumentClasses.execute(
            HttpGet { uri, _ ->
                calls += uri
                val body = if (uri.path.endsWith("/instrument-types")) {
                    """{"instrumentTypes":[{"instrumentTypeID":5,"instrumentTypeDescription":"Stocks"},{"instrumentTypeID":6,"instrumentTypeDescription":"ETF"},{"instrumentTypeID":10,"instrumentTypeDescription":"Crypto"}]}"""
                } else {
                    """{"instrumentDisplayDatas":[
                      {"instrumentID":6270,"instrumentTypeID":5,"instrumentDisplayName":"Apple"},
                      {"instrumentID":1001,"instrumentTypeID":6},
                      {"instrumentID":100032,"instrumentTypeID":10},
                      {"instrumentID":100080,"instrumentDisplayName":"Ether"}
                    ]}"""
                }
                InboundHttp(200, null, body)
            },
            keys(),
            listOf("6270", "1001", "100032", "100080", "999"),
        )
        assertEquals("stock", classes?.get("6270")?.assetClass)
        assertEquals("inside", classes?.get("6270")?.allowlist)
        assertEquals("etf", classes?.get("1001")?.assetClass)
        assertEquals("other", classes?.get("100032")?.assetClass)
        assertEquals("outside", classes?.get("100032")?.allowlist)
        assertEquals("Crypto", classes?.get("100032")?.officialType)
        assertEquals("Stocks", classes?.get("6270")?.officialType)
        assertNull(classes?.get("100080")?.officialType)
        assertEquals("unknown", classes?.get("100080")?.assetClass)
        assertEquals("unknown", classes?.get("999")?.allowlist)
        assertTrue(calls.any { it.path == "/api/v1/market-data/instruments" })
        assertTrue(calls.any { it.path == "/api/v1/market-data/instrument-types" })
        assertFalse(calls.any { it.path.contains("/real") || it.path.contains("/feeds") || it.path.contains("/search") })
    }

    @Test
    fun `missing keys do not call and a long rate limit is not retried`() {
        var calls = 0
        val unread = DemoInstrumentClassRead(
            keys = { null },
            transport = HttpGet { _, _ ->
                calls += 1
                throw IllegalStateException("no network")
            },
        ).read(listOf("6270"))
        assertNull(unread)
        assertEquals(0, calls)

        val limited = DemoInstrumentClasses.execute(
            HttpGet { _, _ ->
                calls += 1
                InboundHttp(429, 30, "")
            },
            keys(),
            listOf("6270"),
            sleep = { throw IllegalStateException("must not wait") },
        )
        assertNull(limited)
        assertEquals(1, calls)
    }

    private fun keys(): DemoKeyMaterial = DemoKeyMaterial.fromEnvironment(
        mapOf("ETORO_DEMO_API_KEY" to apiKey, "ETORO_DEMO_USER_KEY" to userKey),
    )!!
}
