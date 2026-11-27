package com.tradingagent.api

import org.junit.jupiter.api.Test
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.nullValue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class ApiSkeletonTest(
    @Autowired val mockMvc: MockMvc,
) {
    @Test
    fun `live is up and readiness stays down`() {
        mockMvc.perform(get("/health/live"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))

        mockMvc.perform(get("/health/ready-trading"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.reason").value("TRADING_NOT_READY"))

        mockMvc.perform(get("/health/ready-admin"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.reason").value("DATASTORE_NOT_WIRED"))
    }

    @Test
    fun `status stays in observation with the gate closed`() {
        mockMvc.perform(get("/api/v1/system/status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.operational_state").value("OBSERVATION"))
            .andExpect(jsonPath("$.data.trading_enabled").value(false))
            .andExpect(jsonPath("$.data.broker_dispatch").value("disabled"))
            .andExpect(jsonPath("$.data.active_transition_allowed").value(false))
            .andExpect(jsonPath("$.data.strategy_action").value("NO-TRADE"))
            .andExpect(jsonPath("$.data.strategy_reason").value("CAPABILITY_UNVERIFIED"))
            .andExpect(jsonPath("$.data.broker_connected").value(false))
            .andExpect(jsonPath("$.data.probe_error_class").value("KEYS_MISSING"))
            .andExpect(jsonPath("$.data.trading_enabled").value(false))
            .andExpect(jsonPath("$.data.ingestion_enabled").value(false))
            .andExpect(jsonPath("$.data.ingestion_source").value("classpath-fixture"))
            .andExpect(jsonPath("$.meta.environment").value("DEMO"))

        mockMvc.perform(get("/api/v1/evidence-gate"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.allows_active").value(false))
            .andExpect(jsonPath("$.data.rows[0].status").value("UNVERIFIED"))
            .andExpect(jsonPath("$.data.broker_connected").value(false))
            .andExpect(jsonPath("$.data.allows_active").value(false))
    }

    @Test
    fun `overview does not invent portfolio numbers`() {
        mockMvc.perform(get("/api/dashboard/v1/overview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.operational_state").value("OBSERVATION"))
            .andExpect(jsonPath("$.data.trading_enabled").value(false))
            .andExpect(jsonPath("$.data.equity").value(nullValue()))
            .andExpect(jsonPath("$.data.position_count").value(nullValue()))
            .andExpect(jsonPath("$.data.positions").value(nullValue()))
            .andExpect(jsonPath("$.data.orders").value(nullValue()))
            .andExpect(jsonPath("$.data.executions").value(nullValue()))
            .andExpect(jsonPath("$.data.assigned_budget.minor_units").value(50000))
            .andExpect(jsonPath("$.data.assigned_budget.currency").value("EUR"))
            .andExpect(jsonPath("$.data.available_cash").value(nullValue()))
            .andExpect(jsonPath("$.data.broker_cash").value(nullValue()))
            .andExpect(jsonPath("$.data.broker_connected").value(false))
            .andExpect(jsonPath("$.data.probe_error_class").value("KEYS_MISSING"))
            .andExpect(jsonPath("$.data.account_label").value(nullValue()))
            .andExpect(jsonPath("$.data.ledger_status").value("UNOBSERVED"))
            .andExpect(jsonPath("$.data.fx_status").value("UNOBSERVED"))
            .andExpect(jsonPath("$.data.journal_entry_count").value(0))
            .andExpect(jsonPath("$.data.journal_balanced").value(true))
            .andExpect(jsonPath("$.data.configuration_version_number").value(1))
            .andExpect(jsonPath("$.meta.environment").value("DEMO"))
            .andExpect(jsonPath("$.meta.base_currency").value(nullValue()))
    }

    @Test
    fun `configuration and operational state are readable and stay closed`() {
        mockMvc.perform(get("/api/v1/configurations/active"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.version_number").value(1))
            .andExpect(jsonPath("$.data.active").value(true))
            .andExpect(jsonPath("$.data.author").value("daniele"))
            .andExpect(jsonPath("$.data.budget.base_currency").value("EUR"))
            .andExpect(jsonPath("$.data.budget.assigned_budget_minor").value(50000))
            .andExpect(jsonPath("$.data.economic.horizon").value("days"))
            .andExpect(jsonPath("$.data.economic.max_loss_bps").value(1000))
            .andExpect(jsonPath("$.data.economic.max_loss_action").value("block"))
            .andExpect(jsonPath("$.data.economic.benchmark_name").value("MSCI World"))
            .andExpect(jsonPath("$.data.economic.benchmark_return_basis").value("total_return"))
            .andExpect(jsonPath("$.data.economic.leverage").value(1))
            .andExpect(jsonPath("$.data.economic.source_path").value("config/observation-v1.yaml"))
            .andExpect(jsonPath("$.meta.base_currency").value("EUR"))
            .andExpect(jsonPath("$.warnings[0].code").value("CAPABILITY_UNVERIFIED"))
            .andExpect(jsonPath("$.data.content_sha256").isString())

        mockMvc.perform(get("/api/v1/configurations"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))

        mockMvc.perform(get("/api/v1/operational-state"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.state").value("OBSERVATION"))
            .andExpect(jsonPath("$.data.actions.new_entries").value(false))
            .andExpect(jsonPath("$.data.actions.local_exits").value(false))
            .andExpect(jsonPath("$.data.actions.cancel").value(false))
            .andExpect(jsonPath("$.data.actions.reconcile_wired").value(false))

        mockMvc.perform(get("/api/v1/data-health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.source").value("classpath-fixture"))
            .andExpect(jsonPath("$.data.network").value(false))
            .andExpect(jsonPath("$.data.channels[?(@.channel == 'prices')].status", contains("CONFLICT")))
            .andExpect(jsonPath("$.data.channels[?(@.channel == 'orders')].status", contains("NOT_WIRED")))

        val firstDecision = mockMvc.perform(get("/api/v1/decisions/latest"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.action").value("NO-TRADE"))
            .andExpect(jsonPath("$.data.reason_codes[0]").value("CAPABILITY_UNVERIFIED"))
            .andExpect(jsonPath("$.data.policy.accepted").value(false))
            .andExpect(jsonPath("$.data.policy.broker_called").value(false))
            .andExpect(jsonPath("$.data.policy.reason").value("MISSING_INTENT"))
            .andExpect(jsonPath("$.data.signal.orderable").value(false))
            .andExpect(jsonPath("$.data.signal.direction").value("NONE"))
            .andExpect(jsonPath("$.data.signal.instrument_id").value(nullValue()))
            .andExpect(jsonPath("$.data.signal.origin").value("strategy-engine"))
            .andReturn()
        val hash = com.jayway.jsonpath.JsonPath.read<String>(firstDecision.response.contentAsString, "$.data.input_hash")
        mockMvc.perform(get("/api/v1/decisions/latest"))
            .andExpect(jsonPath("$.data.input_hash").value(hash))

        mockMvc.perform(get("/api/v1/audit"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.durable").value(false))
            .andExpect(jsonPath("$.data.chain_valid").value(true))
            .andExpect(jsonPath("$.data.event_count").value(1))
            .andExpect(jsonPath("$.data.daily_digest_sealed").value(true))
            .andExpect(jsonPath("$.data.events[0].event_type").value("DecisionRecorded"))

        mockMvc.perform(get("/api/v1/configurations/active"))
            .andExpect(jsonPath("$.data.valid_from_bar_open").isString())
    }

    @Test
    fun `session cannot place orders`() {
        mockMvc.perform(get("/api/dashboard/v1/session"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.authenticated").value(false))
            .andExpect(jsonPath("$.data.capabilities.place_order").value(false))
            .andExpect(jsonPath("$.data.capabilities.kill_switch").value(false))
    }

    @Test
    fun `mutations are rejected`() {
        listOf(
            post("/api/v1/emergency/kill-switch"),
            post("/api/v1/orders"),
            post("/api/v1/configurations"),
            post("/api/v1/audit"),
            delete("/api/dashboard/v1/conversations/1"),
        ).forEach { request ->
            mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("OBSERVATION_MUTATIONS_DISABLED"))
        }
    }
}
