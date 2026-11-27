package com.tradingagent.modules.brokerread

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeCashBalancesTest {
    @Test
    fun `native available cash is kept and a converted display balance is not a second currency`() {
        val parsed = NativeCashBalances.parse(
            """
            {
              "displayCurrency": "USD",
              "totalBalance": 17017.2,
              "balances": [
                {
                  "accountType": "Cash",
                  "currency": "EUR",
                  "balance": 2042.44,
                  "displayBalance": 2241.55,
                  "displayCurrency": "USD",
                  "exchangeRate": 1.0975,
                  "equityDetails": { "available": 2042.44 }
                },
                {
                  "accountType": "Trading",
                  "currency": "USD",
                  "balance": 14974.76,
                  "displayBalance": 14974.76,
                  "displayCurrency": "USD",
                  "equityDetails": { "available": 665.05 }
                },
                {
                  "accountType": "Crypto",
                  "currency": "BTC",
                  "balance": 0.1,
                  "displayBalance": 100,
                  "displayCurrency": "USD",
                  "equityDetails": { "spendableBalanceInFiat": 100 }
                }
              ]
            }
            """.trimIndent(),
        )
        assertEquals(2, parsed?.size)
        assertEquals(BrokerCashBalance("Cash", 204_244L, "EUR"), parsed?.get(0))
        assertEquals(BrokerCashBalance("Trading", 66_505L, "USD"), parsed?.get(1))
        assertTrue(parsed!!.none { it.currency != "EUR" && it.currency != "USD" })
    }

    @Test
    fun `a cross rate on the payload is kept and a same currency rate is not`() {
        val rates = NativeCashBalances.rates(
            """
            {"balances":[
              {"accountType":"Cash","currency":"EUR","displayCurrency":"USD","displayBalance":2239.05,"exchangeRate":1.09626},
              {"accountType":"Trading","currency":"USD","displayCurrency":"USD","displayBalance":14998.61,"exchangeRate":1}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf(ReportedRate("EUR", "USD", "1.09626")), rates)
        assertTrue(rates.none { it.rate == "2239.05" || it.rate == "1" })
    }

    @Test
    fun `a usd only payload does not invent eur`() {
        val parsed = NativeCashBalances.parse(
            """
            {"balances":[{"accountType":"Trading","currency":"USD","balance":10,"displayBalance":10,"displayCurrency":"EUR","equityDetails":{"available":10}}]}
            """.trimIndent(),
        )
        assertEquals(listOf(BrokerCashBalance("Trading", 1_000L, "USD")), parsed)
    }

    @Test
    fun `trading total balance is not cash and a cash account can use its balance`() {
        val parsed = NativeCashBalances.parse(
            """
            {"balances":[
              {"accountType":"Trading","currency":"USD","balance":14974.76},
              {"accountType":"Cash","currency":"EUR","balance":15.5,"displayCurrency":"USD","displayBalance":17}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf(BrokerCashBalance("Cash", 1_550L, "EUR")), parsed)
    }

    @Test
    fun `a row without a currency is skipped`() {
        val parsed = NativeCashBalances.parse(
            """{"balances":[{"accountType":"Cash","balance":10,"equityDetails":{"available":10}}]}""",
        )
        assertEquals(emptyList<BrokerCashBalance>(), parsed)
    }

    @Test
    fun `a non 200 body is not a balance list`() {
        val read = NativeCashBalances.execute(
            HttpGet { _, _ -> InboundHttp(503, null, """{"balances":[]}""") },
            DemoKeyMaterial.fromEnvironment(mapOf("ETORO_DEMO_API_KEY" to "k", "ETORO_DEMO_USER_KEY" to "u"))!!,
        )
        assertNull(read)
        assertTrue(!NativeCashBalances.ENDPOINT.path.contains("real"))
    }
}
