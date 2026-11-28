package com.tradingagent.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController {
    @GetMapping("/health/live")
    fun live(): Map<String, String> = mapOf("status" to "UP")

    @GetMapping("/health/ready-trading")
    fun readyTrading(): ResponseEntity<Map<String, String>> = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(
            mapOf(
                "status" to "DOWN",
                "operational_state" to "OBSERVATION",
                "reason" to "TRADING_NOT_READY",
            ),
        )

    @GetMapping("/health/ready-admin")
    fun readyAdmin(): ResponseEntity<Map<String, String>> = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(
            mapOf(
                "status" to "DOWN",
                "reason" to "DATASTORE_NOT_WIRED",
            ),
        )
}
