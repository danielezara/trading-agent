package com.tradingagent.modules.strategy

import com.tradingagent.kernel.OperationalState
import com.tradingagent.kernel.ReasonCode
import com.tradingagent.modules.configuration.TradingParameters
import java.security.MessageDigest
import java.time.Instant

data class ChannelObservation(
    val channel: String,
    val acceptedCount: Int,
    val stale: Boolean,
    val conflict: Boolean,
    val qualityMillis: Int,
)

data class DecisionInput(
    val operationalState: OperationalState,
    val evidenceAllowsActive: Boolean,
    val channels: List<ChannelObservation>,
    val evaluatedAt: Instant,
    val fxStatus: String = "UNOBSERVED",
)

data class ObservationSignal(
    val signalId: String,
    val origin: String,
    val originVersion: String,
    val instrumentId: String?,
    val direction: String,
    val evaluatedAt: Instant,
    val validUntil: Instant?,
    val confidenceMillis: Int,
    val rationale: String,
    val orderable: Boolean,
)

data class StrategyOutcome(
    val action: String,
    val reasonCode: ReasonCode,
    val reasonCodes: List<ReasonCode>,
    val summary: String,
    val inputHash: String,
    val evaluatedAt: Instant,
    val signal: ObservationSignal,
)

class StrategyEngine(
    private val qualityThresholdMillis: Int = TradingParameters.current().technical.qualityThresholdMillis,
) {
    fun evaluate(): StrategyOutcome = evaluate(
        DecisionInput(
            operationalState = OperationalState.OBSERVATION,
            evidenceAllowsActive = false,
            channels = emptyList(),
            evaluatedAt = Instant.parse("2026-11-27T00:00:00Z"),
        ),
    )

    fun evaluate(input: DecisionInput): StrategyOutcome {
        val byChannel = input.channels.associateBy { it.channel }
        val missing = mutableListOf<String>()
        var conflict = false
        var stale = false
        val normalized = MANDATORY_CHANNELS.map { name ->
            val observed = byChannel[name]
            val accepted = observed?.acceptedCount ?: 0
            val channelStale = observed?.stale == true
            val channelConflict = observed?.conflict == true
            val quality = observed?.qualityMillis ?: 0
            if (accepted == 0 || quality < qualityThresholdMillis) {
                missing += name
            }
            if (channelConflict) {
                conflict = true
            }
            if (channelStale) {
                stale = true
            }
            ChannelObservation(
                channel = name,
                acceptedCount = accepted,
                stale = channelStale,
                conflict = channelConflict,
                qualityMillis = quality,
            )
        }
        val reasonCodes = mutableListOf<ReasonCode>()
        val capabilityClosed = !input.evidenceAllowsActive || input.operationalState != OperationalState.ACTIVE
        if (capabilityClosed) {
            reasonCodes += ReasonCode.CAPABILITY_UNVERIFIED
        }
        if (input.fxStatus == "UNCONFIGURED") {
            reasonCodes += ReasonCode.FX_UNAVAILABLE
        }
        if (missing.isNotEmpty()) {
            reasonCodes += ReasonCode.DATA_MISSING
        }
        if (conflict) {
            reasonCodes += ReasonCode.DATA_CONFLICT
        }
        if (stale) {
            reasonCodes += ReasonCode.DATA_STALE
        }
        if (reasonCodes.isEmpty()) {
            reasonCodes += ReasonCode.WARMUP_INCOMPLETE
        }
        val action = "NO-TRADE"
        val hash = hash(input.operationalState, input.evidenceAllowsActive, input.fxStatus, input.evaluatedAt, normalized)
        val summary = "NO-TRADE. Motivi: ${reasonCodes.joinToString(", ") { it.name }}."
        val signal = signal(hash, input.evaluatedAt, summary)
        return StrategyOutcome(
            action = action,
            reasonCode = reasonCodes.first(),
            reasonCodes = reasonCodes,
            summary = summary,
            inputHash = hash,
            evaluatedAt = input.evaluatedAt,
            signal = signal,
        )
    }

    private fun signal(inputHash: String, evaluatedAt: Instant, rationale: String): ObservationSignal {
        val identity = buildString {
            append("observation-signal/1\n")
            append("input_hash=").append(inputHash).append('\n')
            append("direction=").append(SIGNAL_DIRECTION).append('\n')
            append("instrument=\n")
            append("confidence_millis=0\n")
            append("orderable=false\n")
        }
        return ObservationSignal(
            signalId = sha256(identity),
            origin = SIGNAL_ORIGIN,
            originVersion = SIGNAL_VERSION,
            instrumentId = null,
            direction = SIGNAL_DIRECTION,
            evaluatedAt = evaluatedAt,
            validUntil = null,
            confidenceMillis = 0,
            rationale = rationale,
            orderable = false,
        )
    }

    private fun hash(
        state: OperationalState,
        evidenceAllowsActive: Boolean,
        fxStatus: String,
        evaluatedAt: Instant,
        channels: List<ChannelObservation>,
    ): String {
        val payload = buildString {
            append("decision-input/v1\n")
            append("state=").append(state.wire).append('\n')
            append("evidence=").append(evidenceAllowsActive).append('\n')
            append("fx=").append(fxStatus).append('\n')
            append("evaluated_at=").append(evaluatedAt).append('\n')
            channels.forEach { channel ->
                append("channel=").append(channel.channel)
                append(" accepted=").append(channel.acceptedCount)
                append(" stale=").append(channel.stale)
                append(" conflict=").append(channel.conflict)
                append(" quality=").append(channel.qualityMillis)
                append('\n')
            }
        }
        return sha256(payload)
    }

    private fun sha256(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
        const val SIGNAL_ORIGIN: String = "strategy-engine"
        const val SIGNAL_VERSION: String = "observation-signal/1"
        const val SIGNAL_DIRECTION: String = "NONE"

        val MANDATORY_CHANNELS: List<String> = listOf(
            "calendar",
            "fundamentals",
            "macro",
            "news",
            "orders",
            "positions",
            "prices",
        )
    }
}
