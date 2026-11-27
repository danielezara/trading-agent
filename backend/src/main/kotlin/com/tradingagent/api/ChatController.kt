package com.tradingagent.api

import com.tradingagent.modules.brokerread.CapabilityProbe
import com.tradingagent.modules.chat.ChatAnswer
import com.tradingagent.modules.chat.ChatContext
import com.tradingagent.modules.chat.ChatPosition
import com.tradingagent.modules.chat.ChatQuery
import com.tradingagent.modules.decision.DecisionCycle
import com.tradingagent.modules.ingestion.IngestionPipeline
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1/chat")
class ChatController(
    private val chatQuery: ChatQuery,
    private val decisionCycle: DecisionCycle,
    private val capabilityProbe: CapabilityProbe,
    private val ingestionPipeline: IngestionPipeline,
) {
    @GetMapping
    fun ask(@RequestParam q: String): ApiEnvelope<ChatTurnData> {
        val at = Instant.now()
        val outcome = decisionCycle.latest().outcome
        val probe = capabilityProbe.current()
        val health = ingestionPipeline.assess(at)
        val prices = health.channels.firstOrNull { it.channel == "prices" }
        val news = health.channels.firstOrNull { it.channel == "news" }
        val macro = health.channels.firstOrNull { it.channel == "macro" }
        val fundamentals = health.channels.firstOrNull { it.channel == "fundamentals" }
        val answer = chatQuery.answer(
            q,
            ChatContext(
                asOf = at,
                decisionAction = outcome.action,
                reasonCodes = outcome.reasonCodes.map { it.name },
                signalOrderable = outcome.signal.orderable,
                positions = probe.positions?.map { position ->
                    ChatPosition(
                        instrumentId = position.instrumentId,
                        quantity = position.quantity,
                        valueMinor = position.value?.minorUnits,
                        valueCurrency = position.value?.currency,
                    )
                },
                pricesStatus = prices?.status,
                pricesStale = prices?.stale,
                pricesSource = prices?.source,
                pricesLicense = prices?.license,
                pricesEventTime = prices?.eventTime,
                network = health.network,
                newsStatus = news?.status,
                newsStale = news?.stale,
                newsSource = news?.source,
                newsLicense = news?.license,
                newsEventTime = news?.eventTime,
                newsAccepted = news?.acceptedCount ?: 0,
                macroStatus = macro?.status,
                macroStale = macro?.stale,
                macroSource = macro?.source,
                macroLicense = macro?.license,
                macroEventTime = macro?.eventTime,
                macroAccepted = macro?.acceptedCount ?: 0,
                fundamentalsStatus = fundamentals?.status,
                fundamentalsStale = fundamentals?.stale,
                fundamentalsSource = fundamentals?.source,
                fundamentalsLicense = fundamentals?.license,
                fundamentalsEventTime = fundamentals?.eventTime,
                fundamentalsAccepted = fundamentals?.acceptedCount ?: 0,
            ),
        )
        check(!answer.executed) { "la chat non può eseguire un'azione" }
        return ApiEnvelope(
            data = chatTurn(answer),
            meta = ApiMeta(schemaVersion = "chat-turn/1"),
            warnings = listOf(
                ApiWarning(
                    code = "READ_ONLY",
                    detail = "La chat legge solo lo stato locale. Nessun modello, nessuna chiave e nessun ordine.",
                ),
            ),
        )
    }
}

data class ChatTurnData(
    val executed: Boolean,
    val refused: Boolean,
    val text: String,
    val environment: String,
    val asOf: Instant,
    val citations: List<ChatCitationData>,
)

data class ChatCitationData(
    val citationId: String,
    val sourceType: String,
    val resourceId: String,
    val label: String,
    val field: String,
    val asOf: Instant?,
)

private fun chatTurn(answer: ChatAnswer) = ChatTurnData(
    executed = answer.executed,
    refused = answer.refused,
    text = answer.text,
    environment = answer.environment,
    asOf = answer.asOf,
    citations = answer.citations.map { citation ->
        ChatCitationData(
            citationId = citation.citationId,
            sourceType = citation.sourceType,
            resourceId = citation.resourceId,
            label = citation.label,
            field = citation.field,
            asOf = citation.asOf,
        )
    },
)
