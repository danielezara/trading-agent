package com.tradingagent.modules.audit

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class AuditEvent(
    val sequence: Long,
    val occurredAt: Instant,
    val correlationId: String,
    val eventType: String,
    val configVersionId: String,
    val configContentSha256: String,
    val componentVersion: String,
    val payloadSha256: String,
    val previousHash: String,
    val eventHash: String,
)

data class AuditVerification(
    val valid: Boolean,
    val eventCount: Int,
    val headHash: String?,
)

class DailyDigestStore {
    private val sealed = linkedMapOf<LocalDate, String>()

    fun seal(day: LocalDate, digest: String) {
        require(digest.length == 64) { "daily digest must be a sha256 hex" }
        val existing = sealed[day]
        if (existing != null && existing != digest) {
            throw IllegalStateException("Riscrittura del digest giornaliero rifiutata.")
        }
        sealed[day] = digest
    }

    fun digest(day: LocalDate): String? = sealed[day]

    fun isSealed(day: LocalDate): Boolean = sealed.containsKey(day)
}

class AuditLog {
    private val lock = Any()
    private val events = mutableListOf<AuditEvent>()
    private val digests = DailyDigestStore()

    fun wired(): Boolean = false

    fun append(
        occurredAt: Instant,
        correlationId: String,
        eventType: String,
        configVersionId: String,
        configContentSha256: String,
        componentVersion: String,
        payload: String,
    ): AuditEvent = synchronized(lock) {
        require(correlationId.isNotBlank()) { "correlation id is required" }
        require(eventType.isNotBlank()) { "event type is required" }
        val sequence = events.size + 1L
        val previous = events.lastOrNull()?.eventHash ?: GENESIS
        val payloadSha = sha256(payload)
        val eventHash = eventHash(
            previous = previous,
            sequence = sequence,
            occurredAt = occurredAt,
            correlationId = correlationId,
            eventType = eventType,
            configVersionId = configVersionId,
            configContentSha256 = configContentSha256,
            componentVersion = componentVersion,
            payloadSha256 = payloadSha,
        )
        val event = AuditEvent(
            sequence = sequence,
            occurredAt = occurredAt,
            correlationId = correlationId,
            eventType = eventType,
            configVersionId = configVersionId,
            configContentSha256 = configContentSha256,
            componentVersion = componentVersion,
            payloadSha256 = payloadSha,
            previousHash = previous,
            eventHash = eventHash,
        )
        events += event
        event
    }

    fun events(): List<AuditEvent> = synchronized(lock) { events.toList() }

    fun verify(): AuditVerification = verify(events())

    fun dailyDigest(day: LocalDate): String = dailyDigest(day, events())

    fun sealDay(day: LocalDate) {
        digests.seal(day, dailyDigest(day))
    }

    fun digestSealed(day: LocalDate): Boolean = digests.isSealed(day)

    fun sealedDigest(day: LocalDate): String? = digests.digest(day)

    companion object {
        const val GENESIS: String = "0000000000000000000000000000000000000000000000000000000000000000"

        fun verify(events: List<AuditEvent>): AuditVerification {
            var previous = GENESIS
            events.forEachIndexed { index, event ->
                val expectedSequence = index + 1L
                val expectedHash = eventHash(
                    previous = previous,
                    sequence = event.sequence,
                    occurredAt = event.occurredAt,
                    correlationId = event.correlationId,
                    eventType = event.eventType,
                    configVersionId = event.configVersionId,
                    configContentSha256 = event.configContentSha256,
                    componentVersion = event.componentVersion,
                    payloadSha256 = event.payloadSha256,
                )
                if (event.sequence != expectedSequence || event.previousHash != previous || event.eventHash != expectedHash) {
                    return AuditVerification(valid = false, eventCount = events.size, headHash = events.lastOrNull()?.eventHash)
                }
                previous = event.eventHash
            }
            return AuditVerification(valid = true, eventCount = events.size, headHash = events.lastOrNull()?.eventHash)
        }

        fun dailyDigest(day: LocalDate, events: List<AuditEvent>): String {
            val material = events
                .filter { LocalDate.ofInstant(it.occurredAt, ZoneOffset.UTC) == day }
                .joinToString("\n") { it.eventHash }
            return sha256(material)
        }

        fun sha256(payload: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        private fun eventHash(
            previous: String,
            sequence: Long,
            occurredAt: Instant,
            correlationId: String,
            eventType: String,
            configVersionId: String,
            configContentSha256: String,
            componentVersion: String,
            payloadSha256: String,
        ): String = sha256(
            listOf(
                previous,
                sequence.toString(),
                occurredAt.toString(),
                correlationId,
                eventType,
                configVersionId,
                configContentSha256,
                componentVersion,
                payloadSha256,
            ).joinToString("\n"),
        )
    }
}
