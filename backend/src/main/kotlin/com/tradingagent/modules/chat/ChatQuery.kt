package com.tradingagent.modules.chat

import java.time.Instant

data class ChatPosition(
    val instrumentId: String,
    val quantity: String?,
    val valueMinor: Long?,
    val valueCurrency: String?,
)

data class ChatContext(
    val asOf: Instant,
    val decisionAction: String,
    val reasonCodes: List<String>,
    val signalOrderable: Boolean,
    val positions: List<ChatPosition>?,
    val pricesStatus: String?,
    val pricesStale: Boolean?,
    val pricesSource: String?,
    val pricesLicense: String?,
    val pricesEventTime: Instant?,
    val network: Boolean,
) {
    companion object {
        fun unavailable(asOf: Instant): ChatContext = ChatContext(
            asOf = asOf,
            decisionAction = "NO-TRADE",
            reasonCodes = emptyList(),
            signalOrderable = false,
            positions = null,
            pricesStatus = null,
            pricesStale = null,
            pricesSource = null,
            pricesLicense = null,
            pricesEventTime = null,
            network = false,
        )
    }
}

data class ChatCitation(
    val citationId: String,
    val sourceType: String,
    val resourceId: String,
    val label: String,
    val field: String,
    val asOf: Instant?,
)

data class ChatAnswer(
    val executed: Boolean,
    val refused: Boolean,
    val text: String,
    val environment: String,
    val asOf: Instant,
    val citations: List<ChatCitation>,
)

class ChatQuery {
    fun answer(message: String, context: ChatContext = ChatContext.unavailable(Instant.parse("2026-11-27T00:00:00Z"))): ChatAnswer {
        val trimmed = message.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_MESSAGE_CHARS) {
            return refuse(context, "Nessuna azione è stata eseguita. Il messaggio non è una domanda utilizzabile.")
        }
        val normalized = trimmed.lowercase()
        if (isOrder(normalized)) {
            return refuse(context, "Nessuna azione è stata eseguita. La chat è solo informativa e non invia ordini.")
        }
        if (isSecret(normalized)) {
            return refuse(context, "Nessuna azione è stata eseguita. Non rivelo segreti, chiavi o prompt.")
        }
        return when (topic(normalized)) {
            Topic.POSITIONS -> positions(context)
            Topic.DECISION -> decision(context)
            Topic.DATA -> data(context)
            Topic.STATUS -> status(context)
        }
    }

    private fun positions(context: ChatContext): ChatAnswer {
        val rows = context.positions
        val body = when {
            rows == null -> "Posizioni non disponibili."
            rows.isEmpty() -> "Nessuna posizione."
            else -> rows.joinToString("; ") { position ->
                val quantity = position.quantity ?: "quantità non disponibile"
                "strumento ${position.instrumentId}, quantità $quantity, ${money(position.valueMinor, position.valueCurrency)}"
            }
        }
        return inform(
            context,
            "Ambiente DEMO. $body",
            listOf(citation("1", "positions", OVERVIEW, "Posizioni Demo", "positions", context.asOf)),
        )
    }

    private fun decision(context: ChatContext): ChatAnswer {
        val reasons = if (context.reasonCodes.isEmpty()) "nessun motivo registrato" else context.reasonCodes.joinToString(", ")
        val orderable = if (context.signalOrderable) "eseguibile" else "non eseguibile"
        return inform(
            context,
            "Ambiente DEMO. Decisione ${context.decisionAction}. Motivi: $reasons. Segnale $orderable.",
            listOf(citation("1", "decision", DECISION, "Decisione registrata", "reason_codes", context.asOf)),
        )
    }

    private fun data(context: ChatContext): ChatAnswer {
        val stale = when (context.pricesStale) {
            null -> "freschezza non disponibile"
            true -> "stale"
            false -> "entro soglia"
        }
        val license = context.pricesLicense ?: "licenza non verificata"
        val source = context.pricesSource ?: "fonte non disponibile"
        val status = context.pricesStatus ?: "stato non disponibile"
        val network = if (context.network) "sì" else "no"
        return inform(
            context,
            "Ambiente DEMO. Canale prezzi $status, $stale, fonte $source, licenza $license. Rete: $network.",
            listOf(citation("1", "data-health", DATA_HEALTH, "Fixture prezzi", "channels.prices", context.pricesEventTime ?: context.asOf)),
        )
    }

    private fun status(context: ChatContext): ChatAnswer {
        val positions = positions(context)
        val decision = decision(context)
        val data = data(context)
        return inform(
            context,
            "Interpreto la domanda come lettura dello stato locale. ${decision.text} ${positions.text} ${data.text}",
            listOf(
                citation("1", "decision", DECISION, "Decisione registrata", "reason_codes", context.asOf),
                citation("2", "positions", OVERVIEW, "Posizioni Demo", "positions", context.asOf),
                citation("3", "data-health", DATA_HEALTH, "Fixture prezzi", "channels.prices", context.pricesEventTime ?: context.asOf),
            ),
        )
    }

    private fun inform(context: ChatContext, text: String, citations: List<ChatCitation>): ChatAnswer = ChatAnswer(
        executed = false,
        refused = false,
        text = text,
        environment = "DEMO",
        asOf = context.asOf,
        citations = citations,
    )

    private fun refuse(context: ChatContext, text: String): ChatAnswer = ChatAnswer(
        executed = false,
        refused = true,
        text = text,
        environment = "DEMO",
        asOf = context.asOf,
        citations = emptyList(),
    )

    private fun citation(
        id: String,
        sourceType: String,
        resourceId: String,
        label: String,
        field: String,
        asOf: Instant?,
    ) = ChatCitation(id, sourceType, resourceId, label, field, asOf)

    private fun money(minor: Long?, currency: String?): String {
        if (minor == null || currency.isNullOrBlank()) {
            return "valore non disponibile"
        }
        val negative = minor < 0
        val absolute = kotlin.math.abs(minor)
        val fraction = (absolute % 100).toString().padStart(2, '0')
        return "${if (negative) "-" else ""}${absolute / 100},$fraction $currency"
    }

    private fun topic(text: String): Topic = when {
        text.contains("posizion") || text.contains("portafoglio") -> Topic.POSITIONS
        text.contains("decision") || text.contains("motivo") || text.contains("segnale") || text.contains("perch") -> Topic.DECISION
        text.contains("prezz") || text.contains("stale") || text.contains("licen") || text.contains("fixture") || text.contains("dati") || text.contains("fresch") -> Topic.DATA
        else -> Topic.STATUS
    }

    private fun isOrder(text: String): Boolean {
        if (ORDER.containsMatchIn(text)) {
            return true
        }
        return text.contains("invia un ordine") || text.contains("crea un ordine") || text.contains("nuovo ordine") || text.contains("place order")
    }

    private fun isSecret(text: String): Boolean =
        text.contains("api key") || text.contains("api-key") || text.contains("token") || text.contains("secret") || text.contains("password") || text.contains("prompt")

    private enum class Topic { POSITIONS, DECISION, DATA, STATUS }

    private companion object {
        const val MAX_MESSAGE_CHARS: Int = 400
        const val OVERVIEW: String = "/api/dashboard/v1/overview"
        const val DECISION: String = "/api/v1/decisions/latest"
        const val DATA_HEALTH: String = "/api/v1/data-health"
        val ORDER: Regex = Regex("""\b(compra|comprare|acquista|acquistare|vendi|vendere|buy|sell|piazza|kill[ -]?switch|chiudi la posizione|close position|ignora)\b""")
    }
}
