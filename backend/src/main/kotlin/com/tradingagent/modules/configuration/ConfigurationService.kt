package com.tradingagent.modules.configuration

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class BudgetPolicy(
    val baseCurrency: String? = null,
    val assignedBudgetMinor: Long? = null,
    val cashBufferMinor: Long? = null,
    val maxPositions: Int? = null,
)

data class ConfigurationVersion(
    val versionId: UUID,
    val versionNumber: Long,
    val author: String,
    val createdAt: Instant,
    val reason: String,
    val contentSha256: String,
    val budget: BudgetPolicy,
    val supersedes: UUID?,
)

class ConfigurationService(
    private val clock: () -> Instant = { Instant.parse("2026-11-27T00:00:00Z") },
) {
    private val lock = Any()
    private val versions = mutableListOf<ConfigurationVersion>()
    private var activeId: UUID

    init {
        val seeded = build(
            versionNumber = 1,
            author = "system",
            reason = "Parametri economici non impostati. Lo stato resta OBSERVATION.",
            budget = BudgetPolicy(),
            supersedes = null,
        )
        versions += seeded
        activeId = seeded.versionId
    }

    fun active(): ConfigurationVersion = synchronized(lock) {
        versions.first { it.versionId == activeId }
    }

    fun versions(): List<ConfigurationVersion> = synchronized(lock) { versions.toList() }

    fun record(author: String, reason: String, budget: BudgetPolicy): ConfigurationVersion = synchronized(lock) {
        validate(author, reason, budget)
        val created = build(
            versionNumber = versions.size + 1L,
            author = author.trim(),
            reason = reason.trim(),
            budget = budget,
            supersedes = activeId,
        )
        versions += created
        created
    }

    fun activate(versionId: UUID, inFlightIntents: Int): ConfigurationVersion = synchronized(lock) {
        require(inFlightIntents >= 0) { "inFlightIntents must be >= 0" }
        if (inFlightIntents > 0) {
            throw IllegalStateException("Cutover rifiutato: esistono intent in volo.")
        }
        val version = versions.firstOrNull { it.versionId == versionId }
            ?: throw IllegalArgumentException("Versione di configurazione sconosciuta.")
        activeId = version.versionId
        version
    }

    private fun build(
        versionNumber: Long,
        author: String,
        reason: String,
        budget: BudgetPolicy,
        supersedes: UUID?,
    ): ConfigurationVersion {
        val createdAt = clock()
        return ConfigurationVersion(
            versionId = UUID.randomUUID(),
            versionNumber = versionNumber,
            author = author,
            createdAt = createdAt,
            reason = reason,
            contentSha256 = contentSha256(versionNumber, author, createdAt, reason, budget),
            budget = budget,
            supersedes = supersedes,
        )
    }

    private fun validate(author: String, reason: String, budget: BudgetPolicy) {
        require(author.isNotBlank()) { "author is required" }
        require(reason.isNotBlank()) { "reason is required" }
        require(budget.assignedBudgetMinor == null || budget.assignedBudgetMinor >= 0) {
            "assigned budget must be >= 0"
        }
        require(budget.cashBufferMinor == null || budget.cashBufferMinor >= 0) {
            "cash buffer must be >= 0"
        }
        require(budget.maxPositions == null || budget.maxPositions >= 0) {
            "max positions must be >= 0"
        }
        val currency = budget.baseCurrency
        if (currency != null) {
            require(currency.length == 3 && currency.all { it.isLetter() }) {
                "base currency must be a 3-letter ISO code"
            }
        }
        val amountsPresent = budget.assignedBudgetMinor != null || budget.cashBufferMinor != null
        require(!amountsPresent || currency != null) { "base currency is required when amounts are set" }
    }

    companion object {
        fun contentSha256(
            versionNumber: Long,
            author: String,
            createdAt: Instant,
            reason: String,
            budget: BudgetPolicy,
        ): String {
            val payload = listOf(
                versionNumber.toString(),
                author,
                createdAt.toString(),
                reason,
                budget.baseCurrency ?: "",
                budget.assignedBudgetMinor?.toString() ?: "",
                budget.cashBufferMinor?.toString() ?: "",
                budget.maxPositions?.toString() ?: "",
            ).joinToString("\n")
            val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}
