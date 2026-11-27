package com.tradingagent.modules

import com.tradingagent.kernel.EvidenceStatus
import com.tradingagent.kernel.Money
import com.tradingagent.kernel.OperationalState
import com.tradingagent.kernel.OrderCreationForbidden
import com.tradingagent.kernel.OrderDispatchForbidden
import com.tradingagent.kernel.OrderIntentState
import com.tradingagent.modules.chat.ChatQuery
import com.tradingagent.modules.configuration.EvidenceGate
import com.tradingagent.modules.executor.DisabledBrokerExecutor
import com.tradingagent.modules.identity.AccessModel
import com.tradingagent.modules.identity.Role
import com.tradingagent.modules.ledger.Ledger
import com.tradingagent.modules.oms.OrderIntentService
import com.tradingagent.modules.operational.OperationalStateService
import com.tradingagent.modules.outbox.OutboxDispatcher
import com.tradingagent.modules.portfolio.PortfolioConstruction
import com.tradingagent.modules.readmodel.DashboardReadModel
import com.tradingagent.modules.risk.RiskEngine
import com.tradingagent.modules.strategy.StrategyEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkeletonBehaviorTest {
    @Test
    fun `strategy risk and portfolio stay closed`() {
        assertEquals("NO-TRADE", StrategyEngine().evaluate().action)
        assertEquals("NO_TRADE", RiskEngine().evaluate().status)
        assertEquals("NO-TRADE", PortfolioConstruction().allocate())
    }

    @Test
    fun `evidence gate cannot open active`() {
        val gate = EvidenceGate()
        assertFalse(gate.allowsActive())
        assertTrue(gate.rows().isNotEmpty())
        assertTrue(gate.rows().all { it.status == EvidenceStatus.UNVERIFIED })
    }

    @Test
    fun `operational state refuses every exit from observation`() {
        val service = OperationalStateService()
        assertEquals(OperationalState.OBSERVATION, service.current())
        assertEquals(OperationalState.OBSERVATION, service.transition(OperationalState.OBSERVATION))
        OperationalState.values().filter { it != OperationalState.OBSERVATION }.forEach { target ->
            assertThrows(IllegalStateException::class.java) { service.transition(target) }
        }
    }

    @Test
    fun `orders cannot be created or dispatched`() {
        assertThrows(OrderCreationForbidden::class.java) { OrderIntentService().create() }
        assertThrows(OrderDispatchForbidden::class.java) { DisabledBrokerExecutor().dispatch("intent-1") }
        assertThrows(OrderDispatchForbidden::class.java) { OutboxDispatcher().dispatchNext() }
        assertEquals(
            listOf(
                "proposed",
                "risk_reserved",
                "dispatch_pending",
                "sent",
                "acknowledged",
                "partially_filled",
                "filled",
                "cancel_pending",
                "cancelled",
                "expired",
                "rejected",
                "unknown",
                "reconciled",
            ),
            OrderIntentState.values().map { it.wire },
        )
    }

    @Test
    fun `no role can mutate and chat never executes`() {
        val access = AccessModel()
        Role.values().forEach { role -> assertFalse(access.allowsMutation(role)) }
        val answer = ChatQuery().answer("compra adesso")
        assertFalse(answer.executed)
        assertTrue(answer.text.contains("Nessuna azione"))
    }

    @Test
    fun `money uses minor units and missing cash stays null`() {
        val money = Money(minorUnits = 1000, currency = "EUR")
        assertEquals(1000L, money.minorUnits)
        assertThrows(IllegalArgumentException::class.java) { Money(1, "US") }
        val snapshot = DashboardReadModel(OperationalStateService(), Ledger()).portfolio()
        assertNull(snapshot.equity)
        assertNull(snapshot.availableCash)
        assertNull(snapshot.positionCount)
        assertFalse(snapshot.tradingEnabled)
        assertEquals(OperationalState.OBSERVATION, snapshot.operationalState)
    }
}
