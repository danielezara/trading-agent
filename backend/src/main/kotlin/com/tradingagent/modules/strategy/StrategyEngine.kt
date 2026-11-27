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
)

data class StrategyOutcome(
    val action: String,
    val reasonCode: ReasonCode,
    val reasonCodes: List<ReasonCode>,
    val summary: String,
    val inputHash: String,
    val evaluatedAt: Instant,
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
        val hash = hash(input.operationalState, input.evidenceAllowsActive, input.evaluatedAt, normalized)
        return StrategyOutcome(
            action = action,
            reasonCode = reasonCodes.first(),
            reasonCodes = reasonCodes,
            summary = "NO-TRADE. Motivi: ${reasonCodes.joinToString(", ") { it.name }}.",
            inputHash = hash,
            evaluatedAt = input.evaluatedAt,
        )
    }

    private fun hash(
        state: OperationalState,
        evidenceAllowsActive: Boolean,
        evaluatedAt: Instant,
        channels: List<ChannelObservation>,
    ): String {
        val payload = buildString {
            append("decision-input/v1\n")
            append("state=").append(state.wire).append('\n')
            append("evidence=").append(evidenceAllowsActive).append('\n')
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
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
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
