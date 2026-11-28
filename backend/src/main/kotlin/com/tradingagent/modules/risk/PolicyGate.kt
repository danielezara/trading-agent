package com.tradingagent.modules.risk

import com.fasterxml.jackson.databind.ObjectMapper

data class DispatchPreconditions(
    val intentId: String? = null,
    val reservationId: String? = null,
    val auditEventId: String? = null,
    val outboxId: String? = null,
)

data class PolicyVerdict(
    val accepted: Boolean,
    val reason: String,
    val brokerCalled: Boolean,
)

class PolicyGate {
    fun evaluate(preconditions: DispatchPreconditions): PolicyVerdict {
        val reason = when {
            preconditions.intentId.isNullOrBlank() -> MISSING_INTENT
            preconditions.reservationId.isNullOrBlank() -> MISSING_RESERVATION
            preconditions.auditEventId.isNullOrBlank() -> MISSING_AUDIT
            preconditions.outboxId.isNullOrBlank() -> MISSING_OUTBOX
            else -> DISPATCH_DISABLED
        }
        return PolicyVerdict(accepted = false, reason = reason, brokerCalled = false)
    }

    companion object {
        const val MISSING_INTENT: String = "MISSING_INTENT"
        const val MISSING_RESERVATION: String = "MISSING_RESERVATION"
        const val MISSING_AUDIT: String = "MISSING_AUDIT"
        const val MISSING_OUTBOX: String = "MISSING_OUTBOX"
        const val DISPATCH_DISABLED: String = "DISPATCH_DISABLED"

        private val mapper = ObjectMapper()

        fun loadFixture(resource: String = "/fixtures/policy/attempts.json"): List<Pair<DispatchPreconditions, String>> {
            val stream = PolicyGate::class.java.getResourceAsStream(resource)
                ?: throw IllegalArgumentException("policy fixture not found")
            val root = mapper.readTree(stream)
            require(root.isArray) { "policy fixture must be an array" }
            return root.map { node ->
                val preconditions = DispatchPreconditions(
                    intentId = text(node, "intent_id"),
                    reservationId = text(node, "reservation_id"),
                    auditEventId = text(node, "audit_event_id"),
                    outboxId = text(node, "outbox_id"),
                )
                preconditions to node.get("reason").asText()
            }
        }

        private fun text(node: com.fasterxml.jackson.databind.JsonNode, field: String): String? =
            node.get(field)?.takeIf { !it.isNull && it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
    }
}
