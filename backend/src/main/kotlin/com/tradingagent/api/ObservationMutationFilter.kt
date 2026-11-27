package com.tradingagent.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ObservationMutationFilter(
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.method == "GET" || request.method == "HEAD") {
            filterChain.doFilter(request, response)
            return
        }
        val problem = mapOf(
            "type" to "about:blank",
            "title" to "Mutazioni disabilitate",
            "status" to HttpStatus.FORBIDDEN.value(),
            "detail" to "Lo scheletro resta in OBSERVATION: nessun ordine, comando o scrittura.",
            "code" to "OBSERVATION_MUTATIONS_DISABLED",
            "retryable" to false,
        )
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = "UTF-8"
        objectMapper.writeValue(response.outputStream, problem)
    }
}
