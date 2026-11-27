package com.tradingagent.modules.brokerread

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

class DemoInstrumentNamesTest {
    private val apiKey = "test-api-key"
    private val userKey = "test-user-key"

    @Test
    fun `a display name is kept and a missing name is not invented`() {
        val names = DemoInstrumentNames.parse(
            """
            {"instrumentDisplayDatas":[
              {"instrumentID":6270,"instrumentDisplayName":"Apple","symbolFull":"AAPL"},
              {"instrumentID":100032,"instrumentDisplayName":"  ","symbolFull":"MSFT"},
              {"instrumentID":1155,"symbolFull":"BTC"}
            ]}
            """.trimIndent(),
        )
        assertEquals("Apple", names["6270"])
        assertNull(names["100032"])
        assertNull(names["1155"])
        assertFalse(names.values.contains("MSFT"))
        assertFalse(names.values.contains("BTC"))
    }

    @Test
    fun `names use the instruments get and do not call news or rates`() {
        val calls = mutableListOf<URI>()
        val names = DemoInstrumentNames.execute(
            HttpGet { uri, _ ->
                calls += uri
                InboundHttp(
                    status = 200,
                    retryAfterSeconds = null,
                    body = """{"instrumentDisplayDatas":[{"instrumentID":6270,"instrumentDisplayName":"Apple"}]}""",
                )
            },
            keys(),
            listOf("6270", "6270"),
        )
        assertEquals("/api/v1/market-data/instruments", calls.single().path)
        assertEquals("instrumentIds=6270", calls.single().query)
        assertFalse(calls.single().path.endsWith("/rates"))
        assertFalse(calls.single().toString().contains("news"))
        assertFalse(calls.single().toString().contains("/feeds"))
        assertFalse(calls.single().path.contains("/real"))
        assertEquals("Apple", names["6270"])
    }

    @Test
    fun `names are not requested without keys`() {
        var calls = 0
        val read = DemoInstrumentNameRead(
            keys = { null },
            transport = HttpGet { _, _ ->
                calls += 1
                InboundHttp(200, null, """{"instrumentDisplayDatas":[]}""")
            },
        )
        assertNull(read.read(listOf("6270")))
        assertEquals(0, calls)
    }

    @Test
    fun `a long rate limit does not retry`() {
        var calls = 0
        val slept = mutableListOf<Duration>()
        val names = DemoInstrumentNames.execute(
            HttpGet { _, _ ->
                calls += 1
                InboundHttp(429, 30, "")
            },
            keys(),
            listOf("6270"),
            sleep = { slept += it },
        )
        assertTrue(names.isEmpty())
        assertEquals(1, calls)
        assertTrue(slept.isEmpty())
    }

    private fun keys() = DemoKeyMaterial.fromEnvironment(
        mapOf("ETORO_DEMO_API_KEY" to apiKey, "ETORO_DEMO_USER_KEY" to userKey),
    )!!
}
