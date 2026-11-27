package com.tradingagent.modules.brokerread

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class EcbEuroFxTest {
    @Test
    fun `a missing usd rate or a failed read does not invent a quote`() {
        val at = Instant.parse("2026-11-27T10:00:00Z")
        assertNull(EcbEuroFx.quote("<Cube time='2026-11-26'></Cube>", at, 100, 4))
        assertNull(EcbEuroFx.quote("<Cube time='2026-11-26'><Cube currency='JPY' rate='174.79'/></Cube>", at, 100, 4))
        val failed = EcbEuroFx(HttpGet { _, _ -> InboundHttp(503, null, "") })
            .read(at, 100, 4)
        assertNull(failed)
        val quote = EcbEuroFx.quote(
            "<Cube time='2026-11-26'><Cube currency='USD' rate='1.0975'/></Cube>",
            at,
            100,
            4,
        )
        assertEquals("ecb-eurofxref", quote?.source)
        assertEquals("2026-11-26", quote?.version)
        assertEquals(10_975L, quote?.quoteMinorPerBaseMinorNumerator)
        assertEquals(10_000L, quote?.quoteMinorPerBaseMinorDenominator)
        assertEquals(100, quote?.haircutBps)
    }
}
