package com.tradingagent.modules.brokerread

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CashMovementsTest {
    @Test
    fun `posted native amounts stay in their currencies and a conversion is not shown`() {
        val parsed = CashMovements.parse(
            """
            {"results":[
              {"id":"12345","direction":"credit","status":"settled","transactionType":"internalTransfer","amount":"100.00","currency":"EUR","originalAmount":"90.00","originalCurrency":"USD","conversionRate":"1.1111","postedAt":"2026-05-03T10:16:12Z","counterparty":{"name":"hidden"}},
              {"id":"99","direction":"debit","status":"settled","transactionType":"bankTransfer","amount":"2.50","currency":"USD","postedAt":"2026-05-04T10:16:12Z"}
            ],"pagination":{"pageSize":50,"hasNext":false}}
            """.trimIndent(),
        )
        assertEquals(2, parsed?.size)
        assertEquals(CashMovement("12345", "Cash", "credit", "settled", "internalTransfer", 10_000L, "EUR", "2026-05-03T10:16:12Z"), parsed?.get(0))
        assertEquals(CashMovement("99", "Cash", "debit", "settled", "bankTransfer", 250L, "USD", "2026-05-04T10:16:12Z"), parsed?.get(1))
        assertTrue(parsed!!.none { it.currency == "USD" && it.minorUnits == 9_000L })
    }

    @Test
    fun `an empty transaction page stays an empty list`() {
        val parsed = CashMovements.parse("""{"results":[],"pagination":{"pageSize":50,"hasNext":false}}""")
        assertEquals(emptyList<CashMovement>(), parsed)
    }

    @Test
    fun `a missing currency is skipped and a trading account is not queried`() {
        val parsed = CashMovements.parse(
            """{"results":[{"id":"1","direction":"credit","status":"settled","transactionType":"internalTransfer","amount":"10.00"}]}""",
        )
        assertEquals(emptyList<CashMovement>(), parsed)
        val ids = CashMovements.cashAccountIds(
            """{"balances":[
              {"accountType":"Trading","accountId":"12345678","currency":"USD"},
              {"accountType":"Cash","accountId":"f0995efc-25a1-465e-bec3-d309aaf00ede","currency":"EUR"}
            ]}""",
        )
        assertEquals(listOf("f0995efc-25a1-465e-bec3-d309aaf00ede"), ids)
    }

    @Test
    fun `an empty live page is returned and a failed page is not invented`() {
        val material = DemoKeyMaterial.fromEnvironment(
            mapOf("ETORO_DEMO_API_KEY" to "k", "ETORO_DEMO_USER_KEY" to "u"),
        )!!
        val empty = CashMovements.execute(
            scripted(
                """{"balances":[{"accountType":"Cash","accountId":"f0995efc-25a1-465e-bec3-d309aaf00ede","currency":"EUR"}]}""",
                """{"results":[],"pagination":{"pageSize":50,"hasNext":false}}""",
            ),
            material,
        )
        assertEquals(emptyList<CashMovement>(), empty)
        val failed = CashMovements.execute(
            scripted(
                """{"balances":[{"accountType":"Cash","accountId":"f0995efc-25a1-465e-bec3-d309aaf00ede","currency":"EUR"}]}""",
                """{"results":[]}""",
                transactionsStatus = 503,
            ),
            material,
        )
        assertNull(failed)
        val uri = CashMovements.transactionsUri("f0995efc-25a1-465e-bec3-d309aaf00ede")
        assertTrue(uri.path.endsWith("/transactions"))
        assertTrue(!uri.path.contains("real"))
    }

    private fun scripted(balances: String, transactions: String, transactionsStatus: Int = 200): HttpGet {
        return HttpGet { uri, _ ->
            if (uri.path == "/api/v1/balances") {
                InboundHttp(200, null, balances)
            } else {
                InboundHttp(transactionsStatus, null, transactions)
            }
        }
    }
}
