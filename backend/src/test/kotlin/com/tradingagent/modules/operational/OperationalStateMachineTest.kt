package com.tradingagent.modules.operational

import com.tradingagent.kernel.OperationalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OperationalStateMachineTest {
    @Test
    fun `fixture covers every event and state without leaving the running process`() {
        val rows = fixtureRows()
        val covered = rows.map { it.from to it.event }.toSet()
        OperationalState.values().forEach { state ->
            OperationalEvent.values().forEach { event ->
                assertTrue(covered.contains(state to event)) { "missing $state $event" }
            }
        }
        rows.forEach { row ->
            assertEquals(
                row.to,
                OperationalStateMachine.next(row.from, row.event, row.evidence),
                "${row.from} ${row.event} evidence=${row.evidence}",
            )
        }
        val service = OperationalStateService()
        assertEquals(OperationalState.OBSERVATION, service.current())
        assertThrows(IllegalStateException::class.java) {
            service.transition(OperationalState.ACTIVE)
        }
        assertEquals(
            OperationalState.ACTIVE,
            OperationalStateMachine.next(
                OperationalState.OBSERVATION,
                OperationalEvent.GATES_AND_ADMIN_READY,
                evidenceAllowsActive = true,
            ),
        )
    }

    @Test
    fun `action matrix follows the operational state`() {
        val rows = listOf(
            perm(OperationalState.OBSERVATION, false, false, false, false),
            perm(OperationalState.OBSERVATION, true, false, false, false),
            perm(OperationalState.ACTIVE, false, true, true, true),
            perm(OperationalState.ACTIVE, true, true, true, true),
            perm(OperationalState.DEGRADED, false, false, false, true),
            perm(OperationalState.DEGRADED, true, false, true, true),
            perm(OperationalState.BLOCKED, false, false, false, false),
            perm(OperationalState.BLOCKED, true, false, false, false),
            perm(OperationalState.EMERGENCY, false, false, true, true),
            perm(OperationalState.EMERGENCY, true, false, true, true),
        )
        rows.forEach { row ->
            val actual = OperationalStateMachine.permissions(row.state, row.brokerFresh)
            assertEquals(row.newEntries, actual.newEntries)
            assertEquals(row.localExits, actual.localExits)
            assertEquals(row.cancel, actual.cancel)
            assertTrue(actual.reconcilePermitted)
            assertFalse(actual.reconcileWired)
        }
    }

    private fun fixtureRows(): List<TransitionRow> {
        val evidenceFalse = """
            OBSERVATION GATES_AND_ADMIN_READY OBSERVATION
            OBSERVATION BROKER_DIVERGENCE BLOCKED
            OBSERVATION AUDIT_FAILURE BLOCKED
            OBSERVATION CAPABILITY_MISMATCH BLOCKED
            OBSERVATION BROKER_STALE BLOCKED
            OBSERVATION LOSS_LIMIT OBSERVATION
            OBSERVATION KILL_SWITCH EMERGENCY
            OBSERVATION SECURITY_INCIDENT EMERGENCY
            OBSERVATION FILL_WITHOUT_STOP EMERGENCY
            OBSERVATION ADMIN_RESET OBSERVATION
            ACTIVE GATES_AND_ADMIN_READY ACTIVE
            ACTIVE BROKER_DIVERGENCE BLOCKED
            ACTIVE AUDIT_FAILURE BLOCKED
            ACTIVE CAPABILITY_MISMATCH BLOCKED
            ACTIVE BROKER_STALE BLOCKED
            ACTIVE LOSS_LIMIT DEGRADED
            ACTIVE KILL_SWITCH EMERGENCY
            ACTIVE SECURITY_INCIDENT EMERGENCY
            ACTIVE FILL_WITHOUT_STOP EMERGENCY
            ACTIVE ADMIN_RESET ACTIVE
            DEGRADED GATES_AND_ADMIN_READY DEGRADED
            DEGRADED BROKER_DIVERGENCE BLOCKED
            DEGRADED AUDIT_FAILURE BLOCKED
            DEGRADED CAPABILITY_MISMATCH BLOCKED
            DEGRADED BROKER_STALE BLOCKED
            DEGRADED LOSS_LIMIT DEGRADED
            DEGRADED KILL_SWITCH EMERGENCY
            DEGRADED SECURITY_INCIDENT EMERGENCY
            DEGRADED FILL_WITHOUT_STOP EMERGENCY
            DEGRADED ADMIN_RESET DEGRADED
            BLOCKED GATES_AND_ADMIN_READY BLOCKED
            BLOCKED BROKER_DIVERGENCE BLOCKED
            BLOCKED AUDIT_FAILURE BLOCKED
            BLOCKED CAPABILITY_MISMATCH BLOCKED
            BLOCKED BROKER_STALE BLOCKED
            BLOCKED LOSS_LIMIT BLOCKED
            BLOCKED KILL_SWITCH EMERGENCY
            BLOCKED SECURITY_INCIDENT EMERGENCY
            BLOCKED FILL_WITHOUT_STOP EMERGENCY
            BLOCKED ADMIN_RESET OBSERVATION
            EMERGENCY GATES_AND_ADMIN_READY EMERGENCY
            EMERGENCY BROKER_DIVERGENCE EMERGENCY
            EMERGENCY AUDIT_FAILURE EMERGENCY
            EMERGENCY CAPABILITY_MISMATCH EMERGENCY
            EMERGENCY BROKER_STALE EMERGENCY
            EMERGENCY LOSS_LIMIT EMERGENCY
            EMERGENCY KILL_SWITCH EMERGENCY
            EMERGENCY SECURITY_INCIDENT EMERGENCY
            EMERGENCY FILL_WITHOUT_STOP EMERGENCY
            EMERGENCY ADMIN_RESET OBSERVATION
        """.trimIndent()
        val rows = evidenceFalse.lineSequence().map { line ->
            val (from, event, to) = line.trim().split(" ")
            TransitionRow(
                OperationalState.valueOf(from),
                OperationalEvent.valueOf(event),
                evidence = false,
                to = OperationalState.valueOf(to),
            )
        }.toMutableList()
        rows += TransitionRow(
            OperationalState.OBSERVATION,
            OperationalEvent.GATES_AND_ADMIN_READY,
            evidence = true,
            to = OperationalState.ACTIVE,
        )
        rows += TransitionRow(
            OperationalState.DEGRADED,
            OperationalEvent.ADMIN_RESET,
            evidence = true,
            to = OperationalState.ACTIVE,
        )
        return rows
    }

    private fun perm(
        state: OperationalState,
        brokerFresh: Boolean,
        newEntries: Boolean,
        localExits: Boolean,
        cancel: Boolean,
    ) = PermissionRow(state, brokerFresh, newEntries, localExits, cancel)
}

private data class TransitionRow(
    val from: OperationalState,
    val event: OperationalEvent,
    val evidence: Boolean,
    val to: OperationalState,
)

private data class PermissionRow(
    val state: OperationalState,
    val brokerFresh: Boolean,
    val newEntries: Boolean,
    val localExits: Boolean,
    val cancel: Boolean,
)
