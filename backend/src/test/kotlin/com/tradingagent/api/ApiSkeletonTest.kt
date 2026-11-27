package com.tradingagent.api

import org.junit.jupiter.api.Test
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
            .andExpect(jsonPath("$.meta.environment").value("DEMO"))

        mockMvc.perform(get("/api/v1/evidence-gate"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.allows_active").value(false))
            .andExpect(jsonPath("$.data.rows[0].status").value("UNVERIFIED"))
    }

    @Test
    fun `overview does not invent portfolio numbers`() {
        mockMvc.perform(get("/api/dashboard/v1/overview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.operational_state").value("OBSERVATION"))
            .andExpect(jsonPath("$.data.trading_enabled").value(false))
            .andExpect(jsonPath("$.data.equity").value(nullValue()))
            .andExpect(jsonPath("$.data.position_count").value(nullValue()))
            .andExpect(jsonPath("$.meta.environment").value("DEMO"))
            .andExpect(jsonPath("$.meta.base_currency").value(nullValue()))
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
            delete("/api/dashboard/v1/conversations/1"),
        ).forEach { request ->
            mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("OBSERVATION_MUTATIONS_DISABLED"))
        }
    }
}
